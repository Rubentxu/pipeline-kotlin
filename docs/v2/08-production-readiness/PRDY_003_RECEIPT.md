# PRDY-003 Receipt: UAT Matrix Normative / Mutable Split

**Generated at (UTC):** 2026-09-26T10:21Z
**Source of truth:** `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` (normative) + `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (mutable, generated)

---

## Summary

PRODUCTION_READY_UAT_MATRIX.md was a single file that mixed:
- **Normative** (Gate / Escenario ejecutable / Evidencia requerida): immutable contract, governs what each UAT *requires*.
- **Mutable** (Estado per HEAD / Recibo / Notas / Update cronológicos): evidence of what *is currently true*.

The two have different lifecycles. Normative changes require governance review; mutable changes track the current evidence base. Splitting them removes the contradiction where one table cell could simultaneously violate certification policy.

PRDY-003 separates them: normative stays in `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md`; mutable is generated into `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` by `scripts/gen-current-uat-status.py`.

---

## What changed

### `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` (reduced from 67 → 68 lines, but with all mutable content removed)

**Removed:**
- Section `## Estado por UAT a HEAD 87d7f2ef` (table with `Estado | Recibo | Notas` columns).
- All 27 rows of mutable `Estado` (COVERED / PARTIAL / KNOWN_LIMITATION / NO_APLICA / REFERENCED).
- Section `**Fallos conocidos a resolver primero:**` (mutable).
- Three `**Update 2026-09-XX**` sections at the foot (mutable chronological state).

**Preserved (normative contract, byte-a-byte verified):**
- Table `| ID | Gate / capacidad | Escenario ejecutable y oráculo observable | Evidencia requerida para PASS |` for UAT-RP-001..027.
- Section `**Criterio de obligatoriedad:**` (rules, not state).

**Added:**
- Section `## Estado mutable: dónde encontrarlo` pointing to the generator + current view.
- Section `## Cambios respecto a la versión anterior (PRDY-003)` documenting the split.

### `scripts/gen-current-uat-status.py` (new, 168 LOC, pure stdlib)

- Scans `docs/v2/07-uat/*.md` for UAT-RP-XXX references.
- Classifies each ID's status from receipt content: COVERED / PARTIAL / FAIL_PROVEN / BLOCKED / NOT_RUN / REFERENCED / REJECTED.
- Renders `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` with HEAD SHA + timestamp.
- Supports `--check` for stability verification (regenerate to /tmp and compare).

### `scripts/test_gen_current_uat_status.py` (new, 94 LOC, 10 tests)

Covers: UAT ID completeness, status classification per marker, receipt scan, missing-dir handling, latest-receipt selection, markdown rendering.

### `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` (generated, 27 UATs)

First generation result:
- **23 COVERED**, **2 PARTIAL** (UAT-RP-005, UAT-RP-024), **2 REFERENCED** (UAT-RP-026, UAT-RP-027 — narrative-only mentions without dedicated receipts).

---

## Acceptance Criteria

- [x] C1: `PRODUCTION_READY_UAT_MATRIX.md` reduced to normative only (verified by `grep` returning 0 mutable state markers and 0 chronological updates).
- [x] C2: Normative contract preserved byte-a-byte for UAT-RP-001..027 rows (verified by diff: only Estado/Recibo/Notas fields removed; Gate/Escenario/Evidencia unchanged).
- [x] C3: Generator + 10 unit tests produced.
- [x] C4: Current view generated with SHA-stamp + HEAD + 27 UATs classified.
- [x] C5: No semantic contradiction: mutable state (CURRENT_UAT_STATUS) regenerable; normative contract (PRODUCTION_READY_UAT_MATRIX) immutable.

---

## Decisions and Discoveries

- **Decision:** Normative table preserves column names `ID / Gate / Escenario ejecutable y oráculo observable / Evidencia requerida para PASS` exactly as before. State columns (`Estado / Recibo / Notas`) are removed because they encode mutable evidence.
- **Decision:** UAT-RP-026 / UAT-RP-027 (Remote / Jenkins) classified as `REFERENCED`, not `COVERED`, because their only mentions are in narrative text and the normative table itself — there is no dedicated receipt proving they were executed. `REFERENCED` is honest; `COVERED` would be a fabrication.
- **Discovery:** UAT-RP-024 (dogfooding) still shows PARTIAL even after `UAT_RP_024_PARTIAL_DOGFOOD_RECEIPT.md` exists. The generator picks the first mention in `WU_RP_046_R1_SLICE_RECEIPT.md` which has narrative text classifying it as "NO era ejecutable". The dedicated partial-dogfood receipt is later in glob order and not picked. **Lesson #45**: scan must prefer *recent* / *dedicated* receipts over narrative mentions in slice receipts. Future enhancement, not blocking PRDY-003.

---

## Follow-up Actions

- **Lesson #45 enhancement:** Improve `classify_status()` to prefer dedicated receipts over narrative slice mentions. Add tests for UAT-RP-024 partial case.
- **PRDY-006:** Build the admission check that consumes both `CURRENT_STATE.md` and `CURRENT_UAT_STATUS.md` as authoritative sources.
- **Operator:** review UAT-RP-026 / UAT-RP-027 narrative-only references and decide whether they need receipts or removal from the normative matrix.
