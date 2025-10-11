(ns clojars.jar-verification
  "Functions for jar build verification and attestation."
  (:require
   [clojure.string :as str])
  (:import
   (java.net
    URI)))

(set! *warn-on-reflection* true)

(def ^:private github-repo-pattern
  #"(?:https?://)?(?:www\.)?github\.com/([^/]+)/([^/\.]+)(?:\.git)?/?")

(def ^:private gitlab-repo-pattern
  #"(?:https?://)?(?:www\.)?gitlab\.com/([^/]+)/([^/\.]+)(?:\.git)?/?")

(defn parse-git-url
  "Parse a git URL to extract repository information.
   Returns map with :host, :owner, :repo-name, :url"
  [url]
  (when url
    (let [url-str (str url)]
      (cond
        ;; GitHub
        (re-find #"github\.com" url-str)
        (when-let [[_ owner repo-name] (re-find github-repo-pattern url-str)]
          {:host :github
           :owner owner
           :repo-name repo-name
           :url (format "https://github.com/%s/%s" owner repo-name)})

        ;; GitLab
        (re-find #"gitlab\.com" url-str)
        (when-let [[_ owner repo-name] (re-find gitlab-repo-pattern url-str)]
          {:host :gitlab
           :owner owner
           :repo-name repo-name
           :url (format "https://gitlab.com/%s/%s" owner repo-name)})

        ;; Generic git URL - try to parse as URI
        :else
        (try
          (let [uri (URI. url-str)]
            {:host :unknown
             :url (.toString uri)})
          (catch Exception _
            nil))))))

(defn extract-scm-info
  "Extract SCM information from parsed POM data.
   Returns a map with :url, :connection, :developer-connection, :tag"
  [pom-data]
  (when-let [scm (:scm pom-data)]
    {:url (:url scm)
     :connection (:connection scm)
     :developer-connection (:developerConnection scm)
     :tag (:tag scm)}))

(defn extract-repo-info
  "Extract repository URL and commit information from POM data.
   Returns map with :repo-url, :commit-tag, :repo-host, :repo-owner, :repo-name"
  [pom-data]
  (when-let [scm-info (extract-scm-info pom-data)]
    (let [url (or (:url scm-info)
                  (:connection scm-info)
                  (:developer-connection scm-info))
          tag (:tag scm-info)
          parsed (parse-git-url url)]
      (when parsed
        (merge
         {:repo-url (:url parsed)
          :commit-tag tag}
         (when (= :github (:host parsed))
           {:repo-host :github
            :repo-owner (:owner parsed)
            :repo-name (:repo-name parsed)})
         (when (= :gitlab (:host parsed))
           {:repo-host :gitlab
            :repo-owner (:owner parsed)
            :repo-name (:repo-name parsed)}))))))

(defn validate-repo-url
  "Validate that a repository URL is accessible and looks legitimate.
   Returns map with :valid? and optionally :error"
  [repo-url]
  (cond
    (nil? repo-url)
    {:valid? false :error "No repository URL provided"}

    (not (str/starts-with? repo-url "https://"))
    {:valid? false :error "Repository URL must use HTTPS"}

    (not (or (str/includes? repo-url "github.com")
             (str/includes? repo-url "gitlab.com")))
    {:valid? false :error "Repository must be hosted on GitHub or GitLab"}

    :else
    {:valid? true}))

(defn extract-verification-info
  "Extract verification information from POM data for storage.
   Returns map suitable for verification-db/add-jar-verification"
  [group-name jar-name version pom-data]
  (let [repo-info (extract-repo-info pom-data)]
    {:group-name group-name
     :jar-name jar-name
     :version version
     :repo-url (:repo-url repo-info)
     :commit-tag (:commit-tag repo-info)
     :commit-sha nil ; To be filled in by verification process
     :verification-status "pending"
     :verification-method nil
     :attestation-url nil
     :reproducibility-script-url nil
     :verification-notes nil}))
