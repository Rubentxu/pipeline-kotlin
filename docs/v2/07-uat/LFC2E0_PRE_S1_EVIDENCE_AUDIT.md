# LFC-2E0 Follow-up: pre-S1 evidence audit (2026-09-11)

Status: **evidence-gathering note, NOT a cycle. S1 still requires explicit user direction to open.**

After LFC-2E0 merge to main @ `bbb2e584`, a defensive validation pass
was run on trunk to confirm the LFC-2E0 inventory claims hold against
the actual code. This note captures findings that materially affect
the **actual scope** of the next cycle, LFC-2E1-S1.

## Findings (all on trunk `main == origin/main == bbb2e584`)

### Finding 1: Inventory claims reproduce against current code

Re-enumerated with grep + grep -c on the production sources:

```text
LEGACY_PLUGIN_IDS (CanonicalCoreStepDecoder.kt L60-72):
  count: 12  (= inventory claim)
  keys:  core.error, core.sleep, core.file.writeFile, core.emit.event,
         core.milestone, core.deleteDir, core.cleanWs, core.load,
         core.pwd, core.isUnix, core.waitUntil, core.archiveArtifacts
  core.echo NOT in LEGACY_PLUGIN_IDS                          CONFIRMED

CoreStepRegistryFactory (registry entries):
  count: 2   (= inventory claim)
  L29:   CoreEchoStep.registerInto(this)
  L37:   CoreShellStep.registerInto(this)

External plugin (ServiceLoader):
  count: 1   (= inventory claim)
  example.uppercase via examples/example-uppercase-plugin/
```

### Finding 2: Event Harness verdict preserved post-merge

```text
:pipeline-event-harness:test --rerun-tasks --tests 'EventHarnessContractTest' \
                                              --tests 'RealHistoryParityTest'
  EventHarnessContractTest:  tests=9  failures=0  errors=0
  RealHistoryParityTest:     tests=10 failures=0  errors=0
  TOTAL:                      19 / 0 / 0
  log sha256:  b1466f91234d538c7c68e82e60815beacdb4c11c2f5946db183d902087e2e842
```

Same as EVT-3 closure cycle. EVT-3 verdict is intact through the LFC-2E0 merge.

### Finding 3: All architecture fitness tests pass

```text
:pipeline-architecture-tests:test --rerun-tasks
  TOTAL: 193 tests, 0 failures, 0 errors, 0 skipped
  log sha256:  f419e9f4a247e4738c3316260f0a10875bd29e1c06fd6d303a05fe668dd21ff8
```

Including all critical FArch* families (domain framework free, application
depends inward, credentials cycle free, canonical bridge/coverage/outcomes,
event payload opacity, durable coordinator scope, etc.).

### Finding 4 (CRITICAL): S3EchoLegacyRemovedFitnessTest is already active

The LFC-2E0 LB-01 anchor said "activate S3EchoLegacyRemovedFitnessTest".
**On current main it is already active and GREEN.**

```text
File: v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/S3EchoLegacyRemovedFitnessTest.kt
  size: 10702 bytes
  @Disabled/@Ignore/@Quarantined: NONE
  Result: 7/7 GREEN
```

It performs structural fitness (not grep-fragile):
1. `core.echo` IS in `StepRegistry` (via `CoreStepRegistryFactory`)
2. `core.echo` is NOT in `LEGACY_PLUGIN_IDS`
3. `core.echo` is NOT in `CanonicalCoreStepDecoder` (no legacy decode path)
4. `core.echo` is NOT in `CanonicalNodeDispatcher` legacy semantics
5. `core.echo` is NOT in legacy metadata
6. CERTIFIED ∩ LEGACY_EXECUTABLE = ∅ policy enforced
7. LEGACY_REMOVED rule: decoder absent, dispatcher absent, registration absent

### Finding 5 (CRITICAL): All 5 echo tests are GREEN on current main

The LFC-2E0 LB-01 anchor said "refixture" the 5 echo tests.
**On current main all 5 are GREEN without modification.**

