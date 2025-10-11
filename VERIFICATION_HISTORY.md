# Jar Verification History and Audit Trail

## Overview

The jar verification system now supports a complete audit trail of verification status changes over time. This allows Clojars to:

1. Track the full history of verification changes for each jar version
2. Document reasons for verification changes (e.g., security issues, policy changes)
3. Record actions taken in response to issues (e.g., jar deletion, CVE reporting)
4. Support retroactive verification degradation when security issues are discovered
5. Maintain accountability with tracked changes including who made the change and when

## Database Schema

### jar_verifications table

The main table storing the current verification status for each jar version:
- `(group_name, jar_name, version)` - unique constraint ensures one current record per version
- `verification_status` - current status (verified, unverified, failed, etc.)
- `verification_method` - method used for verification
- `verified_at` - timestamp of current verification

### jar_verification_history table (NEW)

Historical audit trail of all verification changes:
- `id` - primary key
- `group_name`, `jar_name`, `version` - jar identification
- `verification_status`, `verification_method`, etc. - same fields as main table
- `change_reason` - why the verification status changed (see below)
- `action_taken` - what action was performed in response (see below)
- `changed_by` - username or system identifier who made the change
- `changed_at` - timestamp of the change

## Change Reasons

Documented reasons for verification status changes (programmatically readable):

### Security-related reasons
- `compromised_workflow` - Build workflow or CI/CD pipeline compromised
- `hijacked_repo` - Source repository has been hijacked or taken over
- `malicious_non_main_branch` - Malicious code in non-main branch used for build
- `backdoor_detected` - Backdoor or malicious code detected in artifact
- `security_vulnerability` - Security vulnerability discovered in artifact

### Administrative reasons
- `initial_verification` - Initial verification of a jar version
- `reverification` - Re-verification of previously verified jar
- `build_system_update` - Update due to build system changes
- `policy_change` - Change due to updated verification policies
- `manual_review` - Manual review by administrator or security team
- `automated_scan` - Result of automated security scan

## Actions Taken

Actions performed in response to verification changes:

- `none` - No specific action, informational only
- `jar_deleted` - Jar artifact deleted from repository (critical, irreversible)
- `cve_reported` - CVE reported (high severity, irreversible)
- `user_notified` - User/group owner notified
- `group_suspended` - Entire group suspended from publishing (critical, reversible)
- `verification_downgraded` - Status downgraded to lower trust level (reversible)
- `verification_upgraded` - Status upgraded to higher trust level (reversible)
- `under_investigation` - Issue under investigation (temporary status, reversible)

## Usage Examples

### Recording initial verification

```clojure
(require '[clojars.verification-db :as vdb])

(vdb/add-jar-verification
  db
  {:group-name "com.example"
   :jar-name "my-library"
   :version "1.0.0"
   :verification-status vdb/VERIFICATION-STATUS-VERIFIED
   :verification-method vdb/VERIFICATION-METHOD-SOURCE-MATCH
   :repo-url "https://github.com/example/my-library"
   :commit-sha "abc123def456"
   :commit-tag "v1.0.0"
   :change-reason vdb/CHANGE-REASON-INITIAL-VERIFICATION
   :action-taken vdb/ACTION-TAKEN-NONE
   :changed-by "automation-bot"})
```

### Handling a security issue

```clojure
;; Downgrade verification due to compromised workflow
(vdb/update-jar-verification-with-history
  db
  "com.example"
  "my-library"
  "1.0.0"
  {:verification_status vdb/VERIFICATION-STATUS-FAILED
   :verification_notes "Compromised GitHub Actions workflow detected on 2025-10-11"}
  vdb/CHANGE-REASON-COMPROMISED-WORKFLOW
  vdb/ACTION-TAKEN-USER-NOTIFIED
  "security-team")
```

### Querying verification history

```clojure
;; Get full history for a specific version
(def history (vdb/find-jar-verification-history
               db "com.example" "my-library" "1.0.0"))

;; Get history for all versions of a jar
(def all-history (vdb/find-jar-verification-history-all-versions
                   db "com.example" "my-library"))

;; Find all backdoor detections
(def backdoors (vdb/find-verification-changes-by-reason
                 db vdb/CHANGE-REASON-BACKDOOR-DETECTED 100))

;; Find all deleted jars
(def deletions (vdb/find-verification-changes-by-action
                 db vdb/ACTION-TAKEN-JAR-DELETED 100))
```

## Retroactive Verification Degradation

When a security issue is discovered in a build system or attested workflow (as mentioned in the original issue), all jars using that system can be retroactively downgraded:

```clojure
;; Example: Compromise detected in GitHub Actions reusable workflow
;; Downgrade all jars that used this workflow

(doseq [jar affected-jars]
  (vdb/update-jar-verification-with-history
    db
    (:group_name jar)
    (:jar_name jar)
    (:version jar)
    {:verification_status vdb/VERIFICATION-STATUS-FAILED
     :verification_notes "Used compromised reusable workflow github.com/example/build@v1"}
    vdb/CHANGE-REASON-COMPROMISED-WORKFLOW
    vdb/ACTION-TAKEN-VERIFICATION-DOWNGRADED
    "security-audit-bot"))
```

