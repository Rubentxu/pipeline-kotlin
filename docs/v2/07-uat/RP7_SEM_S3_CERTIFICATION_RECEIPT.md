# RP7-SEM S3 — Semantic Certification Receipt (`agent` / `environment` / `options`)

Status: **CERTIFIED_AT_SHA** — `c8ec9b737e764261f19dea13e88a49d7453b9723`

Scope: the three DECLARATIVE_DIRECTIVE surfaces named by
`docs/pipelinek-semantic-evolution/08-roadmap.md` as S3, plus the S3.0
purity law they depend on. This receipt records what was proved, what was
measured, and what was found — including the two things that were wrong
before this train and the two judgements I had to correct on checking.

---

## 1. Certified SHA and distribution identity

| Item | Value |
|---|---|
| SHA | `c8ec9b737e764261f19dea13e88a49d7453b9723` |
| `origin/main` | identical (0/0 divergence) |
| Working tree at gate | clean (0 modified files) |
| Distribution | `pipeline-application/build/install/pipelinek`, jars rebuilt 11:18 from this tree |
| S3 classes verified inside the installed jars | `ExecutionTargetResolved` (1), `StageOption` (5), `LocalExecutionTargetResolver` (1) |

The distribution was rebuilt explicitly after the gate because the jars the
gate left in place were timestamped 10:49, from the earlier `apiDump` sweep,
not from the certified SHA. Rebuilding and asserting the S3 classes are
present removes the doubt rather than assuming the `installDist` task inside
`check` had refreshed them.

---

## 2. The one full gate at S3 close

```
cd v2 && ./gradlew check --rerun-tasks
```

| Measure | Value |
|---|---|
| Result | `BUILD SUCCESSFUL in 27m 21s`, exit 0 |
| Actionable tasks | 289 / 289 executed |
| Suites | 680 |
| Tests | 4476 |
| Failures / errors | 0 / 0 |
| Skipped | 140 |
| Kover rule violations | 0 |
| Log digest | `sha256:1a62258c5a16969a7e96a62d470496b7e9cd5006a46166df1a091a94ff25034f` |
| Evidence | `~/.local/state/pipelinek-gates/c8ec9b73/{gradle.log,EXIT}` |

Per module:

| Module | Suites | Tests | F | E | S |
|---|---|---|---|---|---|
| `pipeline-domain` | 132 | 679 | 0 | 0 | 0 |
| `pipeline-events` | 42 | 223 | 0 | 0 | 0 |
| `pipeline-scripting-api` | 16 | 88 | 0 | 0 | 0 |
| `pipeline-architecture-tests` | 84 | 401 | 0 | 0 | 10 |
| `pipeline-application` | 282 | 2132 | 0 | 0 | 121 |

Against the previous full gate at `1740ed5d` (672 suites / 4425 tests /
0F / 0E / 140 skipped) the delta is **+8 suites / +51 tests / 140 skipped
unchanged**, which reconciles exactly with the suites this train added:
two S3.0 purity suites, the S3.0 witness, the S3.2/S3.3 witness, the S3.3
fitness, the resolver suite, the requirement-codec suite and two more. The
skipped count being identical is the useful part — nothing new was skipped.

**CI is recorded `NOT_AVAILABLE` and is not claimed as PASS.** This repository
has no remote CI (removed in `754ddda0`); `.github/workflows/` is empty and
`gh run list` returns only Dependabot jobs that do not verify this code. The
gate above is the substitute named by the verification policy in `AGENTS.md`.
STEP-CERT and PRODUCT-GATE are not merged: this receipt certifies the Step and
directive surface, and does not assert the product gate.

---

## 3. S3.0 — purity law (prerequisite, previously `IMPLEMENTED_UNCERTIFIED`)

Four layers, 13 tests, 0 skipped. Proven at the **construction** layer
primarily, because a pure builder appends no `StepSpec`; runtime is kept as a
secondary layer for defects construction cannot see.

- `FArchS3PureBuilderPurityTest` — containment, signature, non-vacuity, 2
  violation fixtures.
