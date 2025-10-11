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

;; Change reasons - reasons for verification status changes
(def CHANGE-REASON-INITIAL-VERIFICATION "initial_verification")
(def CHANGE-REASON-REVERIFICATION "reverification")
(def CHANGE-REASON-COMPROMISED-WORKFLOW "compromised_workflow")
(def CHANGE-REASON-HIJACKED-REPO "hijacked_repo")
(def CHANGE-REASON-MALICIOUS-NON-MAIN-BRANCH "malicious_non_main_branch")
(def CHANGE-REASON-BACKDOOR-DETECTED "backdoor_detected")
(def CHANGE-REASON-SECURITY-VULNERABILITY "security_vulnerability")
(def CHANGE-REASON-BUILD-SYSTEM-UPDATE "build_system_update")
(def CHANGE-REASON-POLICY-CHANGE "policy_change")
(def CHANGE-REASON-MANUAL-REVIEW "manual_review")
(def CHANGE-REASON-AUTOMATED-SCAN "automated_scan")
(def CHANGE-REASON-TRANSITIVE-DEPENDENCY-COMPROMISED "transitive_dependency_compromised")
(def CHANGE-REASON-PROVENANCE-UNCHANGED-DESPITE-COMPROMISED-DEP "provenance_unchanged_despite_compromised_dep")

;; Actions taken - what action was performed in response
(def ACTION-TAKEN-NONE "none")
(def ACTION-TAKEN-JAR-DELETED "jar_deleted")
(def ACTION-TAKEN-CVE-REPORTED "cve_reported")
(def ACTION-TAKEN-USER-NOTIFIED "user_notified")
(def ACTION-TAKEN-GROUP-SUSPENDED "group_suspended")
(def ACTION-TAKEN-VERIFICATION-DOWNGRADED "verification_downgraded")
(def ACTION-TAKEN-VERIFICATION-UPGRADED "verification_upgraded")
(def ACTION-TAKEN-UNDER-INVESTIGATION "under_investigation")
(def ACTION-TAKEN-FLAGGED-FOR-REVIEW "flagged_for_review")
(def ACTION-TAKEN-AUDIT-LOG-UPDATED "audit_log_updated")
(def ACTION-TAKEN-JAR-UPDATED "jar_updated")

;; Forward declaration for add-jar-verification-history
(declare add-jar-verification-history)

(defn add-jar-verification
  "Record verification status for a jar version.
   Uses UPSERT to handle cases where a verification record already exists.
   When updating, also records the change in the history table."
  [db {:keys [group-name jar-name version verification-status
              verification-method repo-url commit-sha commit-tag
              attestation-url reproducibility-script-url verification-notes
              change-reason action-taken changed-by]}]
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
                :verified_at (db/get-time)}
        history-record (assoc record
                              :change_reason (or change-reason CHANGE-REASON-INITIAL-VERIFICATION)
                              :action_taken (or action-taken ACTION-TAKEN-NONE)
                              :changed_by changed-by
                              :changed_at (db/get-time))]
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
                (:verified_at record)])
    ;; Always record in history
    (add-jar-verification-history db history-record)))

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

(defn add-jar-verification-history
  "Record a verification change in the history table.
   This provides a complete audit trail of verification status changes."
  [db {:keys [group-name jar-name version verification-status
              verification-method repo-url commit-sha commit-tag
              attestation-url reproducibility-script-url verification-notes
              change-reason action-taken changed-by changed-at]}]
  (sql/insert! db :jar_verification_history
               {:group_name group-name
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
                :change_reason change-reason
                :action_taken action-taken
                :changed_by changed-by
                :changed_at (or changed-at (db/get-time))}))

(defn find-jar-verification-history
  "Find all verification history records for a specific jar version.
   Returns records in reverse chronological order (newest first)."
  [db group-name jar-name version]
  (q db
     {:select :*
      :from :jar_verification_history
      :where [:and
              [:= :group_name group-name]
              [:= :jar_name jar-name]
              [:= :version version]]
      :order-by [[:changed_at :desc]]}))

(defn find-jar-verification-history-all-versions
  "Find all verification history records for all versions of a jar.
   Returns records in reverse chronological order (newest first)."
  [db group-name jar-name]
  (q db
     {:select :*
      :from :jar_verification_history
      :where [:and
              [:= :group_name group-name]
              [:= :jar_name jar-name]]
      :order-by [[:changed_at :desc]]}))

(defn find-verification-changes-by-reason
  "Find all verification changes with a specific reason.
   Useful for tracking security issues or policy changes."
  [db change-reason limit]
  (q db
     {:select :*
      :from :jar_verification_history
      :where [:= :change_reason change-reason]
      :order-by [[:changed_at :desc]]
      :limit limit}))

(defn find-verification-changes-by-action
  "Find all verification changes where a specific action was taken.
   Useful for tracking what actions were performed."
  [db action-taken limit]
  (q db
     {:select :*
      :from :jar_verification_history
      :where [:= :action_taken action-taken]
      :order-by [[:changed_at :desc]]
      :limit limit}))

