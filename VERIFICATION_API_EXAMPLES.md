# Verification API Examples

This document provides practical examples of using the Clojars verification API.

## REST API Examples

### Example 1: Check if a JAR version is verified

```bash
# Using curl
curl -s https://clojars.org/api/artifacts/org.clojure/clojure/1.11.0/verification | jq

# Expected response for a verified JAR:
{
  "verification_status": "verified",
  "verification_method": "source-match",
  "repo_url": "https://github.com/clojure/clojure",
  "commit_sha": "abc123...",
  "commit_tag": "v1.11.0",
  "attestation_url": "https://github.com/clojure/clojure/actions/runs/...",
  "verified_at": "2024-01-15T10:30:00Z"
}

# For an unverified JAR, you'll get a 404
```

### Example 2: Get verification metrics for a project

```bash
# Using curl
curl -s https://clojars.org/api/artifacts/org.clojure/clojure/verification/metrics | jq

# Expected response:
{
  "total-versions": 50,
  "verified-count": 35,
  "verification-records-count": 40,
  "verification-rate": 0.7
}
```

### Example 3: Get all verification records

```bash
# Using curl
curl -s https://clojars.org/api/artifacts/org.clojure/clojure/verification/all | jq

# Expected response:
{
  "verifications": [
    {
      "version": "1.11.0",
      "verification_status": "verified",
      "verification_method": "source-match",
      "repo_url": "https://github.com/clojure/clojure",
      "commit_sha": "abc123...",
      "commit_tag": "v1.11.0",
      "attestation_url": "https://github.com/clojure/clojure/actions/runs/...",
      "verified_at": "2024-01-15T10:30:00Z"
    },
    // ... more versions
  ]
}
```

## Clojure Examples

### Example 1: Using clj-http

```clojure
(require '[clj-http.client :as http]
         '[cheshire.core :as json])

(defn get-verification-status
  "Get verification status for a specific JAR version."
  [group artifact version]
  (try
    (-> (http/get (format "https://clojars.org/api/artifacts/%s/%s/%s/verification"
                          group artifact version)
                  {:accept :json
                   :as :json})
        :body)
    (catch clojure.lang.ExceptionInfo e
      (when (= 404 (:status (ex-data e)))
        {:verification_status "unverified"
         :message "No verification record found"}))))

;; Usage
(get-verification-status "org.clojure" "clojure" "1.11.0")
;; => {:verification_status "verified", :verification_method "source-match", ...}

(get-verification-status "some.lib" "library" "1.0.0")
;; => {:verification_status "unverified", :message "No verification record found"}
```

### Example 2: Get metrics for multiple projects

```clojure
(defn get-verification-metrics
  "Get verification metrics for a project."
  [group artifact]
  (-> (http/get (format "https://clojars.org/api/artifacts/%s/%s/verification/metrics"
                        group artifact)
                {:accept :json
                 :as :json})
      :body))

(defn check-projects
  "Check verification metrics for multiple projects."
  [projects]
  (map (fn [[group artifact]]
         (let [metrics (get-verification-metrics group artifact)]
           {:project (str group "/" artifact)
            :total-versions (:total-versions metrics)
            :verified-count (:verified-count metrics)
            :verification-rate (* 100 (:verification-rate metrics))}))
       projects))

;; Usage
(check-projects [["org.clojure" "clojure"]
                 ["ring" "ring-core"]
                 ["compojure" "compojure"]])
;; => ({:project "org.clojure/clojure", :total-versions 50, :verified-count 35, :verification-rate 70.0}
;;     {:project "ring/ring-core", :total-versions 30, :verified-count 20, :verification-rate 66.67}
;;     ...)
```

### Example 3: Build a verification report

```clojure
(defn verification-report
  "Generate a verification report for a list of dependencies."
  [deps]
  (let [results (map (fn [[group artifact version]]
                       (let [status (get-verification-status group artifact version)]
                         {:dependency (format "%s/%s %s" group artifact version)
                          :verified? (= "verified" (:verification_status status))
                          :status (:verification_status status)
                          :method (:verification_method status)
                          :repo (:repo_url status)}))
                     deps)
        verified-count (count (filter :verified? results))
        total-count (count results)]
    {:summary {:total total-count
               :verified verified-count
               :unverified (- total-count verified-count)
               :percentage (/ (* 100.0 verified-count) total-count)}
     :details results}))

;; Usage
(verification-report [["org.clojure" "clojure" "1.11.0"]
                      ["ring" "ring-core" "1.9.0"]
                      ["compojure" "compojure" "1.7.0"]])
;; => {:summary {:total 3, :verified 2, :unverified 1, :percentage 66.67}
;;     :details [{:dependency "org.clojure/clojure 1.11.0", :verified? true, ...}
;;               {:dependency "ring/ring-core 1.9.0", :verified? true, ...}
;;               {:dependency "compojure/compojure 1.7.0", :verified? false, ...}]}
```

