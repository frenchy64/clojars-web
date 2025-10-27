# Artifact Integrity Verification Strategy

This document describes how to use the incremental checksum files to verify the integrity of actual JAR and POM artifacts in the Clojars repository over time.

## Overview

The checksum system provides two layers of protection:

1. **Meta-Layer** (this external repo): Verifies incremental checksum files haven't been tampered with
2. **Artifact-Layer** (described here): Uses trusted checksums to verify actual artifacts

## Trust Chain

```
┌─────────────────────────────────────────┐
│ External Verification Repository        │
│ - Git history (immutable audit trail)   │
│ - Meta-checksums of checksum files      │
│ - Independent from main infrastructure  │
└──────────────┬──────────────────────────┘
               │ validates
               ▼
┌─────────────────────────────────────────┐
│ Incremental Checksum Files              │
│ - .checksums/incremental-checksums-*.gz  │
│ - MD5 + SHA1 for each artifact          │
│ - Stored in Clojars S3 bucket           │
└──────────────┬──────────────────────────┘
               │ validates
               ▼
┌─────────────────────────────────────────┐
│ Actual Artifacts                         │
│ - JAR files in repo.clojars.org         │
│ - POM files in repo.clojars.org         │
│ - Downloaded by users via Maven/Lein    │
└─────────────────────────────────────────┘
```

## Verification Strategies

### Strategy 1: Periodic Full Repository Scan

**Goal**: Verify all artifacts match their expected checksums

**Frequency**: Monthly or quarterly

**Process**:
1. Download all verified incremental checksum files
2. Build a complete database of expected checksums
3. Scan repository and verify each artifact
4. Report any mismatches

**Implementation**:

```bash
#!/bin/bash
# Full repository verification scan

# 1. Download and verify checksum files
./scripts/download-checksums.sh
./scripts/verify-checksums.sh

# 2. Extract all artifact checksums into a database
CHECKSUM_DB=$(mktemp)
for file in downloads/incremental-checksums-*.txt.gz; do
  zcat "$file" | while read path md5 sha1; do
    echo "$path|$md5|$sha1" >> "$CHECKSUM_DB"
  done
done

# 3. Verify each artifact in repository
while IFS='|' read -r path expected_md5 expected_sha1; do
  # Download artifact from repo.clojars.org
  ARTIFACT_URL="https://repo.clojars.org/$path"
  TEMP_FILE=$(mktemp)
  
  if curl -sf "$ARTIFACT_URL" -o "$TEMP_FILE"; then
    # Calculate actual checksums
    actual_md5=$(md5sum "$TEMP_FILE" | awk '{print $1}')
    actual_sha1=$(sha1sum "$TEMP_FILE" | awk '{print $1}')
    
    # Compare
    if [ "$actual_md5" != "$expected_md5" ] || [ "$actual_sha1" != "$expected_sha1" ]; then
      echo "MISMATCH: $path"
      echo "  Expected MD5: $expected_md5"
      echo "  Actual MD5:   $actual_md5"
      echo "  Expected SHA1: $expected_sha1"
      echo "  Actual SHA1:   $actual_sha1"
    fi
  else
    echo "MISSING: $path (not found in repository)"
  fi
  
  rm -f "$TEMP_FILE"
done < "$CHECKSUM_DB"

rm -f "$CHECKSUM_DB"
```

**Pros**:
- Comprehensive coverage
- Catches any tampering
- Clear baseline state

**Cons**:
- Resource intensive
- Takes long time (millions of artifacts)
- High bandwidth usage

**Use Case**: Regular audits, compliance checks

---

### Strategy 2: Incremental Verification

**Goal**: Verify newly added artifacts as they're checksummed

**Frequency**: Daily (after new incremental file created)

**Process**:
1. Detect new incremental checksum file
2. Download and verify it
3. Verify artifacts listed in that file only
4. Maintain running database of verified artifacts

**Implementation**:

