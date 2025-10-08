# Build Verification and Attestation System

## Overview

Clojars now includes a build verification and attestation system to help establish trust and provenance for JAR artifacts. This system tracks the verification status of artifacts and provides transparency about their build process.

## Current Features (Phase 1)

### Database Schema

The system uses a `jar_verifications` table to track verification information for each JAR version:

- **verification_status**: The verification state (verified, unverified, partial, failed, pending)
- **verification_method**: How the JAR was verified (source-match, attestation, manual)
- **repo_url**: The Git repository URL
- **commit_sha**: The Git commit SHA used to build the JAR
- **commit_tag**: The Git tag used to build the JAR
- **attestation_url**: Link to build attestation (e.g., GitHub Actions run)
- **reproducibility_script_url**: Link to script for reproducing the build
- **verification_notes**: Additional notes about the verification

### User Interface

On JAR detail pages, you'll see:

1. **Verification Badge**: A colored badge indicating the verification status
   - ✓ Verified (green): The JAR has been successfully verified
   - ⚠ Partially Verified (yellow): Some parts verified, but not complete
   - ⏳ Verification Pending (blue): Verification is in progress
   - ✗ Verification Failed (red): Verification process failed
   - ? Unverified (gray): No verification has been performed

2. **Verification Info**: Details about the verification including:
   - Verification method used
   - Repository URL
   - Git commit SHA and tag
   - Links to attestation and reproducibility scripts

3. **Verification Metrics**: Project-level statistics showing:
   - Number of verified versions vs total versions
   - Verification rate percentage
   - Warning if not all versions are verified

### API Endpoints

The following REST API endpoints are available for programmatic access:

#### Get verification status for a specific version
```
GET /api/artifacts/:group-id/:artifact-id/:version/verification
```

Returns verification details for a specific JAR version.

Example response:
```json
{
  "verification_status": "verified",
  "verification_method": "source-match",
  "repo_url": "https://github.com/user/project",
  "commit_sha": "abc123...",
  "commit_tag": "v1.0.0",
  "attestation_url": "https://github.com/user/project/actions/runs/...",
  "verified_at": "2024-01-15T10:30:00Z"
}
```

#### Get verification metrics for a project
```
GET /api/artifacts/:group-id/:artifact-id/verification/metrics
```

Returns aggregated verification metrics for all versions.

Example response:
```json
{
  "total-versions": 10,
  "verified-count": 7,
  "verification-records-count": 8,
  "verification-rate": 0.7
}
```

#### Get all verification records
```
GET /api/artifacts/:group-id/:artifact-id/verification/all
```

Returns verification records for all versions of a JAR.

## Verification Statuses

- **verified**: The JAR has been successfully verified against its source repository
- **partial**: Some verification checks passed, but not all (e.g., only Clojure sources verified in a mixed JAR)
- **pending**: Verification is currently in progress
- **failed**: Verification checks failed
- **unverified**: No verification has been performed

## Verification Methods

- **source-match**: JAR contents verified to exactly match the source repository
- **attestation**: JAR verified through build attestation (GitHub Actions, etc.)
- **manual**: JAR manually verified by maintainers

## Future Features

Future phases will include:

- Automatic verification during deployment
- Detection of JARs with build artifacts
- Reusable GitHub Actions workflows for attestable builds
- Batch verification of existing JARs
- Enhanced security warnings for unverified JARs

## For Maintainers

### Adding Verification Information

Currently, verification records are added through the database. Future phases will include:

1. Automatic extraction of repo information from POM files during deployment
2. Integration with GitHub Actions for automatic attestation
3. Tools for batch verification of existing artifacts

### Best Practices

To ensure your JARs can be verified:

1. Include complete SCM information in your `project.clj` or `pom.xml`
2. Tag your releases with version tags (e.g., `v1.0.0`)
3. Keep your artifacts source-only when possible
4. Use consistent versioning between Git tags and artifact versions

## Security Considerations

The verification system is designed to help users assess the trustworthiness of artifacts, but:

- Verification is not a guarantee of security
- Always review dependencies before using them
- Keep your dependencies up to date
- Report any suspicious artifacts to the Clojars team

## Contributing

This system is under active development. If you have feedback or want to contribute:

- Open an issue on [clojars-web](https://github.com/clojars/clojars-web)
- Join the discussion in the Clojurians Slack #clojars channel

## Database Migration

The verification system uses a database migration to add the `jar_verifications` table. The migration runs automatically when the application starts.

### Migration Details

Migration: `add-jar-verifications-table`

Creates:
- `jar_verifications` table with verification tracking fields
- Indexes for efficient querying by group/jar and status

## API Examples

### Using curl

Check verification status:
```bash
curl https://clojars.org/api/artifacts/org.clojure/clojure/1.11.0/verification
```

Get project metrics:
```bash
curl https://clojars.org/api/artifacts/org.clojure/clojure/verification/metrics
```

### Using Clojure

```clojure
(require '[clj-http.client :as http]
         '[cheshire.core :as json])

(defn get-verification-status [group artifact version]
  (-> (http/get (format "https://clojars.org/api/artifacts/%s/%s/%s/verification"
                        group artifact version)
                {:accept :json})
      :body
      (json/parse-string true)))

(get-verification-status "org.clojure" "clojure" "1.11.0")
```
