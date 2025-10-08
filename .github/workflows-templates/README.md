# Clojars Attestable Build Workflow Templates

This directory contains GitHub Actions workflow templates for creating attestable builds that integrate with Clojars' verification system.

## Available Templates

### 1. `attestable-build-lein.yml`

For projects using Leiningen (`project.clj`).

**Features:**
- Automatic triggering on version tags (e.g., `v1.0.0`)
- Version verification between tag and `project.clj`
- Test execution before building
- JAR artifact generation with `lein jar`
- Build provenance attestation via GitHub Actions
- Optional automatic deployment to Clojars
- GitHub Release creation with attestation metadata

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
   - Update Java version if needed
   - Update Clojure/Leiningen version if needed
   - Modify test command if your project uses a different alias/profile
   - Adjust build commands if needed

### Step 3: Configure Secrets (Optional, for Automatic Deployment)

If you want automatic deployment to Clojars:

1. Generate a Clojars deploy token:
   - Log in to https://clojars.org
   - Go to Settings → Deploy Tokens
   - Create a new token

2. Add GitHub secrets to your repository:
   - Go to your GitHub repository → Settings → Secrets and variables → Actions
   - Add two secrets:
     - `CLOJARS_USERNAME`: Your Clojars username
     - `CLOJARS_TOKEN`: Your Clojars deploy token

3. Uncomment the deployment step in the workflow file

### Step 4: Create a Release

1. Update your project version
2. Commit your changes
3. Create and push a version tag:
   ```bash
   git tag v1.0.0
   git push origin v1.0.0
   ```

4. The workflow will automatically:
   - Build your project
   - Run tests
   - Generate attestation
   - Create a GitHub Release with the JAR and attestation metadata

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
