# RP7-SEM S3.0 — PURE_BUILDER purity fitness, and three findings it surfaced

```yaml
id: RP7-SEM-S3.0
status: IMPLEMENTED_UNCERTIFIED
base_sha: 1740ed5d4aece02e3837685a4edd788ac3c521b8
head_sha: 78e32af3593416558e1971f689338897c8cc49d5
source_tree_sha: 1740ed5d4aece02e3837685a4edd788ac3c521b8
artifact: v2/pipeline-application/build/install/pipelinek
scope: Semantic Constitution law 3 (PURE_BUILDER purity), four fitness layers
declaration: IMPLEMENTED_UNCERTIFIED, NOT CERTIFIED — see "Why not CERTIFIED"
checks:
  - id: S3-0-CONSTRUCTION
    command: >-
      cd v2 && ./gradlew :pipeline-scripting-api:test
      --tests 'dev.rubentxu.pipeline.v2.dsl.FArchS3PureBuilderConstructionPurityTest' --rerun-tasks
    exit_code: 0
    xml: 3 tests, 0 failures, 0 errors, 0 skipped
    evidence: >-
      Run UNCACHED. A prior run of the same selection reported FROM-CACHE and was
      discarded rather than trusted; a stale Gradle invocation holding the checkout
      lock had to be killed first, which is why --rerun-tasks is recorded here.
    result: PASS
  - id: S3-0-CONTAINMENT
    command: >-
      cd v2 && ./gradlew :pipeline-architecture-tests:test
      --tests 'dev.rubentxu.pipeline.v2.architecture.FArchS3PureBuilderPurityTest' --rerun-tasks
    exit_code: 0
    xml: 4 tests + 2 ViolationFixture, 0 failures, 0 errors, 0 skipped
    evidence: >-
      pipeline-scripting-api/build.gradle.kts declares exactly one project dependency,
      :pipeline-domain, and its sources import no effect package. Both scanner rules
      are paired with a violation fixture that proves the scanner can fail.
    result: PASS
  - id: S3-0-SIGNATURE
    command: within S3-0-CONTAINMENT
    exit_code: 0
    xml: 1 of the 4 tests above
    evidence: >-
      Driven from the manifest's Construct+Category columns, so a builder reclassified
      away from PURE_BUILDER stops being checked here and FArchS0SurfaceManifestTest
      fails instead. A non-vacuity test asserts the resolved row set is non-empty.
    result: PASS
  - id: S3-0-RUNTIME-WITNESS
    command: >-
      cd v2 && ./gradlew :pipeline-application:test
      --tests 'dev.rubentxu.pipeline.v2.application.FArchS3PureBuilderPurityWitnessTest'
    exit_code: 0
    xml: 4 tests, 0 failures, 0 errors, 0 skipped
    evidence: >-
      Runs real pipelines through installDist. 0 SKIPPED is load-bearing: the class is
      gated on @EnabledIf("isRealHostAvailable"), so a missing binary would report a
      vacuous pass. All four ran.
    result: PASS, with a known blind spot — see "The mutation that survived"
  - id: MUTATION-M-S3-1
    command: >-
      scmGit(..) mutated to call echo("MUTACION") before returning its carrier;
      targeted tests run with the mutation applied, then reverted from HEAD
    exit_code: 1 (expected — the construction layer must be RED)
    xml: FArchS3PureBuilderConstructionPurityTest 3 tests, 2 failed
    evidence: >-
      "a consumed pure builder contributes no step of its own": expected 1 step, was 2
      ([Echo, Checkout]). "a discarded pure builder appends nothing": expected [], was
      [Echo(text=MUTACION)]. Control "an emitting step is visible" stayed GREEN.
      The RUNTIME witness stayed GREEN on the same mutation — that is the finding,
      not a pass. Reverted with git restore --source=HEAD; git diff for the file is
      empty and no MUTACION marker remains in any source.
    result: PASS (mutation KILLED by the construction layer, then reverted)
  - id: FULL-LOCAL-GATE
    command: cd v2 && ./gradlew check --rerun-tasks
    exit_code: NOT_RUN for this slice
    evidence: >-
      The last full gate ran at 1740ed5d, BEFORE these tests existed. The S3 train
      runs one full gate at S3 close (RP7-SEM S3.4), so the 13 new tests have been
      verified only in targeted, uncached selection. They are NOT covered by any
      complete-suite run yet.
    result: NOT_RUN
known_failures: []
coverage: >-
  Not measured for the new tests. The modules touched are pipeline-scripting-api,
  pipeline-architecture-tests and pipeline-application; only a test helper
  (PureBuilderProbe) changed in main-test code. No production source changed in S3.0.
```

