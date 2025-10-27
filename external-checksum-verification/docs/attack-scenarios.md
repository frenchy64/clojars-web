# Attack Scenarios and Defenses

This document describes potential attack scenarios against the Clojars checksum system and how the external verification repository defends against them.

## Threat Model

**Attacker Goal**: Inject malicious artifacts into Clojars repository and hide evidence by tampering with checksum files.

**Attacker Capabilities**:
- May compromise main Clojars infrastructure (S3 buckets, servers)
- Can modify files in the repository
- Can upload new malicious artifacts
- May have access to upload credentials

**System Defenses**:
- External verification repository (this repo) is independent
- Git history provides immutable audit trail
- Automated verification runs daily
- Multiple maintainers with separate credentials

## Attack Scenarios

### Scenario 1: Historical File Modification

**Attack Description**:
An attacker compromises the Clojars S3 bucket and modifies an old incremental checksum file to hide evidence of a compromised artifact.

**Example**:
1. Attacker uploads malicious `evil-lib-1.0.jar`
2. Attacker modifies `incremental-checksums-42.txt.gz` to include fake checksums for the malicious JAR
3. To hide tracks, attacker also updates existing entries in the file

**Defense**:
```
When verify-checksums.sh runs:
1. Downloads incremental-checksums-42.txt.gz
2. Computes SHA256: abc123... (new value)
3. Compares with stored meta-checksum: def456... (original value)
4. Mismatch detected! Alert triggered.
```

**Detection Timeline**: Within 24 hours (next daily verification)

**Evidence**:
- Git history shows original SHA256 value
- Timestamp of last legitimate update
- Clear proof of tampering

**Recovery**:
1. Identify when file was modified (S3 versioning or CloudTrail logs)
2. Restore original file from backup
3. Investigate all artifacts added/modified in suspicious timeframe
4. Remove malicious artifacts

---

### Scenario 2: New File Injection

**Attack Description**:
Attacker creates a new incremental checksum file with fake data to legitimize malicious artifacts.

**Example**:
1. Attacker uploads `malware-2.0.jar` to repo
2. Attacker creates `incremental-checksums-99.txt.gz` with fake checksums
3. File includes checksums for both legitimate and malicious artifacts

**Defense**:
```
When check-integrity.sh runs:
1. Checks file sequence: ..., 42, 43, 44, 99
2. Detects gap between 44 and 99
3. Alert: "Sequence gaps detected - possible file deletion or injection"

Additionally:
4. update-checksums.sh adds new file to meta-checksums
5. Git commit shows sudden appearance of file 99
6. Maintainer reviews commit before approving
```

**Detection Timeline**: Immediate (on next PR review) or within 24 hours (automated check)

**Detection Points**:
- Sequence gap analysis
- Git PR review process
- File numbering validation

**Recovery**:
1. Reject or revert the meta-checksum update
2. Investigate file 99 in S3
3. Check if files 45-98 are missing (deletion attack)
4. Verify legitimacy of file 99 with Clojars maintainers

---

### Scenario 3: Mass File Deletion

**Attack Description**:
Attacker deletes multiple incremental checksum files to remove evidence of when certain artifacts were added.

**Example**:
1. Malicious artifact was added when file 30 was created
2. Attacker deletes files 28-32 to hide the evidence
3. Hopes no one notices the gap

**Defense**:
```
When verify-checksums.sh runs:
1. Reads meta-checksums.edn: expects files 1-50
2. Downloads from S3: finds files 1-27, 33-50
3. Reports: "MISSING: 5 file(s) in meta-checksums but not found"
4. Alert triggered for investigation

When check-integrity.sh runs:
1. Sequence analysis: ..., 27, 33, ...
2. Reports: "Gap between 27 and 33"
3. Double alert for deletion + gap
```

**Detection Timeline**: Within 24 hours

**Evidence**:
- Git history shows files 28-32 were previously verified
- Last successful verification timestamp
- S3 deletion logs (if CloudTrail enabled)

**Recovery**:
1. Check S3 versioning to restore deleted files
2. If files cannot be restored, repository is compromised
3. Regenerate checksums from scratch
4. Audit all artifacts added during affected period

---

### Scenario 4: Gradual Drift Attack

**Attack Description**:
Attacker makes small changes over time to avoid detection by large, sudden changes.

**Example**:
1. Week 1: Modify one checksum in file 45
2. Week 2: Modify two checksums in file 46
3. Week 3: Add malicious artifact with fake checksum in file 47
4. Changes are small enough to avoid suspicion

**Defense**:
```
Git provides complete audit trail:

$ git log checksums/meta-checksums.edn
commit abc123...
Date:   Week 3
    Add checksums for 1 new incremental file(s)
    - incremental-checksums-47.txt.gz
    [shows SHA256 value]

commit def456...
Date:   Week 2  
    Add checksums for 1 new incremental file(s)
    - incremental-checksums-46.txt.gz
    [shows SHA256 value that doesn't match current S3]

Alert: incremental-checksums-46.txt.gz changed since Week 2
```

**Detection**: Any file modification detected immediately

**Key Defense**: Git history is immutable (no force-push allowed)

