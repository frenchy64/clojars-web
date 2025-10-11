(ns clojars.unit.verification-legacy-provenance-test
  (:require
   [clojars.test-helper :as help]
   [clojars.verification-db :as vdb]
   [clojars.verification-legacy-provenance :as vgf]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  help/default-fixture
  help/with-clean-database)

(deftest test-verification-method-meets-requirement
  (testing "Verification method hierarchy"
    ;; Higher trust levels meet lower requirements
    (is (vdb/verification-method-meets-requirement?
         vdb/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED
         vdb/VERIFICATION-METHOD-SOURCE-MATCH))
    
    (is (vdb/verification-method-meets-requirement?
         vdb/VERIFICATION-METHOD-SOURCE-MATCH
         vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX))
    
    (is (vdb/verification-method-meets-requirement?
         vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
         vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS))
    
    ;; Lower trust levels don't meet higher requirements
    (is (not (vdb/verification-method-meets-requirement?
              vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
              vdb/VERIFICATION-METHOD-SOURCE-MATCH)))
    
    (is (not (vdb/verification-method-meets-requirement?
              vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS
              vdb/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED)))
    
    ;; Same level always meets requirement
    (is (vdb/verification-method-meets-requirement?
         vdb/VERIFICATION-METHOD-SOURCE-MATCH
         vdb/VERIFICATION-METHOD-SOURCE-MATCH))))

(deftest test-get-and-set-minimum-verification-method
  (testing "Setting and retrieving minimum verification method"
    (let [db (help/db)]
      ;; Initially no setting
      (is (nil? (vdb/get-minimum-verification-method db "com.example")))
      
      ;; Set a minimum method
      (vdb/set-minimum-verification-method 
       db "com.example" vdb/VERIFICATION-METHOD-SOURCE-MATCH true)
      
      ;; Retrieve it
      (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH
             (vdb/get-minimum-verification-method db "com.example")))
      
      ;; Get full settings
      (let [settings (vdb/get-verification-settings db "com.example")]
        (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH
               (:minimum_verification_method settings)))
        (is (true? (:verification_legacy-provenance settings)))
        (is (some? (:verification_last_analyzed settings))))
      
      ;; Update to different method
      (vdb/set-minimum-verification-method 
       db "com.example" vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX false)
      
      (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
             (vdb/get-minimum-verification-method db "com.example")))
      
      ;; Should no longer be legacy-provenance
      (is (false? (:verification_legacy-provenance 
                   (vdb/get-verification-settings db "com.example")))))))

(deftest test-new-project-defaults
  (testing "New projects should get source-match as default"
    (let [db (help/db)]
      ;; New project with no history
      (is (vgf/should-apply-new-project-defaults? db "com.newproject" "my-jar"))
      
      ;; Get requirement for new project
      (let [req (vgf/get-verification-requirement db "com.newproject" "my-jar")]
        (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH (:required-method req)))
        (is (false? (:is-legacy-provenance? req)))
        (is (true? (:is-new-project? req)))))))

(deftest test-existing-project-legacy-provenance
  (testing "Existing projects use legacy-provenance settings"
    (let [db (help/db)]
      ;; Simulate an existing project with legacy-provenance settings
      (vdb/set-minimum-verification-method 
       db "com.oldproject" vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS true)
      
      ;; Should not apply new project defaults
      (is (not (vgf/should-apply-new-project-defaults? db "com.oldproject" "old-jar")))
      
      ;; Get requirement for existing project
      (let [req (vgf/get-verification-requirement db "com.oldproject" "old-jar")]
        (is (= vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS 
               (:required-method req)))
        (is (true? (:is-legacy-provenance? req)))
        (is (false? (:is-new-project? req)))))))