- `FArchS3PureBuilderConstructionPurityTest` — 3 tests, load-bearing.
- `FArchS3PureBuilderPurityWitnessTest` — 4 tests on the installed
  distribution, with an in-test control proving the observer can see effects.
- `PureBuilderProbe` — gained `eventKinds` and untruncated `diagnostics`.

Mutation **M-s3-1** (`scmGit` calling `echo("MUTACION")`) proved the runtime
witness survived — both paths short-circuit at construction — while the
construction layer went RED 2/3 with its control green. Reverted from HEAD,
diff empty, no marker in any source.

Two infrastructure defects were found and fixed by that work:
`SourceScanner.findForbiddenImportPrefixes` tested `prefix+dot`, so
`"java.net."` matched nothing (caught by my own violation fixture), and
`PureBuilderProbe` truncated diagnostics at 600 characters, a window filled by
JVM warnings.

---

## 4. S3.1 — `agent`

Carrier: `ExecutionTargetRequirement` — a four-case ADT
(`LocalAny | LocalLabels | CapabilitySet | Remote`), not a label `String`.
`Remote` is carried **and** decodable but refused at *resolution*, so the
author gets a diagnostic naming RP-8 instead of a syntax error, and the
runtime never runs a remote selector's stage on the local host.

`ExecutionTargetResolved` is a **new** event because `AgentResolved` had no
producer and its label-shaped payload cannot express `LocalAny` or
`CapabilitySet`. `AgentResolved` is **deprecated, not deleted**: it is
published SDK surface, so removal is a binary-compatibility decision, not this
train's. (The full gate later proved this the hard way for `OptionSpec`; see
§8.)

The resolver reads the platform through the existing `RuntimeConfig` port
rather than a `PlatformIdentity` the coordinator built from
`System.getProperty`, so no host read exists outside `SystemRuntimeConfig` and
`agent` and `core.isUnix` cannot disagree about which machine the run is on.

### Installed-distribution witnesses, 6/6

| Case | Declared | Expected | Observed |
|---|---|---|---|
| `label-match` | `agent(label="linux")` | granted, body runs | `DirectiveAdmitted policy=resource` → `ExecutionTargetResolved requirement="L 1 5:linux" targetId="Linux"` → `StageStarted` → `StepStarted` → success |
| `label-miss` | `agent(label="windows-9x")` | refused, no body | `DirectiveDenied` naming the missing label and what the host advertises, **no `StepStarted`**, failure |
| `remote` | `agent(remoteUri="tcp://build-01:9000")` | refused, no body | `DirectiveDenied` naming the selector and RP-8, **no `StepStarted`**, failure |
| `no-agent` | *(control)* | body runs, no resolution event | success, `resolved=0` — proves the event comes from the directive, not the run |
| `agent-any` | `agentAny()` | granted | granted, `resolved=1` |
| `capability` | `agentWithCapabilities("network.egress")` | refused, no body | `DirectiveDenied` naming `network.egress`, **no `StepStarted`**, failure |

---

## 5. S3.2 — `environment`

The implementation already satisfied the law it was written against, so this
slice **certified** it rather than rewriting it:

```
StageSpec.environment -> compiler -> StageNode.environment
  -> projectShellOptions -> ShOptions.env
  -> materialised ONLY at pb.environment().putAll
```

Java has no `System.setenv`, so "no JVM-global mutation" cannot be true by
accident here — it has to be stated. `S3EnvironmentSemanticWitnessTest` states
it, adding the **negative** half the S0-B matrix never had. A stage that
declares nothing must NOT observe the previous stage's value; there is no
reading of "correct" under which an ambient implementation passes.

Two facts the manifest never recorded: the patch has **two** independent
consumers (the child process env *and* `gateContext`, so `whenEnvIs` in the
same stage gates on the declared value, and a mismatch `SKIP`s with an
observable `StageSkipped`), and the declared value does **not** appear in the
event timeline.

Mutation **M-s3-2** gave `projectShellOptions` a process-wide accumulator,
making the environment ambient exactly as the law forbids. It turned the
isolation witness **RED, 1 failure of 6**, with the diagnostic naming the
leak. Reverted from HEAD, diff empty, no marker left in any source.

