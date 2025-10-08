# Clojars Attestable Build Workflow Templates

This directory contains GitHub Actions workflow templates for creating attestable builds that integrate with Clojars' verification system.

## Available Templates

### 1. `attestable-build-lein.yml`

For projects using Leiningen (`project.clj`).

**Features:**
- **Secure multi-job pipeline** with isolated dependency preparation, offline building, and provenance verification
- **Automatic triggering** on version tags (e.g., `v1.0.0`)
- **Official Leiningen installer** with SHA256 verification
- **Minimal profile configuration** excluding user, test, and dev profiles for reproducibility
- **Offline build** using only pre-verified dependencies
- **Complete provenance tracking** - verifies every file in the JAR against source or dependencies
- **Automated security checks** - fails build if unverified Clojure files or suspicious class files found
- **Build attestation** via GitHub Actions
- **Comprehensive provenance report** included with release
- **No automatic deployment** - requires manual review of attestation before Clojars deployment

### 2. `attestable-build-tools.yml`

For projects using tools.build (`deps.edn` + `build.clj`).

**Features:**
- Automatic triggering on version tags (e.g., `v1.0.0`)
- Test execution with `clojure -M:test`
- JAR artifact generation with `clojure -T:build jar`
- Build provenance attestation via GitHub Actions
- Dependency caching for faster builds
- Optional automatic deployment to Clojars
- GitHub Release creation with attestation metadata

## Setup Instructions

### Prerequisites

1. Your project must be hosted on GitHub
2. Your project must have a valid `pom.xml` or generate one during build
3. You need to add SCM information to your project configuration

### Step 1: Add SCM Information to Your Project

#### For Leiningen projects (project.clj):

```clojure
(defproject your-group/your-artifact "1.0.0"
  :description "Your project description"
  :url "https://github.com/yourname/yourproject"
  :scm {:url "https://github.com/yourname/yourproject"
        :tag "v1.0.0"  ; Update this for each release
        :connection "scm:git:git://github.com/yourname/yourproject.git"
        :developerConnection "scm:git:ssh://git@github.com/yourname/yourproject.git"}
  ; ... rest of your config
)
```

#### For tools.build projects (pom-template in deps.edn):

```clojure
{:deps { ... }
 :aliases
 {:build {:deps {io.github.clojure/tools.build {:mvn/version "0.9.5"}}
          :ns-default build}
  :test { ... }}
 
 ; In your build.clj, ensure you generate a pom with SCM info:
 ; (b/write-pom {:class-dir class-dir
 ;               :lib 'your-group/your-artifact
 ;               :version version
 ;               :scm {:url "https://github.com/yourname/yourproject"
 ;                     :tag version
 ;                     :connection "scm:git:git://github.com/yourname/yourproject.git"}
 ;               :basis basis})
}
```

### Step 2: Copy the Workflow Template

1. Create the `.github/workflows` directory in your project if it doesn't exist:
   ```bash
   mkdir -p .github/workflows
   ```

2. Copy the appropriate template to `.github/workflows/release.yml`

3. Customize the workflow:
   - Update Java version if needed (default: Java 21)
   - Update Leiningen version in env vars if needed (default: 2.12.0)
   - The workflow uses minimal profiles by default (`-user,-test,-dev`)
   - Review the provenance verification requirements for your project

### Step 3: Understanding the Leiningen Workflow Pipeline

The Leiningen workflow uses a secure 3-job pipeline:

**Job 1: prepare-dependencies**
- Downloads the official Leiningen installer with SHA256 verification
- Downloads project dependencies
- Creates a minimal Maven cache with only required JARs
- Calculates checksums for all dependencies
- Uploads the minimal cache and checksums for later jobs

**Job 2: build-artifacts**
- Restores and verifies the minimal dependency cache
- Builds JAR and POM **offline** (`LEIN_OFFLINE=true`)
- Generates build attestation
- Uploads artifacts for verification

**Job 3: verify-provenance**
- Analyzes every file in the built JAR
- Verifies each file matches either:
  - Source repository (exact SHA256 match)
  - A dependency JAR (exact SHA256 match)
- **Fails the build** if:
  - Any `.clj` file has unknown provenance
  - Any `.class` file has checksum mismatch with dependencies
- Generates comprehensive provenance report
- Creates GitHub Release with all artifacts and reports

### Step 4: Testing Your Workflow

1. Ensure your `project.clj` version matches your intended tag

2. Create and push a test tag:
   ```bash
   git tag v0.0.1-test
   git push origin v0.0.1-test
   ```

3. Monitor the workflow run on GitHub Actions:
   - Check that all three jobs complete successfully
   - Review the provenance report in the artifacts
   - Verify the attestation was created

4. Delete the test tag after verification:
   ```bash
   git tag -d v0.0.1-test
   git push origin :refs/tags/v0.0.1-test
   gh release delete v0.0.1-test  # if release was created
   ```

