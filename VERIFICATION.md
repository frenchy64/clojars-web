# Build Verification and Attestation System

## Overview

Clojars now includes a build verification and attestation system to help establish trust and provenance for JAR artifacts. This system tracks the verification status of artifacts and provides transparency about their build process.

## Table of Contents

- [Current Features (Phase 1)](#current-features-phase-1)
- [Multi-Platform Build Attestation Support](#multi-platform-build-attestation-support)
  - [GitLab CI/CD](#gitlab-cicd)
  - [Jenkins](#jenkins)
  - [CircleCI](#circleci)
  - [Other CI/CD Platforms](#travis-ci-drone-woodpecker-ci-and-other-platforms)
  - [Local Deployments](#local-deployments-and-self-hosted-ci)
  - [Source-Only JAR Verification](#source-only-jar-verification-the-clojars-advantage)
- [Verification Statuses](#verification-statuses)
- [Verification Methods](#verification-methods)
- [For Maintainers](#for-maintainers)
- [Security Considerations](#security-considerations)

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

### API Examples

For detailed examples of using the API, including Clojure code and integration patterns, see [VERIFICATION_API_EXAMPLES.md](VERIFICATION_API_EXAMPLES.md).

## Verification Statuses

- **verified**: The JAR has been successfully verified against its source repository
- **partial**: Some verification checks passed, but not all (e.g., only Clojure sources verified in a mixed JAR)
- **pending**: Verification is currently in progress
- **failed**: Verification checks failed
- **unverified**: No verification has been performed

## Verification Methods

- **source-match**: JAR contents verified to exactly match the source repository (highest confidence for source-only JARs)
- **source-match-approx**: JAR contents match source except for metadata differences (timestamps, manifest dates)
- **attestation-github-trusted**: JAR verified through Clojars' trusted GitHub Actions workflows
- **attestation-gitlab-slsa**: JAR verified through GitLab CI with SLSA provenance
- **attestation-jenkins**: JAR verified through Jenkins with provenance metadata
- **attestation-circleci**: JAR verified through CircleCI workflow with artifacts
- **attestation-other-ci**: JAR verified through other CI platforms with standardized provenance
- **attestation**: JAR verified through build attestation (generic, legacy GitHub Actions, etc.)
- **manual-verified**: JAR manually verified by Clojars maintainers
- **verified-retrospective**: Historical JAR verified by building from corresponding Git tag

## Multi-Platform Build Attestation Support

While Clojars currently provides reusable GitHub Actions workflows for attestable builds, many Clojure projects use other CI/CD platforms or local deployment processes. This section outlines strategies and equivalent features for achieving build attestation and provenance verification across different platforms.

### GitLab CI/CD

GitLab provides several features that can be used to implement build attestation similar to GitHub Actions:

#### GitLab Provenance Attestation

GitLab 16.0+ includes native support for [SLSA provenance generation](https://docs.gitlab.com/ee/ci/yaml/artifacts_reports.html#artifactsreportscyclonedx) through:

1. **Artifact Attestation**: GitLab CI can generate and sign build provenance using `.gitlab-ci.yml`:

```yaml
build:
  stage: build
  script:
    - lein jar
  artifacts:
    paths:
      - target/*.jar
    reports:
      cyclonedx: target/bom.json  # Software Bill of Materials
  rules:
    - if: $CI_COMMIT_TAG
```

2. **Dependency Verification**: GitLab's dependency proxy can cache and verify dependencies similar to GitHub Actions approach:

```yaml
variables:
  MAVEN_OPTS: "-Dmaven.repo.local=$CI_PROJECT_DIR/.m2/repository"

cache:
  paths:
    - .m2/repository/

verify-dependencies:
  stage: prepare
  script:
    - lein deps
    - find .m2/repository -type f -exec sha256sum {} \; > deps-checksums.txt
  artifacts:
    paths:
      - deps-checksums.txt
    expire_in: 1 hour
```

3. **Job Artifacts with Checksums**: GitLab automatically generates SHA256 checksums for all artifacts and stores them with provenance metadata.

4. **Pipeline Attestation URL**: The pipeline URL serves as an attestation link:
   - Format: `https://gitlab.com/<group>/<project>/-/pipelines/<pipeline_id>`
   - This can be stored in the `attestation_url` field

#### Grandfathering Strategy for GitLab

For existing GitLab projects, Clojars can:

1. **Accept Pipeline URLs as Attestations**: If a user provides a GitLab pipeline URL that:
   - Is publicly accessible
   - Shows successful build from a tagged commit
   - Matches the version being deployed
   - Then treat it as a valid attestation

2. **Verification Method**: Mark as `attestation-gitlab` to distinguish from GitHub attestations

3. **Trust Model**: 
   - Requires the pipeline to be on the same repository indicated in the POM's SCM section
   - Pipeline must be from a tag that matches the version
   - Repository must be publicly accessible

### Jenkins

Jenkins lacks native attestation features but can achieve similar goals through plugins and scripts:

#### Jenkins Build Provenance

1. **Artifact Fingerprinting**: Jenkins has built-in artifact fingerprinting that tracks:
   - Build number and timestamp
   - Git commit SHA
   - Source repository URL
   - Artifact checksums

2. **Pipeline as Code**: Use Jenkinsfile to document the build process:

```groovy
pipeline {
    agent any
    
    stages {
        stage('Verify Dependencies') {
            steps {
                sh 'lein deps'
                sh 'find ~/.m2/repository -type f -exec sha256sum {} \\; > deps-checksums.txt'
                archiveArtifacts 'deps-checksums.txt'
            }
        }
        
        stage('Build') {
            steps {
                sh 'lein jar'
                sh 'sha256sum target/*.jar > jar-checksum.txt'
                archiveArtifacts 'target/*.jar, pom.xml, jar-checksum.txt'
            }
        }
        
        stage('Generate Provenance') {
            steps {
                script {
                    def provenanceData = [
                        build_url: env.BUILD_URL,
                        commit_sha: sh(returnStdout: true, script: 'git rev-parse HEAD').trim(),
                        commit_tag: sh(returnStdout: true, script: 'git describe --tags --exact-match 2>/dev/null || echo none').trim(),
                        repository: sh(returnStdout: true, script: 'git config --get remote.origin.url').trim(),
                        timestamp: new Date().format("yyyy-MM-dd'T'HH:mm:ss'Z'")
                    ]
                    writeJSON file: 'provenance.json', json: provenanceData
                    archiveArtifacts 'provenance.json'
                }
            }
        }
    }
}
```

3. **Plugin Support**:
   - **Jenkins Cryptography Plugin**: For signing artifacts
   - **Git Plugin**: For tracking commit provenance
   - **Artifact Promotion Plugin**: For controlled releases

#### Grandfathering Strategy for Jenkins

For Jenkins builds:

1. **Accept Build URLs as Attestations**: Jenkins build URLs (e.g., `https://jenkins.example.com/job/my-project/123/`)
   - Must be publicly accessible OR
   - User provides a provenance.json file generated during the build

2. **Provenance JSON Schema**: Clojars can accept a standardized provenance.json:

```json
{
  "build_url": "https://jenkins.example.com/job/my-project/123/",
  "commit_sha": "abc123...",
  "commit_tag": "v1.0.0",
  "repository": "https://github.com/user/project",
  "timestamp": "2024-01-15T10:30:00Z",
  "dependencies_checksums_url": "https://jenkins.example.com/job/my-project/123/artifact/deps-checksums.txt"
}
```

3. **Verification Method**: Mark as `attestation-jenkins`

### CircleCI

CircleCI provides artifact storage and build context that can be used for provenance:

#### CircleCI Build Provenance

1. **Config-as-Code**: Use `.circleci/config.yml`:

```yaml
version: 2.1

jobs:
  build:
    docker:
      - image: cimg/clojure:1.11.1
    
    steps:
      - checkout
      
      - restore_cache:
          keys:
            - deps-v1-{{ checksum "project.clj" }}
      
      - run:
          name: Download Dependencies
          command: |
            lein deps
            find ~/.m2/repository -type f -exec sha256sum {} \; > deps-checksums.txt
      
      - save_cache:
          key: deps-v1-{{ checksum "project.clj" }}
          paths:
            - ~/.m2/repository
      
      - run:
          name: Build JAR
          command: lein jar
      
      - run:
          name: Generate Provenance
          command: |
            cat > provenance.json <<EOF
            {
              "build_url": "$CIRCLE_BUILD_URL",
              "commit_sha": "$CIRCLE_SHA1",
              "commit_tag": "$CIRCLE_TAG",
              "repository": "$CIRCLE_REPOSITORY_URL",
              "workflow_id": "$CIRCLE_WORKFLOW_ID"
            }
            EOF
      
      - store_artifacts:
          path: target/*.jar
          destination: artifacts/
      
      - store_artifacts:
          path: provenance.json
          destination: provenance.json
      
      - store_artifacts:
          path: deps-checksums.txt
          destination: deps-checksums.txt

workflows:
  version: 2
  build-and-deploy:
    jobs:
      - build:
          filters:
            tags:
              only: /^v.*/
```

2. **Artifacts API**: CircleCI provides an API to download build artifacts with checksums

#### Grandfathering Strategy for CircleCI

For CircleCI builds:

1. **Accept Workflow URLs**: URLs like `https://app.circleci.com/pipelines/github/user/project/123/workflows/abc`
   - Use CircleCI API to verify the build succeeded
   - Extract commit SHA and tag from the workflow

2. **Artifact Verification**: If artifacts are stored in CircleCI:
   - User provides the workflow URL during deployment
   - Clojars can optionally fetch and verify the provenance.json artifact

3. **Verification Method**: Mark as `attestation-circleci`

### Travis CI, Drone, Woodpecker CI, and Other Platforms

Most modern CI/CD platforms support similar patterns:

1. **Build URL as Provenance**: Store the public build URL in `attestation_url`
2. **Artifact Checksums**: Generate and store checksums during build
3. **Provenance JSON**: Generate a standardized JSON file with build metadata
4. **Tag-based Builds**: Trigger builds on Git tags that match version numbers

#### Universal Grandfathering Strategy

For any CI/CD platform, Clojars can accept attestation through:

1. **User-Provided Provenance File**: During deployment, users can include a `provenance.json` file with:
   - `build_platform`: Name of CI platform (e.g., "travis-ci", "drone", "woodpecker")
   - `build_url`: Public URL to the build
   - `commit_sha`: Git commit hash
   - `commit_tag`: Git tag used for build
   - `repository`: Source repository URL
   - `timestamp`: Build timestamp
   - `dependencies_hash`: Optional hash of all dependencies

2. **Verification Steps**:
   - Validate that `commit_sha` matches the tag in the repository
   - Verify `repository` matches the POM's SCM section
   - Check that `commit_tag` corresponds to the version being deployed
   - If `build_url` is public, verify it's accessible and shows a successful build

### Local Deployments and Self-Hosted CI

For developers who build and deploy locally or use self-hosted CI systems:

#### Standalone Attestation Script

Clojars could provide a standalone script that generates verifiable provenance locally. However, this approach has significant limitations:

**Why Local Attestation Is Problematic:**

1. **No Trusted Build Environment**: Without a trusted CI environment, there's no way to verify:
   - The source code used matches the repository
   - Dependencies weren't tampered with
   - No malicious code was injected during build
   - The build process followed secure practices

2. **Lack of Audit Trail**: Local builds have no:
   - Immutable build logs
   - Publicly accessible build results
   - Independent verification of the build process

3. **Attestation Without Trust**: A locally-generated attestation is essentially self-signed with no third-party verification.

**Alternative Approaches for Local Deployments:**

1. **Repository-Based Verification**: Instead of attestation, Clojars can:
   - Clone the public repository at the specified tag
   - Build the JAR from source
   - Compare checksums with the uploaded JAR
   - This shifts the burden of proof from attestation to reproducibility

2. **Reproducible Builds**: Encourage developers to:
   - Use deterministic build tools (consistent timestamps, ordering)
   - Document exact build environment (JDK version, Leiningen version)
   - Provide a `build.sh` script that recreates the exact build
   - Store build environment in Docker/container images

3. **Self-Hosted CI with Public Access**: Developers can:
   - Set up self-hosted Jenkins, GitLab Runner, or other CI
   - Configure builds to be publicly accessible
   - Use the same attestation process as hosted CI platforms

4. **GitHub Actions for Personal Projects**: Even for local development:
   - Push code to GitHub
   - Use the free tier of GitHub Actions
   - Get full attestation support with zero infrastructure

#### Grandfathering for Historical Artifacts

For older JARs deployed before attestation systems existed:

1. **Retrospective Source Verification**: 
   - User identifies the Git tag/commit that corresponds to the JAR
   - Clojars clones and builds from that tag
   - If checksums match (or only differ in timestamps/metadata), mark as `verified-retrospective`

2. **Manual Verification with Evidence**:
   - Maintainer provides evidence of the build (screenshots, local logs, etc.)
   - Clojars staff manually reviews and approves
   - Mark as `manual-verified`

3. **Grandfather Clause**:
   - All JARs deployed before [attestation-required-date] are marked `unverified-grandfathered`
   - No penalty for being unverified
   - Encouragement to add verification for new versions

### Source-Only JAR Verification: The Clojars Advantage

Most Clojure JARs are simply packaged source code without compilation. This provides a unique opportunity:

#### Repository Cloning During Deployment

Clojars could implement an optional verification-at-deploy process:

1. **Extract Repository Info**: Parse the POM's SCM section to get:
   - Repository URL (must be public GitHub, GitLab, or Bitbucket)
   - Tag or commit corresponding to the version

2. **Clone and Build**: During deployment:
   - Clone the repository at the specified tag/commit
   - Run a standard build command (`lein jar` or `clojure -T:build jar`)
   - Compare the resulting JAR with the uploaded one

3. **Verification Results**:
   - **Exact Match**: Files and checksums match → `verified-source-match`
   - **Metadata-Only Differences**: Only timestamps, manifest dates differ → `verified-source-match-approx`
   - **Source Match, Build Artifacts Present**: All source files match but JAR has .class files → `partial-has-build-artifacts`
   - **Mismatch**: Source doesn't match → `failed-source-mismatch`

4. **Advantages**:
   - No CI infrastructure required from maintainer
   - Works for any public repository
   - Provides high confidence for source-only JARs
   - Can be done transparently during deployment

5. **Challenges**:
   - **Build Time**: Adding minutes to deployment
   - **Build Environment**: Need to support multiple JDK versions, Leiningen versions, tools.build versions
   - **Non-Deterministic Builds**: Timestamps and metadata may differ
   - **Private Repositories**: Can't clone private repos without credentials
   - **Build Complexity**: Some projects have complex build processes

6. **Implementation Strategy**:
   - Start with opt-in: maintainers request build-time verification
   - Support simple projects first (single JAR, standard build)
   - Gradually expand to more complex scenarios
   - Cache build environments to reduce overhead
   - Run verification asynchronously after initial deployment

#### Source-Match Verification Without Cloning

For immediate deployment without delay, a post-deployment verification process:

1. **Asynchronous Verification**: After JAR is deployed:
   - Queue a background job to clone and build
   - Update verification status when complete
   - Email maintainer with results

2. **User-Triggered Verification**: Maintainers can request verification via:
   - Web UI button on JAR page
   - API endpoint: `POST /api/artifacts/:group/:artifact/:version/verify`
   - CLI tool: `clojars verify-source <group>/<artifact> <version>`

3. **Verification Caching**: Once a version is verified:
   - Result is permanent (versions are immutable)
   - No need to re-verify unless requested

### Recommended Path for Different Scenarios

#### For New Projects

1. **GitHub Repository**: Use Clojars' trusted GitHub Actions workflows
   - Full attestation support
   - Automatic provenance tracking
   - Highest trust level

2. **GitLab Repository**: Use GitLab CI with provenance generation
   - Native SLSA support in GitLab 16+
   - Store pipeline URL in attestation_url

3. **Other Public Repository**: Enable source-match verification
   - Provide accurate SCM information in POM
   - Tag releases properly
   - Let Clojars verify by building from source

#### For Existing Projects

1. **Has CI/CD**: Add provenance generation to existing pipeline
   - Generate provenance.json artifact
   - Store build URL
   - Update POM with verification info

2. **No CI/CD**: Consider adding GitHub Actions
   - Free for public repositories
   - Use Clojars' trusted workflows
   - Or enable source-match verification

3. **Historical Versions**: Use retrospective verification
   - Identify corresponding Git tags
   - Request source-match verification
   - Provide manual verification if needed

#### For Private/Closed-Source Projects

1. **Self-Hosted CI with Public Build Pages**: Set up public build results
   - Jenkins with anonymous read access
   - GitLab with public pipelines
   - Store build URLs as attestation

2. **Manual Verification Process**: Work with Clojars team
   - Provide build evidence
   - Get manual verification approval

3. **Source-Only Attestation**: For consulting/commercial projects:
   - If you can make the source repository public (even temporarily)
   - Enable source-match verification
   - Then make repository private again (verification is cached)

### Trust Levels and Verification Methods

Different verification methods provide different levels of trust:

| Verification Method | Trust Level | Requires |
|-------------------|-------------|----------|
| `attestation-github-trusted` | Highest | GitHub Actions with Clojars trusted workflow |
| `attestation-gitlab-slsa` | High | GitLab CI with SLSA provenance |
| `source-match` | High | Public repository, reproducible build |
| `attestation-circleci` | Medium-High | CircleCI workflow with artifacts |
| `attestation-jenkins` | Medium | Jenkins with provenance.json |
| `attestation-other-ci` | Medium | Other CI with standardized provenance |
| `source-match-approx` | Medium | Source matches except metadata |
| `partial-has-build-artifacts` | Low-Medium | Source matches but has .class files |
| `manual-verified` | Low-Medium | Manual review by Clojars team |
| `unverified-grandfathered` | Low | Pre-dates verification system |
| `unverified` | Lowest | No verification attempted |

### Implementation Roadmap

To support multi-platform attestation, Clojars should implement:

**Phase 1: Multi-Platform Attestation Acceptance (Immediate)**
- [ ] Accept provenance.json files during deployment
- [ ] Support attestation URLs from GitLab, CircleCI, Jenkins, etc.
- [ ] Document provenance.json schema
- [ ] Add verification methods for each platform

**Phase 2: Source-Match Verification (Near-term)**
- [ ] Implement asynchronous repository cloning
- [ ] Support standard build commands (lein, tools.build)
- [ ] Compare and verify JAR contents
- [ ] Handle metadata-only differences
- [ ] API endpoint for triggering verification

**Phase 3: Advanced Verification (Medium-term)**
- [ ] Support complex build scenarios
- [ ] Multiple JDK versions
- [ ] Build environment caching
- [ ] Verification webhooks
- [ ] Bulk verification for existing artifacts

**Phase 4: Enhanced Trust Model (Long-term)**
- [ ] Verified build environment containers
- [ ] Cryptographic signing of provenance
- [ ] Integration with Sigstore/SLSA ecosystem
- [ ] Automated security scanning
- [ ] Supply chain security alerts

## Future Features

Future phases will include:

- Automatic verification during deployment
- Detection of JARs with build artifacts
- Reusable GitHub Actions workflows for attestable builds
- Batch verification of existing JARs
- Enhanced security warnings for unverified JARs
- Multi-platform attestation support (GitLab, Jenkins, CircleCI, etc.)
- Source-match verification by cloning and building from repositories
- Asynchronous verification processes
- Support for self-hosted CI systems

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
