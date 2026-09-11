

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

## LFC-2E0 post-merge acceptance parity (2026-09-11, 7f06876b)

After the 11-hypothesis structural sweep at 260fbaf5, the missing piece
was the integration-boundary acceptance oracle. Ran examples/run.sh
TWICE consecutively with hermetic scratch dirs:

- Run #1: 18 PASS, 0 FAIL, exit 0 (log ed80d7c8...)
- Run #2: 18 PASS, 0 FAIL, exit 0 (log 6a5bdb43...)
- All 10 examples match expected exit+outcome
- All 4 contracts (07/08/09/10) PASS differential parity
- examples/01-hello.pipeline.kts emits EchoOutputCaptured → STRONGEST proof
  core.echo resolves through registry path (not legacy)

Trunk: main == origin/main == 7f06876b133c9525bac13e8b8f3577859401113d.

## LFC-2E0 post-merge defensive audit — rounds 2-4 (2026-09-11, 922f95e8 → 394fc1ab)

After the structural sweep + acceptance parity, three additional edge
case rounds drilled into failure modes, durable semantics, and block
step contracts. All findings captured in
`docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md`.

### Round 2 (commit 496699eb, E7..E14)
8 edge cases:
- E7  core.error failure semantics
- E8  write/read filesystem roundtrip
- E9  deleteDir cleanup
- E10 concurrent pipelines same control root
- E11 unknown step (fail-closed compile)
- E12 malformed script (fail-closed compile)
- E13 capability admission (SHELL_OPERATIONS_CAPABILITY)
- E14 Rule-16 UATL008 verification (re-run = 27 tests, 1 skipped, **2 failures** — same as EVT-3 base)

9 SHA-256 digests captured per rule 25.

### Round 3 (commit a6bf21b9, E15..E21)
7 edge cases into durable spine + CLI:
- E15 journal durability across runs (sha256 changes, no corruption)
- E16 --rerun vs --resume distinction (9 vs 6 events)
- E17 validate subcommand (parse-only, no execution)
- E18 --resume idempotency (5x consecutive — same runId, step events only in original block)
- E19 --db isolation (independent runId per db path)
- E20 failure durability (documented: successes cached, failures retry)
- E21 --control-root isolation (EVT-H1 hermeticity: ctl1 reused = same runId; ctl2 different = new runId, even with same db)

7 SHA-256 digests captured.

### Round 4 (commit 394fc1ab, E22..E25)
Block Step contracts via real .pipeline.kts examples:
- E22 parallel { branch("left") { … } branch("right") { … } } — BranchInvoker.invokeAll verified
- E23 retry(count = 3) { sh } (fail then succeed) — BodyInvoker + RetryReconciler
- E24 timeout(time = 2, "SECONDS") { sh } (over-budget) — BodyInvoker + deadline
- E25 Nested catchError (inner FAILURE → outer UNSTABLE) — ERR-S-007 contract

1 SHA-256 digest captured.

### Cumulative verdict

```text
Rounds 1-4 (E1..E25):
  24 PASS
   1 SKIPPED (E4: external plugin — known CLI limitation)

Trunk: main == origin/main == 394fc1ab

Key durability findings:
  - --control-root is the durable op-state anchor (retry/replay cache)
  - --db is only the event journal (observability)
  - Successes cached → --resume reuses (idempotent)
  - Failures NOT cached → --resume re-executes (retry-on-resume)
  - All block steps route through BodyInvoker/BranchInvoker (ADR-0073)

S1 still requires explicit user direction to open (standing instruction).
```

## LFC-2E0 post-merge defensive audit — rounds 5-7 (2026-09-11, 49bbee07 → 594b27d2)

Three additional rounds covering real examples, contracts, Step SDK,
and the durable spine. All findings captured in
`docs/v2/07-uat/LFC2E0_PRE_S1_EVIDENCE_AUDIT.md`.

### Round 5 (commit 49bbee07, E26..E30)
- E26 06-durable (multi-stage --resume)
- E27 04-kotlin-control-flow (script {} block)
- E28 05-failing-step (typed failure)
- E29 example-uppercase-plugin (CERTIFIED external ref)
- E30 Plugin isolation (zero internal imports)

### Round 6 (commit 04f7b039, E31..E35)
- E31 examples/contracts/ typed YAML contracts
- E32 Contract 07 differential test (4/5 PASS, 1 case-mismatch finding)
- E33 examples/README.md accuracy
- E34 v2 module structure (17 modules)
- E35 v2/compatibility/ corpus (17 fixtures, 01-basic runs)

### Round 7 (commit 594b27d2, E36..E40)
- E36 pipeline-step-sdk module structure
- E37 Step SDK public typed surface
- E38 pipeline-architecture-tests module
- E39 S3EchoLegacyRemovedFitnessTest 7/7 GREEN (G4 fitness gate)
- E40 Durable spine architecture

### Round 8 (commit 4224d53f, E39b — precision fix)
- E39b EchoStepContractSuiteTest 17/17 GREEN (G7 StepContractSuite)

