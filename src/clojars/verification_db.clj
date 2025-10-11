(ns clojars.verification-db
  "Database functions for jar verification status tracking."
  (:require
   [clojars.db :as db]
   [honey.sql :as hsql]
   [next.jdbc.sql :as sql]
   [next.jdbc.result-set :as jdbc.result-set]))

(set! *warn-on-reflection* true)

;; Private helper function for queries
(defn- q
  ([db query-data]
   (q db query-data nil))
  ([db query-data opts]
   (sql/query db (hsql/format query-data {:quoted true})
              (assoc opts
                     :builder-fn jdbc.result-set/as-unqualified-lower-maps))))

;; Verification status values
(def VERIFICATION-STATUS-VERIFIED "verified")
(def VERIFICATION-STATUS-UNVERIFIED "unverified")
(def VERIFICATION-STATUS-PARTIAL "partial")
(def VERIFICATION-STATUS-FAILED "failed")
(def VERIFICATION-STATUS-PENDING "pending")

;; Verification methods
(def VERIFICATION-METHOD-SOURCE-MATCH "source-match")
(def VERIFICATION-METHOD-SOURCE-MATCH-APPROX "source-match-approx")
(def VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED "attestation-github-trusted")
(def VERIFICATION-METHOD-ATTESTATION-GITLAB-SLSA "attestation-gitlab-slsa")
(def VERIFICATION-METHOD-ATTESTATION-JENKINS "attestation-jenkins")
(def VERIFICATION-METHOD-ATTESTATION-CIRCLECI "attestation-circleci")
(def VERIFICATION-METHOD-ATTESTATION-OTHER-CI "attestation-other-ci")
(def VERIFICATION-METHOD-ATTESTATION "attestation")
(def VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS "partial-has-build-artifacts")
(def VERIFICATION-METHOD-MANUAL "manual")
(def VERIFICATION-METHOD-MANUAL-VERIFIED "manual-verified")
(def VERIFICATION-METHOD-VERIFIED-RETROSPECTIVE "verified-retrospective")
(def VERIFICATION-METHOD-UNVERIFIED-LEGACY-PROVENANCE "unverified-legacy-provenance")

(defn add-jar-verification
  "Record verification status for a jar version.
   Uses UPSERT to handle cases where a verification record already exists."
  [db {:keys [group-name jar-name version verification-status
              verification-method repo-url commit-sha commit-tag
              attestation-url reproducibility-script-url verification-notes]}]
  (let [record {:group_name group-name
                :jar_name jar-name
                :version version
                :verification_status verification-status
                :verification_method verification-method
                :repo_url repo-url
                :commit_sha commit-sha
                :commit_tag commit-tag
                :attestation_url attestation-url
                :reproducibility_script_url reproducibility-script-url
                :verification_notes verification-notes
                :verified_at (db/get-time)}]
    ;; Use raw SQL for UPSERT since next.jdbc doesn't have direct support
    (sql/query db
               [(str "INSERT INTO jar_verifications "
                     "(group_name, jar_name, version, verification_status, "
                     "verification_method, repo_url, commit_sha, commit_tag, "
                     "attestation_url, reproducibility_script_url, verification_notes, verified_at) "
                     "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?) "
                     "ON CONFLICT (group_name, jar_name, version) "
                     "DO UPDATE SET "
                     "verification_status = EXCLUDED.verification_status, "
                     "verification_method = EXCLUDED.verification_method, "
                     "repo_url = EXCLUDED.repo_url, "
                     "commit_sha = EXCLUDED.commit_sha, "
                     "commit_tag = EXCLUDED.commit_tag, "
                     "attestation_url = EXCLUDED.attestation_url, "
                     "reproducibility_script_url = EXCLUDED.reproducibility_script_url, "
                     "verification_notes = EXCLUDED.verification_notes, "
                     "verified_at = EXCLUDED.verified_at")
                group-name jar-name version verification-status
                verification-method repo-url commit-sha commit-tag
                attestation-url reproducibility-script-url verification-notes
                (:verified_at record)])))

(defn update-jar-verification
  "Update verification status for a jar version."
  [db group-name jar-name version updates]
  (sql/update! db :jar_verifications
               (select-keys updates
                            [:verification_status :verification_method
                             :repo_url :commit_sha :commit_tag
                             :attestation_url :reproducibility_script_url
                             :verification_notes])
               {:group_name group-name
                :jar_name jar-name
                :version version}))

(defn find-jar-verification
  "Find verification record for a specific jar version."
  [db group-name jar-name version]
  (first
   (q db
      {:select :*
       :from :jar_verifications
       :where [:and
               [:= :group_name group-name]
               [:= :jar_name jar-name]
               [:= :version version]]
       :limit 1})))

(defn find-jar-verifications
  "Find all verification records for a jar (all versions)."
  [db group-name jar-name]
  (q db
     {:select :*
      :from :jar_verifications
      :where [:and
              [:= :group_name group-name]
              [:= :jar_name jar-name]]
      :order-by [[:verified_at :desc]]}))

