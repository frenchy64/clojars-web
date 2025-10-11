(ns clojars.verification-legacy-provenance
  "Logic for analyzing historical artifacts and determining minimum verification levels.
  
  This namespace implements the legacy provenance analysis that examines a project's
  historical deployments to determine appropriate verification requirements going forward."
  (:require
   [clojars.verification-db :as vdb]
   [clojure.string :as str]))

(set! *warn-on-reflection* true)

;; Analysis result patterns
(def PATTERN-SOURCE-MATCH :source-match)
(def PATTERN-SOURCE-MATCH-APPROX :source-match-approx)
(def PATTERN-PARTIAL-BUILD-ARTIFACTS :partial-has-build-artifacts)
(def PATTERN-ATTESTATION-REQUIRED :attestation-required)
(def PATTERN-UNVERIFIED :unverified-legacy-provenance)

(defn- analyze-artifact-contents
  "Analyze the contents of a single artifact.
  
  This is a placeholder that would be implemented with actual JAR analysis.
  In practice, this would:
  - Extract JAR contents
  - Clone repository at specified tag/commit
  - Compare source files
  - Identify build artifacts (.class files)
  - Determine if artifacts are from dependencies or project code
  
  Returns a map with:
  - :source-only? - true if no .class files
  - :source-matches? - true if all source files match repo
  - :metadata-only-diff? - true if only timestamps/manifest differ
  - :has-build-artifacts? - true if has .class files
  - :class-from-project? - true if .class files are compiled from project source"
  [_jar-path _repo-url _commit-tag]
  ;; TODO: Implement actual analysis
  ;; For now, return a conservative default
  {:source-only? false
   :source-matches? false
   :metadata-only-diff? false
   :has-build-artifacts? true
   :class-from-project? false
   :analysis-available? false})

(defn- determine-pattern-from-analyses
  "Determine the overall verification pattern from multiple artifact analyses.
  
  Parameters:
  - analyses: sequence of analysis results from analyze-artifact-contents
  
  Returns one of the PATTERN-* constants."
  [analyses]
  (if (empty? analyses)
    PATTERN-UNVERIFIED
    (let [;; Calculate percentages of different patterns
          total (count analyses)
          source-only-count (count (filter :source-only? analyses))
          source-match-count (count (filter :source-matches? analyses))
          approx-count (count (filter :metadata-only-diff? analyses))
          
          ;; Consider a pattern dominant if it appears in >60% of versions
          source-only-dominant? (> (/ source-only-count total) 0.6)
          source-match-dominant? (> (/ source-match-count total) 0.6)
          approx-dominant? (> (/ approx-count total) 0.6)]
      
      (cond
        ;; Most versions are source-only with exact matches
        (and source-match-dominant? source-only-dominant?)
        PATTERN-SOURCE-MATCH
        
        ;; Most versions match source but have metadata differences
        (and source-match-dominant? approx-dominant?)
        PATTERN-SOURCE-MATCH-APPROX
        
        ;; Source matches but has build artifacts
        (and source-match-dominant? (not source-only-dominant?))
        PATTERN-PARTIAL-BUILD-ARTIFACTS
        
        ;; Cannot determine from source - require attestation
        (not source-match-dominant?)
        PATTERN-ATTESTATION-REQUIRED
        
        ;; Fallback for edge cases
        :else
        PATTERN-UNVERIFIED))))