```text
:pipeline-application:test --rerun-tasks \
  --tests 'LegacyEchoUnreachableProofTest' \
  --tests 'EchoDurableSpineTest' \
  --tests 'UatStep002EchoCaptureTest' \
  --tests 'CoreEchoSeamTest' \
  --tests 'EchoStepContractSuiteTest'

  CoreEchoSeamTest:              tests=7  failures=0  errors=0
  EchoStepContractSuiteTest:     tests=17 failures=0  errors=0   (LB-02 contract suite, +1 over 16/17 LB-01 anchor)
  LegacyEchoUnreachableProofTest: tests=4 failures=0  errors=0
  UatStep002EchoCaptureTest:      tests=1 failures=0  errors=0
  EchoDurableSpineTest:           tests=2 failures=0  errors=0
  TOTAL:                          31 / 0 / 0
  log sha256:  a7f53b2f7ad2989a271c1bef39d16d9f46e4127927bce6cc07406091d4f879d3
```

## Implication for LFC-2E1-S1 scope

The user's original plan for S1 said:
- "refixture 5 echo tests" → not needed; already GREEN
- "activate S3EchoLegacyRemovedFitnessTest" → not needed; already active

The **actual scope of S1** is now:

```text
S1 (revised) — CERTIFICATION RECORDING, not implementation

  1. Inventory what is already certified (core.echo) vs LEGACY_REMOVED.
  2. Update LB-01 ledger to formally record core.echo's CERTIFIED +
     LEGACY_REMOVED status with all required evidence:
       - registry entry (CoreStepRegistryFactory.kt L29)
       - no legacy decoder entry
       - no legacy dispatcher entry
       - no legacy metadata entry
       - 17/17 contract suite
       - 7/7 S3EchoLegacyRemovedFitnessTest (structural, not grep)
       - 31/31 the 5 refixture tests (all green on main)
  3. Produce a CORE_ECHO_CERTIFICATION.md receipt following the same
     pattern as S3_ECHO_BURNDOWN_CERTIFICATION.md but explicitly for
     the CERTIFIED + LEGACY_REMOVED combined verdict.
  4. Update STEP_INVENTORY_LFC2E0.md row for core.echo from "CERTIFIED
     (S3 burn-down)" to "CERTIFIED + LEGACY_REMOVED (S1 certification
     recording)".
  5. Re-run the LFC-2E0 inventory gates against current main (no
     regression from the certification recording).
```

If, on opening S1, the cycle discovers any of the 5 tests actually
exercise legacy implementation details (rather than canonical behavior),
that becomes a real refixture task. The current evidence does NOT
support that conclusion — the tests are GREEN and the LB-02 burn-down
was already performed. But S1 must verify this directly.

## Comprehensive hypothesis verification (2026-09-11, post-b7e4a1de)

After the initial 5 findings, a deeper 11-hypothesis sweep was run on
trunk `main == origin/main == b7e4a1de` to exercise every claim in the
LFC-2E0 inventory through concrete checks. All 11 hypotheses verified:

| # | Hypothesis | Result | Evidence |
|---|---|---|---|
| H1 | Each of the 12 legacy keys has a `Canonical*NodeDispatcher` file | PASS (12/12) | All 12 dispatcher files exist in `v2/pipeline-application/.../durable/` |
| H2 | 14 block/orchestration DSL exist in PipelineDsl.kt but are NOT Step keys | PASS | 14/14 DSL found (1-3 occurrences each); intersection with LEGACY+REGISTRY = ∅ |
| H3 | Git family NOT_STARTED — DSL exists, no StepDefinition | PASS | DSL at L1050/1066/1094; no `*StepDefinition*.kt` in `pipeline-step-sdk/scm-git`; not in LEGACY; not in registry |
| H4 | CERTIFIED ∩ LEGACY_EXECUTABLE = ∅ (the catastrophic failure mode) | PASS | LEGACY=12 ∪ REGISTRY=2 = 14, ∩ = ∅; verified with strict awk boundary (false-positive from greedy comment matching caught + corrected) |
| H5 | 67 DSL extension functions in L990-1900 range | PASS | Exactly 67 `fun` declarations in range; 81 total file-wide |
| H6 | 10 example `.pipeline.kts` files exist | PASS | 01-hello through 10-timeout all present |
| H7 | 4 Event Harness contracts for examples 07-10 | PASS | `examples/contracts/07-catch-error.events.yaml` through `10-timeout.events.yaml` |
| H8 | All file:line citations in `STEP_INVENTORY_LFC2E0.md` resolve | PASS | 8/8 `.kt` paths exist (8 Canonical*NodeDispatcher + 3 ContractSuiteTest files cited) |
| H9 | Integration boundary — registry Step classes in compiled jar | PASS | `CoreEchoStep.class` + `CoreShellStep.class` present in `pipeline-application-0.1.0-SNAPSHOT.jar` |
| H10 | Step SDK + EmitEventDispatcher + Sh contract suite GREEN | PASS | EchoContractSuite 17/17, ShContractSuite 17/17, CanonicalEmitEventNodeDispatcherTest 5/5, CoreShellStepTest 12/12 = 51/51 GREEN |
| H11 (Rule 16) | 8 pre-existing failures unchanged post-LFC-2E0 | PASS | Same 8 failures as EVT-3 base SHA: 7 UatLocal (007 sandbox, 008 credentials, 009 archive) + 1 UatCompat001 (fixture14CredentialsBindings) |

