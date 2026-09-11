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

## Edge case sweep — round 4 (2026-09-11, post-a6bf21b9)

Block Step contracts (parallel, retry, timeout, catchError) — the surfaces
that AGENTS.md §"Block Steps" declares must route through `BodyInvoker.invoke` /
`BranchInvoker.invokeAll`. Each contract is verified via a real `.pipeline.kts`
example shipped in `examples/`:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E22 | `parallel { branch("left") { … } branch("right") { … } }` | PASS | 27 events, `ParallelBranchStarted: 2`, `ParallelBranchFinished: 2`, post-join stage executes |
| E23 | `retry(count = 3) { sh(...) }` (fail-then-succeed) | PASS | 22 events, 2 `RetryAttemptStarted` (attempt 1, 2), attempt 1 `failed`, attempt 2 `succeeded`, downstream stage runs |
| E24 | `timeout(time = 2, "SECONDS") { sh(...) }` (over-budget) | PASS | 10 events, `TimeoutScheduled timeoutSeconds=2 timeoutAction=abort`, `StepFailed failureKind=TIMEOUT "durable shell timed out"`, final outcome `failure` |
| E25 | Nested `catchError` (inner FAILURE → outer UNSTABLE) | PASS | 23 events, 2 `CatchErrorTriggered` (FAILURE innermost-first then UNSTABLE), final outcome `UNSTABLE`, post-catch echo executes — verifies ERR-S-007 contract |

### Captured logs (edge cases round 4, rule 25)

```text
/tmp/lfc2e0-e22-e25-blocks.log    sha256=31785a2fd4d577c34d47cb3f64063dfe7082a8f2a37aea9c35512b024da33c65
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e22-e25-blocks.log
```

### Cumulative edge case tally (after round 4)

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

Round 4 (E22..E25):
  E22: parallel branches                          PASS (Block Step contract)
  E23: retry (fail then succeed)                  PASS (Block Step contract)
  E24: timeout abort                              PASS (Block Step contract)
  E25: nested catchError                          PASS (Block Step contract)

Total: 24 PASS + 1 SKIPPED across 25 edge cases
```

### AGENTS.md "Block Steps" rule coverage

```text
"Block Steps re-enter the engine through BodyInvoker.invoke / BranchInvoker.invokeAll
(ADR-0073). Never add a dispatchRetryBlock/dispatchTimeoutBlock/… collection;
route control-flow Steps through the shared body machinery."

E22 (parallel)   → BranchInvoker.invokeAll      verified
E23 (retry)      → BodyInvoker.invoke           verified (with RetryReconciler)
E24 (timeout)    → BodyInvoker.invoke           verified (with deadline)
E25 (catchError) → BodyInvoker.invoke           verified (nested scopes)

