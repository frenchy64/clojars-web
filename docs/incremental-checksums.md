# Incremental Checksum Generation

This tool generates incremental checksum files for JAR and POM artifacts in the Clojars repository, enabling verification of repository integrity over time.

## Purpose

As described in [Issue #149](https://github.com/clojars/clojars-web/issues/149), this tool creates periodic backups of JAR/POM checksums that can be used to verify that the repository hasn't been affected by attacks or corruption over time.

## How It Works

The tool:
1. Lists all JAR and POM files from the S3 repository bucket
2. Reads existing incremental checksum files to determine what has already been processed
3. Computes MD5 and SHA1 checksums for new artifacts only
4. Generates incremental checkpoint files in the format `.checksums/incremental-checksums-{N}.txt.gz`
5. Uploads these files (along with their checksums) to the S3 bucket

Each incremental file contains up to 10,000 artifacts to keep file sizes manageable.

## File Format

Each line in the checksum files has the format:
```
path/to/artifact.jar md5sum sha1sum
```

For example:
```
org/clojure/clojure/1.11.1/clojure-1.11.1.jar a8b0ca91b6bfd94b5e4cc6d8c8e84348 5e62c5e7d2e0f3a5d8e5b5f3c3e4c3e4e4f3c3e4
org/clojure/clojure/1.11.1/clojure-1.11.1.pom b9c1da02c7cfe05c6f5dd7d9d9f95459 6f73d6f8e3f1f4b6e9f6c6g4d4f5d4f5f5g4d4f5
```

## Usage

### From Command Line

```bash
scripts/update-checksums releases/clojars-web-current.jar
```

### As Part of Infrastructure

This tool is designed to be run periodically (e.g., daily) via cron. To integrate with the Clojars infrastructure:

1. Add a cron job to run `scripts/update-checksums` daily
2. The script will automatically:
   - Generate checksum files for new artifacts
   - Upload them to the `.checksums/` directory in the repo bucket
   - Skip already-checksummed artifacts

Example cron entry:
```cron
# Run checksum generation daily at 3 AM
0 3 * * * /path/to/clojars/scripts/update-checksums /path/to/clojars-web-current.jar
```

## Verification

To verify repository integrity:

1. Download all incremental checksum files from `.checksums/` in the repo bucket
2. For each artifact path in the files:
   - Download the artifact
   - Compute its MD5 and SHA1
   - Compare with the stored checksums
3. Any mismatches indicate potential corruption or tampering

## Files Generated

For each run that finds new artifacts, the tool generates:
- `incremental-checksums-N.txt` - Plain text checksum file
- `incremental-checksums-N.txt.gz` - Gzipped version
- `incremental-checksums-N.txt.md5` - MD5 checksum of the plain text file
- `incremental-checksums-N.txt.sha1` - SHA1 checksum of the plain text file
- `incremental-checksums-N.txt.gz.md5` - MD5 checksum of the gzipped file
- `incremental-checksums-N.txt.gz.sha1` - SHA1 checksum of the gzipped file

All files are uploaded to the S3 bucket under the `.checksums/` prefix with `public-read` ACL.

## Testing

Run the test suite:
```bash
make test
```

Or run just the checksum generation tests:
```bash
clojure -M:dev:test -m kaocha.runner --focus clojars.unit.tools.generate-checksums-test
```

## Implementation

- **Main module**: `src/clojars/tools/generate_checksums.clj`
- **Tests**: `test/clojars/unit/tools/generate_checksums_test.clj`
- **Script**: `scripts/update-checksums`

The implementation follows the same patterns as other Clojars tools like `generate_feeds.clj`.
