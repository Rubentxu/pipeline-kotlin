# S3 — Echo Legacy Burn-Down Certification Receipt

**Status**: ✅ `core.echo = CERTIFIED`
**Sequence**: gates → S3.1 → S3.2 → S3.3 → S3.4 → S3.5
**Worktree HEAD at certification**: local-only (recoverable; see "Push checkpoint" below)

## What this receipt proves

`core.echo` is the first Step CERTIFIED through the registry-driven, open-world Step seam
(ADR-0070..0074 / StepConstitution). Every other legacy-routed canonical command is now
classified as `LEGACY_REMOVED`: removed from the source tree, no longer in `LEGACY_PLUGIN_IDS`,
no longer decodable, no longer dispatched. The only path a pipeline can reach `core.echo`
today is through the new registry handler in `CoreEchoStep`.

## Gate receipt chain

| # | Slice | Commit | Test class | SHA-256 | tests | failures | errors |
|---|-------|--------|------------|---------|-------|----------|--------|
| 1 | pre-burn gate 1 (sddk-verify) | `fa7d91b0` | 7 spine classes | — | 36 | 0 | 0 |
| 2 | pre-burn gate 2 (19 pre-existing) | `c3aaab8a` | scripting-kotlin24 + uat-local + corpus+grammar | `3505f70…` `2646fb9…` `db98fe0…` | — | 19 | 0 |
| 3 | LEGACY_UNREACHABLE proof | `2b77bddc` | `LegacyEchoUnreachableProofTest` | — | 4 | 0 | 0 |
| 4 | S3.1+S3.2 atomic (data class + decoder branch + dispatcher + tests) | `20e66ff6` | focused classes | — | 38 | 0 | 0 |
| 5 | S3.3 (metadata table + LEGACY_PLUGIN_IDS rename + fitness) | `2f2df812` | focused classes | — | 38 | 0 | 0 |
| 6 | S3.4 (LEGACY_REMOVED fitness, irreversible) | `3861077a` | `S3EchoLegacyRemovedFitnessTest` | `5c1808f9…` | 7 | 0 | 0 |
| 7 | S3.5 (StepContractSuite — first CERTIFIED core Step) | `6a804ed4` | `EchoStepContractSuiteTest` | `e53bdf8a…` | 17 | 0 | 0 |

## S3.5 coverage matrix

The certification evidence lives in `EchoStepContractSuiteTest` (17 tests):

| # | Coverage | Result |
|---|----------|--------|
| 1 | identity: `CoreEchoStep.KEY == core.echo` + idempotent-registration failure | ✅ |
| 2 | contract completeness: key, descriptor, input codec, output codec, required capabilities | ✅ |
| 3 | codec input: encode emits canonical `{"kind":"echo","text":...}` envelope | ✅ |
| 4 | codec input: decode rejects a non-echo payload kind (fail-closed) | ✅ |
| 5 | codec output: opaque raw-text byte-identical round-trip | ✅ |
| 6 | canonical envelope: well-formed JSON object (durable-spine eligible) | ✅ |
| 7 | registry resolution: production factory contains `core.echo` | ✅ |
| 8 | registry resolution: factory emits fresh per-call registries (defends CLI hot-path) | ✅ |
| 9 | capability admission: `EVENT_SINK` present → `Ready` | ✅ |
| 10 | success: registry-routed echo emits exactly one `EchoOutputCaptured` + SUCCEEDED | ✅ |
| 11 | typed failure: handler exceptions surface as typed `RunOutcome.Failure` | ✅ |
| 12 | fresh durable: exactly one terminal SUCCEEDED operation is journaled | ✅ |
| 13 | replay: SUCCEEDED op reused without re-running the handler | ✅ |
| 14 | divergence: changed text on replay → typed divergence | ✅ |
| 15 | observability: `StepStarted` + `StepFinished` + `EchoOutputCaptured` emitted | ✅ |
| 16 | missing capability: declared but unavailable → `Rejected` admission | ✅ |
| 17 | real pipeline scenario: public DSL `pipeline { stages { stage("echo") { echo("hello registry") } } }` runs end-to-end through compiler + coordinator + handler | ✅ |

## Architecture properties

The S3.4 fitness file (`S3EchoLegacyRemovedFitnessTest`) is mechanically defensible (not
"unreachable today" but "removed from source"):