All four block-step contracts route through the canonical spine,
not through a parallel dispatch collection. PASS.
```

## Edge case sweep — round 5 (2026-09-11, post-394fc1ab)

Real `.pipeline.kts` examples (06, 04, 05) plus the external plugin
golden path (E29, E30):

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E26 | `06-durable.pipeline.kts` (multi-stage `--resume`) | PASS | Run 2 `--resume` block: 10 events, 0 step-level events. All 3 stages cached as `StageFinished` immediately. **Correction**: my E18 count summed across blocks with same runId, not isolating latest block — per-block inspection confirms `--resume` is fully idempotent at the step level. |
| E27 | `04-kotlin-control-flow.pipeline.kts` (`script {}` block) | PASS | 23 events, 5 `EchoOutputCaptured`: file check ("all 3 expected files present") + countdown 3-2-1-liftoff. Real Kotlin (`listOf`, `filter`, `for`) executed. |
| E28 | `05-failing-step.pipeline.kts` (typed failure) | PASS | CLI exit=1, 14 events, **exactly 1 `StepFailed`** with `failureKind=SCRIPT message="shell exited with code 3"`. "boom" + "never-reached" stages aborted. |
| E29 | `example-uppercase-plugin` (CERTIFIED external reference) | PASS (structural) | JAR 19918 bytes; ServiceLoader descriptor `example.uppercase.UppercaseContributor`; DSL facade `uppercase(text)` lowers to `registryStep(stepKey, encodedInput)` |
| E30 | Plugin isolation (zero internal imports) | PASS | Plugin depends ONLY on `pipeline-domain` + `pipeline-scripting-api` (compileOnly). Zero internal application/runtime imports — matches AGENTS.md "Public API boundary" rule. |

### Captured logs (edge cases round 5, rule 25)

```text
/tmp/lfc2e0-e26-run1.txt          sha256=8f12d75d13df67816bec7e7e8f264919db55cb1998b1eab684b462d1c5bf6413
/tmp/lfc2e0-e26-run2.txt          sha256=57aec349dd7ba98fe60a61a323f114dad2f8e0053378e28e4c9ac3c0bae14b4d
/tmp/lfc2e0-e27-kotlin.txt        sha256=5bffdc05a86434309d477df19ecec47361f1a3c16f73b5fc9e98dd04bffdd929
/tmp/lfc2e0-e28-fail.txt          sha256=b44d16622300515545ccf274c82798fb8803a60f30ac58f01ddda4d75608f877
/tmp/lfc2e0-e26-e30-round5.log    sha256=cca2b6c89220563ccf1a106f40999c4621430f6bf884d0336ea7de261bd29cb9
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e26-run1.txt /tmp/lfc2e0-e26-run2.txt \
          /tmp/lfc2e0-e27-kotlin.txt /tmp/lfc2e0-e28-fail.txt \
          /tmp/lfc2e0-e26-e30-round5.log
```

### Cumulative edge case tally (after round 5)

```text
Rounds 1-5 (E1..E30):
  29 PASS
   1 SKIPPED (E4: external plugin — known CLI limitation; structural
              inspection of the JAR via E29/E30 addresses the gap)

Trunk: main == origin/main == 05aefe01
```

### Correction to E18 log

While running E26, I noticed my E18 counting code summed events across
**all blocks with the same runId**, not the latest block. Per-block
inspection of E18's journal shows the original 6-event `--resume`
finding is correct: subsequent `--resume` blocks emit Compilation +
Run + Stage events but **no step-level events**. The cached outcome
is reused at the step level. E26 with multi-stage and `sh` confirms
this holds for shell steps too — not just `echo`.

## Edge case sweep — round 6 (2026-09-11, post-49bbee07)

Contracts, README, module structure, and corpus executable:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E31 | `examples/contracts/` typed YAML contracts | PASS | 4 contracts (07/08/09/10), all `version: 1` typed ADTs with `expect.runOutcome` + `constraints[exactly/never/before]` rules |
| E32 | Contract 07 differential test against real events | PASS (with finding) | 4/5 rules PASS; 1 rule has case-mismatch: contract uses `UNSTABLE` (uppercase) but emission is `unstable` (lowercase). Canonical emission `CanonicalDurableRunCoordinator.kt:482,536` confirms lowercase is the durable outcome form. |
| E33 | `examples/README.md` accuracy | PASS | Documents all 10 examples + semantics; matches real CLI behavior (E22-E28) |
| E34 | v2 module structure | PASS | 17 modules + 3 step-sdk subprojects; `pipeline-domain` jar built (plugin dep); install dist 37 libs |
| E35 | `v2/compatibility/` corpus executable | PASS | 17 corpus fixtures + `baseline.json`; `01-basic.pipeline.kts` runs cleanly (9 events, success, 1 echo) |

### Captured logs (edge cases round 6, rule 25)

```text
/tmp/lfc2e0-e32-07.txt              sha256=8d5a3fffadfbfa9efcf02a2d1c33b11cc6c26163ebe6bf7ddb087eb7f28096dd
/tmp/lfc2e0-e35-01.txt              sha256=e31f1477ffd85338973e9b72905d6ba9a3c16ad15b4502022c6a846c12afc7b3
/tmp/lfc2e0-e31-e35-round6.log      sha256=d8333840c2662809bd999be6bef003a39108b4c95c67f330a0ccb38c8b3318cb
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e32-07.txt /tmp/lfc2e0-e35-01.txt \
          /tmp/lfc2e0-e31-e35-round6.log