## API Endpoints

The system provides REST API endpoints for querying verification history:

### Get History for Specific Version

```bash
GET /api/artifacts/:group/:artifact/:version/verification/history
```

Returns the complete verification history for a specific jar version.

**Example:**
```bash
curl https://clojars.org/api/artifacts/com.example/my-lib/1.0.0/verification/history
```

**Response:**
```json
{
  "history": [
    {
      "verification_status": "failed",
      "verification_method": "source-match",
      "change_reason": "compromised_workflow",
      "action_taken": "verification_downgraded",
      "changed_by": "security-audit-bot",
      "changed_at": "2025-10-11T10:30:00Z",
      "verification_notes": "Used compromised workflow"
    },
    {
      "verification_status": "verified",
      "verification_method": "source-match",
      "change_reason": "initial_verification",
      "action_taken": "none",
      "changed_by": "automation-bot",
      "changed_at": "2025-01-15T14:20:00Z"
    }
  ]
}
```

### Get History for All Versions

```bash
GET /api/artifacts/:group/:artifact/verification/history
```

Returns verification history for all versions of a jar.

### Query by Reason Code

```bash
GET /api/verification/changes/by-reason/:reason?limit=50
```

Find all verification changes with a specific reason code.

**Example:**
```bash
# Find all compromised workflow incidents
curl https://clojars.org/api/verification/changes/by-reason/compromised_workflow?limit=100
```

### Query by Action Code

```bash
GET /api/verification/changes/by-action/:action?limit=50
```

Find all verification changes where a specific action was taken.

**Example:**
```bash
# Find all deleted jars
curl https://clojars.org/api/verification/changes/by-action/jar_deleted?limit=50
```

**Parameters:**
- `limit` (optional): Number of results to return (default: 50, max: 100)

## Migration

The new `jar_verification_history` table is added via database migration:
- Migration name: `add-jar-verification-history-table`
- Creates table with indexes on `(group_name, jar_name, version)`, `changed_at`, `verification_status`, and `change_reason`
- Automatically runs when database is migrated

## Backward Compatibility

The changes are backward compatible:
- Existing code calling `add-jar-verification` without new parameters will work
- New parameters (`change-reason`, `action-taken`, `changed-by`) are optional
- Default values used when not provided:
  - `change-reason` defaults to `CHANGE-REASON-INITIAL-VERIFICATION`
  - `action-taken` defaults to `ACTION-TAKEN-NONE`
  - `changed-by` defaults to `nil`

## Future Enhancements

Potential future enhancements based on this system:

### Transitive Dependency Compromise Handling

When a compromised jar (A) was used to build another jar (B), the system can be extended to:

1. **Track build-time dependencies**: Store the SHA hashes of all dependencies used during build
   - This data can be sourced from attested build output
   - Enables transitive compromise detection

2. **Automated transitive analysis**: When jar A is downgraded:
   - Find all jars built using A (iteratively/transitively)
   - For jars that are source-identical with no build artifacts:
     - Add audit log entry with reason `CHANGE-REASON-PROVENANCE-UNCHANGED-DESPITE-COMPROMISED-DEP`
     - Action: `ACTION-TAKEN-AUDIT-LOG-UPDATED`
     - Note: Provenance unchanged because build artifacts are trivially reproducible
   - For jars containing build artifacts:
     - Flag for manual review with reason `CHANGE-REASON-TRANSITIVE-DEPENDENCY-COMPROMISED`
     - Action: `ACTION-TAKEN-FLAGGED-FOR-REVIEW`
     - Requires thorough maintainer review if automated reproducibility checks unavailable

3. **Verification level promotion**: Use this to incentivize higher verification levels
   - Homepage could showcase latest fully verified projects
   - Track projects making progress toward full verification
   - Projects at maximum verification levels are more resilient to transitive compromises

**Reason codes added for future use:**
- `CHANGE-REASON-TRANSITIVE-DEPENDENCY-COMPROMISED`
- `CHANGE-REASON-PROVENANCE-UNCHANGED-DESPITE-COMPROMISED-DEP`

**Action codes added for future use:**
- `ACTION-TAKEN-FLAGGED-FOR-REVIEW`
- `ACTION-TAKEN-AUDIT-LOG-UPDATED`

### Other Planned Enhancements

1. Web UI to view verification history for jars
2. RSS/Atom feeds for verification changes
3. Email notifications for verification downgrades
4. API endpoints to query verification history
5. Integration with security vulnerability databases
6. Automated scanning and retroactive analysis tools

## Reference

See also:
- `resources/verification-codes.edn` - Complete definitions of all reason and action codes
- `src/clojars/verification_db.clj` - Implementation
- `test/clojars/unit/verification_db_test.clj` - Test examples
