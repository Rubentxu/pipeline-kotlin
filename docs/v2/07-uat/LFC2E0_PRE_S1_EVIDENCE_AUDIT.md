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

## Edge case sweep — round 2 (2026-09-11, post-b7e4a1de)

After the first 6 edge cases, a second round exercised failure modes,
filesystem behavior, concurrency, capability enforcement, and Rule-16
verification with proper XML extraction:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E7 | `core.error` (legacy) failure semantics | PASS | `/tmp/lfc2e0-error-test.log`: exit 1, outcome=failure, 1 `StepFailed` event, "after error" step did NOT execute (stage halted) |
| E8 | Write→read filesystem roundtrip | PASS | `/tmp/lfc2e0-write-read-test.log`: sh wrote file, sh read back via `cat`; on-disk file content matches |
| E9 | `deleteDir` (legacy filesystem cleanup) | PASS | `/tmp/lfc2e0-delete-dir-test.log`: directory deleted; test verified `GONE` (not `STILL EXISTS`) |
| E10 | Concurrent pipelines, same control root | PASS | `/tmp/lfc2e0-concurrent-0{1,2,3}.log`: 3 distinct runIds, all success, no contention crashes |
| E11 | Unknown step key (fail-closed compile) | PASS | `/tmp/lfc2e0-unknown-test.log`: exit 1, "Unresolved reference 'unknownStep'" — fail-closed before execution |
| E12 | Malformed script syntax error | PASS | `/tmp/lfc2e0-malformed-test.log`: exit 1, "Syntax error: Expecting ')'" — fail-closed at parse |
| E13 | Capability admission (SHELL_OPERATIONS_CAPABILITY) | PASS (structural) | `CoreShellStep.contract.requiredCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY)` (L512); `RegistryExecutionPreparation.prepare()` is the fail-closed admission (L45); coordinator calls it before handler invocation (L776) |
| E14 | Rule-16 verification (UATL008 fresh run) | PASS | `/tmp/lfc2e0-e14-uat008.log`: 27 tests, 1 skipped, **2 failures** — identical to EVT-3 base SHA (UAT-L8-CP-001 corpus hash mismatch, CR-BD-027 missing CredentialUsed events) |

### Captured logs (edge cases round 2, rule 25)

```text
/tmp/lfc2e0-error-test.log        sha256=cfd6db512c8de767cef0bf57e47ab7cae294d4d92d105cd417e40203ba49b458
/tmp/lfc2e0-write-read-test.log   sha256=6e79a0875aeb42baa6baa891016343494c41c69b9014d34cdb476710e85276c9
/tmp/lfc2e0-delete-dir-test.log   sha256=2aa31a7ce22c2e94d6de52df9b332f22fcd966fa7698dfa89cac5ba9f3dd6522
/tmp/lfc2e0-concurrent-01.log     sha256=17c8a066d51df6ca1bcfddbb64ed1b472076a5e57d278e5992692e07ccf54099
/tmp/lfc2e0-concurrent-02.log     sha256=024c48c67d35213676850fde8cfebe901f65d452eba225b83c4c58afa504aa57
/tmp/lfc2e0-concurrent-03.log     sha256=2e6359bc5664fe413894b6eb2366989577d8b6ec0bd77219c241aa3eadce2e0b
/tmp/lfc2e0-unknown-test.log      sha256=315108550ebc42ab3df22d0f65b750a40fe9d445d70fff797ebda04839640779
/tmp/lfc2e0-malformed-test.log    sha256=2684325e2b6a639242e685cdbc57a77abfda539ec9a64f60cf55726c093205f7
/tmp/lfc2e0-e14-uat008.log        sha256=e399c75d8543e134f69dd6940c378f4e063a929e928060d8671e7d798cb923fd
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-error-test.log /tmp/lfc2e0-write-read-test.log \
          /tmp/lfc2e0-delete-dir-test.log \
          /tmp/lfc2e0-concurrent-0{1,2,3}.log \
          /tmp/lfc2e0-unknown-test.log /tmp/lfc2e0-malformed-test.log \
          /tmp/lfc2e0-e14-uat008.log
```

### Cumulative edge case tally

```text
Round 1 (E1..E6):
  E1: core.sleep legacy execution                 PASS
  E2: mixed legacy + registry                     PASS
  E3: replay semantics                            PASS
  E4: external plugin coexistence                  SKIPPED (honest finding)
  E5: packaging sanity                            PASS
  E6: CLI flag semantics                          PASS

Round 2 (E7..E14):
  E7:  core.error failure semantics               PASS
  E8:  write/read filesystem roundtrip            PASS
  E9:  deleteDir cleanup                          PASS
  E10: concurrent pipelines                       PASS
  E11: unknown step (fail-closed)                 PASS
  E12: malformed script (fail-closed)             PASS
  E13: capability admission                       PASS (structural)
  E14: Rule-16 UATL008 verification               PASS

Total: 13 PASS + 1 SKIPPED across 14 edge cases
```

## Edge case sweep — round 3 (2026-09-11, post-496699eb)