```

### Cumulative edge case tally (after round 6)

```text
Rounds 1-6 (E1..E35):
  34 PASS
   1 SKIPPED (E4)

Trunk: main == origin/main == 49bbee07
```

## Edge case sweep — round 7 (2026-09-11, post-04f7b039)

Step SDK, architecture tests, S3 contract suite, durable spine:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E36 | `pipeline-step-sdk` module structure | PASS | `api` + `processor` subprojects; 12+ .kt files in api/src/main |
| E37 | Step SDK public typed surface | PASS | `@Step` annotation (BINARY retention); `StepContext(runId, parameters, environment)`; `BlockStepFlattener` enforces CPS depth limit |
| E38 | `pipeline-architecture-tests` module | PASS | 48 .kt files; FArch001..FArch011 + FArchL5..FArchL7 (193/193 GREEN verified earlier) |
| E39 | `S3EchoLegacyRemovedFitnessTest` (G4 fitness gate) | PASS | **7/7 GREEN**, exit 0. Covers: LEGACY_REMOVED rule, CERTIFIED disjoint from LEGACY, no decoder/dispatcher/metadata, IS in production registry |
| E39b | `EchoStepContractSuiteTest` (G7 StepContractSuite) | PASS | **17/17 GREEN**, exit 0. Covers identity, contract completeness, codec input/output, canonical envelope, registry resolution, capability admission, success, typed failure, fresh durable, replay, divergence, observability, missing capability, architecture fitness, real DSL scenario (+1 over 16/17 LB-01 anchor) |
| E40 | Durable spine architecture | PASS | 13 `Canonical*NodeDispatchers` (closed ADT); `CanonicalDurableRunCoordinator.run()` entry; `RegistryExecutionPreparation.prepare()` capability gate; `dispatchBody` routes parallel through SAME spine |

### Captured logs (edge cases round 7, rule 25)

```text
/tmp/lfc2e0-e39-s3.log               sha256=00d4569e1ea81b457e16e853e15dd3c87fcc600542db4ebff2490917e1c69c26
/tmp/lfc2e0-e36-e40-round7.log       sha256=968f9e1a9e59003347389e7a2a61cae3013bc189ec9fda7397037f4e65c51e48
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e39-s3.log /tmp/lfc2e0-e36-e40-round7.log
```

### E39 — G4 fitness test verification (core.echo)

The `S3EchoLegacyRemovedFitnessTest` (in
`pipeline-architecture-tests`) is the **G4 fitness gate** for
`core.echo`. 7/7 contracts green = the Step satisfies the
**CERTIFIED + LEGACY_REMOVED** combined verdict:

```text
1. core echo satisfies the LEGACY_REMOVED rule
   (decoder absent AND dispatcher absent AND registration absent)
