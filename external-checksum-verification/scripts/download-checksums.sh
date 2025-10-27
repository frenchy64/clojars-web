#!/bin/bash
# Download incremental checksum files from Clojars S3 bucket
#
# Usage: ./download-checksums.sh [--bucket BUCKET_NAME] [--output-dir DIR]

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# Default configuration
S3_BUCKET="${S3_BUCKET:-clojars-repo-production}"
OUTPUT_DIR="${OUTPUT_DIR:-$REPO_ROOT/downloads}"
S3_PREFIX=".checksums/"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --bucket)
      S3_BUCKET="$2"
      shift 2
      ;;
    --output-dir)
      OUTPUT_DIR="$2"
      shift 2
      ;;
    --help)
      echo "Usage: $0 [--bucket BUCKET_NAME] [--output-dir DIR]"
      echo ""
      echo "Download incremental checksum files from Clojars S3 bucket"
      echo ""
      echo "Options:"
      echo "  --bucket BUCKET_NAME    S3 bucket name (default: clojars-repo-production)"
      echo "  --output-dir DIR        Output directory (default: downloads/)"
      echo "  --help                  Show this help message"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

# Create output directory
mkdir -p "$OUTPUT_DIR"

echo "=== Downloading incremental checksum files from S3 ==="
echo "Bucket: s3://$S3_BUCKET/$S3_PREFIX"
echo "Output: $OUTPUT_DIR"
echo ""

# Check if AWS CLI is available
if ! command -v aws &> /dev/null; then
  echo "ERROR: AWS CLI is not installed"
  echo "Please install it: https://aws.amazon.com/cli/"
  exit 1
fi

# List all incremental checksum files
echo "Listing files..."
FILES=$(aws s3 ls "s3://$S3_BUCKET/$S3_PREFIX" --no-sign-request 2>/dev/null | \
        grep -E 'incremental-checksums-[0-9]+\.txt(\.gz)?' | \
        awk '{print $4}' || true)

if [ -z "$FILES" ]; then
  echo "WARNING: No checksum files found in s3://$S3_BUCKET/$S3_PREFIX"
  echo "This might be the first run, or the bucket is not accessible"
  exit 0
fi

FILE_COUNT=$(echo "$FILES" | wc -l)
echo "Found $FILE_COUNT files"
echo ""

# Download each file
DOWNLOADED=0
SKIPPED=0

for FILE in $FILES; do
  OUTPUT_FILE="$OUTPUT_DIR/$FILE"
  
  if [ -f "$OUTPUT_FILE" ]; then
    echo "⏭️  Skipping $FILE (already exists)"
    ((SKIPPED++))
    continue
  fi
  
  echo "⬇️  Downloading $FILE..."
  if aws s3 cp "s3://$S3_BUCKET/$S3_PREFIX$FILE" "$OUTPUT_FILE" --no-sign-request 2>/dev/null; then
    ((DOWNLOADED++))
  else
    echo "⚠️  Failed to download $FILE"
  fi
done

echo ""
echo "=== Download Summary ==="
echo "Downloaded: $DOWNLOADED files"
echo "Skipped: $SKIPPED files (already present)"
echo "Total: $FILE_COUNT files"
echo ""
echo "Files saved to: $OUTPUT_DIR"