### Step 5: Deploying to Clojars (Manual Process)

**Important:** The workflow does NOT automatically deploy to Clojars. This is intentional to ensure full review of attestation and provenance.

To deploy after a successful build:

1. Download the JAR from the GitHub Release
2. Review the provenance report
3. Verify the attestation:
   ```bash
   gh attestation verify <jar-file> --repo yourname/yourproject
   ```
4. Deploy manually to Clojars:
   ```bash
   lein deploy clojars
   ```
   Or use your preferred deployment method with the verified artifacts.

### Troubleshooting Provenance Verification

**"Clojure source file with unknown provenance" error:**
- This means a `.clj` file in your JAR doesn't match any file in your source repo or dependencies
- Check if you have generated code or AOT compilation artifacts
- Verify all source files are committed to git
- Review the provenance report for the specific files

**"Checksum mismatch with dependency" error:**
- This indicates a `.class` file that matches a dependency by name but not by content
- This could indicate tampering or version mismatch
- Review your dependencies and ensure no local modifications

**Workflow fails during offline build:**
- Verify all dependencies are declared in `project.clj`
- Check for dependencies that download additional artifacts at runtime
- Review the `prepare-dependencies` job logs

### Step 6: Configure Secrets (NOT Recommended for Automatic Deployment)

The workflow template does NOT include automatic Clojars deployment. If you choose to add it:

1. **⚠️ Warning:** Automatic deployment bypasses manual attestation review
2. Generate a Clojars deploy token:
   - Log in to https://clojars.org
   - Go to Settings → Deploy Tokens
   - Create a new token

2. Add GitHub secrets to your repository:
   - Go to your GitHub repository → Settings → Secrets and variables → Actions
   - Add two secrets:
     - `CLOJARS_USERNAME`: Your Clojars username
     - `CLOJARS_TOKEN`: Your Clojars deploy token

3. Add deployment step to the workflow (not recommended without manual review)

## Verification

### Verifying the Attestation

Anyone can verify the attestation of your artifacts using the GitHub CLI:

```bash
gh attestation verify path/to/artifact.jar --repo yourname/yourproject
```

### Viewing Attestation in Clojars

Once your artifact is deployed to Clojars:

1. Visit your artifact page: `https://clojars.org/your-group/your-artifact`
2. Look for the "Build Verification" section
3. The verification badge will show the status
4. Links to attestation and reproducibility information will be provided

## Attestation Metadata

The workflows generate an `attestation-metadata.json` file containing:

```json
{
  "workflow_run_id": "1234567890",
  "workflow_run_url": "https://github.com/yourname/yourproject/actions/runs/1234567890",
  "commit_sha": "abc123...",
  "commit_tag": "v1.0.0",
  "repository": "yourname/yourproject",
  "attested_at": "2024-01-15T10:30:00Z"
}
```

This file is:
- Included in the GitHub Release
- Can be used to link the JAR to its build provenance
- Provides verifiable build information

## Reproducibility

While these workflows create attestable builds, they don't guarantee byte-for-byte reproducibility due to:

- Timestamps in JAR manifests
- Non-deterministic build tools behavior
- Different build environments

For true reproducibility, additional tooling and standardization is needed (future work).

## Troubleshooting

### "Version mismatch" error

Make sure your project version matches your git tag. If you tag with `v1.0.0`, your project version should be `1.0.0`.

### "JAR file not found"

Check your build configuration. The workflow expects:
- Leiningen: `lein jar` to create a JAR in the `target` directory
- tools.build: `clojure -T:build jar` to create a JAR in the `target` directory

### Attestation fails

Ensure your repository has the correct permissions:
- `id-token: write`
- `contents: write`
- `attestations: write`

These are already set in the templates.

## Best Practices

1. **Always use version tags**: Tag every release with a version tag (e.g., `v1.0.0`)
2. **Keep SCM info updated**: Ensure your SCM information matches your repository
3. **Version consistency**: Keep project version in sync with git tags
4. **Test before release**: Always run tests in the workflow before building
5. **Review attestations**: Periodically verify that attestations are being generated correctly

## Future Enhancements

Planned improvements to the verification system:

- Automatic extraction of attestation URLs during deployment
- Integration with Clojars deployment to automatically link attestations
- Source verification checks
- Detection of build artifacts vs source-only JARs
- Reproducibility verification

## Support

For issues or questions:

- Open an issue on [clojars-web](https://github.com/clojars/clojars-web/issues)
- Join the discussion in #clojars on Clojurians Slack

## Related Documentation

- [VERIFICATION.md](../../VERIFICATION.md) - Overview of the verification system
- [GitHub Actions Attestations](https://docs.github.com/en/actions/security-guides/using-artifact-attestations-to-establish-provenance-for-builds)
- [Clojars Deploy Documentation](https://github.com/clojars/clojars-web/wiki/Deploy)