### Round 9 (commit bee13f75, E41..E45)
- E41 12/12 Canonical*NodeDispatcher files exist (one per legacy key)
- E42 LEGACY_PLUGIN_IDS: 12 entries; core.sh NOT in set (REGISTRY_PRIMARY)
- E43 CoreStepRegistryFactory: 2 entries (echo + sh)
- E44 CanonicalCoreStepMetadata: 12 entries with typed Effect+ReplayPolicy
- E45 LEGACY_PLUGIN_IDS == metadata table (diff empty)

### Round 10 (commit b4acf115, E46..E49 — pre-S1 audit)
- E46 EchoStepContractSuiteTest 17 named contracts (matches G7)
- E47 ShStepContractSuiteTest 17/17 GREEN (G7 for core.sh)
- E48 No S3ShLegacyRemovedFitnessTest exists (DEDICATED_FITNESS_GAP, NOT CERTIFICATION_GAP)
- E49 Historical note: S6.6/S6.7 marked sh as IMPLEMENTED_UNCERTIFIED (intermediate state, not current)

### E39 + E39b — combined S1 evidence

Two complementary mechanical proofs for `core.echo`:

| Suite | Module | Tests | Result | Role |
|---|---|---|---|---|
| `S3EchoLegacyRemovedFitnessTest` | pipeline-architecture-tests | 7 | 7/7 GREEN | G4 fitness gate |
| `EchoStepContractSuiteTest` | pipeline-application | 17 | 17/17 GREEN | G7 StepContractSuite |

The G4 fitness proves CERTIFIED + LEGACY_REMOVED (structural invariant).
The G7 suite proves the full 17-row contract coverage. Together they
mechanically support the S1 certification recording.

### E49 — historical context (corrected by user 2026-09-11)

Earlier audit (commit b4acf115) flagged `core.sh` as
IMPLEMENTED_UNCERTIFIED. **This was reading the intermediate state at
S6.6/S6.7, not the current state at S6.8.**

Per `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md §CERTIFICATION` (line 110):
```text
core.sh = CERTIFIED
LB-02   = REMOVED
```

S6.8 (commits 4fef9f69 / 5aab9976 / e8732757 / 205c7b48 / 95e178aa /
7574302e / f6bbd114) closed the stderr row:
- Single-FD merged durable transcript (plain stdout+stderr both observable)
- console.log rename; jenkins-log.txt isolated behind read-compat
- DurableTaskOutput.consoleTranscript in-memory carrier
- Mandatory rows complete; ShStepContractSuiteTest 17/17
- A5_CoreShLegacyUnreachableProof 8/8

S6.6/S6.7 references in `LB02_S6_7_STDERR_GROUNDING.md` and
`LB02_A4_PRODUCTION_FLIP.md` are **historical**, not current.

### E48 — DEDICATED_FITNESS_GAP (NOT CERTIFICATION_GAP)

`S3ShLegacyRemovedFitnessTest` does not exist (no dedicated
G4-equivalent for sh). However:

```text
core.sh certification can rely on:
  - A5_CoreShLegacyUnreachableProof (8/8 GREEN)
  - ShStepContractSuiteTest (17/17 GREEN)
  - canonical-core gate registry-aware (after 8c4cbbae)
  - LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY
  - LB02_G3_A4_3_TYPED_OUTPUT_CARRIER
  - LB02_G3_A4_8_LEGACY_REGISTRY_PARITY
```

If we want symmetry with echo later, we can create a dedicated
G4-equivalent fitness for sh, but **this is NOT a CERTIFICATION_GAP**
and **does NOT block S1 or S2**.

### Updated inventory verdict (post-reconciliation)

| Key | Status |
|---|---|
| `core.echo` | CERTIFIED + LEGACY_REMOVED (S1 ready) |
| `core.sh` | CERTIFIED + LEGACY_REMOVED (LB-02 S6.8 closure) |
| 12 legacy keys | LEGACY_EXECUTABLE / IMPLEMENTED_UNCERTIFIED (S2 burn-down scope) |
| `example.uppercase` | CERTIFIED (LB-02 EP) |

### Cumulative verdict

```text
Rounds 1-10 (E1..E49):
  46 PASS
   1 SKIPPED (E4: external plugin — known CLI limitation)
   1 DEDICATED_FITNESS_GAP (E48: missing S3Sh G4 fitness; NOT a cert gap)

Trunk: main == origin/main == ca550da0 (after pre-S1 reconciliation)
```

## LFC-2E1-S1 cycle — `core.echo` CERTIFIED + LEGACY_REMOVED recording

**Cycle branch:** `cycle/lfc2-e1-s1-echo-legacy-removed`
**Cycle commit:** `ad4867f1`
**Status:** CLOSED (recording/closure only, NO production code change)
**Date:** 2026-09-11T09:17Z

### Mechanical proofs (fresh on `ca550da0`)