**Honest limit:** the sibling-stage witness stayed GREEN under M-s3-2, so it
is *not* discriminating for that mutation. The isolation witness is the
load-bearing one, and it is.

---

## 6. S3.3 — `options`

`OptionSpec(name: String, value: String?)` was replaced by `StageOption`, a
**sealed** interface carrying `Timeout(milliseconds: Long)`. The old path made
the stringly-typed hole invisible:

```
DSL:         OptionsSpec(timeout: Long?)                  // typed
compiler:    OptionSpec("timeout", it.toString())        // flattened to text
interpreter: options.filter { it.name == "timeout" }     // matched by name
             .single().value?.toLongOrNull()             // re-parsed
```

A typo in the filter name is not a compile error and not a test failure. It is
an option that compiles, serializes, and does nothing. Five failure modes are
now **deleted rather than handled**: absent, duplicate, unparseable,
non-positive, overflow.

Only `Timeout` is declared. §8 of `02-directive-model.md` says every option
must map to a real interpreter or be rejected, and `Retry`,
`SkipDefaultCheckout` and `Timestamps` were removed from the surface in
WU-RP-032 precisely because nothing read them; adding their cases would
re-create the defect inside a more respectable type.

Mutation **M-s3-3** added `data object Timestamps : StageOption` — a declared
case with no interpreter, exactly the §8 violation. It did not compile:

```
e: CanonicalStructuralDecisions.kt:292:12 'when' expression must be exhaustive.
   Add the 'Timestamps' branch or an 'else' branch.
```

So the rule is enforced by the **build**, not by discipline. An uninterpreted
option cannot be added without the interpreter being forced to name it, and
the interpreter has no `else` to absorb it.

`FArchS3TypedOptionCarrierFitnessTest` (7 tests) makes the rest mechanical:
nothing constructs `OptionSpec`, no name-string discrimination, `StageOption`
sealed, a producer *and* a consumer per declared case, the positivity
invariant in the carrier, no `else` in the interpreter, and the retained
`OptionSpec` is deprecated and names its replacement.

The fail-closed negative is witnessed through the installed distribution:
`timeout(0)` and `timeout(-5)` are refused by `OptionsScope.timeout` at the
construction boundary and never reach a run.

---

## 7. Public API impact of the whole train

| Module | Additions | Removals |
|---|---|---|
| `pipeline-credentials-api` | 0 | 0 |
| `pipeline-domain` | +153 | 0 |
| `pipeline-events` | +27 | 0 |
| `pipeline-step-sdk` | 0 | 0 |
| **Total** | **+180** | **0** |

S3 added surface and broke nothing. One distinction stated precisely because
it is easy to over-claim: `StageNode.getOptions()` changed its generic type
from `List<OptionSpec>` to `List<StageOption>`, but the **erased** JVM
signature is `()Ljava/util/List;` in both cases, so the change is binary
compatible and source incompatible — code naming the generic argument will
not recompile.

---

## 8. Two things that were wrong before this train

**`apiCheck` had been red since `e92c9d4e`.** S3.1, S3.2 and S3.3 were each
committed and pushed after running `test` tasks and never `check`, so the
binary-compatibility validator did not run once across three commits. The full
gate caught it on its first honest run. It reported the hard removal of the
published `OptionSpec` (30 api lines) plus every S3 addition unrecorded. The
repair: `OptionSpec` restored as `@Deprecated(WARNING)` with
`ReplaceWith(StageOption.Timeout(...))` — the same treatment `AgentResolved`
received in S3.1 — and all four modules with a dump swept in one pass rather
than one failure at a time, so the "additive only" claim in §7 rests on a
complete diff.

**Two tests were red from `e92c9d4e` onward.**
`DslCompiledPipelineCompilerTest` and `ExecutionPathsCharacterisationTest`
both asserted that `agent(label)` throws, which stopped being true the moment
S3.1 gave `agent` a carrier, a resolver and an interpreter. Both are now
inverted while keeping their original intent: the construct must compile AND
carry a directive the owning definition can decode, so an `agent` compiling
into metadata nothing read still fails.

