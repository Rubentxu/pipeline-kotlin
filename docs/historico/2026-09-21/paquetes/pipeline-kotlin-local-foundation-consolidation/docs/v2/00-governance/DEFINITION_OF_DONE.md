# Definition of Done

A consolidation story is done only when all applicable items are true.

## Code

- target contract implemented;
- legacy caller migrated;
- no new TODO/compatibility shim without expiry milestone;
- failure/cancellation path implemented;
- no direct adapter/global-state bypass introduced.

## Tests

- unit/contract tests;
- relevant black-box UAT;
- architecture fitness;
- regression suite;
- performance/security test where applicable.

## Documentation

- ADR/spec updated if behavior/contract changed;
- CLI/plugin API docs regenerated where relevant;
- debt entry closed/updated;
- migration note added for removed public behavior.

## Deletion

A replacement story is **not done** until the old production path is deleted, or an accepted ADR explicitly gives it a bounded compatibility lifetime.

## Evidence receipt

Record exact commit, commands, test counts/results, removed paths, measured metrics and remaining known limitations.