| Layer | Suite | Tests | Result | log sha256 |
|---|---|---|---|---|
| G4 architecture fitness | `S3EchoLegacyRemovedFitnessTest` | 7 | 7/7 GREEN | `78b4650b141c2d2985eed9f69f659760eeb76975a6b62497e434db9d28bb298b` |
| G7 StepContractSuite | `EchoStepContractSuiteTest` | 17 | 17/17 GREEN | `9ea8f96effbe1cfe9460af099b1c1b1b98eb1775e22ebaa8286124cf7e3f0f7f` |
| Echo test suite (5 files) | LegacyEchoUnreachable + EchoDurable + UatStep002 + CoreEchoSeam + EchoStepContractSuite | 31 | 31/31 GREEN | `82554dd709c420e95ef5e206b4b7b52bf006ab4a638333d893c1cec107d3a1eb` |
| Real execution parity | `examples/01-hello.pipeline.kts` | — | SUCCESS, 9 events, 1 EchoOutputCaptured | `a1d5ee77f438719fa3febc8ca54a8a702c10f6c46394b464d18ae6bc67a1d4fa` |

### Final statement (machine-derived)

```text
core.echo:
  delivery:    CORE
  execution:   REGISTRY_PRIMARY
  legacy:      REMOVED
  certification: CERTIFIED

proof:
  - G4 architecture fitness (S3EchoLegacyRemovedFitnessTest, 7/7 GREEN)
  - G7 StepContractSuite (EchoStepContractSuiteTest, 17/17 GREEN)
  - Echo test suite (5 files, 31/31 GREEN)
  - Real execution parity (examples/01-hello.pipeline.kts, SUCCESS)
```

### Deliverables (commit `ad4867f1`)

```text
+ openspec/changes/lfc2-e1-s1-echo-legacy-removed/proposal.md
+ openspec/changes/lfc2-e1-s1-echo-legacy-removed/design.md
+ openspec/changes/lfc2-e1-s1-echo-legacy-removed/tasks.md
+ docs/v2/07-uat/CORE_ECHO_CERTIFICATION.md
+ docs/v2/07-uat/CORE_ECHO_G4_FITNESS_RECEIPT.md
M docs/v2/07-uat/STEP_INVENTORY_LFC2E0.md (core.echo row updated)
M docs/v2/07-uat/LFC2E0_CLOSURE_RECEIPT.md (section 9 updated)
```

### Forbidden changes (NOT in S1)

```text
- any production code change
- any test refixture (the 31 echo tests were already GREEN pre-S1)
- core.sh (already CERTIFIED + LEGACY_REMOVED per LB-02 S6.8)
- the 12 legacy keys (LFC-2E1-S2 scope)
- S3ShLegacyRemovedFitnessTest creation (DEDICATED_FITNESS_GAP, deferred)
```

### S2 scope (next cycle, separate branch, NOT this PR)

Burn-down of 12 legacy keys:
- Derive from `LEGACY_PLUGIN_IDS` (12 members demonstrated)
- Group by semantic family: P0 (error/sleep/pwd/isUnix) → P1 (deleteDir/cleanWs/waitUntil) → P2 (milestone/load/archiveArtifacts/emit.event/writeFile)
- Do NOT migrate the 12 in a single commit/ciclo
- Law: registry implementation + parity + legacy unreachable + legacy removed + certification = closure
- `core.sh` does NOT belong to S2 scope (already CERTIFIED + LEGACY_REMOVED)

S1 still requires explicit user direction to merge the cycle branch to main.

## Active Change — S2-A2 G1 core.sleep registry candidate (2026-09-11)

**Status: G1 candidate registered, no cutover.** Branch
`cycle/lfc2-e1-s2-legacy-catalog-burn-down`, base `5df378bf`.

**Changed surfaces:**
- `pipeline-application`: new `CoreSleepStep` typed registry candidate and
  `CoreSleepStepUnitTest`.
- `CoreStepRegistryFactory`: candidate registration only.
- `RegistryExecutionBoundary`: timeout catches before `CancellationException`; ordinary
  cancellation rethrows structurally and is never classified ENGINE.
- G1 receipt: `docs/v2/07-uat/S2_A2_CORE_SLEEP_G1_REGISTRY_CANDIDATE_RECEIPT.md`.

**Known impact:** the candidate uses `delay(input.seconds.seconds)`, validates
`seconds > 0` at typed-input decode, declares no capabilities, and retains the
legacy `READ_ONLY` / `MEMOIZED` descriptor. `StructuralFamilyResolver` still selects
`LegacyCore` because `core.sleep` remains in `LEGACY_PLUGIN_IDS`.

**Verification fresh at G1:**
- L0 `:pipeline-application:compileTestKotlin` PASS.
- L1 `CoreSleepStepUnitTest` 10/10 PASS.
- L2 114/114 PASS: CoreSleep candidate, both G0 legacy characterization classes,
  GenericRegistryExecutionCarrier, A4_3 typed-shell boundary consumer, and echo/error
  regression/contract suites. Fresh XML canaries verified.

**Deliberately not run:** module suite and full `check`. The change is bounded to the
application registry/boundary and direct consumers were tested. G1 does not cut over
production sleep routing, so no installed CLI parity claim is valid or needed here.

**Next:** G2/G3 must establish candidate-vs-legacy differential/parity evidence before
any `LEGACY_PLUGIN_IDS` change. Do not describe `core.sleep` as CERTIFIED.
