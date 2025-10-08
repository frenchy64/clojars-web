(ns clojars.unit.attestation-verification-test
  (:require
   [clojars.attestation-verification :as av]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(def test-trusted-workflows
  [{:repo "clojars/clojars-web"
    :workflow ".github/workflows/attestable-build-lein.yml"
    :ref "refs/heads/main"}
   {:repo "clojars/clojars-web"
    :workflow ".github/workflows/attestable-clojure-cli.yml"
    :ref "refs/heads/main"}
   {:repo "clojars/clojars-web"
    :workflow ".github/workflows/attestable-build-lein.yml"
    :ref "refs/tags/*"}])

(deftest parse-workflow-ref-test
  (testing "Valid workflow reference parsing"
    (is (= {:repo "clojars/clojars-web"
            :workflow ".github/workflows/attestable-build-lein.yml"
            :ref "main"}
           (#'av/parse-workflow-ref "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@main")))
    
    (is (= {:repo "org/repo"
            :workflow ".github/workflows/build.yml"
            :ref "refs/heads/develop"}
           (#'av/parse-workflow-ref "org/repo/.github/workflows/build.yml@refs/heads/develop")))
    
    (is (= {:repo "user/project"
            :workflow ".github/workflows/release.yml"
            :ref "v1.0.0"}
           (#'av/parse-workflow-ref "user/project/.github/workflows/release.yml@v1.0.0"))))
  
  (testing "Invalid workflow reference parsing"
    (is (nil? (#'av/parse-workflow-ref nil)))
    (is (nil? (#'av/parse-workflow-ref "")))
    (is (nil? (#'av/parse-workflow-ref "invalid-format")))
    (is (nil? (#'av/parse-workflow-ref "org/repo/workflows/build.yml@main"))) ;; Missing .github
    (is (nil? (#'av/parse-workflow-ref "org/repo/.github@main"))))) ;; No workflow file

(deftest ref-matches-test
  (testing "Exact ref matching"
    (is (#'av/ref-matches? "refs/heads/main" "refs/heads/main"))
    (is (#'av/ref-matches? "refs/tags/v1.0.0" "refs/tags/v1.0.0"))
    (is (not (#'av/ref-matches? "refs/heads/develop" "refs/heads/main"))))
  
  (testing "Wildcard ref matching"
    (is (#'av/ref-matches? "refs/tags/v1.0.0" "refs/tags/*"))
    (is (#'av/ref-matches? "refs/tags/v2.3.4" "refs/tags/v*"))
    (is (#'av/ref-matches? "refs/heads/main" "refs/heads/*"))
    (is (not (#'av/ref-matches? "refs/heads/main" "refs/tags/*")))
    (is (not (#'av/ref-matches? "refs/tags/1.0.0" "refs/tags/v*"))))
  
  (testing "No constraint (nil pattern)"
    (is (#'av/ref-matches? "refs/heads/main" nil))
    (is (#'av/ref-matches? "refs/tags/v1.0.0" nil))
    (is (#'av/ref-matches? "anything" nil))))

(deftest workflow-trusted-test
  (testing "Trusted workflow detection"
    ;; Main branch workflow
    (is (#'av/workflow-trusted?
         {:repo "clojars/clojars-web"
          :workflow ".github/workflows/attestable-build-lein.yml"
          :ref "refs/heads/main"}
         test-trusted-workflows))
    
    ;; Tag pattern match
    (is (#'av/workflow-trusted?
         {:repo "clojars/clojars-web"
          :workflow ".github/workflows/attestable-build-lein.yml"
          :ref "refs/tags/v1.0.0"}
         test-trusted-workflows))
    
    ;; Another trusted workflow
    (is (#'av/workflow-trusted?
         {:repo "clojars/clojars-web"
          :workflow ".github/workflows/attestable-clojure-cli.yml"
          :ref "refs/heads/main"}
         test-trusted-workflows)))
  
  (testing "Untrusted workflow detection"
    ;; Wrong repo
    (is (not (#'av/workflow-trusted?
              {:repo "malicious/repo"
               :workflow ".github/workflows/attestable-build-lein.yml"
               :ref "refs/heads/main"}
              test-trusted-workflows)))
    
    ;; Wrong workflow file
    (is (not (#'av/workflow-trusted?
              {:repo "clojars/clojars-web"
               :workflow ".github/workflows/custom-build.yml"
               :ref "refs/heads/main"}
              test-trusted-workflows)))
    
    ;; Wrong ref
    (is (not (#'av/workflow-trusted?
              {:repo "clojars/clojars-web"
               :workflow ".github/workflows/attestable-build-lein.yml"
               :ref "refs/heads/feature-branch"}
              test-trusted-workflows)))
    
    ;; Tag doesn't match pattern
    (is (not (#'av/workflow-trusted?
              {:repo "clojars/clojars-web"
               :workflow ".github/workflows/attestable-clojure-cli.yml"
               :ref "refs/tags/v1.0.0"} ;; clojure-cli workflow doesn't have tag pattern
              test-trusted-workflows)))))

(deftest verify-attestation-workflow-test
  (testing "Valid trusted workflow verification"
    (let [result (av/verify-attestation-workflow
                  "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@refs/heads/main"
                  test-trusted-workflows)]
      (is (:valid result))
      (is (= "clojars/clojars-web" (get-in result [:workflow :repo])))
      (is (= ".github/workflows/attestable-build-lein.yml" (get-in result [:workflow :workflow]))))
    
    (let [result (av/verify-attestation-workflow
                  "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@refs/tags/v1.2.3"
                  test-trusted-workflows)]
      (is (:valid result))))
  
  (testing "Invalid workflow reference"
    (let [result (av/verify-attestation-workflow
                  "invalid-format"
                  test-trusted-workflows)]
      (is (not (:valid result)))
      (is (str/includes? (:reason result) "Could not parse"))))
  
  (testing "Untrusted workflow"
    (let [result (av/verify-attestation-workflow
                  "malicious/repo/.github/workflows/build.yml@main"
                  test-trusted-workflows)]
      (is (not (:valid result)))
      (is (str/includes? (:reason result) "not in the trusted workflow list")))
    
    (let [result (av/verify-attestation-workflow
                  "clojars/clojars-web/.github/workflows/custom.yml@main"
                  test-trusted-workflows)]
      (is (not (:valid result)))
      (is (str/includes? (:reason result) "not in the trusted workflow list")))))

(deftest trusted-workflow-list-test
  (testing "Default trusted workflows"
    (let [workflows (av/trusted-workflow-list)]
      (is (seq workflows))
      (is (every? #(and (:repo %) (:workflow %)) workflows))))
  
  (testing "Custom config override"
    (let [custom-workflows [{:repo "custom/repo" :workflow ".github/workflows/build.yml"}]
          workflows (av/trusted-workflow-list {:trusted-workflows custom-workflows})]
      (is (= custom-workflows workflows)))))

(deftest format-trusted-workflow-test
  (testing "Workflow formatting"
    (is (= "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@refs/heads/main"
           (av/format-trusted-workflow
            {:repo "clojars/clojars-web"
             :workflow ".github/workflows/attestable-build-lein.yml"
             :ref "refs/heads/main"})))
    
    (is (= "org/repo/.github/workflows/build.yml"
           (av/format-trusted-workflow
            {:repo "org/repo"
             :workflow ".github/workflows/build.yml"})))))

(deftest list-trusted-workflows-test
  (testing "List all trusted workflows"
    (let [listed (av/list-trusted-workflows {:trusted-workflows test-trusted-workflows})]
      (is (= 3 (count listed)))
      (is (every? string? listed))
      (is (some #(str/includes? % "attestable-build-lein") listed))
      (is (some #(str/includes? % "attestable-clojure-cli") listed)))))

(deftest integration-test
  (testing "Full workflow: attestation from trusted source accepted"
    (let [workflow-ref "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@refs/heads/main"
          result (av/verify-attestation-workflow workflow-ref test-trusted-workflows)]
      (is (:valid result))
      (is (= "clojars/clojars-web" (get-in result [:workflow :repo])))))
  
  (testing "Full workflow: attestation from untrusted source rejected"
    (let [workflow-ref "untrusted/repo/.github/workflows/malicious.yml@main"
          result (av/verify-attestation-workflow workflow-ref test-trusted-workflows)]
      (is (not (:valid result)))
      (is (string? (:reason result))))))
