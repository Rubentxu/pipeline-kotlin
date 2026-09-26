# Information Gaps Requiring Verification During Execution

The audit establishes the existence of the following gaps but does not contain enough evidence to resolve them without fresh execution.

## GAP-001 — Exact composition of the 41 failures

Known:
- a recent full-suite run reported 3187 tests / 41 failures;
- they were described as pre-existing.

Unknown:
- exact test names;
- how many are mandatory versus legacy/fixture debt;
- whether all 41 still reproduce on the reconciled candidate.

Action: PR-008.

## GAP-002 — Final intended HAR-007 public contract

Known:
- current behavior was documented as failing the expected harness scenario;
- typed `DirFailureMode` and an implicit isolation path were previously considered.

Unknown:
- which behavior is the normative supported contract for the next candidate if current ADR/specs disagree.

Action: PR-009 must resolve contract precedence before implementation.

## GAP-003 — Current vulnerability state

Known:
- Dependabot/SBOM mechanisms exist;
- dependency PR backlog exists.

Unknown:
- candidate-current CVE/SCA result.

Action: PR-012.

## GAP-004 — Candidate-current coverage

Known historical aggregate:
- line ~77.9%;
- branch ~56.3%;
- class ~87.4%.

Unknown:
- exact metrics for the candidate after PR reconciliation/HAR-007.

Action: PR-013.

## GAP-005 — Candidate-current memory behavior

Known:
- previous M5 observation reported ~11 GB RSS without an accepted SLO.

Unknown:
- current repeatability and suitable upper bound.

Action: PR-015.

## GAP-006 — External harness authority wiring

Known:
- the project moved certification authority toward an external harness;
- GitHub branch protection currently has no required status check.

Unknown:
- exact mechanism currently available to publish a cryptographically or digest-bound verdict back to GitHub.

Action: PR-006.
