# S2-A4 / G2 — core.emit.event Differential Contract Freeze

- **Gate**: G2 (differential-contract freeze) — NO flip, NO whitelist change, NO routing change.
- **Base SHA**: `15a3f10c` (G1) · **G2 commit**: `294c67e1`
- **Result**: **UNKNOWN_DIFFERENTIALS = 0**

## Exit state (G2 contract)

| Flag | Value |
|---|---|
| REGISTERED | true |
| REGISTRY_PRIMARY | **false** (no flip — G3/G4) |
| LEGACY_UNREACHABLE | false |
| LEGACY_REMOVED | false |
| CONTRACT_SUITE | false (G6) |
| CERTIFIED | false (G8) |
| StructuralFamily(`core.emit.event`) | `LegacyCore` |
| Counters | **9 / 9 / 9** (`LEGACY_PLUGIN_IDS` untouched) |

## Normative matrix (frozen)

| Dimension / case | Legacy | Registry candidate | Verdict |
|---|---|---|---|
| identity / envelope | `core.emit.event` / dsl-v1, `kind` first, JSON-null values kept | same | **PARITY** |
| CatchErrorEntered | Success / no event | same | **PARITY** |
| CatchErrorTriggered | Success / no event (real event at coordinator fold-walk) | same | **PARITY** |
| StageMarkedUnstable valid | event + Unstable, stageName fallback to current stage | fallback via `StageIdentity.name` | **PARITY** |
| StageMarkedUnstable explicit stageName | payload wins | payload wins | **PARITY** |
| FileWritten valid | event + Success | same | **PARITY** |
| atomicallyMoved absent/"true"/"false" | false/true/false | same | **PARITY** |
| atomicallyMoved "TRUE"/"yes"/"1"/garbage | **false** (lenient) | same — `toBooleanStrictOrNull() ?: false` kept verbatim | **PARITY** |
| unknown kind | typed SCHEMA / no event; decode stays total | same (whitelist = handler semantics) | **PARITY** |
| StageMarkedUnstable missing message | untyped exception → INFRASTRUCTURE | typed SCHEMA / no event | **APPROVED_FIX** |
| FileWritten missing path/sha256/size | untyped exception → INFRASTRUCTURE | typed SCHEMA / no event | **APPROVED_FIX** |
| FileWritten non-numeric size | untyped exception → INFRASTRUCTURE | typed SCHEMA / no event | **APPROVED_FIX** |
| effects / replay / recovery | READ_ONLY / MEMOIZED / legacy | identical row | **PARITY** |
| event sink access | implicit (dispatch context) | declared `EVENT_SINK_CAPABILITY`, fail-closed | **APPROVED_ARCHITECTURAL_DELTA** |
| stage identity | implicit runtime context | declared `STAGE_IDENTITY_CAPABILITY`, fail-closed | **APPROVED_ARCHITECTURAL_DELTA** |

## Frozen laws

1. **`wire/decode validity != emit.event semantic validity`** — unknown kind: decode SUCCESS,
   handler `Failure(SCHEMA)`. Whitelist remains Step semantics, never codec policy.
2. **atomicallyMoved**: no hardening. `"TRUE"→false, "yes"→false, "1"→false, garbage→false`.
   Any future SCHEMA-on-invalid is a separate versioned contract change.
3. **No new validations**: blank `message`/`stageName`/`sha256`/`path`, negative `size`, unknown
   payload fields all stay accepted with parity (explicit NO-NEW-VALIDATIONS test).
4. **Static capabilities**: the StepDefinition contract is static and fail-closed; no per-kind
   dynamic requiredCapabilities. `CatchErrorEntered` not using StageIdentity on a specific
   invocation does not weaken the declaration.
5. **Admission matrix**: both capabilities → Ready; EVENT_SINK missing → Rejected before handler;
   STAGE_IDENTITY missing → Rejected before handler; no legacy equivalent (architectural delta).
6. **Replay contract**: fresh → RERUN (execute once); MEMOIZED+READ_ONLY+SUCCEEDED → SKIP
   (handler not re-invoked, NO duplicate event append — critical for StageMarkedUnstable and
   FileWritten); journaled FAILED → RERUN. Frozen via `DefaultEffectReplayPolicy`, engine law
   untouched.
7. **Semantic parity only**: eventId/occurredAt/sequence excluded. Compared: event type, runId,
   stageName, message, path, sha256, size, atomicallyMoved, event cardinality, StepOutcome.
8. **Supported contract ≠ currently reachable**: CatchErrorEntered/Triggered stay
   supported-but-internal markers; FileWritten stays supported with no compiler callsite.
   No kind removal authorized by G2.

## Evidence

- `CoreEmitEventDifferentialContractTest` **14 tests / 0 failures** (fresh XML canary): the SAME
  `InMemoryEventStore` is fed by both the legacy dispatcher and the registry candidate; parity
  tests assert identical outcomes + semantic event pairs; APPROVED_FIX tests assert the legacy
  throw baseline per case AND the candidate's typed SCHEMA with zero events.
- Regression neighbors green (fresh XML): `CoreEmitEventStepUnitTest` 18/0,
  `CoreWriteFileStepUnitTest` 9/0, `CanonicalCoreStepCommandRegistryTest` 11/0,
  `CompatibilityCorpusTest.fixture17` 1/0.
- The 12 pre-existing `CanonicalDurableRunCoordinatorTest` failures (Rule-16, base `0f53e487`)
  remain out of G2 scope; this gate did not widen that set (G2 adds only a new test class).

## Next

G3/G4 (REGISTRY_PRIMARY flip + LEGACY_UNREACHABLE). Awaiting explicit authorization — STOP here.
