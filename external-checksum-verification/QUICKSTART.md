# Quick Start Guide

This guide helps you get started with the external checksum verification system.

## Prerequisites

- Bash shell (Linux/macOS or WSL on Windows)
- Git
- AWS CLI (optional for S3 access, can use --no-sign-request for public buckets)
- Standard Unix tools: curl, sha256sum, md5sum

## Initial Setup

### 1. Clone the Repository

```bash
git clone <external-repo-url>
cd external-checksum-verification
```

### 2. Run Initial Download

Download all existing incremental checksum files from Clojars:

```bash
./scripts/download-checksums.sh
```

This creates a `downloads/` directory with all checksum files.

### 3. Initialize Meta-Checksums

Create the initial meta-checksums database:

```bash
./scripts/update-checksums.sh --commit
```

This:
- Calculates SHA256, MD5, and size for each downloaded file
- Stores in `checksums/meta-checksums.edn`
- Commits to git with a descriptive message

### 4. Verify Everything Works

Run a verification check:

```bash
./scripts/verify-checksums.sh
```

Should output: `✅ Verification complete. No tampering detected.`

## Daily Operations

### Automated (Recommended)

GitHub Actions runs automatically every day at 6 AM UTC:
1. Downloads new checksum files
2. Verifies existing files haven't changed
3. Updates meta-checksums for new files
4. Creates alerts if tampering detected

### Manual Verification

Run whenever you want to check integrity:

```bash
# Download latest files
./scripts/download-checksums.sh

# Verify integrity
./scripts/verify-checksums.sh

# Update meta-checksums for new files
./scripts/update-checksums.sh --commit

# Push changes
git push
```

### Full Integrity Check

Run comprehensive checks including sequence analysis:

```bash
./scripts/check-integrity.sh
```

This performs 6 different integrity checks and reports any anomalies.

## Interpreting Results

### ✅ Success Messages

```
✅ VERIFIED: incremental-checksums-1.txt.gz
✅ Pass: All files match expected checksums
✅ Pass: No gaps in file sequence
```

Everything is working correctly.

### 🆕 New Files

```
🆕 NEW: incremental-checksums-50.txt.gz (not yet in meta-checksums)
```

New checksum files were uploaded. Run `update-checksums.sh` to add them.

### ⚠️ Warnings

```
⚠️ WARNING: Gaps detected in file sequence:
   - Gap between 42 and 45
```

Investigate why files 43-44 are missing. Could be legitimate or deletion attack.

### 🚨 CRITICAL Alerts

```
🚨 TAMPERING DETECTED: incremental-checksums-30.txt.gz
   Expected: abc123...
   Actual:   def456...
   ⚠️ THIS FILE HAS BEEN MODIFIED!
```

**IMMEDIATE ACTION REQUIRED**:
1. Check git history: `git log checksums/meta-checksums.edn`
2. Contact Clojars maintainers
3. Review S3 CloudTrail logs
4. Follow incident response plan (see docs/attack-scenarios.md)

## Common Tasks

### Check Specific File

```bash
# Download just one file
curl -O "https://repo.clojars.org/.checksums/incremental-checksums-42.txt.gz"

# Calculate its SHA256
sha256sum incremental-checksums-42.txt.gz

# Compare with meta-checksums.edn
grep "incremental-checksums-42" checksums/meta-checksums.edn
```

### View Audit Trail

```bash
# See all changes to meta-checksums
git log --patch checksums/meta-checksums.edn

# See when specific file was added
git log --all --grep="incremental-checksums-42"

# View file at specific time
git show HEAD~5:checksums/meta-checksums.edn
```

### Rollback Bad Update

```bash
# If you accidentally committed wrong data
git revert HEAD

# If you need to go back further
git reset --hard HEAD~3  # Goes back 3 commits
git push --force  # ONLY if absolutely necessary and coordinated
```

**WARNING**: Force-push destroys audit trail. Only use in emergency.

### Export for Backup

```bash
# Create offline backup
tar czf checksum-backup-$(date +%Y%m%d).tar.gz \
    checksums/ \
    downloads/

# Copy to secure offline storage
```

## Troubleshooting

### "No checksum files found"

**Problem**: First run or bucket is inaccessible.

**Solution**:
- Check S3 bucket name in script (default: clojars-repo-production)
- Verify AWS CLI can access bucket
- Try with `--no-sign-request` flag for public access

### "AWS CLI is not installed"

**Problem**: AWS CLI not available.

**Solution**:
```bash
# On Linux/macOS
pip install awscli

# Or use package manager
brew install awscli  # macOS
apt install awscli   # Ubuntu/Debian
```

### "MISSING: X file(s)"

**Problem**: Files in meta-checksums not found in downloads.

**Causes**:
1. Incomplete download (network issue)
2. Files deleted from S3 (attack or mistake)
3. You didn't run download-checksums.sh

**Solution**:
```bash
# Re-download
rm -rf downloads/
./scripts/download-checksums.sh

# If still missing, investigate S3 bucket
```

### Scripts Show "Permission denied"

**Problem**: Scripts not executable.

**Solution**:
```bash
chmod +x scripts/*.sh
```

## Security Best Practices

### For Repository Maintainers

1. **Protect main branch**: Require pull request reviews
2. **Enable branch protection**: No direct pushes to main
3. **2FA required**: All maintainers must use 2FA
4. **Regular audits**: Monthly review of git history
5. **Monitor alerts**: Respond to GitHub Actions failures immediately

### For Contributors

1. **Never force-push**: Destroys audit trail
2. **Descriptive commits**: Explain what and why
3. **Review before merge**: Check all automated PRs
4. **Report anomalies**: Create issue if something looks wrong

## Next Steps

1. ✅ Complete initial setup (above)
2. 📖 Read [attack-scenarios.md](docs/attack-scenarios.md) to understand threats
3. 📖 Read [integrity-strategy.md](docs/integrity-strategy.md) for verification strategies
4. 🔔 Configure GitHub notifications for this repository
5. 📅 Schedule monthly audit review
6. 🔒 Set up additional security measures (2FA, branch protection)

## Getting Help

- **Issues**: [Create a GitHub issue](../../issues/new)
- **Security**: Email security@clojars.org (for security concerns only)
- **Documentation**: See [docs/](docs/) directory

## Quick Reference

| Task | Command |
|------|---------|
| Download files | `./scripts/download-checksums.sh` |
| Verify integrity | `./scripts/verify-checksums.sh` |
| Add new files | `./scripts/update-checksums.sh --commit` |
| Full check | `./scripts/check-integrity.sh` |
| View history | `git log checksums/meta-checksums.edn` |
| Manual verify | `sha256sum file.gz` then check edn |

---

**Remember**: This external repository is a critical security component. Any tampering with Clojars checksum files will be detected here. Treat it with appropriate care and security.
