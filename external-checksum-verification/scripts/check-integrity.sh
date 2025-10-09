#!/bin/bash
# Comprehensive integrity check with attack detection
#
# Usage: ./check-integrity.sh [--downloads-dir DIR] [--meta-file FILE]

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
      echo "Run comprehensive integrity check with attack scenario detection"
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

echo "========================================"
echo "  CLOJARS CHECKSUM INTEGRITY CHECK"
echo "========================================"
echo ""
echo "Downloads: $DOWNLOADS_DIR"
echo "Meta-checksums: $META_FILE"
echo ""

WARNINGS=0
ERRORS=0
ALERTS=()

# Check 1: Verify file integrity
echo "=== Check 1: File Integrity ==="
if "$SCRIPT_DIR/verify-checksums.sh" --downloads-dir "$DOWNLOADS_DIR" --meta-file "$META_FILE"; then
  echo "✅ Pass: All files match expected checksums"
else
  echo "❌ FAIL: File integrity check failed"
  ALERTS+=("File tampering detected")
  ((ERRORS++))
fi
echo ""

# Check 2: Sequence continuity (detect gaps in file numbering)
echo "=== Check 2: Sequence Continuity ==="
if [ -d "$DOWNLOADS_DIR" ]; then
  FILE_NUMBERS=$(ls "$DOWNLOADS_DIR"/incremental-checksums-*.txt* 2>/dev/null | \
                 grep -oP '(?<=incremental-checksums-)\d+' | \
                 sort -n || true)
  
  if [ -n "$FILE_NUMBERS" ]; then
    PREV_NUM=0
    GAPS=()
    
    for NUM in $FILE_NUMBERS; do
      if [ $PREV_NUM -gt 0 ] && [ $((NUM - PREV_NUM)) -gt 1 ]; then
        GAPS+=("Gap between $PREV_NUM and $NUM")
      fi
      PREV_NUM=$NUM
    done
    
    if [ ${#GAPS[@]} -gt 0 ]; then
      echo "⚠️  WARNING: Gaps detected in file sequence:"
      for GAP in "${GAPS[@]}"; do
        echo "   - $GAP"
      done
      ALERTS+=("Sequence gaps detected - possible file deletion")
      ((WARNINGS++))
    else
      echo "✅ Pass: No gaps in file sequence"
    fi
  else
    echo "ℹ️  No files found to check"
  fi
else
  echo "⚠️  WARNING: Downloads directory not found"
  ((WARNINGS++))
fi
echo ""

# Check 3: Duplicate detection
echo "=== Check 3: Duplicate Detection ==="
if [ -d "$DOWNLOADS_DIR" ]; then
  DUPLICATES=$(find "$DOWNLOADS_DIR" -name "incremental-checksums-*.txt*" -type f | \
               grep -oP '(?<=incremental-checksums-)\d+(?=\.)' | \
               sort | uniq -d || true)
  
  if [ -n "$DUPLICATES" ]; then
    echo "🚨 ALERT: Duplicate file numbers detected:"
    for DUP in $DUPLICATES; do
      echo "   - Number $DUP appears multiple times"
    done
    ALERTS+=("Duplicate file numbers - possible injection attack")
    ((ERRORS++))
  else
    echo "✅ Pass: No duplicate file numbers"
  fi
else
  echo "⚠️  WARNING: Downloads directory not found"
fi
echo ""

# Check 4: Git history consistency
echo "=== Check 4: Git History Consistency ==="
cd "$REPO_ROOT"
if git rev-parse --git-dir > /dev/null 2>&1; then
  # Check if meta-checksums has been force-pushed (would show as rewrite)
  COMMIT_COUNT=$(git log --oneline "$META_FILE" 2>/dev/null | wc -l || echo "0")
  
  if [ "$COMMIT_COUNT" -gt 0 ]; then
    echo "✅ Pass: Git history intact ($COMMIT_COUNT commits)"
    
    # Check for sudden large additions (potential mass replacement)
    RECENT_ADDITIONS=$(git log -1 --numstat "$META_FILE" 2>/dev/null | \
                       awk '/incremental-checksums/ {sum+=$1} END {print sum}' || echo "0")
    
    if [ "$RECENT_ADDITIONS" -gt 100 ]; then
      echo "⚠️  WARNING: Large number of files added in recent commit ($RECENT_ADDITIONS)"
      echo "   This could indicate bulk replacement attack"
      ALERTS+=("Mass file addition detected")
      ((WARNINGS++))
    fi
  else
    echo "ℹ️  Info: No git history yet (initial setup)"
  fi
else
  echo "ℹ️  Info: Not a git repository"
fi
echo ""

# Check 5: File size anomalies
echo "=== Check 5: File Size Anomalies ==="
if [ -d "$DOWNLOADS_DIR" ] && [ -f "$META_FILE" ]; then
  # Calculate average file size from meta-checksums
  AVG_SIZE=$(grep -oP '(?<=:size )\d+' "$META_FILE" 2>/dev/null | \
             awk '{sum+=$1; count++} END {if(count>0) print int(sum/count); else print 0}')
  
  if [ "$AVG_SIZE" -gt 0 ]; then
    echo "Average file size: $AVG_SIZE bytes"
    
    # Check for files that are significantly different
    ANOMALIES=0
    for FILE_PATH in "$DOWNLOADS_DIR"/incremental-checksums-*.txt* "$DOWNLOADS_DIR"/incremental-checksums-*.txt.gz; do
      [ -f "$FILE_PATH" ] || continue
      
      SIZE=$(stat -f%z "$FILE_PATH" 2>/dev/null || stat -c%s "$FILE_PATH")
      
      # Flag if file is less than 10% or more than 1000% of average
      if [ "$SIZE" -lt $((AVG_SIZE / 10)) ] || [ "$SIZE" -gt $((AVG_SIZE * 10)) ]; then
        echo "⚠️  Anomaly: $(basename "$FILE_PATH") size $SIZE bytes (avg: $AVG_SIZE)"
        ((ANOMALIES++))
      fi
    done
    
    if [ $ANOMALIES -gt 0 ]; then
      echo "⚠️  WARNING: $ANOMALIES file(s) with unusual sizes"
      ALERTS+=("Size anomalies detected")
      ((WARNINGS++))
    else
      echo "✅ Pass: All file sizes within normal range"
    fi
  else
    echo "ℹ️  Info: Not enough data to determine average size"
  fi
fi
echo ""

# Check 6: Timestamp analysis
echo "=== Check 6: Timestamp Analysis ==="
if [ -f "$META_FILE" ]; then
  # Extract timestamps and check for anomalies
  TIMESTAMPS=$(grep -oP '(?<=:timestamp ")[^"]+' "$META_FILE" 2>/dev/null || true)
  
  if [ -n "$TIMESTAMPS" ]; then
    TS_COUNT=$(echo "$TIMESTAMPS" | wc -l)
    echo "Analyzing $TS_COUNT timestamp(s)..."
    
    # Check for timestamps in the future
    NOW=$(date -u +%s)
    FUTURE_COUNT=0
    
    while IFS= read -r TS; do
      TS_EPOCH=$(date -d "$TS" +%s 2>/dev/null || echo "0")
      if [ "$TS_EPOCH" -gt "$NOW" ]; then
        echo "⚠️  Future timestamp detected: $TS"
        ((FUTURE_COUNT++))
      fi
    done <<< "$TIMESTAMPS"
    
    if [ $FUTURE_COUNT -gt 0 ]; then
      echo "⚠️  WARNING: $FUTURE_COUNT file(s) with future timestamps"
      ALERTS+=("Future timestamps detected")
      ((WARNINGS++))
    else
      echo "✅ Pass: All timestamps are valid"
    fi
  else
    echo "ℹ️  Info: No timestamps to analyze"
  fi
fi
echo ""

# Summary
echo "========================================"
echo "  INTEGRITY CHECK SUMMARY"
echo "========================================"
echo ""
echo "Errors: $ERRORS"
echo "Warnings: $WARNINGS"
echo ""

if [ ${#ALERTS[@]} -gt 0 ]; then
  echo "🚨 SECURITY ALERTS:"
  for ALERT in "${ALERTS[@]}"; do
    echo "   ⚠️  $ALERT"
  done
  echo ""
fi

if [ $ERRORS -gt 0 ]; then
  echo "❌ CRITICAL: Integrity check FAILED with $ERRORS error(s)"
  echo "   Immediate investigation required!"
  exit 2
elif [ $WARNINGS -gt 0 ]; then
  echo "⚠️  WARNING: Integrity check completed with $WARNINGS warning(s)"
  echo "   Review recommended"
  exit 1
else
  echo "✅ SUCCESS: All integrity checks passed"
  echo "   No security issues detected"
  exit 0
fi
