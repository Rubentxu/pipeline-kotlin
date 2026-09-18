# WU-LPR-060 — Certification Ledger — Receipt

Trunk at completion: `a8d9c61f` (WU-LPR-103). Date: 2026-09-18.

## What exists

- `scripts/gen-certification-ledger.py` — deterministic generator (same pattern
  as WU-LPR-061's `gen-disabled-inventory.py`): sources are the registry
  (`CoreStepRegistryFactory.registerInto` calls), the StepKey constants of each
  registered `Core*Step`, the WU-LPR-032 admission table, and the live corpus.
  FAIL-LOUD: a registered Step absent from the admission table aborts generation.
- `docs/v2/07-uat/WU_LPR_060_CERTIFICATION_LEDGER.md` — GENERATED output; the
  single authority for counts. Manual counts in other docs are superseded.

## Derived truth (OBSERVED from generation)

- Registered core StepDefinitions: 14
- SUPPORTED_CERTIFIED: 13
- EXPERIMENTAL: 1 (`core.pwd.tmp`, G3T)
- SUPPORTED_NOT_CERTIFIED: 0
- Registered keys without a live corpus fixture: 1 (`core.emit.event`) — flagged
  in the ledger, not hidden.
- @Disabled counts: owned by WU-LPR-061 inventory (68 total; MANDATORY_SUPPORTED 0).
- Known pre-existing gap: UatLocal008 CR-BD-027 (CredentialUsed per-use events)
  PRE_EXISTING on base `c29e3c1f` (worktree reproduction, WU-LPR-103 receipt).

## Verification

- Generator re-run twice: identical output modulo generation timestamp.
- Corpus gate (live evidence input): CompatibilityCorpusTest 22/22 fresh (WU-LPR-103).

## Checkpoint handoff

This closes the last work unit before the PRODUCT CERTIFICATION CHECKPOINT.
The STOP report (Gate-1 blockers, certification classes, live compatibility,
mandatory disabled, security/CLI/observation gates, YES/NO question) is
delivered to the human in-session per the LPR train contract.
