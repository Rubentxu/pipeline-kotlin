

## Active Change — RETRY-D durable retry reconciliation (2026-09-10, HEAD `e4cca233`)

**Status: DESIGN GATE / NO PRODUCTION EDITS.** The public installed-distribution retry acceptance previously reproduced a real defect: `fail → success → rerun` with the same DB/control state appended a second `retry-ok`. Timeout and parallel installed UATs remain green, but E-EM-11 must remain OPEN.

**Grounded SUT:** `CanonicalDurableRunCoordinator.dispatchBody` derives deterministic child identities using `parentBodyPath + BlockSegment(attempt, "retry-attempt") + child segment`, then calls canonical `dispatch()`. It has no retry-control journal fact and starts at attempt 1 on each fresh coordinator invocation. `OperationJournal` has `beginOperation`, `append`, and exact `get(opId, attempt)`, but no transaction or fault seam.

**Design artifacts, uncommitted:** `openspec/changes/retry-d-durable-reconciliation/{proposal,design,tasks}.md` and proposed `docs/v2/04-adrs/ADR-0075-retry-control-durable-reconciliation.md`. They require a retry control row keyed by the full inherited canonical OpId plus journal attempt ordinal, a pure reconciliation ADT, canonical child re-entry only, and an R5 test-only `OperationJournal` decorator that throws after delegating the exact child terminal append. No production fault port and no events as durable authority.

**Verification executed:** docs-only `git diff --check` PASS. No Gradle run was relevant because production/test sources are unchanged.

**Next after explicit design/ADR approval:** implement only the typed retry reconciliation, then progressively run R1/R3-R6 focused tests and the real `installDist` R2 acceptance. Do not add RetryAttemptFinished/TimeoutScheduled, change timeout/parallel, reopen grammar UATs, alter `StepExecutors.kt`, or commit before R2, R5, and R6 are green.

## CTX-P4-EX handoff (2026-09-10)
- examples/run.sh = real-CLI gate: expected exit+outcome matrix, event contracts 07-10.
  Full gate GREEN (exit 0), commit d0ccf4b5.
- CLI: flags MUST precede script path (`run --db X script`); trailing flags silently
  ignored — strictness is an OPEN item.
- Durable rerun: default ReusePriorRun; --rerun fresh; --resume continues. CLI reprints
  prior journal with ORIGINAL timestamps → scope new events by occurredAt > max(prev run).
- P6 parallel test note: UatDsl003ParallelTest P6 passes via the same CLI (verified fresh XML).

## Event Spine integration handoff (2026-09-10)
- Branch docs/event-spine-evolution-integration (f3bf32e1 + 3c12ec19), pusheada; NO mergeada a main.
- EVT/POL integrados en ROADMAP.md (tras EM, antes de M5), backlog EVT-00..16/POL-00..09,
  MILESTONES replacement, ADR-0077..0080 PROPOSED, design docs 06-design, UAT_EVT_REAL_EXAMPLES,
  examples/contracts YAML (proposales), openspec changes event-spine-evolution + policy-guardrails.
- AGENTS_CANDIDATE NO fusionado (merge guide paso 6: solo tras receipts EVT/POL).
- Solo EVT-0 autorizado a arrancar en primer ciclo de código.
- Pendiente: revisión ADR-0077..0079, merge a main tras aprobación.

## Corrección pack EVT (2026-09-10, 5adfe0cf)
- Reemplazado pack por revisión corregida grounded en d0ccf4b5 (P4-EX closed).
- Cambio clave: 07-10 son baseline CLOSED; EVT-08 = migración con paridad
  diferencial old(run.sh)/new(harness); ley de migración EVT-3 en
  docs/v2/00-context/EVT_P4_EX_BASELINE.md. No recrear examples nunca.

## EVT-0 ciclo SDDK cerrado (2026-09-10)
- sddk cycle evt-0-grounding (B-direct): CLOSED, ledger seq 851, todos los gates PASSED.
- Branch docs/evt-0-grounding @ cfd83619 pusheada (EVT/POL integration + EVT-0 receipt).
- Prioridad acordada con usuario: EVT-0..3 P0; luego LFC-2 ∥ EVT-4; M4 reactivado después;
  POL shadow P2; CloudEvents SDK/NATS deferred (EVT-5).