(deftest test-check-deployment-meets-requirements
  (testing "Deployment requirement checking"
    (let [db (help/db)]
      ;; Set up a project with source-match requirement
      (vdb/set-minimum-verification-method 
       db "com.example" vdb/VERIFICATION-METHOD-SOURCE-MATCH true)
      
      ;; Deployment with source-match should be allowed
      (let [result (vgf/check-deployment-meets-requirements
                   db "com.example" "my-jar" 
                   vdb/VERIFICATION-METHOD-SOURCE-MATCH)]
        (is (true? (:allowed? result)))
        (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH (:required-method result)))
        (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH (:actual-method result)))
        (is (nil? (:reason result))))
      
      ;; Deployment with higher trust level should be allowed
      (let [result (vgf/check-deployment-meets-requirements
                   db "com.example" "my-jar"
                   vdb/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED)]
        (is (true? (:allowed? result))))
      
      ;; Deployment with lower trust level should be rejected
      (let [result (vgf/check-deployment-meets-requirements
                   db "com.example" "my-jar"
                   vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS)]
        (is (false? (:allowed? result)))
        (is (some? (:reason result)))
        (is (re-find #"requires.*source-match" (:reason result)))))))

(deftest test-analyze-project-with-verified-versions
  (testing "Analyzing project with existing verified versions"
    (let [db (help/db)]
      ;; Create a group and add some users
      (help/add-group db "testuser" "com.example")
      
      ;; Add some jar versions
      (help/add-jar db "testuser" 
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.0.0"})
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.1.0"})
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.2.0"})
      
      ;; Add verification records showing source-match pattern
      (vdb/add-jar-verification db
        {:group-name "com.example"
         :jar-name "my-lib"
         :version "1.0.0"
         :verification-status vdb/VERIFICATION-STATUS-VERIFIED
         :verification-method vdb/VERIFICATION-METHOD-SOURCE-MATCH
         :repo-url "https://github.com/example/my-lib"
         :commit-tag "v1.0.0"
         :commit-sha nil
         :attestation-url nil
         :reproducibility-script-url nil
         :verification-notes nil})
      
      (vdb/add-jar-verification db
        {:group-name "com.example"
         :jar-name "my-lib"
         :version "1.1.0"
         :verification-status vdb/VERIFICATION-STATUS-VERIFIED
         :verification-method vdb/VERIFICATION-METHOD-SOURCE-MATCH
         :repo-url "https://github.com/example/my-lib"
         :commit-tag "v1.1.0"
         :commit-sha nil
         :attestation-url nil
         :reproducibility-script-url nil
         :verification-notes nil})
      
      (vdb/add-jar-verification db
        {:group-name "com.example"
         :jar-name "my-lib"
         :version "1.2.0"
         :verification-status vdb/VERIFICATION-STATUS-VERIFIED
         :verification-method vdb/VERIFICATION-METHOD-SOURCE-MATCH
         :repo-url "https://github.com/example/my-lib"
         :commit-tag "v1.2.0"
         :commit-sha nil
         :attestation-url nil
         :reproducibility-script-url nil
         :verification-notes nil})
      
      ;; Analyze the project
      (let [analysis (vgf/analyze-project-for-legacy-provenance 
                     db "com.example" "my-lib")]
        (is (= :source-match (:pattern analysis)))
        (is (= vdb/VERIFICATION-METHOD-SOURCE-MATCH (:minimum-method analysis)))
        (is (= 3 (:verified-count analysis)))
        (is (true? (:has-sufficient-data? analysis)))))))

(deftest test-apply-legacy-provenance
  (testing "Applying legacy-provenance sets minimum verification method"
    (let [db (help/db)]
      ;; Create a group and add some users
      (help/add-group db "testuser" "com.example")
      
      ;; Add jar versions
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.0.0"})
      
      ;; Add verification
      (vdb/add-jar-verification db
        {:group-name "com.example"
         :jar-name "my-lib"
         :version "1.0.0"
         :verification-status vdb/VERIFICATION-STATUS-VERIFIED
         :verification-method vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
         :repo-url "https://github.com/example/my-lib"
         :commit-tag "v1.0.0"
         :commit-sha nil
         :attestation-url nil
         :reproducibility-script-url nil
         :verification-notes nil})
      
      ;; Apply legacy-provenance
      (let [analysis (vgf/apply-legacy-provenance db "com.example" "my-lib")]
        ;; Should have analyzed and set the minimum method
        (is (some? (:pattern analysis)))
        
        ;; Check that settings were updated
        (let [settings (vdb/get-verification-settings db "com.example")]
          (is (some? (:minimum_verification_method settings)))
          (is (true? (:verification_legacy-provenance settings)))
          (is (some? (:verification_last_analyzed settings))))))))

(deftest test-find-recent-versions
  (testing "Finding recent versions for analysis"
    (let [db (help/db)]
      ;; Create a group and add some jars
      (help/add-group db "testuser" "com.example")
      
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.0.0"})
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.1.0"})
      (help/add-jar db "testuser"
                   {:group-id "com.example"
                    :artifact-id "my-lib"
                    :version "1.2.0"})
      
      ;; Find recent versions
      (let [versions (vdb/find-recent-versions db "com.example" "my-lib" 5)]
        (is (= 3 (count versions)))
        ;; Should be in descending order by creation date
        (is (= "1.2.0" (:version (first versions))))))))
