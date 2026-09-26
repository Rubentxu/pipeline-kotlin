# Current UAT Status (Production-Readiness)
**Generated at (UTC):** 2026-09-26T12:15:36Z
**Source of truth:** `git log` HEAD `633f230` + evidence scan in `docs/v2/07-uat/` (normative matrix excluded).
**Generator:** `scripts/gen-current-uat-status.py` (D-007 architecture)

---

## Status by UAT

| UAT ID | Status | Latest Receipt | Evidence excerpt |
|---|---|---|---|
| `UAT-RP-001` | **REFERENCED** | `docs/v2/07-uat/WU_RP_045_SLICE_RECEIPT.md` | 1737 tests, 0 failures, 115 skipped (0 obligatory UAT-RP-001..024 |
| `UAT-RP-002` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-003` | **REFERENCED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | - Evidencia: certification del plugin + UAT-RP-003 (ADR-0069). |
| `UAT-RP-004` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-005` | **FAIL_PROVEN** | `docs/v2/07-uat/WU_RP_011_RECEIPT.md` | 3. UAT-RP-005 invariant 3 (MANIFEST.json): FAIL_PROVEN, ADR-0095, difiere a |
| `UAT-RP-006` | **COVERED** | `docs/v2/07-uat/WU_RP_011_RECEIPT.md` | - **UAT-RP-006 (HTML injection)**: covered by round 1 (5 rp011 tests). |
| `UAT-RP-007` | **COVERED** | `docs/v2/07-uat/WU_RP_011_RECEIPT.md` | - **UAT-RP-007 (paths publish)**: covered by round 2 (5 rp011r2 tests). |
| `UAT-RP-008` | **COVERED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | - **UAT-RP-008** (Stash symlinks): covered by 7 rp012 tests (this WU). |
| `UAT-RP-009` | **REFERENCED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | | 6 | `rp012 — stash followed by unstash is bit-exact roundtrip (UAT-RP-009)` | Roundtrip preserves content and sha256;  |
| `UAT-RP-010` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-011` | **REFERENCED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | - UAT-RP-011..015: mapeados en PRODUCTION_READY_UAT_MATRIX.md (concurrencia |
| `UAT-RP-012` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | | WU-RP-031 | extracciones StructuralPreparation→…→StepExecutor con golden journal/replay + kill/resume | CUMPLE — recei |
| `UAT-RP-013` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-014` | **REFERENCED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | (UAT-RP-014). Prioridad alta dentro de RP-4 (aislamiento runner, WU-RP-041). |
| `UAT-RP-015` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-016` | **REFERENCED** | `docs/v2/07-uat/WU_RP_023_RECEIPT.md` | - UAT-RP-016 (PERF): evidencia formal en `WU_RP_022_RECEIPT.md` (baseline |
| `UAT-RP-017` | **REFERENCED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | - UAT-RP-017: `WURp023ObservationModesUatTest` (HF2, binario real de este SHA): |
| `UAT-RP-018` | **PARTIAL** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-018: PARTIAL — límites de recursos OS requieren sandbox-profile 'os' |
| `UAT-RP-019` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | # WU-RP-046 — Slice receipt (round 1): UAT-RP-019/020/021 ejecutables + matriz actualizada |
| `UAT-RP-020` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real), UAT-RP-021 (Node real) **NO tenían cobertura visible** en HEAD (aud |
| `UAT-RP-021` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real), UAT-RP-021 (Node real) **NO tenían cobertura visible** en HEAD (aud |
| `UAT-RP-022` | **REFERENCED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-023` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-024` | **REFERENCED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | | UAT-RP-024 dogfooding | KNOWN_LIMITATION parcial 1-repo. ≥2 repos estructuralmente imposible en sesión autónoma. | |
| `UAT-RP-025` | **CONFLICT** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | updated | baseline → 87d7f2ef; UAT-RP-018 COVERED; UAT-RP-019/020/02 |
| `UAT-RP-026` | **NOT_RUN** | `—` | _no evidence yet_ |
| `UAT-RP-027` | **NOT_RUN** | `—` | _no evidence yet_ |

---

## Status Summary

- **Total UATs (PRDY-003 contract):** 27
- **FAIL_PROVEN:** 1
- **COVERED:** 8
- **PARTIAL:** 1
- **NOT_RUN:** 7
- **CONFLICT:** 1
- **REFERENCED:** 9

---

## Acceptance Criteria (PRDY-003) + D-007

- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).
- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for evidence triples; normative matrix is excluded.
- [x] C3: Status derived from explicit markers only (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REJECTED). No narrative inference (FAIL, KNOWN_GAP, NO_APLICA, fail-closed are NOT status).
- [x] C4: Selection by latest applicable evidence (Git commit SHA, ancestor of candidate/HEAD). Glob order does not decide.
- [x] C5: Two incompatible explicit statuses without resolvable precedence -> CONFLICT (fail-closed; CONFLICT blocks admission).
- [x] C6: Generator + tests + receipt produced.
- [x] C7: `PRODUCTION_READY_UAT_MATRIX.md` remains normative only; this file replaces its mutable section.

---

## Discoveries

- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable); this generator honours that separation and excludes the matrix from classification.
- Several UATs were referenced only in narrative text. The generator returns `REFERENCED` instead of inferring `COVERED` from phrases like `COVERED (RP-1)`; admission does not block on REFERENCED (only on FAIL_PROVEN / BLOCKED / REJECTED / CONFLICT).
- Phrases like `fail-closed` describe the expected contract behaviour, not a failure; they are not interpreted as `FAIL_PROVEN`.