### Rule 16 evidence detail

```text
UatLocal007SandboxProfileTest:    tests=12 failures=2 errors=0
  - SB-S-010 resume with profile change none-to-local re-attaches(Path) FAILED
  - SB-S-008 parallel branches have isolated cwds(Path) FAILED
UatLocal008CredentialsTest:       tests=27 skipped=1 failures=2 errors=0
  - UAT-L8-CP-001 original 4 corpus files byte-identical to cycle base() FAILED
  - CR-BD-027 CredentialUsed per use(Path) FAILED
UatLocal009TopStepsTest:          tests=13 failures=3 errors=0
  - CR-U9-008 archiveArtifacts sha256 and size in event() FAILED
  - CR-U9-011 archiveArtifacts AntStyleGlob pattern matches files() FAILED
  - CR-U9-012 cross-step writeFile then archiveArtifacts picks up file() FAILED
UatCompat001CorpusSmokeRunTest:   tests=2  failures=1 errors=0
  - fixture14CredentialsBindings FAILED (pre-existing per S2_5_7_GATE_EVIDENCE.md)
TOTAL: 8 failures, 0 new failures introduced by LFC-2E0
```

### Captured logs (rule 25)

```text
/tmp/lfc2e0-validate-event-harness.log   sha256=b1466f91234d538c7c68e82e60815beacdb4c11c2f5946db183d902087e2e842
/tmp/lfc2e0-validate-farch020.log        sha256=02aa478f5203f4a73d2069254c7e99919e79d7ceefe03ac72cbbda4ebe627da7
/tmp/lfc2e0-validate-all-farch.log       sha256=f419e9f4a247e4738c3316260f0a10875bd29e1c06fd6d303a05fe668dd21ff8
/tmp/lfc2e0-validate-echo-tests.log      sha256=a7f53b2f7ad2989a271c1bef39d16d9f46e4127927bce6cc07406091d4f879d3
/tmp/lfc2e0-validate-step-sdk-v2.log     sha256=6b03696fdc96b112fbba5c882e2fae824ed83da02cb5932e65b8c1c5264e5767
/tmp/lfc2e0-validate-emit-event.log      sha256=bdc937d20d89fa4f97dc6169540ee41778435b87e736b83e0c67aaa4a0dc1c56
/tmp/lfc2e0-rule16.log                   sha256=b132972a2edb12fa37ff11e8448a41a9eb9be1ade1ee1c660462d7dc7de45e3f
/tmp/lfc2e0-rule16-compat.log            sha256=17101d377fa0f44cb948d24cb83fca56eae9c612ce4ee1d4249f255979a862b0
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-validate-*.log /tmp/lfc2e0-rule16*.log
```

Receipt-level digest at extension time: see `git log -p` for the amended
self-digest; this note is appended evidence, not a fresh closure.

### Honest correction recorded

The H4 verification initially produced a **false positive** (claiming
`core.sh` violated the invariant). The bug was a greedy awk regex
that matched `core.sh` mentions in **doc comments** (lines 45, 53, 57,
75 of `CanonicalCoreStepDecoder.kt`) instead of only the `LEGACY_PLUGIN_IDS`
set values. After tightening the boundary (`/^[[:space:]]*"core\./` +
`/^[[:space:]]*\)/`), the assertion passed cleanly. This is recorded
because: (a) it shows the verification itself was honest about its own
limits; (b) the lesson generalizes — strict boundary parsing matters for
every grep/awk/grep -c claim in this receipt.

## Public acceptance oracle (2026-09-11, post-b7e4a1de)

The 11-hypothesis sweep covered **structural** claims. The strongest
acceptance gate is **behavioral**: does the installed production binary
execute the 10 documented `.pipeline.kts` examples and emit the events
the 4 contracts (07-10) require?

`examples/run.sh` was run **twice consecutively** with hermetic scratch
directories (the EVT-3 receipt pattern). Both runs:

