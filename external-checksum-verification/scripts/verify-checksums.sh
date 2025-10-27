#!/bin/bash
# Verify that downloaded checksum files match stored meta-checksums
#
# Usage: ./verify-checksums.sh [--downloads-dir DIR] [--meta-file FILE]

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# Default configuration
DOWNLOADS_DIR="${DOWNLOADS_DIR:-$REPO_ROOT/downloads}"
META_FILE="${META_FILE:-$REPO_ROOT/checksums/meta-checksums.edn}"

# Parse arguments
while [[ $# -gt 0 ]]; do
  case $1 in
    --downloads-dir)
      DOWNLOADS_DIR="$2"
      shift 2
      ;;
    --meta-file)
      META_FILE="$2"
      shift 2
      ;;
    --help)
      echo "Usage: $0 [--downloads-dir DIR] [--meta-file FILE]"
      echo ""
      echo "Verify downloaded checksum files against stored meta-checksums"
      echo ""
      echo "Options:"
      echo "  --downloads-dir DIR   Directory with downloaded files (default: downloads/)"
      echo "  --meta-file FILE      Meta-checksums EDN file (default: checksums/meta-checksums.edn)"
      echo "  --help                Show this help message"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "=== Verifying Checksum File Integrity ==="
echo "Downloads: $DOWNLOADS_DIR"
echo "Meta-checksums: $META_FILE"
echo ""

# Check if meta-checksums file exists
if [ ! -f "$META_FILE" ]; then
  echo "WARNING: Meta-checksums file not found: $META_FILE"
  echo "This might be the initial setup. Run update-checksums.sh to initialize."
  exit 0
fi

# Read meta-checksums and verify each file
VERIFIED=0
FAILED=0
NEW=0
MISSING=0

# Extract file entries from EDN (simple grep-based parsing)
# Format: {:file "filename.txt.gz" :sha256 "abc123..." :size 12345 :timestamp "..."}
FILES_IN_META=$(grep -oP '(?<=:file ")[^"]+' "$META_FILE" 2>/dev/null || true)

if [ -z "$FILES_IN_META" ]; then
  echo "No files recorded in meta-checksums yet"
  exit 0
fi

echo "Checking $(echo "$FILES_IN_META" | wc -l) files recorded in meta-checksums..."
echo ""

for FILE in $FILES_IN_META; do
  FILE_PATH="$DOWNLOADS_DIR/$FILE"
  
  if [ ! -f "$FILE_PATH" ]; then
    echo "❌ MISSING: $FILE (recorded in meta-checksums but not found)"
    ((MISSING++))
    continue
  fi
  
  # Extract expected SHA256 from meta-checksums
  EXPECTED_SHA=$(grep -A2 "\"$FILE\"" "$META_FILE" | grep -oP '(?<=:sha256 ")[^"]+' | head -1)
  
  if [ -z "$EXPECTED_SHA" ]; then
    echo "⚠️  WARNING: No SHA256 found for $FILE in meta-checksums"
    continue
  fi
  
  # Calculate actual SHA256
  ACTUAL_SHA=$(sha256sum "$FILE_PATH" | awk '{print $1}')
  
  if [ "$EXPECTED_SHA" = "$ACTUAL_SHA" ]; then
    echo "✅ VERIFIED: $FILE"
    ((VERIFIED++))
  else
    echo "🚨 TAMPERING DETECTED: $FILE"
    echo "   Expected: $EXPECTED_SHA"
    echo "   Actual:   $ACTUAL_SHA"
    echo "   ⚠️  THIS FILE HAS BEEN MODIFIED!"
    ((FAILED++))
  fi
done

# Check for new files not in meta-checksums
echo ""
echo "Checking for new files..."
if [ -d "$DOWNLOADS_DIR" ]; then
  for FILE_PATH in "$DOWNLOADS_DIR"/incremental-checksums-*.txt* "$DOWNLOADS_DIR"/incremental-checksums-*.txt.gz; do
    [ -f "$FILE_PATH" ] || continue
    FILE=$(basename "$FILE_PATH")
    
    if ! echo "$FILES_IN_META" | grep -q "^$FILE$"; then
      echo "🆕 NEW: $FILE (not yet in meta-checksums)"
      ((NEW++))
    fi
  done
fi

echo ""
echo "=== Verification Summary ==="
echo "✅ Verified: $VERIFIED files"
echo "❌ Failed: $FAILED files"
echo "🆕 New: $NEW files (not yet tracked)"
echo "❌ Missing: $MISSING files (in meta-checksums but not found)"
echo ""

if [ $FAILED -gt 0 ]; then
  echo "🚨 SECURITY ALERT: $FAILED file(s) have been tampered with!"
  echo "   Historical checksum files have been modified."
  echo "   This indicates a potential security breach."
  echo "   Investigation required immediately."
  exit 1
fi

if [ $MISSING -gt 0 ]; then
  echo "⚠️  WARNING: $MISSING file(s) are missing!"
  echo "   Files recorded in meta-checksums were not found."
  echo "   This could indicate file deletion or incomplete download."
  exit 1
fi

if [ $NEW -gt 0 ]; then
  echo "ℹ️  Info: $NEW new file(s) found."
  echo "   Run update-checksums.sh to add them to meta-checksums."
fi

echo "✅ Verification complete. No tampering detected."
