# PR-ADR-002 — Single Release Admission Authority

**Status:** PROPOSED

## Context

The audit found that GitHub Actions is no longer treated as the certification authority, while branch protection has no required checks. An external harness exists, but repository protection does not mechanically bind promotion to its result.

## Decision

Use one release-admission result as the only required promotion check.

The check payload binds:
- source SHA;
- artifact SHA256;
- harness verdict digest;
- supported profile;
- admission timestamp/identity.

Individual CI jobs remain evidence producers, not independent sources of release truth.

## Fail-closed rules

Admission FAILS if:
- check absent;
- SHA mismatch;
- artifact mismatch;
- verdict stale;
- mandatory scenario not PASS.

## Consequences

This preserves external certification while restoring mechanical protection of `main`/release promotion.