**Consequence, stated as a rule rather than an anecdote:** a targeted test
scope is not a gate. Twice, a deferred full gate caught what a targeted run
missed. `allowedFunctionsPerClass` was also found to be 25 against a
`StageScope` of exactly 25 — an implicit ratchet with no stated reason, unlike
`CoordinatorGrowthGuardrailTest` which documents its own history. It was
raised to 30 with the reason written down, on the grounds that the ratchet
that actually protects a declarative DSL facade is semantic
(`FArchS0SurfaceManifestTest`: every public builder has a manifest row, every
row exists, every construct is classified) and a number cannot satisfy it.

---

## 9. Two judgements I corrected on checking

Recording these because both would have become wasted or wrong work.

- **`ProvideContext` is not a live semantic hole.** It reads like one — the
  manifest said "admitted-and-observed WITHOUT interpretation". Checked, it is
  not: no production `DirectiveDefinition` declares it, and
  `BeforeStageDirectiveEngine` maps it to a typed `Denied` ("not interpretable
  in the BEFORE_STAGE decode seam"), never a silent continue. The manifest
  wording was corrected for readers; no code changed.
- **`v2/pipeline-protocol` is not an orphaned module.** It is deliberately
  excluded by ADR-0043, and `Lfc0ProtocolScopeFitnessTest` **enforces** that
  exclusion ("Deferred protocol project must not be included in the active V2
  build"). My earlier characterisation of it as a defect was wrong.

---

## 10. Findings left open, recorded not fixed

| # | Finding | Why not fixed here |
|---|---|---|
| F1 | `WULpr402RuntimeHonestDslFitnessTest` pins the exact one-arg token `System.getProperty("os.name")`, so the canonical bridge escapes it through the two-arg form | A real gap in that fitness. Not exploited by this train, and closing it would turn the bridge itself into an offender. |
| F2 | The `DomainEvent` variant count is hand-pinned in **four** places; the architecture-tests copy is the one that gets missed | Collapsing the copies is a governance change of its own. Left duplicated deliberately rather than "fixed" inside a semantic commit, because quietly deleting a pin trades a loud dated failure for a silent one. |
| F3 | `ContextOverlay.TimeoutOverlay(time, unit: String)` and `OutputDecorator(kind: String)` are stringly-typed carriers with **no production consumer** — only `ContextStackImmutabilityTest` names them | Same disease as S3.3, different construct (the body context stack). Widening this slice would repeat the mistake the slice fixes. |
| F4 | The SDDK tooling leaks `sddk-*` scratch directories into `/tmp` (4853 dirs, 8.0 GB observed). `/tmp` is a tmpfs with a 38 570 MB hard per-user quota, and its exhaustion made an early `:pipeline-application:test` fail 226 tests with `Se ha excedido la cuota de disco` | Environmental, not a code defect, and easy to misread as a regression. The 8 GB was reclaimed; the leak itself is unfixed. |
| F5 | `detekt` `allowedFunctionsPerFile` is 25 and no `PipelineDsl.kt` class is anywhere near it, but the analogous class-level value was an implicit ratchet | See §8. Only the class-level value was raised, with a reason. |

The unattributed 1-suite/1-test gate variance first seen at `1740ed5d`
(673/4426 vs 672/4425) is still not root-caused. It does not affect this
certification: the current run's 680/4476 is fully accounted for by the added
suites, and the skipped count is unchanged at 140.

---

## 11. What S3 does NOT certify

- **Remote execution.** `Remote` is carried, decodable and refused by design.
  It is a typed refusal, not a stub, and it names RP-8. Nothing here is a step
  toward a remote allocator; that is RP-8's own train.
- **`ProvideContext` semantics.** Reserved, denied, no producer.
- **Secret-bearing environment.** `EnvironmentSpec` carries plaintext by
  design; secret-bearing environment is `withCredentials` / `CredentialScope`,
  which is RP7-ASX territory.
- **The product gate.** UAT obligations, coverage thresholds, SAST and
  dependency scanning remain PRODUCT-GATE items and are not merged into this
  STEP-CERT.