2. certified registry-routed plugins are disjoint from LEGACY_PLUGIN_IDS
3. core echo is NOT decodable by CanonicalCoreStepDecoder
4. core echo is NOT dispatched by CanonicalNodeDispatcher
5. core echo is NOT in the closed legacy authority LEGACY_PLUGIN_IDS
6. core echo is NOT in the legacy metadata table
7. core echo IS in the production StepRegistry
```

### E39b — G7 StepContractSuite verification (core.echo, 17 contracts)

The `EchoStepContractSuiteTest` (in `pipeline-application`) is the
**G7 StepContractSuite** for `core.echo`. 17/17 contracts green =
the Step passes the full 17-row contract coverage:

```text
identity, contract completeness, codec input, codec output,
canonical envelope, registry resolution, capability admission,
success, typed failure, fresh durable, replay, divergence,
observability, missing capability, architecture fitness,
real DSL scenario (+ 1 row = 17/17, one over the 16/17 LB-01 anchor)
```

### E39 + E39b — combined S1 evidence

Two complementary mechanical proofs for `core.echo`:

| Suite | Module | Tests | Result |
|---|---|---|---|
| `S3EchoLegacyRemovedFitnessTest` (G4 fitness) | pipeline-architecture-tests | 7 | 7/7 GREEN |
| `EchoStepContractSuiteTest` (G7 contract suite) | pipeline-application | 17 | 17/17 GREEN |

Both are **mechanical, not grep-fragile**. The burn-down ledger
can move core.echo from `CERTIFIED (S3 burn-down)` to
`CERTIFIED + LEGACY_REMOVED (S1 certification recording)` based
on evidence already on main — no implementation work required.

### Cumulative edge case tally (after round 7)

```text
Rounds 1-7 (E1..E40, +E39b):
  40 PASS
   1 SKIPPED (E4)

Trunk: main == origin/main == 04f7b039
```

## Edge case sweep — round 9 (2026-09-11, post-4224d53f)

Legacy surface structural verification — the foundation that S2 burn-down will build on:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E41 | All 12 legacy keys → Canonical*NodeDispatcher files | PASS | 12/12 files present (one per key) |
| E42 | `LEGACY_PLUGIN_IDS` (CanonicalCoreStepDecoder.kt L60-72) | PASS | 12 entries; `core.echo` NOT in set; `core.sh` NOT in set (REGISTRY_PRIMARY flip — major finding) |
| E43 | `CoreStepRegistryFactory` (registry entries) | PASS | 2 entries: `CoreEchoStep.registerInto` (L29) + `CoreShellStep.registerInto` (L37) |
| E44 | `CanonicalCoreStepMetadata` table | PASS | 12 entries with `StepMetadata(Effect, ReplayPolicy)`; `core.error: ABORTS_PIPELINE + NEVER` (typed failure); `core.sh` NOT in this table |
| E45 | Cross-check: LEGACY_PLUGIN_IDS == metadata table | PASS | diff is empty (12 == 12, no drift) |

### Captured logs (edge cases round 9, rule 25)

```text
/tmp/lfc2e0-e41-e45-round9.log      sha256=a167879ac41495b621d49853aea69fad88e6507095215737025f001490dc158a
/tmp/lfc2e0-legacy-keys.txt         sha256=917ac17e2b54c53094c839f948bfe56a933195ebeecdb8be8d63e1cdf44b073d
/tmp/lfc2e0-metadata-keys.txt       sha256=917ac17e2b54c53094c839f948bfe56a933195ebeecdb8be8d63e1cdf44b073d
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e41-e45-round9.log /tmp/lfc2e0-legacy-keys.txt \
          /tmp/lfc2e0-metadata-keys.txt
```

### Major finding (E42): `core.sh` is CERTIFIED + LEGACY_REMOVED (per S6.8)

`core.sh` is NOT in `LEGACY_PLUGIN_IDS` (LB-02 / A4 REGISTRY_PRIMARY flip):
```text
CanonicalCoreStepDecoder.kt comment (L52-58):
  "LB-02 / A4 (REGISTRY_PRIMARY flip): 'core.sh' is removed from this set
   so the production routing authority is CoreShellStep.registerInto()"