```text
Run #1 (TMPDIR=/tmp/lfc2e0-runsh-scratch):
  exit:    0
  PASS:    18 markers (10 examples + 4 contracts + 4 harness parity)
  FAIL:    0 markers
  log sha256: ed80d7c83e264cf8386d82c1a44272599c76fc02f2549e00717e3f6b6106313b

Run #2 (TMPDIR=/tmp/lfc2e0-runsh-scratch2, fresh):
  exit:    0
  PASS:    18 markers
  FAIL:    0 markers
  log sha256: 6a5bdb43319010711256f2b22a9053689b06a7d8e6d550f85291495612f1ba3a
```

All 10 examples match expected exit+outcome:

```text
01-hello:               exit=0  outcome=success    (expected success)
02-multi-stage:         exit=0  outcome=success    (expected success)
03-shell:               exit=0  outcome=success    (expected success)
04-kotlin-control-flow: exit=0  outcome=success    (expected success)
05-failing-step:        exit=1  outcome=failure    (expected failure)
06-durable:             exit=0  outcome=success    (expected success)
07-catch-error:         exit=0  outcome=unstable   (expected unstable)
08-parallel:            exit=0  outcome=success    (expected success)
09-retry:               exit=0  outcome=success    (expected success)
10-timeout:             exit=1  outcome=failure    (expected failure)
```

All 4 contracts PASS differential parity:

```text
07-catch-error: 2 CatchErrorTriggered (FAILURE→UNSTABLE, innermost-first) + post-catch echo
08-parallel:    second run reuses terminal aggregate (0 branch events, 0 step events)
09-retry:       RetryAttemptFinished failed→succeeded (exactly 2 attempts)
10-timeout:     TimeoutScheduled + sh aborted by deadline
```

### Strongest evidence: 01-hello event history

```text
Total events: 9
Event kinds:  CompilationStarted(1) CompilationFinished(1) RunStarted(1)
              StageStarted(1) StepStarted(1) EchoOutputCaptured(1)
              StepFinished(1) StageFinished(1) RunFinished(1)
```

`EchoOutputCaptured` is the typed domain event that the LFC-2E0 inventory
attributes to `core.echo` (registry path). The fact that this event is
emitted by the installed production binary executing the documented
`examples/01-hello.pipeline.kts` is the strongest possible proof that:

1. The binary resolves `echo(...)` via the registry path (not legacy);
2. The registry entry `CoreEchoStep` produces the typed event the Event
   Harness contract expects;
3. The Event Harness verdicts are reproducible post-LFC-2E0 merge.

This **closes the integration-boundary gate** the 11-hypothesis sweep
deliberately did not cover (it was structural, not behavioral).

### Captured logs (run.sh parity, rule 25)

```text
/tmp/lfc2e0-runsh-parity.log     sha256=ed80d7c83e264cf8386d82c1a44272599c76fc02f2549e00717e3f6b6106313b
/tmp/lfc2e0-runsh-parity2.log    sha256=6a5bdb43319010711256f2b22a9053689b06a7d8e6d550f85291495612f1ba3a
/tmp/lfc2e0-installDist.log      sha256=9f2a1a42adaf810dca1cba1cd06c260748a042325e3318f19fc3005b222c1a93
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-runsh-parity*.log /tmp/lfc2e0-installDist.log
```

## Edge case sweep (2026-09-11, post-b7e4a1de)

After the structural + acceptance parity passes, an edge-case sweep
exercised failure modes and integration boundaries not covered by the
canonical examples:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E1 | `core.sleep` (legacy) executes correctly via legacy path | PASS | `/tmp/lfc2e0-legacy-test.log`: exit 0, `legacy-stage/sleep-0` stepType="sleep", duration ~1.018s matches `sleep(1)` |
| E2 | Mixed legacy (`sleep`/`pwd`/`isUnix`) + registry (`echo`) coexist | PASS | `/tmp/lfc2e0-mixed-test.log`: exit 0, 6 Steps emitted, `PwdResolved` + `UnixDetected` + 3 `EchoOutputCaptured` |
| E3 | Replay semantics on legacy key (`--rerun` vs default ReusePriorRun) | PASS | `--rerun`=6.6s, default=5.0s; cached events reused on default mode, fresh on `--rerun` |
| E4 | External plugin `example.uppercase` coexistence with core | SKIPPED | CLI has no `--script-classpath` flag; `examples/run.sh` itself doesn't exercise external plugins (consistent with inventory: "no example in 01..10"). Not a regression — this is a known limitation pre-LFC-2E0 |
| E5 | Packaging: `installDist` jar contents sanity | PASS | 38 jars / 95MB; `pipeline-domain-0.1.0-SNAPSHOT.jar` contains `StepRegistry`/`StepContract`/`StepCodec`; `pipeline-application-0.1.0-SNAPSHOT.jar` contains `CoreStepRegistryFactory`/`CoreEchoStep`/`CoreShellStep` |
| E6 | CLI flag semantics (`--rerun` / `--resume` / default) | PASS | Run #1 fresh=6.6s, Run #2 default reuse=5.0s, Run #3 `--rerun`=6.7s, Run #4 `--resume`=5.2s; semantics match `DurableRunPolicy.ReusePriorRun`/`FreshRun`/`Continue` |