(defn update-jar-verification-with-history
  "Update verification status for a jar version and record the change in history.
   This is the preferred way to update verification status to ensure audit trail."
  [db group-name jar-name version updates change-reason action-taken changed-by]
  (let [current (find-jar-verification db group-name jar-name version)
        merged-updates (merge current updates)
        history-record (assoc merged-updates
                              :group-name group-name
                              :jar-name jar-name
                              :version version
                              :change-reason change-reason
                              :action-taken action-taken
                              :changed-by changed-by)]
    ;; Update current state
    (sql/update! db :jar_verifications
                 (select-keys updates
                              [:verification_status :verification_method
                               :repo_url :commit_sha :commit_tag
                               :attestation_url :reproducibility_script_url
                               :verification_notes])
                 {:group_name group-name
                  :jar_name jar-name
                  :version version})
    ;; Record in history
    (add-jar-verification-history db history-record)))

;; Security Reports and Statistics

(defn get-security-statistics
  "Get overall security statistics for verification changes.
   Returns counts by reason and action, plus recent critical events."
  [db]
  (let [;; Count changes by reason
        reason-counts (q db
                        {:select [:change_reason [[:count :*] :count]]
                         :from :jar_verification_history
                         :where [:not [:is :change_reason nil]]
                         :group-by [:change_reason]
                         :order-by [[[:count :*] :desc]]})
        ;; Count changes by action
        action-counts (q db
                        {:select [:action_taken [[:count :*] :count]]
                         :from :jar_verification_history
                         :where [:not [:is :action_taken nil]]
                         :group-by [:action_taken]
                         :order-by [[[:count :*] :desc]]})
        ;; Get recent critical events (last 30 days)
        critical-reasons #{CHANGE-REASON-COMPROMISED-WORKFLOW
                          CHANGE-REASON-HIJACKED-REPO
                          CHANGE-REASON-BACKDOOR-DETECTED
                          CHANGE-REASON-TRANSITIVE-DEPENDENCY-COMPROMISED}
        recent-critical (q db
                          {:select [:group_name :jar_name :version :change_reason
                                   :action_taken :changed_at]
                           :from :jar_verification_history
                           :where [:and
                                  [:in :change_reason critical-reasons]
                                  [:> :changed_at
                                   {:select [[[:raw "current_timestamp - interval '30 days'"]]]
                                    :from [[{:select [[1 :dummy]]} :dual]]}]]
                           :order-by [[:changed_at :desc]]
                           :limit 100})]
    {:reason-counts (into {} (map (juxt :change_reason :count) reason-counts))
     :action-counts (into {} (map (juxt :action_taken :count) action-counts))
     :recent-critical-events recent-critical
     :total-history-entries (reduce + (map :count reason-counts))}))

(defn get-compromise-trend
  "Get trend data for compromise incidents over time.
   Returns counts grouped by date for the last N days."
  [db days]
  (q db
     {:select [[[:raw "date(changed_at)"] :date]
               [[:count :*] :count]]
      :from :jar_verification_history
      :where [:and
              [:in :change_reason
               [CHANGE-REASON-COMPROMISED-WORKFLOW
                CHANGE-REASON-HIJACKED-REPO
                CHANGE-REASON-BACKDOOR-DETECTED
                CHANGE-REASON-TRANSITIVE-DEPENDENCY-COMPROMISED]]
              [:> :changed_at
               {:select [[[:raw (str "current_timestamp - interval '" days " days'")]]]
                :from [[{:select [[1 :dummy]]} :dual]]}]]
      :group-by [[[:raw "date(changed_at)"]]]
      :order-by [[:date :asc]]}))

(defn get-verification-status-distribution
  "Get current distribution of verification statuses across all jars."
  [db]
  (q db
     {:select [:verification_status [[:count :*] :count]]
      :from :jar_verifications
      :group-by [:verification_status]
      :order-by [[[:count :*] :desc]]}))

(defn get-most-impacted-groups
  "Get groups most impacted by verification downgrades."
  [db limit]
  (q db
     {:select [:group_name [[:count :*] :downgrade_count]]
      :from :jar_verification_history
      :where [:= :action_taken ACTION-TAKEN-VERIFICATION-DOWNGRADED]
      :group-by [:group_name]
      :order-by [[[:count :*] :desc]]
      :limit limit}))

(defn generate-security-report
  "Generate a comprehensive security report with all statistics."
  [db]
  (let [stats (get-security-statistics db)
        trend (get-compromise-trend db 30)
        distribution (get-verification-status-distribution db)
        impacted-groups (get-most-impacted-groups db 10)]
    {:generated-at (db/get-time)
     :statistics stats
     :compromise-trend-30d trend
     :verification-distribution (into {} (map (juxt :verification_status :count) distribution))
     :most-impacted-groups impacted-groups}))
