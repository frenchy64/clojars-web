(ns clojars.tools.generate-checksums
  "Tool to generate incremental checksums for JAR and POM files in the repository.
  
  This creates checkpoint files that save checksums for artifacts over time,
  allowing verification of repository integrity."
  (:gen-class)
  (:require
   [clojars.config :refer [config]]
   [clojars.file-utils :as fu]
   [clojars.s3 :as s3]
   [clojars.util :refer [concatv]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [digest])
  (:import
   (java.io
    FileOutputStream
    OutputStream
    PrintWriter)
   java.util.zip.GZIPOutputStream))

(set! *warn-on-reflection* true)

(defn- artifact-files
  "Returns a sorted seq of artifact file paths (jars and poms) from the S3 bucket."
  [s3-bucket]
  (sort
   (into []
         (filter #(or (.endsWith ^String % ".jar")
                      (.endsWith ^String % ".pom")))
         (s3/list-object-keys s3-bucket))))

(defn- compute-file-checksums
  "Computes MD5 and SHA1 checksums for a file in S3.
  Returns a map with :path, :md5, and :sha1 keys."
  [s3-bucket path]
  (try
    (when-let [stream (s3/get-object-stream s3-bucket path)]
      (let [bytes (with-open [^java.io.InputStream in stream]
                    (let [baos (java.io.ByteArrayOutputStream.)]
                      (io/copy in baos)
                      (.toByteArray baos)))
            md5 (digest/md5 bytes)
            sha1 (digest/sha-1 bytes)]
        {:path path
         :md5 md5
         :sha1 sha1}))
    (catch Exception e
      (printf "Error computing checksums for %s: %s\n" path (.getMessage e))
      nil)))

(defn- format-checksum-line
  "Formats a checksum entry as a line for the output file."
  [{:keys [path md5 sha1]}]
  (format "%s %s %s" path md5 sha1))

(defn- read-existing-checksums
  "Reads the set of paths that have already been checksummed from existing
  incremental checksum files in S3."
  [s3-bucket]
  (let [existing-files (filter #(.startsWith ^String % ".checksums/incremental-checksums-")
                               (s3/list-object-keys s3-bucket ".checksums/"))]
    (into #{}
          (mapcat
           (fn [file]
             (try
               (when-let [stream (s3/get-object-stream s3-bucket file)]
                 (with-open [in (-> stream
                                    (java.util.zip.GZIPInputStream.)
                                    (io/reader))]
                   (into []
                         (comp
                          (map str/trim)
                          (filter #(not (str/blank? %)))
                          (map #(first (str/split % #"\s+"))))
                         (line-seq in))))
               (catch Exception e
                 (printf "Error reading existing checksums from %s: %s\n" file (.getMessage e))
                 [])))
           existing-files))))

(defn- next-checksum-file-number
  "Determines the next incremental checksum file number."
  [s3-bucket]
  (let [existing-files (filter #(.startsWith ^String % ".checksums/incremental-checksums-")
                               (s3/list-object-keys s3-bucket ".checksums/"))
        numbers (keep (fn [path]
                        (when-let [[_ num] (re-find #"incremental-checksums-(\d+)\.txt\.gz" path)]
                          (Integer/parseInt num)))
                      existing-files)]
    (if (seq numbers)
      (inc (apply max numbers))
      1)))

(def ^:private checksums-per-file
  "Number of checksums to include in each incremental file"
  10000)

(defn- write-to-file
  "Writes data to a file, optionally gzipping it."
  ([data filename gzip?]
   (write-to-file data filename gzip? println))
  ([data ^String filename gzip? out-fn]
   (with-open [w (-> (FileOutputStream. filename)
                     (cond-> gzip? (GZIPOutputStream.))
                     (as-> ^OutputStream % (PrintWriter. %)))]
     (printf ">> Writing %s..." filename)
     (binding [*out* w]
       (doseq [form data]
         (out-fn form))))
   (println "DONE")
   filename))

(defn- write-sums [f]
  [(fu/create-checksum-file f :md5)
   (fu/create-checksum-file f :sha1)])

(defn- put-files
  "Uploads files to S3 with the given prefix."
  ([s3-bucket files]
   (put-files s3-bucket "" files))
  ([s3-bucket prefix files]
   (run! #(let [f (io/file %)]
            (printf ">> Uploading %s to S3..." (.getPath f))
            (s3/put-file s3-bucket (str prefix (.getName f)) f
                         {:ACL "public-read"})
            (println "DONE"))
         files)))

(defn generate-incremental-checksums
  "Generates incremental checksum files for artifacts not yet checksummed.
  Returns a seq of generated file paths."
  [dest s3-bucket]
  (let [all-files (artifact-files s3-bucket)
        existing-checksums (read-existing-checksums s3-bucket)
        new-files (remove existing-checksums all-files)
        file-number (next-checksum-file-number s3-bucket)]
    (println (format "Found %d total artifacts, %d already checksummed, %d new"
                     (count all-files)
                     (count existing-checksums)
                     (count new-files)))
    (if (empty? new-files)
      (do
        (println "No new artifacts to checksum")
        [])
      (let [checksums (keep #(compute-file-checksums s3-bucket %) new-files)
            partitions (partition-all checksums-per-file checksums)]
        (println (format "Generating %d incremental checksum file(s)..." (count partitions)))
        (into []
              (mapcat
               (fn [[idx checksums-batch]]
                 (let [file-num (+ file-number idx)
                       filename (format "incremental-checksums-%d.txt" file-num)
                       gz-filename (str filename ".gz")
                       file-path (str dest "/" filename)
                       gz-file-path (str dest "/" gz-filename)]
                   (println (format "Writing %s with %d checksums..." gz-filename (count checksums-batch)))
                   (write-to-file
                    (map format-checksum-line checksums-batch)
                    file-path
                    nil
                    println)
                   (write-to-file
                    (map format-checksum-line checksums-batch)
                    gz-file-path
                    :gzip
                    println)
                   (concatv
                    [file-path gz-file-path]
                    (write-sums file-path)
                    (write-sums gz-file-path)))))
              (map-indexed vector partitions))))))

(defn generate+store-incremental-checksums
  "Generates and stores incremental checksum files to S3."
  [s3-client feed-dir]
  (put-files s3-client ".checksums/"
             (generate-incremental-checksums feed-dir s3-client)))

(defn -main [feed-dir env]
  (let [{:keys [s3]} (config (keyword env))
        repo-s3-client (s3/s3-client (:repo-bucket s3))]
    (println "Generating incremental checksums...")
    (generate+store-incremental-checksums repo-s3-client feed-dir)
    (println "DONE")))
