# External Checksum Verification Repository

This is a demonstration of the external verification repository design for Clojars checksum integrity.

## Purpose

This repository serves as an independent, external record of checksums for the incremental checksum files stored in the Clojars repository. By maintaining this external record:

1. **Tamper Detection**: Any modification to historical checksum files can be detected
2. **Trust Chain**: Provides an independent verification layer outside of the main infrastructure
3. **Attack Prevention**: Makes it difficult for an attacker who compromises the main repo to cover their tracks

## Repository Structure

```
external-checksum-verification/
├── README.md                    # This file
├── checksums/                   # Directory storing checksums of checksum files
│   └── meta-checksums.edn      # EDN file with checksums of incremental-checksums-*.txt.gz
├── scripts/
│   ├── download-checksums.sh   # Download incremental checksum files from S3
│   ├── verify-checksums.sh     # Verify existing checksums haven't changed
│   ├── update-checksums.sh     # Add new checksum files to meta-checksums.edn
│   └── check-integrity.sh      # Full integrity check with attack detection
├── .github/
│   └── workflows/
│       └── verify.yml          # GitHub Actions to run periodic verification
└── docs/
    ├── attack-scenarios.md     # Documented attack scenarios and defenses
    └── integrity-strategy.md   # Overall integrity verification strategy
```

## How It Works

### 1. Download Phase
- `download-checksums.sh` downloads all incremental checksum files from S3
- Downloads to a temporary directory for verification

### 2. Verification Phase
- `verify-checksums.sh` checks that existing files haven't been modified
- Compares downloaded file checksums against stored meta-checksums
- Detects any tampering with historical checksum files

### 3. Update Phase
- `update-checksums.sh` adds checksums for newly discovered files
- Only updates meta-checksums.edn for files not previously recorded
- Commits changes to version control for audit trail

### 4. Integrity Check Phase
- `check-integrity.sh` runs a comprehensive integrity check
- Detects various attack scenarios
- Generates reports on any anomalies

## Attack Scenarios Defended Against

### 1. **Historical File Modification**
**Attack**: Attacker modifies an old incremental-checksums-N.txt.gz file to hide evidence of a compromised artifact.

**Defense**: The external repo stores checksums of these files. Any modification is detected when verify-checksums.sh runs.

### 2. **New File Injection**
**Attack**: Attacker adds a new incremental-checksums-N.txt.gz file with fake checksums for malicious artifacts.

**Defense**: The external repo tracks file numbering sequence. Gaps or duplicates in numbering are detected.

### 3. **File Deletion**
**Attack**: Attacker deletes checksum files to remove evidence.

**Defense**: The external repo maintains a complete list. Missing files are detected during verification.

### 4. **Gradual Drift**
**Attack**: Attacker makes small modifications over time to avoid detection.

**Defense**: Git history in the external repo shows all changes. Automated verification runs regularly.

### 5. **Complete Repository Replacement**
**Attack**: Attacker replaces entire .checksums/ directory with fabricated data.

**Defense**: The external repo's git history cannot be modified retroactively. Sudden appearance of many "new" files triggers alerts.

## Usage

### Initial Setup
```bash
# Clone this repository
git clone <external-repo-url>
cd external-checksum-verification

# Run initial download and verification
./scripts/download-checksums.sh
./scripts/update-checksums.sh
```

### Regular Verification (Automated via GitHub Actions)
```bash
# Download latest files
./scripts/download-checksums.sh

# Verify no tampering occurred
./scripts/verify-checksums.sh

# Add new checksums
./scripts/update-checksums.sh

# Run full integrity check
./scripts/check-integrity.sh
```

### Manual Investigation
```bash
# Check specific file
./scripts/verify-checksums.sh incremental-checksums-42.txt.gz

# View audit trail
git log checksums/meta-checksums.edn
```

## Security Considerations

1. **Repository Access**: This repository should have restricted write access, separate from main Clojars infrastructure
2. **Git History**: Never force-push or rewrite history - maintains audit trail
3. **Automated Alerts**: GitHub Actions workflow sends alerts on verification failures
4. **Multiple Maintainers**: Requires multiple maintainers to review and approve changes
5. **Offline Backups**: Regular backups of this repository to offline storage

## Integration with Main System

The main Clojars infrastructure:
1. Generates incremental checksum files daily
2. Uploads to `.checksums/` in the repo bucket
3. This external repo verifies them independently
4. Both systems must agree for checksums to be trusted

## Artifact Integrity Verification Strategy

See `docs/integrity-strategy.md` for detailed information on how to use these checksums to verify actual JAR/POM artifacts over time.
