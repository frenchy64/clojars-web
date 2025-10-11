(ns clojars.unit.verification-db-test
  (:require
   [clojars.db :as db]
   [clojars.test-helper :as help]
   [clojars.verification-db :as verification-db]
   [clojure.test :refer [deftest is testing use-fixtures]]))

(use-fixtures :each
  help/with-clean-database)

(defn- add-test-jar
  "Helper to add a test jar to the database."
  [db]
  (db/add-user db "test@example.com" "testuser" "password")
  (db/add-member db "org.clojars.testuser" db/SCOPE-ALL "testuser" "testuser")
  (db/add-jar db "testuser"
              {:group "org.clojars.testuser"
               :name "test-jar"
               :version "1.0.0"
               :description "A test jar"
               :homepage "https://example.com"
               :authors ["Test User"]
               :packaging :jar
               :dependencies []}))

(deftest add-and-find-jar-verification
  (testing "Can add and retrieve jar verification"
    (add-test-jar help/*db*)
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "test-jar"
      :version "1.0.0"
      :verification-status verification-db/VERIFICATION-STATUS-VERIFIED
      :verification-method verification-db/VERIFICATION-METHOD-SOURCE-MATCH
      :repo-url "https://github.com/testuser/test-jar"
      :commit-sha "abc123"
      :commit-tag "v1.0.0"})
    
    (let [verification (verification-db/find-jar-verification
                        help/*db*
                        "org.clojars.testuser"
                        "test-jar"
                        "1.0.0")]
      (is (some? verification))
      (is (= "verified" (:verification_status verification)))
      (is (= "source-match" (:verification_method verification)))
      (is (= "https://github.com/testuser/test-jar" (:repo_url verification)))
      (is (= "abc123" (:commit_sha verification)))
      (is (= "v1.0.0" (:commit_tag verification))))))

(deftest find-nonexistent-verification
  (testing "Returns nil for nonexistent verification"
    (let [verification (verification-db/find-jar-verification
                        help/*db*
                        "nonexistent"
                        "jar"
                        "1.0.0")]
      (is (nil? verification)))))

(deftest update-jar-verification
  (testing "Can update existing verification"
    (add-test-jar help/*db*)
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "test-jar"
      :version "1.0.0"
      :verification-status verification-db/VERIFICATION-STATUS-PENDING
      :verification-method nil})
    
    (verification-db/update-jar-verification
     help/*db*
     "org.clojars.testuser"
     "test-jar"
     "1.0.0"
     {:verification_status verification-db/VERIFICATION-STATUS-VERIFIED
      :verification_method verification-db/VERIFICATION-METHOD-SOURCE-MATCH
      :commit_sha "def456"})
    
    (let [verification (verification-db/find-jar-verification
                        help/*db*
                        "org.clojars.testuser"
                        "test-jar"
                        "1.0.0")]
      (is (= "verified" (:verification_status verification)))
      (is (= "source-match" (:verification_method verification)))
      (is (= "def456" (:commit_sha verification))))))

(deftest count-verified-versions
  (testing "Counts verified versions correctly"
    (add-test-jar help/*db*)
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "test-jar"
                 :version "1.1.0"
                 :description "A test jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    
    ;; Add verification for v1.0.0 as verified
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "test-jar"
      :version "1.0.0"
      :verification-status verification-db/VERIFICATION-STATUS-VERIFIED
      :verification-method verification-db/VERIFICATION-METHOD-SOURCE-MATCH})
    
    ;; Add verification for v1.1.0 as unverified
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "test-jar"
      :version "1.1.0"
      :verification-status verification-db/VERIFICATION-STATUS-UNVERIFIED
      :verification-method nil})
    
    (let [verified-count (verification-db/count-verified-versions
                          help/*db*
                          "org.clojars.testuser"
                          "test-jar")]
      (is (= 1 verified-count)))))

(deftest verification-metrics
  (testing "Calculates verification metrics correctly"
    (add-test-jar help/*db*)
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "test-jar"
                 :version "1.1.0"
                 :description "A test jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "test-jar"
                 :version "1.2.0"
                 :description "A test jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    
    ;; Verify only 1 of 3 versions
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "test-jar"
      :version "1.0.0"
      :verification-status verification-db/VERIFICATION-STATUS-VERIFIED
      :verification-method verification-db/VERIFICATION-METHOD-SOURCE-MATCH})
    
    (let [metrics (verification-db/verification-metrics
                   help/*db*
                   "org.clojars.testuser"
                   "test-jar")]
      (is (= 3 (:total-versions metrics)))
      (is (= 1 (:verified-count metrics)))
      (is (= 1 (:verification-records-count metrics)))
      (is (< 0.33 (:verification-rate metrics) 0.34)))))

(deftest find-unverified-jars
  (testing "Finds jars without verification records"
    (add-test-jar help/*db*)
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "verified-jar"
                 :version "1.0.0"
                 :description "A verified jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    
    ;; Add verification for one jar
    (verification-db/add-jar-verification
     help/*db*
     {:group-name "org.clojars.testuser"
      :jar-name "verified-jar"
      :version "1.0.0"
      :verification-status verification-db/VERIFICATION-STATUS-VERIFIED
      :verification-method verification-db/VERIFICATION-METHOD-SOURCE-MATCH})
    
    (let [unverified (verification-db/find-unverified-jars help/*db* 10 0)]
      ;; Should find test-jar v1.0.0 but not verified-jar v1.0.0
      (is (= 1 (count unverified)))
      (is (= "test-jar" (:jar_name (first unverified)))))))

(deftest test-new-verification-methods
  (testing "Can use new verification method constants"
    (add-test-jar help/*db*)
    
    ;; Test all new verification methods
    (doseq [[method status] [[verification-db/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-ATTESTATION-GITLAB-SLSA
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-ATTESTATION-JENKINS
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-ATTESTATION-CIRCLECI
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS
                               verification-db/VERIFICATION-STATUS-PARTIAL]
                              [verification-db/VERIFICATION-METHOD-MANUAL-VERIFIED
                               verification-db/VERIFICATION-STATUS-VERIFIED]
                              [verification-db/VERIFICATION-METHOD-UNVERIFIED-LEGACY-PROVENANCE
                               verification-db/VERIFICATION-STATUS-UNVERIFIED]]]
      (verification-db/add-jar-verification
       help/*db*
       {:group-name "org.clojars.testuser"
        :jar-name "test-jar"
        :version (str "1.0.0-" (name method))
        :verification-status status
        :verification-method method
        :repo-url "https://github.com/testuser/test-jar"})
      
      (let [verification (verification-db/find-jar-verification
                          help/*db*
                          "org.clojars.testuser"
                          "test-jar"
                          (str "1.0.0-" (name method)))]
        (is (some? verification))
        (is (= method (:verification_method verification)))
        (is (= status (:verification_status verification)))))))

(deftest test-verification-method-hierarchy
  (testing "Verification method hierarchy works correctly"
    ;; Same level
    (is (verification-db/verification-method-meets-requirement?
         verification-db/VERIFICATION-METHOD-SOURCE-MATCH
         verification-db/VERIFICATION-METHOD-SOURCE-MATCH))
    
    ;; Higher trust meets lower requirement
    (is (verification-db/verification-method-meets-requirement?
         verification-db/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED
         verification-db/VERIFICATION-METHOD-SOURCE-MATCH))
    
    (is (verification-db/verification-method-meets-requirement?
         verification-db/VERIFICATION-METHOD-SOURCE-MATCH
         verification-db/VERIFICATION-METHOD-SOURCE-MATCH-APPROX))
    
    ;; Lower trust doesn't meet higher requirement
    (is (not (verification-db/verification-method-meets-requirement?
              verification-db/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
              verification-db/VERIFICATION-METHOD-SOURCE-MATCH)))
    
    (is (not (verification-db/verification-method-meets-requirement?
              verification-db/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS
              verification-db/VERIFICATION-METHOD-ATTESTATION-GITHUB-TRUSTED)))))

(deftest test-group-verification-settings
  (testing "Can set and retrieve group verification settings"
    ;; Initially no settings
    (is (nil? (verification-db/get-minimum-verification-method help/*db* "com.example")))
    
    ;; Set minimum verification method
    (verification-db/set-minimum-verification-method
     help/*db*
     "com.example"
     verification-db/VERIFICATION-METHOD-SOURCE-MATCH
     true)
    
    ;; Retrieve it
    (is (= verification-db/VERIFICATION-METHOD-SOURCE-MATCH
           (verification-db/get-minimum-verification-method help/*db* "com.example")))
    
    ;; Get full settings
    (let [settings (verification-db/get-verification-settings help/*db* "com.example")]
      (is (= verification-db/VERIFICATION-METHOD-SOURCE-MATCH
             (:minimum_verification_method settings)))
      (is (true? (:verification_legacy_provenance settings)))
      (is (some? (:verification_last_analyzed settings))))
    
    ;; Update settings
    (verification-db/set-minimum-verification-method
     help/*db*
     "com.example"
     verification-db/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
     false)
    
    (let [settings (verification-db/get-verification-settings help/*db* "com.example")]
      (is (= verification-db/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
             (:minimum_verification_method settings)))
      (is (false? (:verification_legacy_provenance settings))))))

(deftest test-find-recent-versions
  (testing "Can find recent versions for a jar"
    (add-test-jar help/*db*)
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "test-jar"
                 :version "1.1.0"
                 :description "A test jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    (db/add-jar help/*db* "testuser"
                {:group "org.clojars.testuser"
                 :name "test-jar"
                 :version "1.2.0"
                 :description "A test jar"
                 :authors ["Test User"]
                 :packaging :jar
                 :dependencies []})
    
    (let [versions (verification-db/find-recent-versions
                    help/*db*
                    "org.clojars.testuser"
                    "test-jar"
                    5)]
      (is (= 3 (count versions)))
      ;; Should be in descending order
      (is (= "1.2.0" (:version (first versions)))))))