```bash
#!/bin/bash
# Incremental artifact verification

LAST_VERIFIED_FILE="last_verified.txt"
LAST_NUM=$(cat "$LAST_VERIFIED_FILE" 2>/dev/null || echo "0")

# Find new checksum files
for file in downloads/incremental-checksums-*.txt.gz; do
  NUM=$(echo "$file" | grep -oP '(?<=incremental-checksums-)\d+')
  
  if [ "$NUM" -le "$LAST_NUM" ]; then
    continue  # Already verified
  fi
  
  echo "Verifying artifacts in $file..."
  
  # Extract and verify each artifact
  zcat "$file" | while read path md5 sha1; do
    ARTIFACT_URL="https://repo.clojars.org/$path"
    TEMP_FILE=$(mktemp)
    
    if curl -sf "$ARTIFACT_URL" -o "$TEMP_FILE"; then
      actual_md5=$(md5sum "$TEMP_FILE" | awk '{print $1}')
      actual_sha1=$(sha1sum "$TEMP_FILE" | awk '{print $1}')
      
      if [ "$actual_md5" = "$md5" ] && [ "$actual_sha1" = "$sha1" ]; then
        echo "✓ $path"
      else
        echo "✗ MISMATCH: $path"
        # Alert system
      fi
    fi
    
    rm -f "$TEMP_FILE"
  done
  
  # Update last verified
  echo "$NUM" > "$LAST_VERIFIED_FILE"
done
```

**Pros**:
- Efficient (only checks new artifacts)
- Fast execution
- Lower bandwidth usage

**Cons**:
- Doesn't catch changes to old artifacts
- Requires state tracking

**Use Case**: Continuous monitoring, quick detection

---

### Strategy 3: Random Sampling

**Goal**: Randomly verify subset of artifacts to detect tampering

**Frequency**: Daily

**Process**:
1. Select random sample of artifacts (e.g., 1000)
2. Verify their checksums
3. Statistical analysis to detect anomalies

**Implementation**:

```bash
#!/bin/bash
# Random sampling verification

SAMPLE_SIZE=1000

# Build full checksum database
CHECKSUM_DB=$(mktemp)
for file in downloads/incremental-checksums-*.txt.gz; do
  zcat "$file" >> "$CHECKSUM_DB"
done

# Random sample
SAMPLE=$(shuf -n "$SAMPLE_SIZE" "$CHECKSUM_DB")

VERIFIED=0
FAILED=0

echo "$SAMPLE" | while read path md5 sha1; do
  ARTIFACT_URL="https://repo.clojars.org/$path"
  TEMP_FILE=$(mktemp)
  
  if curl -sf "$ARTIFACT_URL" -o "$TEMP_FILE"; then
    actual_md5=$(md5sum "$TEMP_FILE" | awk '{print $1}')
    actual_sha1=$(sha1sum "$TEMP_FILE" | awk '{print $1}')
    
    if [ "$actual_md5" = "$md5" ] && [ "$actual_sha1" = "$sha1" ]; then
      ((VERIFIED++))
    else
      ((FAILED++))
      echo "MISMATCH: $path"
    fi
  fi
  
  rm -f "$TEMP_FILE"
done

echo "Sample verification: $VERIFIED verified, $FAILED failed out of $SAMPLE_SIZE"

# Statistical threshold: if >0.1% fail, investigate
if [ "$FAILED" -gt $((SAMPLE_SIZE / 1000)) ]; then
  echo "ALERT: Failure rate above threshold!"
fi

rm -f "$CHECKSUM_DB"
```

**Pros**:
- Fast execution
- Low resource usage
- Statistical confidence

**Cons**:
- May miss isolated tampering
- Requires statistical analysis

**Use Case**: Daily monitoring, early warning system

---

### Strategy 4: User-Driven Verification

**Goal**: Leverage Maven/Leiningen to verify artifacts at download time

**Frequency**: Every artifact download