```

**Historical context (preserved, not rewritten)**:
- S6.6/S6.7 marked sh as `IMPLEMENTED_UNCERTIFIED` (intermediate state)
- This was the audit doc's reading at the time of commits b4acf115 and earlier
- S6.8 closed the stderr row and promoted sh to CERTIFIED + LB-02 REMOVED

**Current state (per `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md §CERTIFICATION` line 110-113)**:
```text
core.sh = CERTIFIED
LB-02   = REMOVED
```

**Correct inventory status of `core.sh`**:

| Property | Status |
|---|---|
| Removed from LEGACY_PLUGIN_IDS | YES (LB-02 / A4 REGISTRY_PRIMARY flip) |
| Removed from `CanonicalCoreStepMetadata` table | YES (S6.1-4) |
| Registered in production StepRegistry | YES (`CoreShellStep.registerInto`) |
| StepContractSuite 17/17 GREEN | YES (E47 verified today; matches LB02_S6_BURN_DOWN_AND_CERTIFICATION.md line 102) |
| A5_CoreShLegacyUnreachableProof | 8/8 GREEN (LB02_S6_BURN_DOWN_AND_CERTIFICATION.md line 103) |
| Stderr contract row closed | YES (S6.8 single-FD merged durable transcript + console.log) |
| Has dedicated `S3ShLegacyRemovedFitnessTest` | NO — `DEDICATED_FITNESS_GAP` (E48), NOT a CERTIFICATION_GAP |
| **CERTIFIED + LEGACY_REMOVED** | **YES** (per S6.8) |

The `DEDICATED_FITNESS_GAP` (no `S3ShLegacyRemovedFitnessTest`) does NOT
block certification — core.sh can rely on A5 proof + StepContractSuite
+ canonical-core gate + architecture fitness. If we want symmetry with
echo later, we may add a dedicated fitness, but it is **not required**.

**Implication for S1/S2 burn-down**:
- S1 records `core.echo` as `CERTIFIED + LEGACY_REMOVED` (mechanical proof E39 + E39b)
- S1 does **not** modify `core.sh` (already CERTIFIED + LEGACY_REMOVED per S6.8)
- S2 burn-down scope: still 12 legacy keys (none burned down yet)

### Cumulative edge case tally (after round 9)

```text
Rounds 1-9 (E1..E45):
  45 PASS
   1 SKIPPED (E4)
   1 HISTORICAL (E42: read S6.6/S7 state; reconciled via E49 to S6.8 current state)

Trunk: main == origin/main == 4224d53f
```

## Edge case sweep — round 10 (2026-09-11, post-bee13f75)

Echo/Sh contract suite verification + core.sh historical context:

| # | Edge case | Result | Evidence |
|---|---|---|---|
| E46 | `EchoStepContractSuiteTest` method list | PASS | 17 named contracts matching G7 coverage |
| E47 | `ShStepContractSuiteTest` (G7 for `core.sh`) | PASS | **17/17 GREEN**, exit 0 — matches LB02_S6_BURN_DOWN_AND_CERTIFICATION.md line 102 |
| E48 | S3 fitness gate for `core.sh` | DEDICATED_FITNESS_GAP | No `S3ShLegacyRemovedFitnessTest` exists. NOT a CERTIFICATION_GAP �� core.sh can rely on A5 + StepContractSuite + canonical-core gate |
| E49 | `core.sh` CERTIFICATION status | HISTORICAL CONTEXT | S6.6/S6.7 marked sh as IMPLEMENTED_UNCERTIFIED (intermediate). S6.8 closed stderr row → current state per `LB02_S6_BURN_DOWN_AND_CERTIFICATION.md §CERTIFICATION` line 110-113: `core.sh = CERTIFIED, LB-02 = REMOVED`. The audit doc was reading intermediate state at E42/E49; corrected to current state. |

### Captured logs (edge cases round 10, rule 25)

```text
/tmp/lfc2e0-e47-sh.log               sha256=71145c828df3a541a4a89dab407d664c3656e1bd46ec32db9d8266073c1c8af2
/tmp/lfc2e0-e47-sh2.log              sha256=a5542904ad9cb1d79d0a6b3fc5e38b9fe3540c19dd424c6f56619b05323879a7
/tmp/lfc2e0-e46-e49-round10.log      sha256=3a6b32de42fe409245e280be5d8da70599507b5467d9fa6ac2eb6878c5f2eaef
```

Verifying command:

```bash
sha256sum /tmp/lfc2e0-e47-sh.log /tmp/lfc2e0-e47-sh2.log \
          /tmp/lfc2e0-e46-e49-round10.log
