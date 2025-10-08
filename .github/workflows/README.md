# Clojars Trusted Reusable Workflows

This directory contains reusable GitHub Actions workflows that Clojars maintains and trusts for generating build attestations.

## What Are These Workflows?

These are **reusable workflows** that provide secure, reproducible builds with complete provenance tracking. Unlike workflow templates that users copy into their repositories, these workflows are:

- **Maintained by Clojars**: We vet and update these workflows
- **Automatically trusted**: Clojars accepts attestations from these workflows
- **Centrally managed**: Users get security updates automatically
- **Scalable**: No need to vet each user's custom workflow

## Available Workflows

### 1. Leiningen Build (`attestable-build-lein.yml`)

Secure 3-job pipeline for Leiningen projects with full provenance tracking.

**Usage:**
```yaml
# In your repo: .github/workflows/release.yml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  build:
    uses: clojars/clojars-web/.github/workflows/attestable-build-lein.yml@main
    with:
      java-version: '21'  # Optional, defaults to '21'
      lein-version: '2.12.0'  # Optional, defaults to '2.12.0'
```

**What it does:**
1. Downloads and verifies all dependencies with SHA256 checksums
2. Builds JAR and POM offline with `LEIN_OFFLINE=true`
3. Analyzes every file in the JAR for provenance
4. Fails if any `.clj` file can't be traced to source or dependencies
5. Generates GitHub attestation ONLY after verification passes
6. Uploads all checksums and provenance reports to GitHub Release

### 2. tools.build Build (`attestable-build-tools.yml`)

Secure 3-job pipeline for tools.build projects with full provenance tracking.

**Usage:**
```yaml
# In your repo: .github/workflows/release.yml
name: Release
on:
  push:
    tags: ['v*']

jobs:
  build:
    uses: clojars/clojars-web/.github/workflows/attestable-build-tools.yml@main
    with:
      java-version: '21'  # Optional, defaults to '21'
      clojure-version: '1.12.3.1577'  # Optional, defaults to '1.12.3.1577'
```

**What it does:**
1. Downloads and verifies Clojure CLI and all dependencies
2. Builds JAR offline with network isolation
3. Analyzes complete provenance of all files
4. Fails if any source files can't be verified
5. Generates attestation after verification
6. Uploads checksums and provenance to GitHub Release

## Security Model

### Why Reusable Workflows?

When users copy workflow templates, Clojars would need to vet every custom workflow to trust its attestations. This doesn't scale.

With reusable workflows:
- Clojars vets workflows **once**
- Users **call** trusted workflows (don't copy them)
- Clojars verifies attestations came from trusted workflows
- Users benefit from centralized security updates

### Attestation Verification

Clojars deployment infrastructure checks attestations using `clojars.attestation-verification`:

```clojure
(require '[clojars.attestation-verification :as av])

;; Verify attestation came from trusted workflow
(av/verify-attestation-workflow 
  "clojars/clojars-web/.github/workflows/attestable-build-lein.yml@refs/heads/main")
;; => {:valid true :workflow {...}}

;; Untrusted workflow rejected
(av/verify-attestation-workflow 
  "user/repo/.github/workflows/custom.yml@main")
;; => {:valid false :reason "Workflow 'user/repo/...' is not in the trusted workflow list"}
```

### Adding New Trusted Workflows

To add a new workflow to the trusted list:

1. Create the workflow in this directory
2. Thoroughly review security practices
3. Add to `clojars.attestation-verification/default-trusted-workflows`
4. Add tests in `test/clojars/unit/attestation_verification_test.clj`
5. Deploy Clojars with updated trust list

## Workflow Architecture

All workflows follow a secure 3-job pipeline:

### Job 1: prepare-dependencies
- Downloads tooling (Lein/Clojure CLI) with SHA256 verification
- Downloads project dependencies
- Creates minimal dependency cache (only required JARs)
- Calculates and saves SHA256 for all files
- Uploads dependency cache and checksums

### Job 2: build-artifacts
- Restores dependency cache
- Verifies all checksums from Job 1
- Builds offline (LEIN_OFFLINE=true or network isolation)
- Uploads JAR and POM as artifacts

### Job 3: verify-provenance
- Restores everything and verifies checksums
- Unzips JAR and analyzes each file
- Verifies each file against source repo or dependencies
- **Fails build if any source can't be verified**
- Generates comprehensive provenance report
- **Attests artifacts ONLY after verification passes**
- Creates GitHub Release with all artifacts and reports

## For Project Maintainers

### Prerequisites

Your project needs:
- Public GitHub repository
- Git tags for versions (e.g., `v1.0.0`)
- For Lein: `project.clj` with version matching tag
- For tools.build: `build.clj` with `:build` alias

### Getting Started

1. **Create workflow file** in your repo: `.github/workflows/release.yml`

2. **Use the appropriate reusable workflow:**
   ```yaml
   # For Leiningen projects
   uses: clojars/clojars-web/.github/workflows/attestable-build-lein.yml@main
   
   # For tools.build projects
   uses: clojars/clojars-web/.github/workflows/attestable-build-tools.yml@main
   ```

3. **Tag and push:**
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

4. **Check the build:**
   - GitHub Actions will run the workflow
   - Build will fail if any source can't be verified
   - Success creates a GitHub Release with attestation

5. **Deploy to Clojars:**
   - Clojars will verify the attestation came from a trusted workflow
   - Manual deployment step (automatic deployment after attestation verification coming soon)

### Troubleshooting

**Build fails with "Unverified .clj file":**
- File in JAR doesn't match source repo or dependencies
- Check if file is generated code that should be in `.gitignore`
- Ensure git tag matches the actual source used to build

**Build fails with "Suspicious .class file":**
- `.class` file has same name as dependency but different checksum
- Possible tampering or build artifact pollution
- Clean your local build and try again

**Checksum verification fails:**
- Dependency was modified between jobs
- Network issue during download
- Retry the workflow

## Development

### Testing Workflows Locally

You can't run GitHub Actions locally, but you can test the build steps:

```bash
# Test Lein build process
git clone your-repo
cd your-repo
lein with-profile -user,-test,-dev deps
lein with-profile -user,-test,-dev jar
```

### Adding Features

When updating these workflows:
1. Maintain backward compatibility
2. Update documentation
3. Test with multiple projects
4. Update attestation-verification tests if trust model changes

## Support

For questions or issues:
- Open an issue in [clojars/clojars-web](https://github.com/clojars/clojars-web)
- Email: contact@clojars.org
- Clojars Slack: #clojars on Clojurians

## License

Same as Clojars: Eclipse Public License 1.0