## What S3.0 was for, and what it actually cost to get right

Semantic Constitution law 3 says a configuration builder "MUST NOT append a
Step, emit an event, acquire a capability or perform I/O". The previous session
recorded that only the consumption half had a test, and named this the one real
gap. It was a real gap, but the obvious way to close it does not work, and the
reason is worth more than the test that replaced it.

The first implementation was a runtime witness: run real pipelines through the
installed distribution and assert that a pure builder emits no event and executes
no step. To check that this was not a green light wired to nothing, the obvious
move is a mutation — make `scmGit(..)` call `echo(..)` before returning its
carrier, which is exactly "a builder that appends a Step". The witness **stayed
green**.

Not by luck. Two independent gates refuse the run before anything executes:

- a **discarded** carrier is refused at script construction by the MUST_CONSUME
  gate (S0-C1);
- a **consumed** carrier is refused at the canonical bridge, because
  `core.checkout` has no plugin registration today (S0-B, tracked separately).

In both cases an appended step can never run, never succeed and never appear in
the timeline. The witness was measuring "no effect was *observed*", and a
build-time refusal guarantees that for a good builder and a bad one alike. It
proved the gate fires, not that the builder is pure.

So purity is observed one layer earlier, where the impurity is still present in
the data rather than hidden behind a refusal.

| Layer | Claim | Mechanism | Mutation M-S3-1 |
|---|---|---|---|
| Construction | a pure builder contributes no `StepSpec` to the stage it builds | reads the built `StageSpec.steps` directly | **RED** |
| Containment | the module hosting every `PURE_BUILDER` cannot reach effect machinery | import scan + dependency assertion | n/a (structural) |
| Signature | no `PURE_BUILDER` names an effect-bearing type | reflection over manifest rows | n/a (structural) |
| Runtime | no SCM / credential / network event on the installed binary | event-log classification | **GREEN — blind spot** |

The containment layer is the load-bearing structural claim and the cheapest.
Every `PURE_BUILDER` the manifest can name is declared in
`pipeline-scripting-api`, and that module's only compile dependency is
`:pipeline-domain`. It therefore has no `EventSink`, no capability access, no
journal and no process adapter **on its classpath at all** — a builder living
there cannot emit an event or acquire a capability, because there is nothing to
call. That is worth more than a per-method signature check, which would only
re-prove the same thing for the methods somebody remembered to write down. The
signature layer is kept as defence in depth: if the module ever gains a
dependency, containment fails loudly and signature still holds the line.

The runtime layer is kept despite its blind spot, because it fails on a
different defect: a builder that reaches the effect boundary by some route
construction cannot see. Neither layer subsumes the other.

## Two defects the new tests found in existing test infrastructure

**A silently disabled scanner rule.** `SourceScanner.findForbiddenImportPrefixes`
matches `fqcn == prefix || fqcn.startsWith("$prefix.")`. A prefix written
`"java.net."` is therefore tested as `"java.net.."` and can never match anything.
The rule would have reported "no network imports in the DSL module" forever,
which is indistinguishable from correct. It was caught only because the
containment rule ships with a violation fixture that writes a synthetic
`import java.net.Socket` and asserts the scanner finds it. That fixture is now
part of this slice, and it is the reason the rule is trusted.