### Captured logs (edge cases, rule 25)

```text
/tmp/lfc2e0-legacy-test.log     sha256=71b9cbdd9af0d3dd7cd2491d10d6672286209df9325d913ce29a34fe2fb2df59
/tmp/lfc2e0-mixed-test.log      sha256=4e1741c1f07812479096298102f447b38863d7d74ebe7f5bceadf86b02793ded
/tmp/lfc2e0-replay-run1.log     sha256=b13d1c08b85c9498374d07d6a38069c9523da223578547867d1ad6d5eb19e184
/tmp/lfc2e0-replay-run2.log     sha256=a78e27c93d0512185be299d1b70fe6d71559f78c4839446f8403442f71bf12c8
/tmp/lfc2e0-default-run1.log    sha256=58802a7dbfbae95b34b5abcc5996960a64fdda81bfce9d5190faeac5c6ac198a
/tmp/lfc2e0-default-run2.log    sha256=7161e127d0072c9852bb959230889bbeffce90d957318cd83345ad25d3e6716f
/tmp/lfc2e0-rerun-run.log       sha256=0107896fd74963346e984857212c76938f223db67e6e73675c69848c7a3ad572
/tmp/lfc2e0-resume-run1.log     sha256=f2f7ff3f6f4561c9e12c7899bf0cd86f18861ae2a23d954541783b7004b49f04
/tmp/lfc2e0-resume-run2.log     sha256=cdd2511b2fd0cf08a9a63a8c7a3cd42ba01eec9d58e21265544edfa37d3d5417
/tmp/lfc2e0-resume-run3.log     sha256=2fd997fc64627a2d56c8588a907d3396e8f630978e1ada618f01a8ad93a0ea72
/tmp/lfc2e0-resume-run4.log     sha256=8907f787d6d6f8eb1da3dffff552679d7e0ce1f4f76d9c252586a737d181b037
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-legacy-test.log /tmp/lfc2e0-mixed-test.log \
          /tmp/lfc2e0-replay-*.log /tmp/lfc2e0-default-*.log /tmp/lfc2e0-rerun-*.log \
          /tmp/lfc2e0-resume-*.log
```

### E4 honest finding (skipped)

`example.uppercase` is registered via ServiceLoader. The `examples/run.sh`
script does NOT include the plugin jar on the classpath, and the CLI
lacks a `--script-classpath` flag. To exercise E4 would require either:
1. Adding the jar to `examples/run.sh` (production change, out of LFC-2E0 scope)
2. Adding a new CLI flag (production change, ADR required)
3. A custom test harness (out of validation scope)

The LFC-2E0 inventory notes "Real examples: none in 01..10" for `example.uppercase`
— this is honest. The plugin's `CERTIFIED` verdict comes from
`S3EchoLegacyRemovedFitnessTest`-style certification (per
`LB02_EP_EXAMPLE_UPPERCASE_CERTIFICATION.md`), not from example-based parity.

E4 is therefore a known limitation of the validation surface, NOT a
regression. S1 will need to either add the plugin classpath or rely on
the existing certification receipt.

## What this note is NOT

This is **not** an S1 cycle opening. Per the user's standing instruction:

> "NO empieces todavía el burn-down de las 12 legacy keys.
> Primero abre un ciclo separado: `lfc2-e1-s1-echo-legacy-removed`"

S1 opening still requires explicit user direction. This note exists
so that when S1 opens, its scope is calibrated against real evidence
rather than the LFC-2E0 LB-01 anchor (which appears to have been
written from a pre-LB-02-burn-down perspective).
