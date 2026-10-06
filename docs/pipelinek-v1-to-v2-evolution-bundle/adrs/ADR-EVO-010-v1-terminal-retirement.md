# ADR-EVO-010 — V1 retirement is the final certified programme milestone

**Status:** Proposed

## Decision

Keep V1 only as a migration/reference oracle while V2 replacements are built. After all adopted capabilities are certified and rejected ideas documented, export any required archaeology outside the repository and delete V1 code, legacy build wiring, scripts, examples and legacy documentation.

The resulting repository must have automated tests proving no production/build/doc references remain.

## Rationale

Deleting early loses comparison value; keeping forever preserves ambiguity and accidental dependencies. A terminal migration gate resolves both risks.