**A diagnostic window too small to contain the fact.** The first witness
asserted against `PureBuilderProbe.summary`, which truncates at 600 characters.
The installed distribution emits a block of JVM `sun.misc.Unsafe` warnings on
stderr before any pipeline diagnostic, so the truncated window did not contain
the sentence distinguishing "refused for a missing capability" from "refused
because the plugin key is not registered" — and both present as `exit != 0`
with zero events, so only the text separates them. The probe now carries
untruncated `diagnostics` alongside the readable `summary`.

## Why this is IMPLEMENTED_UNCERTIFIED, not CERTIFIED

Certification requires evidence bound to the exact candidate SHA (Semantic
Constitution §10). No full gate has run since these tests were added: the last
complete run was at `1740ed5d`, which predates them. They are verified only by
targeted, uncached selection. Recording `CERTIFIED` on that basis would repeat
the mistake the mutation just exposed — accepting a measurement that did not
discriminate. S3.4 runs the gate that promotes this slice.

## Findings recorded here rather than left in a closeout file

These three were found while reconciling state at the start of this slice. They
are open, and none of them is fixed by S3.0.

### F1 — `AgentResolved` is a declared event with no producer

`AgentResolved` exists in `DomainEvent.kt:168`, is decoded in
`JsonEventLog.kt:178`, projected in `EnvelopeProjector`, sequenced in
`SequenceAssigner`, and is enumerated by `FArchL7DomainEventExhaustivityTest`.
**Nothing constructs it**, because `agent(..)` is an
`UNSUPPORTED_FAIL_CLOSED` stub that throws. `UatDsl001JenkinsFamiliarityTest:90`
says so explicitly and declines to assert it.

The shape of the orphan is what makes it a defect rather than a stub: the event
is wired end to end for everything a *reader* needs — schema, decoder,
projection, sequencing, identity — and the single missing piece is the
*writer*. A consumer that pattern-matches on it will compile, will be covered
by the exhaustiveness fitness, and will never see one.

This is the **same defect class S0-B found in `TimeoutTriggered`**: an event that
is fully wired into the vocabulary, the codec, the store, the sequence assigner
and the identity projector, with no producer, so a run that "resolves an agent"
is indistinguishable from a run that never mentioned one. Under the Semantic
Constitution that is a declared-but-never-interpreted construct.

It also makes S3.1 sharper than the proposal document implies. The event
contract is already built; what is missing is the producer. S3.1 must either
give `agent` a real vertical that emits it, or the declaration must be removed.
Leaving it is not an option, and the current state is a latent lie of the exact
kind the Semantic Honesty Gate exists to remove.

### F2 — `v2/pipeline-protocol` is orphaned and has never been tested

The directory exists with two test files
(`GoldenBinaryCompatibilityTest.kt`, `ProtocolCoreTest.kt`) and a
`build.gradle.kts`, but it is **not listed in `v2/settings.gradle.kts`**. It has
no `build/` directory at any depth, so it has never been part of a Gradle build
and its tests have never run in any gate, ever. This is a repository defect,
not a policy breach by the HTTP work.

It is not the explanation for the suite-count delta below — the module never
contributed a suite in either run — but it means the count reconciliation has to
be treated as genuinely open rather than attributed to a known cause.

### F3 — the full-gate suite count does not reconcile across two runs

| Run | SHA | Suites | Tests | Fail | Err | Skip |
|---|---|---|---|---|---|---|
| WU-093 receipt | `530ffa98` | 673 | 4426 | 0 | 0 | 140 |
| This gate | `1740ed5d` | 672 | 4425 | 0 | 0 | 140 |

The `v2/` tree is byte-identical between the two SHAs:
`git diff 530ffa98..1740ed5d -- v2/` is empty, digest
`e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855`
(the SHA-256 of zero bytes). The eight files that differ are documentation plus
`.agent/scripts/regenerate_step_inventory.py`.

