(ns clojars.unit.tools.generate-checksums-test
  (:require
   [clojars.file-utils :as fu]
   [clojars.s3 :as s3]
   [clojars.test-helper :as help]
   [clojars.tools.generate-checksums :as checksums]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.test :refer [deftest is use-fixtures]]
   [digest])
  (:import
   (java.util.zip
    GZIPInputStream)))

(defn setup-s3 [f]
  ;; Add some test JAR and POM files to S3
  (let [test-content "test content for checksums"]
    (s3/put-object help/*s3-repo-bucket* "org/example/foo/1.0.0/foo-1.0.0.jar"
                   (io/input-stream (.getBytes test-content)))
    (s3/put-object help/*s3-repo-bucket* "org/example/foo/1.0.0/foo-1.0.0.pom"
                   (io/input-stream (.getBytes test-content)))
    (s3/put-object help/*s3-repo-bucket* "org/example/bar/2.0.0/bar-2.0.0.jar"
                   (io/input-stream (.getBytes test-content)))
    (s3/put-object help/*s3-repo-bucket* "org/example/bar/2.0.0/bar-2.0.0.pom"
                   (io/input-stream (.getBytes test-content))))
  (f))

(use-fixtures :each
  help/default-fixture
  help/with-s3-repo-bucket
  setup-s3)

(deftest test-generate-incremental-checksums-first-run
  ;; First run should create incremental-checksums-1.txt.gz
  (let [_files (checksums/generate-incremental-checksums "/tmp" help/*s3-repo-bucket*)
        txt-file (io/file "/tmp" "incremental-checksums-1.txt")
        gz-file (io/file "/tmp" "incremental-checksums-1.txt.gz")]
    
    ;; Check that files were created
    (is (.exists txt-file))
    (is (.exists gz-file))
    (is (fu/valid-checksum-file? txt-file :md5 :fail-if-missing))
    (is (fu/valid-checksum-file? txt-file :sha1 :fail-if-missing))
    (is (fu/valid-checksum-file? gz-file :md5 :fail-if-missing))
    (is (fu/valid-checksum-file? gz-file :sha1 :fail-if-missing))
    
    ;; Check that the file contains the expected checksums
    (let [content (slurp txt-file)
          lines (str/split-lines content)]
      (is (= 4 (count lines))) ;; 2 JARs + 2 POMs
      
      ;; Each line should have format: path md5 sha1
      (doseq [line lines]
        (let [parts (str/split line #"\s+")]
          (is (= 3 (count parts)) (str "Line should have 3 parts: " line))
          (let [[path md5-sum sha1-sum] parts]
            (is (or (.endsWith path ".jar") (.endsWith path ".pom")))
            (is (= 32 (count md5-sum))) ;; MD5 is 32 hex chars
            (is (= 40 (count sha1-sum))))))) ;; SHA1 is 40 hex chars
    
    ;; Check that gzipped file has same content
    (let [gz-content (with-open [in (GZIPInputStream. (io/input-stream gz-file))]
                       (slurp in))]
      (is (= (slurp txt-file) gz-content)))))

(deftest test-generate-incremental-checksums-subsequent-run
  ;; First run
  (checksums/generate-incremental-checksums "/tmp" help/*s3-repo-bucket*)
  
  ;; Upload the first checksum file to S3 to simulate it being stored
  (s3/put-file help/*s3-repo-bucket* 
               ".checksums/incremental-checksums-1.txt.gz"
               (io/file "/tmp" "incremental-checksums-1.txt.gz"))
  
  ;; Add a new artifact
  (s3/put-object help/*s3-repo-bucket* "org/example/baz/3.0.0/baz-3.0.0.jar"
                 (io/input-stream (.getBytes "new artifact")))
  
  ;; Second run should create incremental-checksums-2.txt.gz with only the new artifact
  (let [_files (checksums/generate-incremental-checksums "/tmp" help/*s3-repo-bucket*)
        txt-file (io/file "/tmp" "incremental-checksums-2.txt")
        gz-file (io/file "/tmp" "incremental-checksums-2.txt.gz")]
    
    (is (.exists txt-file))
    (is (.exists gz-file))
    
    ;; Should only have 1 line for the new JAR
    (let [content (slurp txt-file)
          lines (str/split-lines content)]
      (is (= 1 (count lines)))
      (is (str/includes? (first lines) "baz-3.0.0.jar")))))

(deftest test-generate-incremental-checksums-no-new-files
  ;; First run
  (checksums/generate-incremental-checksums "/tmp" help/*s3-repo-bucket*)
  
  ;; Upload the first checksum file to S3
  (s3/put-file help/*s3-repo-bucket* 
               ".checksums/incremental-checksums-1.txt.gz"
               (io/file "/tmp" "incremental-checksums-1.txt.gz"))
  
  ;; Second run with no new artifacts should return empty list
  (let [result (checksums/generate-incremental-checksums "/tmp" help/*s3-repo-bucket*)]
    (is (empty? result))))

(deftest test-generate+store-incremental-checksums
  ;; This test verifies the full workflow including S3 upload
  (checksums/generate+store-incremental-checksums help/*s3-repo-bucket* "/tmp")
  
  ;; Verify files were uploaded to S3
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt"))
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.gz"))
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.md5"))
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.sha1"))
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.gz.md5"))
  (is (s3/object-exists? help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.gz.sha1"))
  
  ;; Verify the checksums in the file are correct
  (let [stream (s3/get-object-stream help/*s3-repo-bucket* ".checksums/incremental-checksums-1.txt.gz")
        content (with-open [in (GZIPInputStream. stream)]
                  (slurp in))
        lines (str/split-lines content)]
    (is (= 4 (count lines)))
    
    ;; Verify one of the checksums is correct
    (let [line (first lines)
          [path md5-sum sha1-sum] (str/split line #"\s+")
          actual-stream (s3/get-object-stream help/*s3-repo-bucket* path)
          actual-bytes (with-open [in actual-stream]
                         (let [baos (java.io.ByteArrayOutputStream.)]
                           (io/copy in baos)
                           (.toByteArray baos)))
          actual-md5 (digest/md5 actual-bytes)
          actual-sha1 (digest/sha-1 actual-bytes)]
      (is (= md5-sum actual-md5))
      (is (= sha1-sum actual-sha1)))))