- Próximo ciclo: EVT-1 (ResourceRef + PipelineEventEnvelope) — mismo patrón sddk.

## EVT-0 cierre en trunk (2026-09-10, 1f39d17d)
- main == origin/main == 1f39d17d (FF de docs/evt-0-grounding @22f199c0 + disposition).
- Pack staging: retenido solo como provenance, README apunta a docs/v2 (disposition note).
- Cycle evt-0-grounding CLOSED; branches merged conservadas (convención del repo).
- SIGUIENTE: EVT-1 ResourceRef+Envelope, slice behavior-preserving, leyes 1-12 del usuario.

## EVT-1 identity slice (2026-09-10, HEAD 5a50319c)
- Added v2/pipeline-events identity pkg: ResourceKind/ResourceRef/ResourceRefs/EventRef/
  PipelineEventEnvelope/EnvelopeProjector + EnvelopeCodec + CloudEvents characterization.
  Tests: ResourceRefDeterminismTest (9) + EnvelopeProjectionTest (9) = 18 GREEN; module
  :pipeline-events:test 124/124 GREEN. P4-EX oracle (examples/run.sh) 10/10 exit 0.
- CRITICAL oracle gotcha: run.sh durable examples share scratch/durable-shell/ op-state
  across runs; deleting only the *.db leaves stale retry op dirs -> coordinator skips
  steps silently and contract 09 fails. Clean `durable-shell/` dir + /tmp/pipeline-retry-done
  before oracle runs. NOT a code regression (contamination reproduced on pre-branch binary).
- Known launcher note: TMPDIR here = ~/.jcode/scratch so oracle scratch lives at
  $TMPDIR/pipeline-examples.

## EVT-1 CLOSED (2026-09-10, trunk 4ca1dfed)
- Cycle evt-1-resource-ref-envelope CLOSED (SDDK seq 864, all gates PASSED).
- Identity ownership FINAL: ResourceKind/ResourceRef/ResourceRefs in pipeline-domain
  (dev.rubentxu.pipeline.v2.domain.identity); EventRef/Envelope/Projector in pipeline-events.
- Module gates: pipeline-domain 360 tests, pipeline-events 117 tests, all green.
- P4-EX oracle now hermetic per-run (run.sh --control-root mktemp); INC-EVT-H1 filed
  as BASELINE harness debt. Contract-09 flake = stale durable-shell state, never EVT-1.

## EVT-3 CLOSED + LFC-2E integrated on trunk (2026-09-11)

### EVT-3 closure cycle (cycle evt-3-event-harness)
- Branch: docs/evt-3-event-harness, base 1b950074, HEAD df22ff01 + closure commit d0aedb5d
- Receipt: docs/v2/07-uat/EVT_3_CLOSURE_RECEIPT.md (210 lines)
- L0 compile GREEN (38 tasks UP-TO-DATE)
- L1-L2 :pipeline-event-harness:test --rerun-tasks = 19/19 GREEN (HF0 9/9 + mutation 10/10)
- L3 examples/run.sh x2 consecutive = 10/10 + 4/4 parity each run
- L4 FArch020EventHarnessIsolationTest = 4/4 GREEN
- L4 CanonicalEmitEventNodeDispatcherTest = 5/5 GREEN
- L5 ./gradlew -p v2 check = same 8 pre-existing failures as base SHA, 0 regressions
- Rule-16: introduced failures = 0
- FF-merge to main: main == origin/main == d0aedb5d
- EVT-3 status in EVENT_SPINE_EVOLUTION.md: CLOSED @ df22ff01
- EVT-4 status: PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY
- EVT-5 status: PENDING-DEFERRED (no transport selected)

### LFC-2E documentation integration cycle
- Source: PR #22 / branch origin/docs/lfc2-step-ecosystem-expansion @ 2bf7bb5d (7 doc commits)
- Integration branch: docs/lfc2-step-ecosystem-expansion-integration
- 7 PR commits rebased cleanly on d0aedb5d (only 1 conflict on EVENT_SPINE_EVOLUTION.md)
- 2 conflict zones resolved semantically:
  - Status line: keep EVT-3 CLOSED SHA from EVT-3, keep Placement text from LFC-2E
  - EVT-4 status: combine PENDING label (EVT-3) + start condition (LFC-2E)