> **Path note (2026-10-03).** The generator was later moved to
> `scripts/regenerate-step-inventory.py` by the RP7-SEM S3 governance WU, which
> is the only entry in this list that is NOT a pure documentation change. The
> statement above is left as written because it was true at the SHA it
> describes.

The delta is one suite and one test, with identical failure, error and skip
totals. **Attribution: UNRESOLVED.** What was checked and ruled out:

- `v2/pipeline-protocol` (F2) — never built, so it cannot explain either run.
- Class-level conditions. Three `scm-git` classes are gated on
  `@EnabledIfEnvironmentVariable(V2_GIT_AVAILABLE=true)`; the variable is unset,
  but a disabled-by-condition JUnit class still emits an XML with its tests
  counted as skipped, and the skip total is identical in both runs.
- Stale results. All 672 XML files present now were written by this run, so the
  earlier total cannot be reproduced from anything left on disk.

The honest position is that this gate measured 672/4425 and that is what is
recorded. The older 673/4426 was **not** inherited, and the difference was not
rounded away. It is worth noting that the same discipline error this slice was
built to prevent — accepting a number that did not discriminate — is what a
fabricated suite count would have been.

## Gate evidence from this session, recorded because it is not in any receipt

The full gate at `1740ed5d` backs the `tests-pass` and `policy-compliant`
receipts on SDDK cycle `p-1f3622e11c093341/rp6c-http-request`. Those receipts
live in the SDDK ledger, not in this repository, so the raw figures are recorded
here while the cycle is still open.

- `cd v2 && ./gradlew check --rerun-tasks`, exit 0, BUILD SUCCESSFUL in 25m 43s
- 289/289 actionable tasks executed; log digest
  `sha256:2bfa0e5e7bce592435120256f15f1d4d8e9dc9b90eb9743bc38081c84795b314`
- 672 suites, 4425 tests, 0 failures, 0 errors, 140 skipped
- 0 kover rule violations
- H8 installed ladder: `HttpInstalledUatTest` 16/0/0/0, 132.222s,
  `sha256:525b9ffb919f5e0f033e7117d90511d4e4b2f12965cbb4307f4f44029b67303b`
- Architecture fitness: 81 suites, 387 tests, 0 failures,
  `sha256:aa885ea054958990cc84633952f36f96352e85ecc21aaa1de43c18395c7a0957`
- S0 manifest fitness: 10/0/0/0,
  `sha256:c30460d26e0928e105bdc397efd190bb0e3e9d30daded5eb00abd55dda959789`
- CI: `NOT_AVAILABLE`. No remote CI surface exists; commit `754ddda0` retired all
  four GitHub Actions workflows on 2026-09-30, `.github/workflows/` is empty, and
  `gh run list` returns only Dependabot jobs. Never recorded as PASS. See
  AGENTS.md item 7 and `CERTIFICATION_PROTOCOL.md` §4, which keep STEP-CERT (no
  remote CI required) separate from PRODUCT-GATE (requires it, `BLOCKED_EXTERNAL`).

### The rp6c-http-request cycle cannot be closed, and why

`phase.verify.complete` declares four gates. Two are now recorded PASSED with
full evidence. The other two cannot be evaluated in this SDDK build:

> `debt detection is not implemented in this build, so there is no report to read
> and no gate to evaluate. This command previously wrote a report for a hard-coded
> cycle that did not belong to the caller, with no findings, and the debt gates
> answered PASS over that fabricated report. That made the verdict independent of
> the project it claimed to check.`

The cycle is therefore left **OPEN in `verify`** with the lease released, per
the AGENTS.md convention that an interruption preserves OPEN state. This is a
toolchain capability gap, not a WU-093 defect: WU-093 is certified at STEP-CERT
on `530ffa98` and the open items it carries are recorded in
`WU093_HTTP_IMPLEMENTATION_RECEIPT.md`.