## Tool Integration Examples

### Example 1: Leiningen Plugin (Conceptual)

```clojure
;; In a hypothetical lein plugin
(ns leiningen.check-verification
  (:require [clj-http.client :as http]
            [leiningen.core.project :as project]))

(defn check-verification
  "Check verification status of all dependencies."
  [project]
  (let [deps (project/get-dependencies project)]
    (doseq [[group artifact version] deps]
      (let [status (get-verification-status group artifact version)]
        (println (format "%s/%s %s: %s"
                        group artifact version
                        (:verification_status status)))))))

;; Usage: lein check-verification
```

### Example 2: CLI Tool (Conceptual)

```bash
#!/usr/bin/env bash
# check-jar-verification.sh - Check if a JAR is verified

GROUP=$1
ARTIFACT=$2
VERSION=$3

if [ -z "$GROUP" ] || [ -z "$ARTIFACT" ] || [ -z "$VERSION" ]; then
    echo "Usage: $0 GROUP ARTIFACT VERSION"
    echo "Example: $0 org.clojure clojure 1.11.0"
    exit 1
fi

RESPONSE=$(curl -s "https://clojars.org/api/artifacts/$GROUP/$ARTIFACT/$VERSION/verification")

if echo "$RESPONSE" | grep -q "verification_status"; then
    STATUS=$(echo "$RESPONSE" | jq -r '.verification_status')
    echo "$GROUP/$ARTIFACT $VERSION: $STATUS"
    
    if [ "$STATUS" = "verified" ]; then
        exit 0
    else
        exit 1
    fi
else
    echo "$GROUP/$ARTIFACT $VERSION: unverified (no record found)"
    exit 1
fi
```

### Example 3: CI/CD Integration

```yaml
# .github/workflows/check-dependencies.yml
name: Check Dependency Verification

on: [push, pull_request]

jobs:
  check-deps:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4
      
      - name: Check verification status of dependencies
        run: |
          #!/bin/bash
          set -e
          
          # Extract dependencies from project.clj or deps.edn
          # This is a simplified example
          DEPS=(
            "org.clojure/clojure:1.11.0"
            "ring/ring-core:1.9.0"
          )
          
          UNVERIFIED=0
          for DEP in "${DEPS[@]}"; do
            IFS=':' read -r COORD VERSION <<< "$DEP"
            IFS='/' read -r GROUP ARTIFACT <<< "$COORD"
            
            STATUS=$(curl -s "https://clojars.org/api/artifacts/$GROUP/$ARTIFACT/$VERSION/verification" \
                     | jq -r '.verification_status // "unverified"')
            
            echo "$DEP: $STATUS"
            
            if [ "$STATUS" != "verified" ]; then
              UNVERIFIED=$((UNVERIFIED + 1))
            fi
          done
          
          if [ $UNVERIFIED -gt 0 ]; then
            echo "WARNING: $UNVERIFIED unverified dependencies found"
            # Optionally fail the build
            # exit 1
          fi
```

## Response Status Codes

- `200 OK`: Verification record found
- `404 Not Found`: No verification record exists for this artifact
- `400 Bad Request`: Invalid request parameters

## Rate Limiting

The Clojars API does not currently have rate limiting, but please be respectful:

- Cache responses when possible
- Don't make unnecessary repeated requests
- Consider using batch endpoints when available

## Best Practices

1. **Cache verification status**: Don't check the same artifact repeatedly
2. **Handle 404s gracefully**: No record doesn't necessarily mean "unsafe"
3. **Check metrics, not just status**: A project with 90% verified versions is different from one with 10%
4. **Consider verification date**: Recent verifications are more trustworthy
5. **Check attestation links**: When available, verify the build attestation

## Future API Endpoints

Planned future endpoints:

- `GET /api/verification/recent` - Recently verified artifacts
- `GET /api/verification/stats` - Overall verification statistics
- `GET /api/verification/feed` - Feed of verification events
- `POST /api/verification/check` - Batch verification checking

## Questions or Issues?

- Documentation: [VERIFICATION.md](VERIFICATION.md)
- Issues: [clojars-web/issues](https://github.com/clojars/clojars-web/issues)
- Discussion: #clojars on Clojurians Slack
