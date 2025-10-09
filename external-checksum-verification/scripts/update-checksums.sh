#!/bin/bash
# Update meta-checksums.edn with checksums of new incremental checksum files
#
# Usage: ./update-checksums.sh [--downloads-dir DIR] [--meta-file FILE] [--commit]

set -e

SCRIPT_DIR="$( cd "$( dirname "${BASH_SOURCE[0]}" )" && pwd )"
REPO_ROOT="$(dirname "$SCRIPT_DIR")"

# Default configuration
DOWNLOADS_DIR="${DOWNLOADS_DIR:-$REPO_ROOT/downloads}"
META_FILE="${META_FILE:-$REPO_ROOT/checksums/meta-checksums.edn}"
DO_COMMIT=false

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
    --commit)
      DO_COMMIT=true
      shift
      ;;
    --help)
      echo "Usage: $0 [--downloads-dir DIR] [--meta-file FILE] [--commit]"
      echo ""
      echo "Update meta-checksums.edn with checksums of new files"
      echo ""
      echo "Options:"
      echo "  --downloads-dir DIR   Directory with downloaded files (default: downloads/)"
      echo "  --meta-file FILE      Meta-checksums EDN file (default: checksums/meta-checksums.edn)"
      echo "  --commit              Commit changes to git"
      echo "  --help                Show this help message"
      exit 0
      ;;
    *)
      echo "Unknown option: $1"
      exit 1
      ;;
  esac
done

echo "=== Updating Meta-Checksums ==="
echo "Downloads: $DOWNLOADS_DIR"
echo "Meta-file: $META_FILE"
echo ""

# Create meta-checksums file if it doesn't exist
mkdir -p "$(dirname "$META_FILE")"

if [ ! -f "$META_FILE" ]; then
  echo ";; Meta-checksums for incremental checksum files" > "$META_FILE"
  echo ";; Format: EDN list of maps with :file, :sha256, :md5, :size, :timestamp" >> "$META_FILE"
  echo "[" >> "$META_FILE"
  echo "]" >> "$META_FILE"
  echo "Created new meta-checksums file"
fi

# Get list of existing files in meta-checksums
EXISTING_FILES=$(grep -oP '(?<=:file ")[^"]+' "$META_FILE" 2>/dev/null || true)

# Find new files
NEW_FILES=()
if [ -d "$DOWNLOADS_DIR" ]; then
  for FILE_PATH in "$DOWNLOADS_DIR"/incremental-checksums-*.txt "$DOWNLOADS_DIR"/incremental-checksums-*.txt.gz; do
    [ -f "$FILE_PATH" ] || continue
    FILE=$(basename "$FILE_PATH")
    
    if ! echo "$EXISTING_FILES" | grep -q "^$FILE$"; then
      NEW_FILES+=("$FILE")
    fi
  done
fi

if [ ${#NEW_FILES[@]} -eq 0 ]; then
  echo "No new files to add"
  exit 0
fi

echo "Found ${#NEW_FILES[@]} new file(s) to add:"
for FILE in "${NEW_FILES[@]}"; do
  echo "  - $FILE"
done
echo ""

# Create temporary file for new entries
TEMP_ENTRIES=$(mktemp)

# Calculate checksums for each new file
for FILE in "${NEW_FILES[@]}"; do
  FILE_PATH="$DOWNLOADS_DIR/$FILE"
  
  echo "Processing $FILE..."
  
  # Calculate checksums
  SHA256=$(sha256sum "$FILE_PATH" | awk '{print $1}')
  MD5=$(md5sum "$FILE_PATH" | awk '{print $1}')
  SIZE=$(stat -f%z "$FILE_PATH" 2>/dev/null || stat -c%s "$FILE_PATH")
  TIMESTAMP=$(date -u +"%Y-%m-%dT%H:%M:%SZ")
  
  # Extract file number for sorting
  FILE_NUM=$(echo "$FILE" | grep -oP '(?<=incremental-checksums-)\d+' || echo "0")
  
  # Add entry to temp file
  cat >> "$TEMP_ENTRIES" << EOF
 {:file "$FILE"
  :number $FILE_NUM
  :sha256 "$SHA256"
  :md5 "$MD5"
  :size $SIZE
  :timestamp "$TIMESTAMP"}
EOF
done

# Insert new entries into meta-checksums.edn
# Remove closing bracket, add new entries, add closing bracket back
sed -i.bak '$d' "$META_FILE"  # Remove last line (closing bracket)

# If file has content other than opening bracket, add newline
if [ $(wc -l < "$META_FILE") -gt 3 ]; then
  echo "" >> "$META_FILE"
fi

cat "$TEMP_ENTRIES" >> "$META_FILE"
echo "]" >> "$META_FILE"

rm "$TEMP_ENTRIES"
rm -f "${META_FILE}.bak"

echo ""
echo "✅ Added ${#NEW_FILES[@]} new file(s) to meta-checksums"

# Sort entries by file number (optional, for cleaner diffs)
# This would require more sophisticated EDN parsing

if [ "$DO_COMMIT" = true ]; then
  echo ""
  echo "Committing changes to git..."
  
  cd "$REPO_ROOT"
  git add "$META_FILE"
  
  COMMIT_MSG="Add checksums for ${#NEW_FILES[@]} new incremental file(s)"
  for FILE in "${NEW_FILES[@]}"; do
    COMMIT_MSG="$COMMIT_MSG
- $FILE"
  done
  
  git commit -m "$COMMIT_MSG"
  echo "✅ Changes committed"
fi

echo ""
echo "=== Update Complete ==="
echo "Meta-checksums file updated: $META_FILE"
if [ "$DO_COMMIT" = true ]; then
  echo "Changes committed to git"
else
  echo "Run with --commit to commit changes to git"
fi
