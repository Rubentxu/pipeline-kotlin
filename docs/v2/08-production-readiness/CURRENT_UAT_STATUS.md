# Current UAT Status (Production-Readiness)
**Generated at (UTC):** 2026-09-26T16:29:01Z
**Source of truth:** `git log` HEAD `f7f09ab` + evidence scan in `docs/v2/07-uat/` (normative matrix excluded).
**Generator:** `scripts/gen-current-uat-status.py` (D-007 + T0E-EVID-01 architecture)

---

## Status by UAT

| UAT ID | Status | Latest Receipt | Evidence excerpt |
|---|---|---|---|
| `UAT-RP-001` | **REFERENCED** | `docs/v2/07-uat/WU_RP_045_SLICE_RECEIPT.md` | 1737 tests, 0 failures, 115 skipped (0 obligatory UAT-RP-001..024 |
| `UAT-RP-002` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-002 | COVERED | candidate=56467ed2 | tests=oracle_a_gradlew_version+oracle_b_workflow_artifact_upl |
| `UAT-RP-003` | **REFERENCED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | - Evidencia: certification del plugin + UAT-RP-003 (ADR-0069). |
| `UAT-RP-004` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-004 | COVERED | candidate=56467ed2 | tests=DslCompiledPipelineCompilerTest:13+CliCompileErrorExits |
| `UAT-RP-005` | **KNOWN_LIMITATION** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | 3. UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a |
| `UAT-RP-006` | **REFERENCED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | - **UAT-RP-006 (HTML injection)**: covered by round 1 (5 rp011 tests). |
| `UAT-RP-007` | **REFERENCED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | - **UAT-RP-007 (paths publish)**: covered by round 2 (5 rp011r2 tests). |
| `UAT-RP-008` | **COVERED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | - **UAT-RP-008** (Stash symlinks): covered by 7 rp012 tests (this WU). |
| `UAT-RP-009` | **REFERENCED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | | 6 | `rp012 — stash followed by unstash is bit-exact roundtrip (UAT-RP-009)` | Roundtrip preserves content and sha256;  |
| `UAT-RP-010` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-010 | COVERED | candidate=56467ed2 | tests=JsonEventLogRoundTripTest:28 | exit=0 |
| `UAT-RP-011` | **REFERENCED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | - UAT-RP-011..015: mapeados en PRODUCTION_READY_UAT_MATRIX.md (concurrencia |
| `UAT-RP-012` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | | WU-RP-031 | extracciones StructuralPreparation→…→StepExecutor con golden journal/replay + kill/resume | CUMPLE — recei |
| `UAT-RP-013` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-013 | COVERED | candidate=56467ed2 | tests=DivergenceDetectorTest:4 | exit=0 |
| `UAT-RP-014` | **REFERENCED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | (UAT-RP-014). Prioridad alta dentro de RP-4 (aislamiento runner, WU-RP-041). |
| `UAT-RP-015` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-015 | COVERED | candidate=56467ed2 | tests=Lpr011SecretRedactionTranscriptUatTest:6+Lpr011r2Secret |
| `UAT-RP-016` | **REFERENCED** | `docs/v2/07-uat/WU_RP_023_RECEIPT.md` | - UAT-RP-016 (PERF): evidencia formal en `WU_RP_022_RECEIPT.md` (baseline |
| `UAT-RP-017` | **REFERENCED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | - UAT-RP-017: `WURp023ObservationModesUatTest` (HF2, binario real de este SHA): |
| `UAT-RP-018` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | - UAT-RP-018: PARTIAL — límites de recursos OS requieren sandbox-profile 'os' |
| `UAT-RP-019` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-019 | COVERED | candidate=86c9ace8 | tests=WURp019GradleRealUatTest:2 | exit=0 | xml-sha256=f1dc46 |
| `UAT-RP-020` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-020 | COVERED | candidate=86c9ace8 | tests=WURp020MavenRealUatTest:2 | exit=0 | xml-sha256=95e0008 |
| `UAT-RP-021` | **COVERED** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | UAT-EVIDENCE | UAT-RP-021 | COVERED | candidate=86c9ace8 | tests=WURp021NodeRealUatTest:2 | exit=0 | xml-sha256=ff69036e |
| `UAT-RP-022` | **REFERENCED** | `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-023` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R2_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-024` | **KNOWN_LIMITATION** | `docs/v2/07-uat/T0E_CLOSURE_RECEIPT.md` | | UAT-RP-024 dogfooding | KNOWN_LIMITATION parcial 1-repo. ≥2 repos estructuralmente imposible en sesión autónoma. | |
| `UAT-RP-025` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |
| `UAT-RP-026` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |
| `UAT-RP-027` | **NOT_APPLICABLE** | `—` | _not applicable to current profile_ |

---

## Status Summary

- **Total UATs (PRDY-003 contract):** 27
- **COVERED:** 12
- **KNOWN_LIMITATION:** 2
- **REFERENCED:** 10
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

## Discoveries

- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable); this generator honours that separation and excludes the matrix from classification.
- Several UATs were referenced only in narrative text. The generator returns `REFERENCED` instead of inferring `COVERED`; admission does not block on REFERENCED (only on FAIL_PROVEN / BLOCKED / REJECTED / NOT_RUN / CONFLICT).
- Multi-UAT free-form lines (no `UAT-EVIDENCE | ...` marker) cannot be safely scoped and yield REFERENCED for every UAT on the line. New receipts SHOULD use the marker.
- SHA lexical order is NOT causal recency; the certifier uses DAG-maximal commits (`git merge-base --is-ancestor`).
- KNOWN_LIMITATION is recognised as PARTIAL-equivalent (causally supersedes older FAIL_PROVEN) per ADR-0095.
