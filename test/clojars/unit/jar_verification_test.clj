(ns clojars.unit.jar-verification-test
  (:require
   [clojars.jar-verification :as jar-verification]
   [clojure.test :refer [deftest is testing]]))

(deftest parse-git-url-github
  (testing "Parses GitHub URLs correctly"
    (let [result (jar-verification/parse-git-url "https://github.com/clojars/clojars-web")]
      (is (= :github (:host result)))
      (is (= "clojars" (:owner result)))
      (is (= "clojars-web" (:repo-name result)))
      (is (= "https://github.com/clojars/clojars-web" (:url result))))
    
    (let [result (jar-verification/parse-git-url "https://github.com/user/repo.git")]
      (is (= :github (:host result)))
      (is (= "user" (:owner result)))
      (is (= "repo" (:repo-name result))))))

(deftest parse-git-url-gitlab
  (testing "Parses GitLab URLs correctly"
    (let [result (jar-verification/parse-git-url "https://gitlab.com/user/project")]
      (is (= :gitlab (:host result)))
      (is (= "user" (:owner result)))
      (is (= "project" (:repo-name result)))
      (is (= "https://gitlab.com/user/project" (:url result))))))

(deftest parse-git-url-unknown
  (testing "Handles unknown git hosts"
    (let [result (jar-verification/parse-git-url "https://example.com/repo.git")]
      (is (= :unknown (:host result)))
      (is (= "https://example.com/repo.git" (:url result))))))

(deftest parse-git-url-invalid
  (testing "Returns nil for invalid URLs"
    (is (nil? (jar-verification/parse-git-url "not a url")))))

(deftest extract-scm-info
  (testing "Extracts SCM info from POM data"
    (let [pom-data {:scm {:url "https://github.com/user/repo"
                          :connection "scm:git:git://github.com/user/repo.git"
                          :developerConnection "scm:git:ssh://git@github.com/user/repo.git"
                          :tag "v1.0.0"}}
          result (jar-verification/extract-scm-info pom-data)]
      (is (= "https://github.com/user/repo" (:url result)))
      (is (= "scm:git:git://github.com/user/repo.git" (:connection result)))
      (is (= "scm:git:ssh://git@github.com/user/repo.git" (:developer-connection result)))
      (is (= "v1.0.0" (:tag result))))))

(deftest extract-repo-info
  (testing "Extracts repository info from POM data"
    (let [pom-data {:scm {:url "https://github.com/clojars/clojars-web"
                          :tag "v1.0.0"}}
          result (jar-verification/extract-repo-info pom-data)]
      (is (= "https://github.com/clojars/clojars-web" (:repo-url result)))
      (is (= "v1.0.0" (:commit-tag result)))
      (is (= :github (:repo-host result)))
      (is (= "clojars" (:repo-owner result)))
      (is (= "clojars-web" (:repo-name result))))))

(deftest validate-repo-url-valid
  (testing "Validates valid repository URLs"
    (is (:valid? (jar-verification/validate-repo-url "https://github.com/user/repo")))
    (is (:valid? (jar-verification/validate-repo-url "https://gitlab.com/user/repo")))))

(deftest validate-repo-url-invalid
  (testing "Rejects invalid repository URLs"
    (let [result (jar-verification/validate-repo-url nil)]
      (is (not (:valid? result)))
      (is (= "No repository URL provided" (:error result))))
    
    (let [result (jar-verification/validate-repo-url "http://github.com/user/repo")]
      (is (not (:valid? result)))
      (is (= "Repository URL must use HTTPS" (:error result))))
    
    (let [result (jar-verification/validate-repo-url "https://example.com/repo")]
      (is (not (:valid? result)))
      (is (= "Repository must be hosted on GitHub or GitLab" (:error result))))))

(deftest extract-verification-info
  (testing "Extracts verification info for storage"
    (let [pom-data {:scm {:url "https://github.com/user/repo"
                          :tag "v1.0.0"}}
          result (jar-verification/extract-verification-info
                  "org.clojars.user"
                  "my-lib"
                  "1.0.0"
                  pom-data)]
      (is (= "org.clojars.user" (:group-name result)))
      (is (= "my-lib" (:jar-name result)))
      (is (= "1.0.0" (:version result)))
      (is (= "https://github.com/user/repo" (:repo-url result)))
      (is (= "v1.0.0" (:commit-tag result)))
      (is (= "pending" (:verification-status result))))))
