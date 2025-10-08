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
(def VERIFICATION-METHOD-ATTESTATION "attestation")
(def VERIFICATION-METHOD-MANUAL "manual")

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