**Evidence**:
- Every change is recorded with timestamp
- Original SHA256 values are preserved
- Cannot be retroactively modified

---

### Scenario 5: Complete Repository Replacement

**Attack Description**:
Attacker gains access to S3 and replaces entire `.checksums/` directory with fabricated data.

**Example**:
1. Attacker deletes all incremental-checksums files
2. Attacker uploads new set of files 1-100 with fake data
3. All checksums point to malicious artifacts
4. Appears as if repository has always been this way

**Defense**:
```
When download-checksums.sh runs:
1. Finds 100 files in S3
2. Compares with meta-checksums.edn: has records for 50 files

When verify-checksums.sh runs:
1. Files 1-50: SHA256 mismatch - all modified!
2. Files 51-100: Not in meta-checksums - all new!
3. Massive alert triggered

When check-integrity.sh runs:
1. Git history shows 50 files added gradually over months
2. Suddenly 100 files appear in S3
3. Mass replacement detected
4. "Large number of files added in recent commit" warning
```

**Detection Timeline**: Immediate

**Evidence**:
- Git history shows legitimate gradual growth
- Sudden appearance of many files contradicts history
- All historical checksums fail verification

**Critical Defense**: External repo's git history cannot be rewritten by attacker who only compromises main infrastructure

---

### Scenario 6: Time-Based Confusion Attack

**Attack Description**:
Attacker manipulates file timestamps to create confusion about file creation order.

**Example**:
1. Attacker creates incremental-checksums-99.txt.gz
2. Sets file timestamp to 2020 (appears old)
3. Hopes it's accepted as historical file

**Defense**:
```
Meta-checksums.edn stores addition timestamp:
{:file "incremental-checksums-99.txt.gz"
 :timestamp "2025-01-15T10:30:00Z"  ; When WE recorded it
 :number 99}

Git commit shows:
commit xyz789...
Author: GitHub Actions
Date:   2025-01-15

Even if S3 file timestamp is 2020:
1. Git history proves we only saw it in 2025
2. Sequence number 99 doesn't match 2020 timeline
3. Inconsistency detected
```

**Detection**: Timestamp analysis in check-integrity.sh

**Evidence**:
- Git commit timestamp is authoritative
- Cannot be backdated without force-push
- S3 timestamp mismatch raises suspicion

---

## Defense-in-Depth Summary

| Attack Vector | Primary Defense | Secondary Defense | Detection Time |
|---------------|-----------------|-------------------|----------------|
| File Modification | SHA256 verification | Git history | < 24 hours |
| File Injection | Sequence analysis | PR review | Immediate/24h |
| File Deletion | Missing file detection | Git history | < 24 hours |
| Gradual Changes | Immutable git history | Continuous verification | Immediate |
| Mass Replacement | Historical comparison | Git evidence | Immediate |
| Timestamp Manipulation | Git commit time | Sequence analysis | < 24 hours |

## Security Best Practices

### For Repository Maintainers

1. **Never force-push** - Destroys audit trail
2. **Review all PRs** - Especially automated ones
3. **Multiple approvers** - Require 2+ maintainers
4. **Separate credentials** - External repo uses different AWS account
5. **Monitor alerts** - Respond to GitHub Issues immediately
6. **Offline backups** - Regular exports to cold storage

### For System Operators

1. **Enable S3 versioning** - Allows file recovery
2. **CloudTrail logging** - Tracks all S3 operations
3. **MFA for sensitive operations** - Prevents credential compromise
4. **Separate AWS accounts** - Limit blast radius
5. **Regular audits** - Monthly review of git history

### For Security Researchers

1. **Check git history** - Look for suspicious patterns
2. **Verify manually** - Spot-check random files
3. **Compare timestamps** - S3 vs git vs expected
4. **Analyze growth patterns** - Should be gradual
5. **Report anomalies** - Create GitHub issues

## Incident Response Plan

### If Tampering Detected

1. **Immediate Actions** (0-1 hour)
   - Stop all automated systems
   - Freeze S3 bucket (versioning, no modifications)
   - Alert all maintainers
   - Create security incident ticket

2. **Investigation** (1-24 hours)
   - Determine when tampering occurred
   - Identify affected files
   - Check S3 CloudTrail logs
   - Review git history for timeline
   - Assess blast radius

3. **Containment** (24-48 hours)
   - Remove compromised artifacts
   - Restore clean checksum files
   - Regenerate affected incremental files
   - Update meta-checksums with corrections

4. **Recovery** (48-72 hours)
   - Verify all artifacts in affected period
   - Re-checksum suspicious artifacts
   - Update documentation
   - Resume automated systems

5. **Post-Incident** (1-2 weeks)
   - Root cause analysis
   - Update security controls
   - Enhance monitoring
   - Public disclosure (if appropriate)

## Conclusion

The external verification repository provides robust defense against tampering by:

1. **Independent verification** - Outside attacker's control
2. **Immutable history** - Git records all changes
3. **Automated detection** - Daily checks catch changes quickly
4. **Multiple checks** - Sequence, size, timestamp validation
5. **Clear evidence** - Audit trail for investigation

No single point of compromise can hide evidence of an attack.
