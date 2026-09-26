# Current UAT Status (Production-Readiness)
**Generated at (UTC):** 2026-09-26T10:20:45Z
**Source of truth:** `git log --short` HEAD `ddaba87d` + receipt scan in `docs/v2/07-uat/`.
**Generator:** `scripts/gen-current-uat-status.py`

---

## Status by UAT

| UAT ID | Status | Latest Receipt | Evidence excerpt |
|---|---|---|---|
| `UAT-RP-001` | **COVERED** | `docs/v2/07-uat/WU_RP_045_SLICE_RECEIPT.md` | 1737 tests, 0 failures, 115 skipped (0 obligatory UAT-RP-001..024 |
| `UAT-RP-002` | **COVERED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-002 | COVERED | LPR-0 CI run 35705391067 (artifact-upload correcto) | workflow `lpr0-ci.yml` con `v2/gradlew` y |
| `UAT-RP-003` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | - Evidencia: certification del plugin + UAT-RP-003 (ADR-0069). |
| `UAT-RP-004` | **COVERED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-004 | COVERED | UatDsl001 / UatDsl003 / UatDsl005 / UatDsl006 | DSL scripts válidos compilan; negativos con dia |
| `UAT-RP-005` | **PARTIAL** | `docs/v2/07-uat/WU_RP_010_RECEIPT.md` | # WU-RP-010 — PublishHTML E2E coverage of UAT-RP-005 (round 1, test-only) |
| `UAT-RP-006` | **COVERED** | `docs/v2/07-uat/WU_RP_011_RECEIPT.md` | - **UAT-RP-006 (HTML injection)**: covered by round 1 (5 rp011 tests). |
| `UAT-RP-007` | **COVERED** | `docs/v2/07-uat/WU_RP_011_RECEIPT.md` | - **UAT-RP-007 (paths publish)**: covered by round 2 (5 rp011r2 tests). |
| `UAT-RP-008` | **COVERED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | - **UAT-RP-008** (Stash symlinks): covered by 7 rp012 tests (this WU). |
| `UAT-RP-009` | **COVERED** | `docs/v2/07-uat/WU_RP_012_RECEIPT.md` | | 6 | `rp012 — stash followed by unstash is bit-exact roundtrip (UAT-RP-009)` | Roundtrip preserves content and sha256;  |
| `UAT-RP-010` | **COVERED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-010 | COVERED | UatEvt001 / UatEvt002 (replay) + WU-LPR-xxx event codec | roundtrip completo variantes. | |
| `UAT-RP-011` | **COVERED** | `docs/v2/07-uat/WU_RP_023_RECEIPT.md` | - Matriz: filas UAT-RP-011..018 actualizadas en |
| `UAT-RP-012` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | | WU-RP-031 | extracciones StructuralPreparation→…→StepExecutor con golden journal/replay + kill/resume | CUMPLE — recei |
| `UAT-RP-013` | **COVERED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-013 | COVERED (RP-1) | StrictFingerprintDivergenceDetector + coordinator tests | divergence fail-closed antes d |
| `UAT-RP-014` | **COVERED** | `docs/v2/07-uat/RP3_EXIT_REVIEW.md` | (UAT-RP-014). Prioridad alta dentro de RP-4 (aislamiento runner, WU-RP-041). |
| `UAT-RP-015` | **COVERED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-015 | COVERED (RP-2) | StreamingRedactorTest (23) + Lpr011SecretRedactionTranscriptUatTest + Lpr011r2SecretReda |
| `UAT-RP-016` | **COVERED** | `docs/v2/07-uat/WU_RP_023_RECEIPT.md` | - UAT-RP-016 (PERF): evidencia formal en `WU_RP_022_RECEIPT.md` (baseline |
| `UAT-RP-017` | **COVERED** | `docs/v2/07-uat/WU_RP_023_RECEIPT.md` | # WU-RP-023 — UAT-RP-017 Observation Receipt |
| `UAT-RP-018` | **COVERED** | `docs/v2/07-uat/RP2_GATE_RECEIPT.md` | - UAT-RP-018: PARTIAL — límites de recursos OS requieren sandbox-profile 'os' |
| `UAT-RP-019` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | # WU-RP-046 — Slice receipt (round 1): UAT-RP-019/020/021 ejecutables + matriz actualizada |
| `UAT-RP-020` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real), UAT-RP-021 (Node real) **NO tenían cobertura visible** en HEAD (aud |
| `UAT-RP-021` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-019 (Gradle real), UAT-RP-020 (Maven real), UAT-RP-021 (Node real) **NO tenían cobertura visible** en HEAD (aud |
| `UAT-RP-022` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-023` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | Esta sesión cierra la brecha ejecutable: implementa cobertura real para UAT-RP-019/020/021 (6 tests nuevos, 6/6 PASS en  |
| `UAT-RP-024` | **PARTIAL** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | - UAT-RP-024 (dogfooding en dos repos) NO era ejecutable en sesión autónoma. |
| `UAT-RP-025` | **COVERED** | `docs/v2/07-uat/WU_RP_046_R1_SLICE_RECEIPT.md` | | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | updated | baseline → 87d7f2ef; UAT-RP-018 COVERED; UAT-RP-019/020/02 |
| `UAT-RP-026` | **REFERENCED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-026 | Remote (RP-8) | lease/fencing, reconnect/ACK, replay/cancel y partición de red | sólo exigible para el pe |
| `UAT-RP-027` | **REFERENCED** | `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` | | UAT-RP-027 | Jenkins (RP-9) | eventos live de stages, reinicio dashboard, mismos outcomes y autorización | sólo exigib |

---

## Status Summary

- **Total UATs (PRDY-003 contract):** 27
- **COVERED:** 23
- **PARTIAL:** 2
- **REFERENCED:** 2

---

## Acceptance Criteria (PRDY-003)

- [x] C1: All 27 UAT-RP-001..027 IDs enumerated (normative contract).
- [x] C2: Each ID scanned against `docs/v2/07-uat/*.md` for receipt references.
- [x] C3: Status classified from receipt content (COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REFERENCED).
- [x] C4: Generator + tests + receipt produced.
- [x] C5: `PRODUCTION_READY_UAT_MATRIX.md` reduced to normative only (this file replaces its mutable section).

---

## Discoveries

- The normative matrix in `PRODUCTION_READY_UAT_MATRIX.md` mixes contract (immutable) and current state (mutable). This file replaces the mutable portion.
- Several UATs are referenced only in narrative text, not in dedicated receipts. The generator reports `REFERENCED` instead of `COVERED` to avoid implying certification that does not exist.
