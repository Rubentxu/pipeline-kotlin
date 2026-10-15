# Current UAT Status (Production-Readiness)
**Generated at (UTC):** 2026-10-15T20:56:32Z
**Source of truth:** `git log` HEAD `b2adcff` + evidence scan in `docs/v2/07-uat/` (normative matrix excluded).
**Generator:** `scripts/gen-current-uat-status.py` (D-007 + T0E-EVID-01 architecture)

---

## Status by UAT

| UAT ID | Status | Latest Receipt | Evidence excerpt |
|---|---|---|---|
| `UAT-RP-001` | **REFERENCED** | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` | - `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` table rows `UAT-RP-001`..`027` |
| `UAT-RP-002` | **COVERED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | UAT-EVIDENCE | UAT-RP-002 | COVERED | candidate=9d0607d7 | tests=oracle_a_gradlew_version+oracle_b_workflow_artifact_upl |
| `UAT-RP-003` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | UAT-EVIDENCE | UAT-RP-003 | COVERED | candidate=7904b3c3 | tests=StepRegistryTest:8+StepDefinitionContributorTest:5+Regi |
| `UAT-RP-004` | **COVERED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | UAT-EVIDENCE | UAT-RP-004 | COVERED | candidate=9d0607d7 | tests=DslCompiledPipelineCompilerTest:13+CliCompileErrorExits |
| `UAT-RP-005` | **REFERENCED** | `docs/v2/07-uat/B1_HARNESS_UAT_STATUS_BINDING_RECEIPT.md` | | F1 | `_MARKER_PATTERN` was anchored at `^\s*UAT-EVIDENCE`, so a marker wrapped in backticks did not parse as a marker  → **narrative-superseded: KNOWN_LIMITATION** (older evidence: `docs/v2/07-uat/RP3_EXIT_REVIEW.md`) |
| `UAT-RP-006` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-006 | COVERED | candidate=70339af3 | tests=PublishHtmlOperationsAdapterUatTest:14 | exit=0 | xml-s |
| `UAT-RP-007` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-007 | COVERED | candidate=70339af3 | tests=PublishHtmlOperationsAdapterUatTest:14 | exit=0 | xml-s |
| `UAT-RP-008` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-008 | COVERED | candidate=70339af3 | tests=StashOperationsAdapterUatTest:7 | exit=0 | xml-sha256=e |
| `UAT-RP-009` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-009 | COVERED | candidate=70339af3 | tests=StashOperationsAdapterUatTest:7 | exit=0 | xml-sha256=e |
| `UAT-RP-010` | **COVERED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | UAT-EVIDENCE | UAT-RP-010 | COVERED | candidate=9d0607d7 | tests=JsonEventLogRoundTripTest:28 | exit=0 | xml-sha256=551c |
| `UAT-RP-011` | **REFERENCED** | `docs/v2/07-uat/WIP9_OBSERVABILITY_RECEIPT.md` | - **UAT-RP-011/012/013/015 no existen por esos IDs.** El roadmap usa IDs abstractos de un conjunto → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/RP2_GATE_RECEIPT.md`) |
| `UAT-RP-012` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | | WU-RP-031 | extracciones StructuralPreparation→…→StepExecutor con golden journal/replay + kill/resume | CUMPLE — recei |
| `UAT-RP-013` | **COVERED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=9d0607d7 | tests=DivergenceDetectorTest:4 | exit=0 | xml-sha256=1c29ed0e |
| `UAT-RP-014` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | UAT-EVIDENCE | UAT-RP-014 | COVERED | candidate=97a3cdb4 | tests=BodyExecutionPolicyTest:28 (5 nested classes: Represent |
| `UAT-RP-015` | **REFERENCED** | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` | | 2 | `Lpr011r2SecretRedactionAtRestUatTest` | **PRODUCT-GATE** (security) + STEP-CERT C15 | `CERTIFICATION_PROTOCOL.md` → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md`) |
| `UAT-RP-016` | **REFERENCED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | - **REFERENCED:** 2 (UAT-RP-001 perf baseline narrative, UAT-RP-016 PERF) |
| `UAT-RP-017` | **REFERENCED** | `docs/v2/07-uat/WIP9_OBSERVABILITY_RECEIPT.md` | | `WURp023ObservationModesUatTest` (UAT-RP-017) | 1 | 0 | 0 | → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/RP2_GATE_RECEIPT.md`) |
| `UAT-RP-018` | **PARTIAL** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | - **PARTIAL:** 1 (UAT-RP-018 sandbox resource limits) |
| `UAT-RP-019` | **REFERENCED** | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` | `UAT-RP-019`, `UAT-RP-020`, `UAT-RP-021` (`WURp019GradleRealUatTest`, → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`) |
| `UAT-RP-020` | **REFERENCED** | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` | `UAT-RP-019`, `UAT-RP-020`, `UAT-RP-021` (`WURp019GradleRealUatTest`, → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`) |
| `UAT-RP-021` | **REFERENCED** | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` | `UAT-RP-019`, `UAT-RP-020`, `UAT-RP-021` (`WURp019GradleRealUatTest`, → **narrative-superseded: COVERED** (older evidence: `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`) |
| `UAT-RP-022` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-022 | COVERED | candidate=92f7c4a7 | tests=distZip_byte_identical_double_build | exit=0 | note=rec |
| `UAT-RP-023` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | ## 3. UAT-RP-023 — COVERED con KNOWN_GAP documentado ✅ |
| `UAT-RP-024` | **KNOWN_LIMITATION** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | UAT-EVIDENCE | UAT-RP-024 | KNOWN_LIMITATION | candidate=789e6e01 | tests=structural_partial_1-repo_dogfooding | exit=0  |
| `UAT-RP-025` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |
| `UAT-RP-026` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |
| `UAT-RP-027` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |

---

## Status Summary

- **Total UATs (PRDY-003 contract):** 27
- **COVERED:** 13
- **PARTIAL:** 1
- **KNOWN_LIMITATION:** 1
- **REFERENCED:** 9
- **NOT_APPLICABLE:** 3

---

## Applicability Gates (T0E-EVID-01 E3)

| UAT | Gate | Status |
|---|---|---|
| `UAT-RP-025` | SDKMAN_READY declared | **NOT_APPLICABLE** |
| `UAT-RP-026` | REMOTE profile enabled | **NOT_APPLICABLE** |
| `UAT-RP-027` | Jenkins adapter enabled | **NOT_APPLICABLE** |

---

## Acceptance Criteria (PRDY-003 + D-007 + T0E-EVID-01)

- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).
- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for evidence triples; normative matrix is excluded.
- [x] C3: Statuses from explicit markers only (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REJECTED / KNOWN_LIMITATION). No narrative inference.
- [x] C4: Selection by DAG-maximal commits (T0E-EVID-01). SHA lexical order is NOT used as recency.
- [x] C5: Per-UAT status scoping (T0E-EVID-01). Multi-UAT free-form lines do not certify any UAT (REFERENCED only).
- [x] C6: Two incompatible explicit statuses among maximals -> CONFLICT (fail-closed; CONFLICT blocks admission).
- [x] C7: Generator + tests + receipt produced.
- [x] C8: `PRODUCTION_READY_UAT_MATRIX.md` remains normative only; this file replaces its mutable section.
- [x] C9: Applicability gates produce NOT_APPLICABLE (non-blocking) for UAT-RP-025/026/027 when the corresponding feature is not declared in the codebase.

---

## Malformed marker-shaped lines (refused; no certification)

- `docs/v2/07-uat/RP2_GATE_RECEIPT.md`: `UAT-EVIDENCE`. Este turno autónomo re-ejecuta el test contra el
- `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`: UAT-EVIDENCE marker.

---

## Narrative supersessions (REFERENCED rows hiding an older explicit status)

These rows are REFERENCED because the DAG-maximal evidence is a narrative mention, while an older receipt did carry an explicit status. The derivation follows ADR-0095/T0E-EVID-01; the list exists so nobody reads REFERENCED as 'never certified'. To restore the status, publish evidence with a real marker (`UAT-EVIDENCE | <UAT> | <STATUS> | candidate=<sha>`), or amend the rule with an ADR.

| UAT | Hidden status | Older evidence | Superseding narrative |
|---|---|---|---|
| `UAT-RP-005` | **KNOWN_LIMITATION** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | `docs/v2/07-uat/B1_HARNESS_UAT_STATUS_BINDING_RECEIPT.md` |
| `UAT-RP-011` | **COVERED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md`, `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | `docs/v2/07-uat/WIP9_OBSERVABILITY_RECEIPT.md` |
| `UAT-RP-015` | **COVERED** | `docs/v2/07-uat/T0E_EVID_01_E2_VERIFY_AT_9D0607D7.md` | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-017` | **COVERED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | `docs/v2/07-uat/WIP9_OBSERVABILITY_RECEIPT.md` |
| `UAT-RP-019` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`, `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-020` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`, `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` |
| `UAT-RP-021` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md`, `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | `docs/v2/07-uat/UAT_GATE_CLASSIFICATION.md` |

---

## Discoveries

- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable); this generator honours that separation and excludes the matrix from classification.
- Several UATs were referenced only in narrative text. The generator returns `REFERENCED` instead of inferring `COVERED`; admission does not block on REFERENCED (only on FAIL_PROVEN / BLOCKED / REJECTED / NOT_RUN / CONFLICT).
- Multi-UAT free-form lines (no `UAT-EVIDENCE | ...` marker) cannot be safely scoped and yield REFERENCED for every UAT on the line. New receipts SHOULD use the marker.
- SHA lexical order is NOT causal recency; the certifier uses DAG-maximal commits (`git merge-base --is-ancestor`).
- KNOWN_LIMITATION is recognised as PARTIAL-equivalent (causally supersedes older FAIL_PROVEN) per ADR-0095.
