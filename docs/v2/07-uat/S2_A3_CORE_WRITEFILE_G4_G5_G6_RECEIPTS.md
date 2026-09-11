# S2-A3 — `core.file.writeFile` G4/G5/G6 INTERMEDIATE RECEIPTS

Consolidated per-gate receipts (mirrors the S2-A2 receipt family; full evidence
lives in the commit messages and the G8 final receipt).

---

## G4 — REGISTRY_PRIMARY (`7e36db93`)

- `"core.file.writeFile"` removed from `LEGACY_PLUGIN_IDS`: 10 → 9.
- `CoreWriteFileRegistryPrimaryFitnessTest` added (5 tests):
  registry membership, `classify() == Registry`, capability declaration,
  9-residual-key set equality.
- Counter assertions updated in `CanonicalCoreStepCommandRegistryTest`,
  `CoreErrorRegistryPrimaryFitnessTest` (G5 set + G6 counters), and
  `CoreSleepRegistryPrimaryFitnessTest`.
- Legacy decoder branch + dispatcher + metadata row remained intact until G5
  (state: LEGACY_UNREACHABLE, not LEGACY_REMOVED).
- Installed-dist evidence: fixture 17 fresh run exit 0, `out.txt = "hello"`,
  single `FileWritten` via the certified `FileWriteExecutor` substrate;
  replay with same `--db/--control-root` did NOT re-execute the `sh` child
  (READ_ONLY reuse held).

---

## G5 — LEGACY_REMOVED (`92810856`)

Physical deletion (net −218 lines):

- `CanonicalCoreStepDecoder`: `WriteFile` subtype, `WRITE_FILE_PLUGIN_ID`,
  decode branch removed (sealed hierarchy 10 → 9).
- `CanonicalNodeDispatcher`: `writeFileDispatcher` field, when-branch,
  `writeFileContext()` removed.
- `CanonicalWriteFileNodeDispatcher.kt` deleted (its co-located
  `CanonicalEmitEventDispatchContext` relocated to
  `CanonicalEmitEventNodeDispatcher.kt`).
- `CanonicalCoreStepMetadata`: `core.file.writeFile` row removed (10 → 9 rows).
- `CanonicalWriteFileNodeDispatcherTest.kt` deleted; WriteFile rows removed
  from decoder/registry tests.
- `S3WriteFileLegacyRemovedFitnessTest` added:
  LEGACY_REMOVED = membership absent ∧ command absent ∧ decoder absent ∧
  dispatcher absent ∧ metadata absent; residual authorities converge to
  exact 9/9/9 snapshots (LEGACY_PLUGIN_IDS / metadata rows / dispatchers).
- `S3SleepLegacyRemovedFitnessTest` + `S3ErrorLegacyRemovedFitnessTest`
  snapshots updated 10→9 and 11→9.
- Rule-16 proof: the stale `decode(core.sleep) → Pwd()` decoder test was
  failing at base `7e36db93` too (worktree `/tmp/g5base`, base JAR copied for
  the uppercase plugin dependency); corrected to assert fail-closed
  `IllegalArgumentException` rather than being counted as a regression.
- XML canary evidence: application L2 batch (3+11+14+9+5+1 tests, 0 failures);
  architecture batch (4+4+12, 0 failures).

---

## G6 — StepContractSuite (`9ab67c72`)

`WriteFileStepContractSuiteTest`: 21/21 across the 19-row coverage matrix
(identity, contract completeness, codec round-trips + fail-closed rejections,
byte-identical dsl-v1 envelope, production registry resolution, fresh factory
consistency, capability declaration/admission/missing-capability, success,
typed failure, fresh durable, replay, divergence, observability, real DSL
scenario).

**Replay-law finding (RED→GREEN cycle):** the first replay draft asserted
sleep-style MEMOIZED reuse and failed (`StepStarted 2 != 1`). The real
`DefaultEffectReplayPolicy` re-executes the `WRITES_WORKSPACE` family on
replay (like `core.sh`); reuse is the READ_ONLY path. The test was corrected
to codify the certified engine law — the engine was NOT weakened.