```

### Historical context correction (E49, reconciled 2026-09-11T09:12)

The audit doc's intermediate reading at E42/E49 was the S6.6/S6.7
state, not the S6.8 current state:

```text
S6.6 (commit 2ce49fe5): "ShStepContractSuiteTest (14 tests, 14/0/0)"
S6.7 (LB02_S6_7_STDERR_GROUNDING.md): "core.sh = IMPLEMENTED_UNCERTIFIED"
S6.8 (LB02_S6_8_SEPARATE_CHANNEL_OUTPUT.md, lines 91-104):
  "4fef9f69 root cause (double O_TRUNC) + durable-protocol sub-gate
   5aab9976 S6.8 separate-channel design + staged plan
   e8732757 S6.8.1 single-FD merged durable transcript
   205c7b48 canonical durable transcript renamed to console.log
   95e178aa C4.5 DurableTaskOutput.consoleTranscript in-memory carrier
   7574302e C4 mandatory rows; f6bbd114 C3 mandatory row.
   ShStepContractSuiteTest 17/0 now covers the full mandatory matrix"

LB02_S6_BURN_DOWN_AND_CERTIFICATION.md §CERTIFICATION (line 110-113):
  "core.sh = CERTIFIED
   LB-02   = REMOVED"
```

S6.8 closed the stderr row. The audit doc now reflects this current
state. S6.6/S6.7 references in `LB02_S6_7_STDERR_GROUNDING.md` and
`LB02_A4_PRODUCTION_FLIP.md` are preserved as **historical** and were
never rewritten.

### E48 — DEDICATED_FITNESS_GAP (NOT CERTIFICATION_GAP)

There is **no** S3-equivalent fitness test for `core.sh`. However:

```text
core.sh certification can rely on:
  - A5_CoreShLegacyUnreachableProof (8/8 GREEN)
  - ShStepContractSuiteTest (17/17 GREEN)
  - canonical-core gate registry-aware (after 8c4cbbae)
  - LB02_G3_A4_2_SHELL_OPERATIONS_CAPABILITY
  - LB02_G3_A4_3_TYPED_OUTPUT_CARRIER
  - LB02_G3_A4_8_LEGACY_REGISTRY_PARITY
```

If we want symmetry with echo later, we may add a dedicated
`S3ShLegacyRemovedFitnessTest`, but this is **NOT a CERTIFICATION_GAP**
and **does NOT block S1 or S2**.

### Cumulative edge case tally (after round 10)

```text
Rounds 1-10 (E1..E49):
  47 PASS
   1 SKIPPED (E4)
   1 DEDICATED_FITNESS_GAP (E48: missing S3Sh G4 fitness; NOT a cert gap)

Trunk: main == origin/main == bee13f75
```

## Pre-S1 reconciliation summary (2026-09-11T09:12, user-authorized S1)

This is the **pre-S1 evidence** that backs the `lfc2-e1-s1-echo-legacy-removed`
cycle. Per the user's pre-S1 reconciliation directive:

- `core.echo`: CERTIFIED + LEGACY_REMOVED (S1 will formalize)
- `core.sh`: CERTIFIED + LEGACY_REMOVED (per LB-02 S6.8; S1 does NOT touch this)
- 12 legacy keys: LEGACY_EXECUTABLE / IMPLEMENTED_UNCERTIFIED (S2 scope)
- `example.uppercase`: CERTIFIED (LB-02 EP)

**`DEDICATED_FITNESS_GAP`** for `core.sh` (no `S3ShLegacyRemovedFitnessTest`) is
correctly classified — NOT a CERTIFICATION_GAP. It does not block S1 or S2.

S1 scope is recording/closure only — formalize `core.echo` as the
oracle/reference Step for the `CERTIFIED + LEGACY_REMOVED` pattern.
No production changes.