- 1 ROADMAP.md amendment commit (a14e5e6f): EVT section now shows closure status + priority chain
- FF-merge to main: main == origin/main == a14e5e6f
- 8 required files preserved (per user law #4):
  - docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md
  - docs/v2/03-specifications/STEP_ECOSYSTEM_POLICY.md
  - docs/v2/03-specifications/STEP_PLUGIN_SDK.md
  - docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md (reconciled)
  - docs/v2/05-roadmap/LFC2_HONEST_DSL_CLOSURE.md
  - docs/v2/05-roadmap/LFC2_STEP_ECOSYSTEM_EXPANSION.md
  - docs/v2/05-roadmap/ROADMAP.md (amended)
  - openspec/changes/lfc2-step-constitution-plugin-seam/tasks.md

### Next cycle: LFC-2E0 — certify existing families
- Step inventory must be MACHINE-DERIVED, not matrix-hypothesis
- Required fields per Step: delivery, DSL present?, StepDefinition present?,
  canonical execution?, legacy executable path?, typed input?, typed output?,
  capabilities?, replay policy?, real example?, Event Harness contract?, certification state?
- STEP_ECOSYSTEM_MATRIX.md is the hypothesis to verify, not the evidence
- Priority order E0..E10 (E0 first: certify existing; E1: universal core freeze)
- Do NOT open EVT-4 during these cycles unless explicit reprioritization

## EVT-3 closure receipt digest gap closed (2026-09-11, b6689aa8)

Honest assessment identified one outstanding weak point: the EVT-3 closure
receipt cited argv + exit code per gate but did NOT include SHA-256 digests
of the captured logs (AGENTS.md rule 25 partial coverage).

Closed by adding section 9 to `docs/v2/07-uat/EVT_3_CLOSURE_RECEIPT.md`:

```text
EVT-3 closure logs (sha256):
  /tmp/evt3-l0-compile.log                   sha256=c1f224272d811c22031b6f49c0936392211a40e76a4fb498dd2c47db2a1f5336
  /tmp/evt3-l1-harness-test-rerun.log        sha256=cb48f0206faa75a4255666e0165fe85cfae7bae6fd73c959ed1d6e0f5cfd50af
  /tmp/evt3-parity-run.log                   sha256=a260291827bc2625254d6fdf66ba0fde5373cfa74724a3e8aaa9e1c04f939d47
  /tmp/evt3-parity-run2.log                  sha256=a570cd5cc1ad7342a3c407eee1196947f367349517b94c529d64026e2680b765
  /tmp/evt3-l4-archfit.log                   sha256=6a724e32dd59c4bfe9f62dbc1adbeb8fbb2b93087ffe272b48301d43df0ad36b
  /tmp/evt3-l4-eventtests.log                sha256=75eac8b8cd4bf5f50b3715dbfbf71164b90cde88eca2757243fc68e64f21812c
  /tmp/evt3-l5-check.log                     sha256=034a3f933fda22168f7f847b0c457065ef023c1ad088c5f752bb9f3b599541fb

Base-SHA evidence:
  /tmp/base-fixture14-verify.log             sha256=af626b31d5f1c001365022e07c272277d2e43fd2c32f9e7a1a7dab05e53f7c94
  /tmp/base-scripting-verify.log             sha256=6fd6eba3a9cd9837b78629487702748372988a4516fe7e8b6dec02b00401ff60

Receipt-level digest (self-referential):
  docs/v2/07-uat/EVT_3_CLOSURE_RECEIPT.md    sha256=3030bcdb66345da202e7a2c1ca29c8a7ffb1f698b7f0abc2880261a6348c7c6f
```

Also updated closure gates checklist to [x] `HEAD == origin/main` (verified
4dc49435 == 4dc49435 post-amendment).

Trunk: main == origin/main == 4dc49435c74d836a3380678f4fd40dc410dd700b.

## LFC-2E0 inventory cycle (2026-09-11)

### Discoveries (machine-derived, NOT hypothesis)

- **Production registry has 2 core keys**: core.echo + core.sh (per `CoreStepRegistryFactory`).
- **12 legacy core keys** still routed through `Canonical*NodeDispatcher`:
  core.{error, sleep, file.writeFile, emit.event, milestone, deleteDir,
        cleanWs, load, pwd, isUnix, waitUntil, archiveArtifacts}
- **External plugin (1)**: example.uppercase (CERTIFIED via ServiceLoader).
- **Block / orchestration DSL is NOT a Step key**: retry, timeout, parallel,
  catchError, warnError, unstable, dir, withEnv, withCredentials, readFile,
  fileExists, timestamps, ansiColor, node — re-enters engine via
  BodyInvoker.invoke / BranchInvoker.invokeAll (ADR-0073).
- **Git family NOT_STARTED**: DSL exists (PipelineDsl.kt L1050, L1066, L1094)
  but no StepDefinition in pipeline-step-sdk/scm-git and no entry in registry.
- **3 CERTIFIED Steps total**: core.echo (S3 burn-down), core.sh (S6 burn-down),
  example.uppercase (EP burn-down).

### Deliverables

- `docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md` — machine-derived table (239 lines)
- `docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md` — corrected with
  LEGACY_IMPLEMENTED_UNCERTIFIED state + certification snapshot at top
- `openspec/changes/lfc2-step-ecosystem-expansion/{proposal,design,tasks}.md`
- Branch: `cycle/lfc2-step-ecosystem-expansion` (pushed, NOT merged)

### Verification

- L0 compile `:pipeline-event-harness + :pipeline-application` GREEN
  (`BUILD SUCCESSFUL in 3s`, 35 tasks UP-TO-DATE; no production code change).
- All internal links resolve.
- No production code touched.

### Rebase onto trunk (2026-09-11, after `4dc49435`)

Branch was rebased onto `origin/main = 4dc49435` to absorb the EVT-3
digest-gap closure commits. Conflict on `.agent/TESTING-STATE.md` resolved
semantically: both histories (EVT-3 receipt digest closure + LFC-2E0
inventory) preserved. No other file conflicted. Branch tip after rebase
recorded in the LFC-2E0 PR receipt.

### Next: LFC-2E1 (universal core freeze)

Burn down 12 legacy keys onto the registry seam via G0..G8 sequence:
- P0 first: core.{error, sleep, pwd, isUnix}
- P1 second: core.{deleteDir, cleanWs, waitUntil}
- P2 third: core.{milestone, load, archiveArtifacts, emit.event, file.writeFile}

**LB-01 anchor (added 2026-09-11)**: E1-S1 (LB-02 LEGACY_REMOVED slice —
refixture `LegacyEchoUnreachableProofTest`, `EchoDurableSpineTest`,
`UatStep002EchoCaptureTest`, `CoreEchoSeamTest`, `EchoStepContractSuiteTest`;
activate `S3EchoLegacyRemovedFitnessTest`) PRECEDES E1-S2 burn-down of the
12 legacy keys. Per LB-01, CERTIFIED requires LEGACY_REMOVED.

EVT-4 stays PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout E0..E10.

## LFC-2E0 LB-01 anchoring (2026-09-11, 25a96d18)

### Memory facts respected

1. `.agent/TESTING-STATE.md` is tracked despite the `.agent/` ignore rule;
   commits succeed without `-f` (verified — commit b5315fb7 + 25a96d18 included
   the file).
2. LB-01 (Legacy Burn-down Policy) tracks B1.2c3 closure; CERTIFIED requires
   LEGACY_REMOVED. Per LB-01 the next slice is:
   - remove reachable unused legacy Echo machinery
   - refixture legacy tests
   - activate the fitness guard
   - record LEGACY_REMOVED (NOT re-CERTIFIED)

### LB-01 sequencing applied to LFC-2E1

- E1-S1 (FIRST): LB-02 LEGACY_REMOVED slice
  - core.echo is already CERTIFIED (S3 burn-down, LEGACY_REMOVED achieved)
  - but reachable legacy Echo machinery may still exist in tests:
    LegacyEchoUnreachableProofTest, EchoDurableSpineTest,
    UatStep002EchoCaptureTest, CoreEchoSeamTest, EchoStepContractSuiteTest
  - Action: refixture each to drive core.echo exclusively through registry seam;
    activate S3EchoLegacyRemovedFitnessTest; record LEGACY_REMOVED in LB-01 ledger
- E1-S2: burn down 12 legacy keys via G0..G8 (P0: error/sleep/pwd/isUnix; P1:
  deleteDir/cleanWs/waitUntil; P2: milestone/load/archiveArtifacts/emit.event/file.writeFile)

### Branch state

- cycle/lfc2-step-ecosystem-expansion @ 25a96d18 (pushed, NOT merged)
- LB-01 anchoring documented in:
  - openspec/changes/lfc2-step-ecosystem-expansion/tasks.md (E1-S1 + E1-S2 split)
  - openspec/changes/lfc2-step-ecosystem-expansion/design.md (rationale)
  - docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md (sequencing section)
  - docs/v2/01-product/STEP_ECOSYSTEM_MATRIX.md (E1-S1 in family progression)

EVT-4 stays PENDING-DEFERRED-BY-LOCAL-FIRST-PRIORITY throughout E1..E10.

## LFC-2E0 MERGED on trunk (2026-09-11, 5efac6c0)

Cycle lfc2-step-ecosystem-expansion E0 inventory slice CLOSED:

- Branch rebased onto origin/main = 4dc49435 (semantic conflict
  resolution on .agent/TESTING-STATE.md preserved BOTH histories)
- PR #23 created DRAFT, marked READY for review, MERGED at 2026-09-11T07:24:12Z
- Method: --merge (non-squash; 5 individual commits preserved)
- Merge commit: 5efac6c0c90c5b5be28e1cd898a6b325dca8ae85
- 11/11 gates PASS (trunk baseline, machine-derived inventory, matrix
  reflects code, no false CERTIFIED, zero production source, no policy
  contradiction, Rule 16 clean, working tree clean, L0 compile 37/37
  UP-TO-DATE)
- 7 LFC-2E integration files preserved (zero drift)
- 6 expected files now on main: STEP_ECOSYSTEM_MATRIX, STEP_INVENTORY_LFC2E0,
  LFC2E0_CLOSURE_RECEIPT, openspec/{proposal,design,tasks}, .agent/TESTING-STATE
- Post-merge L0 on main: 37/37 UP-TO-DATE in 1s

Trunk: main == origin/main == 5efac6c0.

**Next (NOT STARTED per user instruction)**: LFC-2E1-S1
(lfc2-e1-s1-echo-legacy-removed) — turn core.echo into the oracle:
CERTIFIED + LEGACY_REMOVED. S2 burn-down of 12 legacy keys gated on S1.

## LFC-2E0 post-merge defensive audit (2026-09-11, cca0fe14)

After LFC-2E0 merge @ bbb2e584, a defensive validation pass was run on
trunk (NOT a new cycle; pre-positioning evidence for S1):

- Inventory claims re-enumerated: 12 LEGACY + 2 registry + 1 external
  plugin = 15 production keys (matches STEP_INVENTORY_LFC2E0.md)
- Event Harness: 19/19 GREEN (EVT-3 verdict preserved post-merge)
- Architecture fitness family: 193/193 GREEN
- S3EchoLegacyRemovedFitnessTest: 7/7 GREEN (already active, not
  quarantined; structural fitness, not grep-fragile)
- 5 echo tests (LegacyEchoUnreachableProofTest, EchoDurableSpineTest,
  UatStep002EchoCaptureTest, CoreEchoSeamTest, EchoStepContractSuiteTest):
  31/31 GREEN (no refixture needed)

**Honest finding for S1 scope**: the LB-01 anchor framed S1 as
"refixture + activate fitness". On current main, neither is needed.
The actual S1 scope is certification RECORDING:
1. Update LB-01 ledger to formally record core.echo's
   CERTIFIED + LEGACY_REMOVED combined verdict with full evidence
2. Produce CORE_ECHO_CERTIFICATION.md receipt
3. Update STEP_INVENTORY_LFC2E0.md row from "CERTIFIED (S3 burn-down)"
   to "CERTIFIED + LEGACY_REMOVED (S1 certification recording)"
4. Re-run LFC-2E0 gates (no regression)

Receipt: docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md

S1 still requires explicit user direction to open (standing instruction).

Trunk: main == origin/main == cca0fe149d529a8dc9e1b9afec9a2871abadb7c8.