- `LEGACY_REMOVED` ⟺ (no `CanonicalCoreStepDecoder.Echo`) ∧ (no `CanonicalNodeDispatcher.Echo`) ∧ (no `core.echo` in legacy metadata table)
- `CERTIFIED ∩ LEGACY_EXECUTABLE = ∅`: a Step cannot be both registry-routed and legacy-decodable
- `core.echo` IS in `CoreStepRegistryFactory.registry()` (registry-routed resolution works)

The `core.echo` implementation source-of-truth lives in `CoreEchoStep.kt`. The legacy dispatch
forms for `core.echo` (data class in sealed hierarchy, `ECHO_PLUGIN_ID`, `echoDispatcher`,
`echoContext()`, legacy metadata row, registry decode branch) are all absent — the file-level
absence test (`S3EchoLegacyRemovedFitnessTest`) verifies this without textual regex drift.

## What changed in S3

### Removed (legacy)
- `CanonicalCoreStepCommand.Echo` data class
- `ECHO_PLUGIN_ID` constant
- `CanonicalCoreStepDecoder` legacy decode `when` branch for echo
- `CanonicalEchoNodeDispatcher.kt`
- `echoDispatcher` field on `CanonicalNodeDispatcher`
- `echoContext()` helper on `CanonicalNodeDispatcher`
- `CanonicalCoreStepCommand.Echo` dispatch case in `CanonicalNodeDispatcher`
- `core.echo` row in `CanonicalCoreStepMetadata.table`
- `ALL_PLUGIN_IDS` (renamed → `LEGACY_PLUGIN_IDS`)
- Test files `CanonicalEchoNodeDispatcherTest.kt`, `CanonicalNodeDispatcherTest.kt`, the
  dual-phase negative-no-registry case in `EchoDurableSpineTest.kt`
- Stray docstring references

### Substituted for legacy fixtures
- `CanonicalCoreStepCommand.Sleep(1)` replaces `Echo` in:
  `ExecutionBoundaryFactoryTest`, `LegacyExecutionAdapterTest`,
  `RegistryExecutionBoundaryTest`, `SeamedExecutionRouterTest`.

### Added (new path)
- `CoreEchoStep.kt` (already from B1.2c3 slice 1)
- `S3EchoLegacyRemovedFitnessTest.kt` (S3.4)
- `EchoStepContractSuiteTest.kt` (S3.5)

## Final certification state

| Step | State | Evidence |
|------|-------|----------|
| `core.echo` | ✅ **CERTIFIED** | `EchoStepContractSuiteTest` 17/17, `S3EchoLegacyRemovedFitnessTest` 7/7 |
| `core.sh` | pending certification (S4 sequence; outside this burn-down) | — |
| every other `LEGACY_PLUGIN_IDS` entry | pending S4..S6 burn-down | — |

## Push checkpoint

This commit is local-only at certification time. The pre-burn gate evidence
(`docs/v2/07-uat/S2_5_7_GATE_EVIDENCE.md`) was pushed to `origin/main` at `14f5d2b3`. The
post-burn state (HEAD now at `6a804ed4`) is a continuation of the same branch and is the
natural next push target once the receiving side is ready.

## Reasoning log (kept for traceability)

The user's stated invariants we honored:
- No silent baseline widening — 19 pre-existing failures each got a fresh canary and SHA-256
- No artificial-alive wrappers during S3.1 — the data class is fully removed, no stub `Echo`
- Compile errors as the dependency map — S3.1 + S3.2 ran as one atomic commit because removing
  the data class forces removing every dependent dispatch case
- Naming must be semantically true — `ALL_PLUGIN_IDS` (which excluded registry-routed plugins)
  was renamed to `LEGACY_PLUGIN_IDS` so the closed authority stays semantically correct
- `LEGACY_REMOVED` ≠ `LEGACY_UNREACHABLE` — the S3.4 fitness enforces the source-level rule
  mechanically (file-level absence), not the runtime property (path currently unreachable)
- Real pipeline scenario — `EchoStepContractSuiteTest` runs the actual public DSL
  `pipeline { stages { stage("echo") { echo("hello registry") } } }` and asserts the full
  compiled → coordinated → handler → emitted-event chain, not just a manual `StepDefinition`
  construction.
- Tests must be tracked — `.agent/` is gitignored so durable evidence lives under
  `docs/v2/07-uat/`.

## Next steps (S4..)

The same S3 sequence (atomic gates → S4.1 → S4.2 → S4.3 → S4.4 → S4.5) applies to the next
legacy canonical command. The new Step registration template (see `CoreEchoStep.kt` and
`EchoStepContractSuiteTest`) is the canonical example to port.