**Process**:
1. User downloads artifact via Maven/Lein
2. Tool automatically checks SHA1 against expected value
3. If mismatch, alert user and fail build

**Implementation**:

Maven already does this! The `.sha1` files next to each artifact are checked automatically.

**Enhancement**: Extend to cross-check against our trusted checksums:

```xml
<!-- Maven plugin to verify against Clojars checksums -->
<plugin>
  <groupId>org.clojars</groupId>
  <artifactId>checksum-verifier-plugin</artifactId>
  <version>1.0.0</version>
  <executions>
    <execution>
      <goals>
        <goal>verify-checksums</goal>
      </goals>
      <configuration>
        <checksumSource>https://repo.clojars.org/.checksums/</checksumSource>
        <failOnMismatch>true</failOnMismatch>
      </configuration>
    </execution>
  </executions>
</plugin>
```

**Pros**:
- Distributed verification
- Immediate detection
- No central infrastructure needed

**Cons**:
- Requires user adoption
- Extra download time
- Cache invalidation complexity

**Use Case**: End-user protection, defense in depth

---

### Strategy 5: Continuous Monitoring with Alerts

**Goal**: Real-time detection of artifact tampering

**Frequency**: Continuous

**Process**:
1. Monitor S3 bucket for artifact modifications
2. Check if modification matches expected checksum update
3. Alert if unexpected change

**Implementation**:

```python
# AWS Lambda function triggered by S3 events

import boto3
import hashlib

def lambda_handler(event, context):
    for record in event['Records']:
        bucket = record['s3']['bucket']['name']
        key = record['s3']['object']['key']
        
        # Only check JAR/POM files
        if not (key.endswith('.jar') or key.endswith('.pom')):
            continue
        
        # Download artifact
        s3 = boto3.client('s3')
        obj = s3.get_object(Bucket=bucket, Key=key)
        content = obj['Body'].read()
        
        # Calculate checksums
        md5 = hashlib.md5(content).hexdigest()
        sha1 = hashlib.sha1(content).hexdigest()
        
        # Check against trusted checksums
        expected = get_expected_checksum(key)  # From checksum database
        
        if expected and (md5 != expected['md5'] or sha1 != expected['sha1']):
            # ALERT: Artifact modified unexpectedly!
            send_alert(f"Artifact {key} modified without checksum update")
            
    return {'statusCode': 200}
```

**Pros**:
- Immediate detection
- Automated response
- No missed changes

**Cons**:
- Requires AWS infrastructure
- False positives possible
- Additional costs

**Use Case**: Production monitoring, incident response

---

## Attack Scenarios and Defenses

### Attack: Replace Artifact with Malicious Version

**Scenario**:
1. Attacker compromises S3 bucket
2. Replaces `popular-lib-1.0.0.jar` with malicious version
3. Keeps same filename and `.sha1` file

**Detection**:

**Method 1 - Periodic Scan**:
```
- Scan downloads popular-lib-1.0.0.jar
- Calculates: SHA1 = xyz789 (malicious)
- Expected from checksum: SHA1 = abc123 (legitimate)
- MISMATCH DETECTED
```

**Method 2 - User Verification**:
```
- User downloads via Maven
- Maven checks .sha1 file: matches (attacker updated it)
- Enhanced plugin checks against trusted checksums
- MISMATCH DETECTED, build fails
```

**Method 3 - Continuous Monitoring**:
```
- S3 PutObject event triggers Lambda
- Lambda calculates new SHA1: xyz789
- Queries checksum database: expects abc123
- IMMEDIATE ALERT
```

**Timeline**:
- Continuous monitoring: < 1 minute
- User verification: At download time
- Periodic scan: < 24 hours (daily) or < 30 days (monthly)

**Recovery**:
1. Identify malicious artifact
2. Restore original from backup
3. Revoke attacker access
4. Alert all users who may have downloaded compromised version

---

### Attack: Gradual Artifact Replacement