(defn count-verified-versions
  "Count how many verified versions exist for a jar."
  [db group-name jar-name]
  (-> (q db
         {:select [[[:count :*] :count]]
          :from :jar_verifications
          :where [:and
                  [:= :group_name group-name]
                  [:= :jar_name jar-name]
                  [:= :verification_status VERIFICATION-STATUS-VERIFIED]]
          :limit 1})
      first
      :count))

(defn count-total-versions-with-verification
  "Count total number of versions with verification records for a jar."
  [db group-name jar-name]
  (-> (q db
         {:select [[[:count :*] :count]]
          :from :jar_verifications
          :where [:and
                  [:= :group_name group-name]
                  [:= :jar_name jar-name]]
          :limit 1})
      first
      :count))

(defn verification-metrics
  "Get verification metrics for a jar (all versions)."
  [db group-name jar-name]
  (let [total-versions (db/count-versions db group-name jar-name)
        verified-count (count-verified-versions db group-name jar-name)
        records-count (count-total-versions-with-verification db group-name jar-name)]
    {:total-versions total-versions
     :verified-count verified-count
     :verification-records-count records-count
     :verification-rate (if (pos? total-versions)
                          (/ (float verified-count) total-versions)
                          0.0)}))

(defn find-recent-verified-jars
  "Find recently verified jars."
  [db limit]
  (q db
     {:select [:group_name :jar_name :version :verification_status
               :verification_method :verified_at]
      :from :jar_verifications
      :where [:= :verification_status VERIFICATION-STATUS-VERIFIED]
      :order-by [[:verified_at :desc]]
      :limit limit}))

(defn find-unverified-jars
  "Find jars that have no verification record.
   Returns group_name, jar_name, and version for jars missing verification."
  [db limit offset]
  (q db
     {:select [:j.group_name :j.jar_name :j.version]
      :from [[:jars :j]]
      :left-join [[:jar_verifications :v]
                  [:and
                   [:= :j.group_name :v.group_name]
                   [:= :j.jar_name :v.jar_name]
                   [:= :j.version :v.version]]]
      :where [:is :v.id :null]
      :order-by [[:j.created :desc]]
      :limit limit
      :offset offset}))

(defn get-minimum-verification-method
  "Get the minimum required verification method for a group.
   Returns the minimum verification method string or nil if not set."
  [db group-name]
  (-> (q db
         {:select [:minimum_verification_method]
          :from :group_settings
          :where [:= :group_name group-name]
          :limit 1})
      first
      :minimum_verification_method))

(defn set-minimum-verification-method
  "Set the minimum required verification method for a group.
   Also updates legacy provenance status and analysis timestamp."
  [db group-name minimum-method legacy-provenance?]
  (sql/insert! db :group_settings
               {:group_name group-name
                :minimum_verification_method minimum-method
                :verification_legacy_provenance legacy-provenance?
                :verification_last_analyzed (db/get-time)}
               {:on-conflict [:group_name]
                :do-update-set [:minimum_verification_method
                                :verification_legacy_provenance
                                :verification_last_analyzed]}))

(defn get-verification-settings
  "Get all verification-related settings for a group."
  [db group-name]
  (-> (q db
         {:select [:minimum_verification_method
                   :verification_legacy_provenance
                   :verification_last_analyzed]
          :from :group_settings
          :where [:= :group_name group-name]
          :limit 1})
      first))

(defn verification-method-meets-requirement?
  "Check if an actual verification method meets a minimum requirement.
   Returns true if actual-method meets or exceeds required-method."
  [actual-method required-method]
  (let [;; Define hierarchy from highest to lowest trust
        hierarchy {VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED 100
                   VERIFICATION-METHOD-ATTESTATION-GITLAB-SLSA 90
                   VERIFICATION-METHOD-SOURCE-MATCH 85
                   VERIFICATION-METHOD-ATTESTATION-CIRCLECI 75
                   VERIFICATION-METHOD-ATTESTATION-JENKINS 70
                   VERIFICATION-METHOD-ATTESTATION-OTHER-CI 65
                   VERIFICATION-METHOD-SOURCE-MATCH-APPROX 60
                   VERIFICATION-METHOD-ATTESTATION 60
                   VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS 40
                   VERIFICATION-METHOD-MANUAL-VERIFIED 35
                   VERIFICATION-METHOD-VERIFIED-RETROSPECTIVE 35
                   VERIFICATION-METHOD-MANUAL 30
                   VERIFICATION-METHOD-UNVERIFIED-LEGACY-PROVENANCE 10
                   "unverified" 0}
        actual-level (get hierarchy actual-method 0)
        required-level (get hierarchy required-method 0)]
    (>= actual-level required-level)))

(defn find-recent-versions
  "Find recent versions of a jar for legacy provenance analysis.
   Returns up to `limit` most recent versions."
  [db group-name jar-name limit]
  (q db
     {:select [:version :created]
      :from :jars
      :where [:and
              [:= :group_name group-name]
              [:= :jar_name jar-name]]
      :order-by [[:created :desc]]
      :limit limit}))
