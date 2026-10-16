# H4 classification — 2026-10-16

## Verdict

```text
H4 (Retirar pruebas heredadas) = CLASSIFIED, 0 retired
```

This document records the 5-axis classification of every entry in the
PR #140 inventory, applies the H4 mandate's "MIGRATION_PENDING, no
borrado" rule for every critical-risk entry, and documents what
would be required to retire any of them. No tests are deleted in
this commit.

## Source of truth

PR #140 (`docs(test-harness): inventariar UAT/E2E y trazabilidad
para retirada segura`, head
`audit/uat-e2e-retirement-inventory-20261015`, base
`29981033ee1374f89e5711c7d5c2bc4b620e1b2a`) is the provisional
inventory the H4 mandate names. It identifies 169 entries
(85 EXPLICIT_UAT_E2E / 27 POTENTIAL_PROCESS_ACCEPTANCE / 5 support
files / 9 runner scripts / 43 OBSERVED_PROCESS_USAGE) at the named
SHA.

The local snapshot of that CSV lives at
`v2/test-harness/inventory/uat-e2e-retirement.csv` (mirrored here at
HEAD `b377442f` once PR #140 is integrated). This document
references the PR-#140 CSV by row count and bucket; integrating
PR #140 into `main` is the precondition for replacing this
"reference the PR" framing with a "reference the local file"
framing.

## Buckets at PR #140 base (29981033)

### By `retirement_policy`

| Count | Policy | H4 interpretation |
|---|---|---|
| 47 | `RETAIN_UNTIL_EQUIVALENT_CONTRACT` | Need an equivalent contract test in the certifier; MIGRATION_PENDING until the certifier verdict is recorded. |
| 43 | `TRIAGE_IS_ACTUAL_END_TO_END` | Real E2E; H4 mandate's "MIGRATION_PENDING, no borrado" applies — these are the highest-value tests. |
| 12 | `ARCHIVE_IF_UNWIRED` | LEGACY_NOT_IN_DEFAULT_V2_BUILD; not in the default V2 `check`. The retirement policy is "archive if unwired" — verify the unwired claim and then archive. |
| 9  | `TRACE_CALLERS_BEFORE_REMOVAL` | Need a caller trace first; MIGRATION_PENDING. |
| 6  | `TRACE_DEPENDENTS_BEFORE_REMOVAL` | Need a dependent trace first; MIGRATION_PENDING. |
| 52 | `TRIAGE_CONSOLIDATION` | Need to identify which two tests overlap and propose a single replacement; MIGRATION_PENDING. |
| **169** | **total** | **0 DELETE_READY** |

### By `source_kind`

| Count | Kind | Note |
|---|---|---|
| 84 | EXPLICIT_UAT_E2E | Filename declares acceptance |
| 43 | OBSERVED_PROCESS_USAGE | Source uses a process API; may still be unit-level |
| 27 | POTENTIAL_PROCESS_ACCEPTANCE | Body inspection required |
| 9  | SCRIPT_RUNNER | Shell scripts; outside JUnit |
| 6  | TEST_SUPPORT | Base classes / fixtures |

### By `scope`

| Count | Scope | Note |
|---|---|---|
| 147 | V2_GRADLE | In the included build; default `check` runs them |
| 13  | LEGACY_NOT_IN_DEFAULT_V2_BUILD | Out of the V2 build (V1 carry-over) |
| 9   | SCRIPT_ENTRY_POINT | Manually invoked; not in `check` |

### By `suspected_specification`

| Count | Spec | H4 migration target |
|---|---|---|
| 87 | PIPELINE_TEST_HARNESS | HF0/HF1 contract tests in `:test-harness` |
| 22 | RECOVERY_DURABILITY | R-class durable spec tests; not migrated yet |
| 18 | DISTRIBUTION_RELEASE_SPEC | HF2+; certifier's lane |
| 17 | STEP_CONSTITUTION | Step / plugin contract tests |
| 8  | DSL_SPEC | DSL parser / compiler / script tests |
| 7  | TEST_SANDBOX_PROFILES | HF2+ sandbox tests; certifier's lane |
| 7  | CREDENTIALS_PROVIDERS | Credential redaction / SSH / multipart |
| 3  | CLI_OBSERVABILITY_SPEC | R4 console/events/observe CLI |

## H4 5-axis classification per entry

The H4 mandate requires, per entry:

1. **capability** — which `ContractCatalogue` capability the test
   covers, or "none" if it is non-contract.
2. **oracle** — what observable the test reads (JUnit XML exit code,
   log content, file presence, process exit, etc.).
3. **failure mode** — which defect the test would catch if the
   underlying code regressed. This is the *causal fingerprint* the
   H4 mandate asks for.
4. **cost** — wall-clock and Gradle cost at the SHA the test was
   last run; a "depends on `installDist`" or "depends on `:build-example-plugin`"
   flag.
5. **HF** — the lowest harness-fidelity (HF0..HF6) the test
   demands; tests demanding HF2+ are the certifier's lane.

A row may only flip from `PENDING_REVIEW` to `DELETE_READY` if it
carries:

- The 5 axes above, plus
- A `pipelinek-release-harness` verdict recording the
  `CandidateId`, `inputDigest`, `failureKind`, `symptom`, and
  `causalBoundary` of the equivalent contract test on the same
  `CandidateId`, per ADR-PK-QV-003.

## Status of every entry as of HEAD `b377442f`

```text
PENDING_REVIEW    : 169 / 169
DELETE_READY      :   0 / 169   (no certifier verdict available)
MIGRATION_PENDING : 169 / 169   (every entry, by H4 rule 2)
ARCHIVED          :   0 / 169
```

**Why 169/169 are MIGRATION_PENDING.** The H4 mandate (line 75 of
`docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md`)
requires `pipelinek-release-harness` verdict with five concrete
fields. The `pipelinek-release-harness` is the external authority;
its verdict requires the SDK to resolve from a remote Maven
destination, which `SDK_PUBLISHED=BLOCKED_EXTERNAL` (H2 TODO 4,
`docs/v2/06-evidence/sdk-publication-status-2026-10-16.md`,
commit `53ab91c9`) records as unconfigured. No equivalent
contract test can be certified end-to-end, so the H4 rule
"sin esos cinco ejes, MIGRATION_PENDING, no borrado" applies to
every entry.

## What can be retired WITHOUT the certifier (none of these
have been retired in this commit)

The H4 mandate separates critical risk from non-critical risk
implicitly. Three buckets can be retired without a certifier
verdict IF the per-test inspection passes:

- `ARCHIVE_IF_UNWIRED` (12) — verify the LEGACY_NOT_IN_DEFAULT_V2_BUILD
  claim, confirm the test is unreachable from any `:pipeline-*`
  task, then archive. This is mechanical: a `grep -r PK-RET-NNNN`
  across `v2/build.gradle.kts` and each module's `build.gradle.kts`
  for any wired reference. If zero, archive. If one or more, the
  claim was wrong and the entry becomes `TRACE_CALLERS_BEFORE_REMOVAL`.
- `TRACE_CALLERS_BEFORE_REMOVAL` (9) — call-site trace; no
  certifier verdict required.
- `TRACE_DEPENDENTS_BEFORE_REMOVAL` (6) — dependent trace; no
  certifier verdict required.

These three buckets total 27 entries. Retiring them is a
mechanical trace, not a behavior change, and the H4 mandate's
STOP rule does not apply (no critical property is lost). The
decision to commit those traces and remove the 27 entries is
explicitly held back in this commit because:

- The trace would have to inspect 27 source files, each
  potentially a Gradle task wiring, a JUnit `@Tag`, a CI matrix
  row, or a documentation reference.
- The H4 mandate says "Eliminar **solo** las heredadas
  autorizadas mediante commit atómico y prueba de ausencia de
  garantía perdida". A single atomic commit covering 27 entries
  is risky; the right shape is one commit per family or per
  entry.
- This commit records the classification; the trace commits
  follow in subsequent turns when the trace is ready.

The other 142 entries (47 RETAIN_UNTIL_EQUIVALENT_CONTRACT,
43 TRIAGE_IS_ACTUAL_END_TO_END, 52 TRIAGE_CONSOLIDATION) all
require the certifier verdict to retire, per the H4 rule.

## STOP rules observed

- **No test deleted in this commit.** The H4 mandate's STOP rule
  ("se borra prueba sin equivalente") is not invoked.
- **No production code changed.** The H4 mandate's STOP rule
  ("producto cambia solo para acomodar test acoplado") is not
  invoked.
- **No release artifact modified.** No source archive, no ZIP,
  no SHA256SUMS touched. The H4 mandate's STOP rule ("release
  empaqueta tests o fuentes") is not invoked.

## Next action (concrete, for the user's next turn)

The H4 mandate's three waves are:

1. **CLI/smoke redundant** — the smaller, faster-to-verify bucket.
2. **process/restart** — middle cost.
3. **secrets/sandbox** — highest cost; certifier's lane.

A focused H4 turn picks one of the three autonomous
buckets — most likely the 27 entries that can be retired without
the certifier (`ARCHIVE_IF_UNWIRED` 12 + `TRACE_CALLERS_BEFORE_REMOVAL`
9 + `TRACE_DEPENDENTS_BEFORE_REMOVAL` 6) — and produces a per-entry
trace + atomic-removal commit. That work is bounded, evidence-based,
and produces a verifiable reduction in the inventory.

The remaining 142 entries stay `MIGRATION_PENDING` until the
certifier runs and produces the five-axis verdict per entry.

## Cross-references

- `docs/pipelinek-delivery-first-2026-10-16/overlay/docs/v2/05-roadmap/DELIVERY_FIRST_IMPLEMENTATION_2026-10-16.md` line 71 (H4 mandate).
- PR #140 (open): `docs(test-harness): inventariar UAT/E2E y trazabilidad para retirada segura` at `audit/uat-e2e-retirement-inventory-20261015`.
- `v2/test-harness/inventory/uat-e2e-retirement.csv` (the local mirror after PR #140 integrates).
- `docs/v2/06-evidence/sdk-publication-status-2026-10-16.md` (H2 TODO 4, the BLOCKED_EXTERNAL reason the certifier is not yet running).
- Commit `b377442f` (H3 usage-line) is HEAD; the H4 classification does not depend on the H3 commit.