**Scenario**:
1. Attacker replaces old artifacts gradually (one per week)
2. Updates checksum files to match
3. Hopes external repo doesn't notice

**Detection**:

**External Repo Defense**:
```
- Attacker modifies incremental-checksums-15.txt.gz (old file)
- External repo's verify-checksums.sh runs
- SHA256 of file changes: detected immediately
- TAMPERING ALERT triggered
```

**Key Point**: Even if attacker updates checksums, the external repo catches the modification of old checksum files.

**Timeline**: < 24 hours

---

### Attack: Inject Malicious Artifact Without Checksum

**Scenario**:
1. Attacker uploads `evil-lib-1.0.0.jar`
2. Doesn't add to any checksum file
3. Hopes it goes unnoticed

**Detection**:

**Method 1 - Repository Completeness Check**:
```bash
# List all artifacts in S3
aws s3 ls s3://clojars-repo-production/ --recursive | \
  grep -E '\.(jar|pom)$' > all_artifacts.txt

# List all artifacts in checksums
zcat downloads/*.gz | awk '{print $1}' | sort > checksummed_artifacts.txt

# Find difference
comm -23 all_artifacts.txt checksummed_artifacts.txt
# Shows: evil-lib-1.0.0.jar (not in any checksum file)
```

**Timeline**: Next completeness audit (weekly/monthly)

**Response**:
1. Investigate unchecksummed artifact
2. Determine if legitimate (new upload) or malicious
3. Add to quarantine if suspicious
4. Generate checksums for legitimate new artifacts

---

## Recommended Implementation Schedule

### Phase 1: Foundation (Week 1-2)
- ✅ Implement incremental checksum generation (DONE)
- ✅ Set up external verification repository (DONE)
- ✅ Implement basic verification scripts (DONE)

### Phase 2: Automated Verification (Week 3-4)
- Deploy GitHub Actions for daily verification
- Set up alerting system
- Create incident response procedures

### Phase 3: Artifact Verification (Week 5-8)
- Implement incremental artifact verification
- Deploy random sampling checks
- Set up continuous monitoring

### Phase 4: User Tools (Week 9-12)
- Develop Maven plugin for user verification
- Create Leiningen plugin
- Documentation and user guides

### Phase 5: Ongoing Operations
- Daily: Random sampling + incremental verification
- Weekly: Completeness check (find unchecksummed artifacts)
- Monthly: Full repository scan
- Quarterly: Security audit and review

## Metrics and Reporting

### Key Metrics

1. **Verification Coverage**: % of artifacts verified
2. **Detection Latency**: Time from attack to detection
3. **False Positive Rate**: Incorrect tampering alerts
4. **Verification Speed**: Artifacts verified per hour

### Monthly Report Template

```markdown
# Clojars Integrity Report - January 2025

## Summary
- Total artifacts: 1,234,567
- Verified this month: 1,234,567 (100%)
- Mismatches detected: 0
- New artifacts: 12,345
- Checksum files: 125

## Verification Activities
- Daily random sampling: 31 runs, 31,000 artifacts checked
- Incremental verification: Daily, 12,345 new artifacts
- Full repository scan: 1 run (monthly), all artifacts verified

## Security Events
- Tampering attempts: 0
- False positives: 2 (investigated, resolved)
- System availability: 99.9%

## Action Items
- None

## Next Month
- Continue daily monitoring
- Quarterly full audit scheduled
- Review and update procedures
```

## Conclusion

The multi-layered verification strategy provides comprehensive protection:

1. **External repo** ensures checksum files are trustworthy
2. **Periodic scans** verify artifact integrity over time
3. **Incremental checks** catch tampering of new artifacts quickly
4. **Random sampling** provides statistical confidence
5. **User verification** distributes trust and detection
6. **Continuous monitoring** enables real-time response

Together, these strategies make it extremely difficult for an attacker to inject malicious artifacts and hide evidence, even with access to the main infrastructure.