(defn analyze-project-for-legacy-provenance
  "Analyze recent versions of a project to determine appropriate verification level.
  
  Parameters:
  - db: database connection
  - group-name: the group name
  - jar-name: the jar name
  
  Returns a map with:
  - :pattern - one of the PATTERN-* constants
  - :minimum-method - the recommended minimum verification method
  - :analyses - details of individual version analyses (for debugging)
  - :recommendation - human-readable explanation"
  [db group-name jar-name]
  (let [;; Get recent versions (last 5 or from last 2 years)
        recent-versions (vdb/find-recent-versions db group-name jar-name 5)
        
        ;; For each version, try to analyze it
        ;; In practice, this would download/access the artifact and analyze it
        analyses (map (fn [version]
                        (let [verification (vdb/find-jar-verification 
                                           db group-name jar-name (:version version))]
                          (if verification
                            ;; If already verified, use existing verification data
                            {:version (:version version)
                             :verified? true
                             :method (:verification_method verification)
                             :analysis-available? true}
                            ;; Otherwise, would need to analyze the artifact
                            ;; For now, mark as not analyzed
                            {:version (:version version)
                             :verified? false
                             :analysis-available? false})))
                      recent-versions)
        
        ;; Check if we have enough data to make a determination
        verified-count (count (filter :verified? analyses))
        has-sufficient-data? (or (>= verified-count 3)
                                (>= (count analyses) 5))
        
        ;; If we have verified versions, use those to determine pattern
        pattern (if has-sufficient-data?
                  (let [verified-methods (map :method (filter :verified? analyses))
                        ;; Determine dominant pattern from verified methods
                        source-match-count (count (filter #(= vdb/VERIFICATION-METHOD-SOURCE-MATCH %) 
                                                        verified-methods))
                        approx-count (count (filter #(= vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX %) 
                                                   verified-methods))
                        attestation-count (count (filter #(str/includes? % "attestation") 
                                                        verified-methods))]
                    (cond
                      (> source-match-count (* 0.6 verified-count))
                      PATTERN-SOURCE-MATCH
                      
                      (> approx-count (* 0.6 verified-count))
                      PATTERN-SOURCE-MATCH-APPROX
                      
                      (> attestation-count (* 0.6 verified-count))
                      PATTERN-ATTESTATION-REQUIRED
                      
                      :else
                      PATTERN-UNVERIFIED))
                  ;; Not enough data - be conservative
                  PATTERN-UNVERIFIED)
        
        ;; Map pattern to verification method
        minimum-method (case pattern
                        :source-match vdb/VERIFICATION-METHOD-SOURCE-MATCH
                        :source-match-approx vdb/VERIFICATION-METHOD-SOURCE-MATCH-APPROX
                        :partial-has-build-artifacts vdb/VERIFICATION-METHOD-PARTIAL-HAS-BUILD-ARTIFACTS
                        :attestation-required vdb/VERIFICATION-METHOD-ATTESTATION
                        :unverified-legacy-provenance vdb/VERIFICATION-METHOD-UNVERIFIED-LEGACY-PROVENANCE)
        
        ;; Generate recommendation text
        recommendation (case pattern
                        :source-match
                        "Project has consistently deployed source-only artifacts. New versions should be source-only."
                        
                        :source-match-approx
                        "Project has deployed reproducible source artifacts with minor metadata differences. This is acceptable."
                        
                        :partial-has-build-artifacts
                        "Project includes compiled code alongside Clojure source. Consider using build attestation for higher trust."
                        
                        :attestation-required
                        "Project cannot be verified from source. Build attestation is required."
                        
                        :unverified-legacy-provenance
                        "Insufficient data to determine verification pattern. Legacy provenance as unverified.")]
    
    {:pattern pattern
     :minimum-method minimum-method
     :analyses analyses
     :recommendation recommendation
     :analyzed-count (count analyses)
     :verified-count verified-count
     :has-sufficient-data? has-sufficient-data?}))

(defn apply-legacy-provenance
  "Apply legacy provenance analysis to a project and set minimum verification level.
  
  Parameters:
  - db: database connection
  - group-name: the group name
  - jar-name: the jar name (optional, for project-specific analysis)
  
  If jar-name is provided, analyzes that specific project.
  If jar-name is nil, applies group-wide settings.
  
  Returns the analysis result."
  [db group-name jar-name]
  (let [analysis (analyze-project-for-legacy-provenance db group-name jar-name)]
    ;; Set the minimum verification method for the group
    (vdb/set-minimum-verification-method 
     db 
     group-name 
     (:minimum-method analysis)
     true) ; Mark as legacy-provenance
    
    ;; Return the analysis for logging/reporting
    analysis))

(defn should-apply-new-project-defaults?
  "Determine if a project should get new project defaults (source-match required).
  
  New projects are those with:
  - No previous deployments, OR
  - First deployment after legacy provenance system is enabled
  
  Returns true if new project defaults should apply."
  [db group-name _jar-name]
  (let [settings (vdb/get-verification-settings db group-name)]
    ;; If no settings exist or verification was never analyzed, it's a new project
    (or (nil? settings)
        (nil? (:minimum_verification_method settings)))))

(defn get-verification-requirement
  "Get the verification requirement for a deployment.
  
  Parameters:
  - db: database connection  
  - group-name: the group name
  - jar-name: the jar name
  
  Returns a map with:
  - :required-method - the minimum verification method required
  - :is-legacy-provenance? - whether this is from legacy provenance
  - :is-new-project? - whether this is considered a new project"
  [db group-name jar-name]
  (let [settings (vdb/get-verification-settings db group-name)
        is-new-project? (should-apply-new-project-defaults? db group-name jar-name)]
    
    (if is-new-project?
      ;; New project - use strict defaults
      {:required-method vdb/VERIFICATION-METHOD-SOURCE-MATCH
       :is-legacy-provenance? false
       :is-new-project? true}
      
      ;; Existing project - use configured or legacy-provenance settings
      {:required-method (or (:minimum_verification_method settings)
                           vdb/VERIFICATION-METHOD-UNVERIFIED-LEGACY-PROVENANCE)
       :is-legacy-provenance? (:verification_legacy-provenance settings false)
       :is-new-project? false})))

(defn check-deployment-meets-requirements
  "Check if a deployment meets the verification requirements.
  
  Parameters:
  - db: database connection
  - group-name: the group name
  - jar-name: the jar name
  - actual-method: the verification method that was achieved
  
  Returns a map with:
  - :allowed? - whether deployment should be allowed
  - :reason - explanation if not allowed
  - :required-method - what was required
  - :actual-method - what was provided"
  [db group-name jar-name actual-method]
  (let [requirement (get-verification-requirement db group-name jar-name)
        required-method (:required-method requirement)
        meets-requirement? (vdb/verification-method-meets-requirement? 
                           actual-method required-method)]
    
    {:allowed? meets-requirement?
     :required-method required-method
     :actual-method actual-method
     :is-new-project? (:is-new-project? requirement)
     :is-legacy-provenance? (:is-legacy-provenance? requirement)
     :reason (when-not meets-requirement?
               (format "Deployment requires verification method '%s' or higher, but only '%s' was provided"
                      required-method
                      (or actual-method "none")))}))