Drilling deeper into the durable spine and CLI semantics — the surfaces
that S1 will need to assert against:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E15 | Event journal durability across runs | PASS | run.db sha256 changed between runs; no event duplication; full 9-event execution first run |
| E16 | `--rerun` vs `--resume` semantics | PASS | `--rerun` = 9 events (full re-execution); `--resume` = 6 events (cached outcome reused) |
| E17 | `validate` subcommand (no execution) | PASS | Only CompilationStarted/Finished events; "VALIDATION SUCCESSFUL" |
| E18 | `--resume` idempotency (5x consecutive) | PASS | All share same `runId`; step-level events appear only in original block |
| E19 | `--db` isolation (different path) | PASS | Independent runId, fresh journal, original db sha256 unchanged |
| E20 | Failure durability (`--resume` of failure) | PASS (with documented behavior) | Successes cached (idempotent); failures re-execute (retry semantics) — both observable & consistent |
| E21 | `--control-root` isolation (EVT-H1 hermeticity) | PASS | Same db + same ctl = same runId; same db + different ctl = different runId; confirms `examples/run.sh` hermeticity rationale |

### Captured logs (edge cases round 3, rule 25)

```text
/tmp/lfc2e0-e15-journal.log      sha256=f5fc9718163fb7562351b71e74165dc40d095ed2155431fcb5e90dd5ab994e71
/tmp/lfc2e0-e16-rerun.log        sha256=c48cdc8a12457c14c0ff723fc03aa565f021089e5995c919f95913b5a5bb364e
/tmp/lfc2e0-e17-validate.log     sha256=516aed8c6ec6e8648f0a2830ea8c641714b74082c45d4a2c99809c410b4452a6
/tmp/lfc2e0-e18-idempotent.log   sha256=1df9a34909c4f2b5cf9d05402a1a12082cd4e3cd2f32d6ce186ad98452732ce9
/tmp/lfc2e0-e19-isolation.log    sha256=f9798d029b4ce4133485a46fe89e8a0a69a56ecea9373161aea8971117dbfee3
/tmp/lfc2e0-e20-failure.log      sha256=02770e89491f4146901f49baf81ff12dcc6138274d78b7206e480d039aa6bc54
/tmp/lfc2e0-e21-ctlroot.log      sha256=dd81ee8c9dc7d89570b06a8e09f30ae736d340f420eb96ef95659b884c92d4ea
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e15-journal.log /tmp/lfc2e0-e16-rerun.log \
          /tmp/lfc2e0-e17-validate.log /tmp/lfc2e0-e18-idempotent.log \
          /tmp/lfc2e0-e19-isolation.log /tmp/lfc2e0-e20-failure.log \
          /tmp/lfc2e0-e21-ctlroot.log
```

### Cumulative edge case tally (after round 3)

```text
Round 1 (E1..E6):
  E1: core.sleep legacy execution                 PASS
  E2: mixed legacy + registry                     PASS
  E3: replay semantics                            PASS
  E4: external plugin coexistence                  SKIPPED (honest finding)
  E5: packaging sanity                            PASS
  E6: CLI flag semantics                          PASS

Round 2 (E7..E14):
  E7:  core.error failure semantics               PASS
  E8:  write/read filesystem roundtrip            PASS
  E9:  deleteDir cleanup                          PASS
  E10: concurrent pipelines                       PASS
  E11: unknown step (fail-closed)                 PASS
  E12: malformed script (fail-closed)             PASS
  E13: capability admission                       PASS (structural)
  E14: Rule-16 UATL008 verification               PASS

Round 3 (E15..E21):
  E15: journal durability                         PASS
  E16: --rerun vs --resume distinction            PASS
  E17: validate subcommand (no execution)         PASS
  E18: --resume idempotency                       PASS
  E19: --db isolation                             PASS
  E20: failure durability                         PASS (documented)
  E21: --control-root isolation (EVT-H1)          PASS

Total: 20 PASS + 1 SKIPPED across 21 edge cases
```

### Key durability finding (E20 + E21)

The two surfaces interact:
- `--control-root` is the **durable op-state anchor** (retry decisions, replay cache)
- `--db` is only the **event journal** (observability)
- **Successes** are cached at the control-root level → `--resume` reuses cached outcome
- **Failures** are NOT cached → `--resume` re-executes the failing step (retry-on-resume)

This is **deliberate design**, observable, and consistent across runs.
The `examples/run.sh EVT-H1` hermeticity debt (hermeticity requires
per-run control-root) is validated: ctl1 reused = same runId, ctl2
different = new runId, even with same db.

## What this note is NOT

This is **not** an S1 cycle opening. Per the user's standing instruction:

> "NO empieces todavía el burn-down de las 12 legacy keys.
> Primero abre un ciclo separado: `lfc2-e1-s1-echo-legacy-removed`"

S1 opening still requires explicit user direction. This note exists
so that when S1 opens, its scope is calibrated against real evidence
rather than the LFC-2E0 LB-01 anchor (which appears to have been
written from a pre-LB-02-burn-down perspective).
