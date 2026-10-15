# UAT Gate Classification — pre-existing red tests

Status: **CLASSIFICATION ONLY. Nothing here was made to pass, deleted, or disabled.**

Base SHA: `3ee1bad8` (`main`) · Date: 2026-10-15
Last refresh: 2026-10-15 — added rows 11-15 and §7, covering the five findings
of the external harness's `v0.61.0-rc2` diagnostic note. Three resolved with
evidence (two of them by overturning the note's diagnosis), one closed as not a
product defect, one escalated as a contract decision. No PRODUCT-GATE
re-evaluation; still `BLOCKED_EXTERNAL`.

## Why this document exists

Eight UATs in `:pipeline-application` and two pre-existing `@Disabled` rows
in `:pipeline-output-store` (`SegmentOutputStoreTest` OUT-01 / OUT-02) have
been red across at least eight consecutive candidates. They were carried in a
"Known issues (carried over, not introduced by this candidate)" bullet of the
handoff, unchanged, at `evidence/0-60-rc2/HANDOFF-TO-CERTIFIER.md` through
`evidence/0-61-rc1/HANDOFF-TO-CERTIFIER.md`. The two `SegmentOutputStoreTest`
rows had no previous classification in this document; they were tracked in a
working note (`WIP4_OUT01_OUT02_REPRODUCED.md`) but never surfaced as
verdicts. This refresh (2026-10-15) brings them under the same rubric as the
eight.

Carrying a red test forward because it is *old* is not a classification. The
question that actually decides whether a release may ship is **which gate
requires this test**, and that question is answerable from documents this
repository already has. This document answers it, per test.

Two things this document deliberately does **not** do:

- it does not weaken, skip, disable, or delete any of the eight tests;
- it does not fix any product defect it finds. Where a red test looks like a
  genuine product defect rather than a stale expectation, that is recorded in
  §5 and escalated, not silently repaired here.

## The two gates being classified against

From `CERTIFICATION_PROTOCOL.md` §4 ("Dos gates de certificación
independientes"), verbatim:

> **STEP-CERT:** Step completo y ejecutable en el SHA certificado, sin bypass
> del registro, sin regressiones conocidas del perfil, matriz C01..C19 sustentada
> por pruebas REALES y receipt G0..G8.
>
> **PRODUCT-GATE:** todas las UAT obligatorias de PRODUCTION_READY_UAT_MATRIX.md,
> CI real del SHA, cobertura declarada, SAST/dependency scan, seguridad del
> perfil, presupuesto de rendimiento aprobado, distZip reproducible e
> instalación limpia. Un Step-CERT nuevo no vuelve verde el PRODUCT-GATE.

Three properties of §4 decide this whole classification, and each was verified
against the file rather than assumed:

1. **§4 names no test class.** `CERTIFICATION_PROTOCOL.md` contains zero matches
   for a `*Test`/`*Uat` identifier. It names one other document:
   `PRODUCTION_READY_UAT_MATRIX.md`.
2. **`PRODUCTION_READY_UAT_MATRIX.md` names only three test classes**, at rows
   `UAT-RP-019`, `UAT-RP-020`, `UAT-RP-021` (`WURp019GradleRealUatTest`,
   `WURp020MavenRealUatTest`, `WURp021NodeRealUatTest`), each behind an opt-in
   env gate. **None of the eight red classes appears in that matrix.**
3. **§6** ("Un fallo P0, un caso de aislamiento inseguro o una prueba
   obligatoria ausente impide certificar") is the clause that makes an
   unclassified red test dangerous. "Obligatoria" is therefore the whole
   question, and it is answerable.

A test required by neither gate is not thereby useless — it is **non-blocking**,
and the reason it may stay red is that nothing normative says it must be green
today. That is a weaker statement than "it is fine", and it is the strongest
statement the evidence supports.

### Which gate is currently in force

**PRODUCT-GATE is `BLOCKED_EXTERNAL` and cannot be evaluated in this repository
at all.** From `AGENTS.md` (Política de verificación, 2026-10-03):

> No existe integración continua remota en este repositorio: `754ddda0`
> ("chore(ci): remove dead GitHub Actions workflows", 2026-09-30) retiró
> `lpr0-ci.yml`, `release.yml`, `v2-baseline.yml` y `sdkman-publish.yml` […]
> `.github/workflows/` está vacío y `gh run list` sólo devuelve jobs de
> Dependabot, que no verifican este código.

and:

> **"CI verde" no es una evidencia disponible aquí.**

Repeated, consistently, at `docs/v2/05-roadmap/ROADMAP.md:253-254`,
`docs/v2/05-roadmap/P0_RELEASE_RECONCILIATION.md:82-83`,
`docs/v2/04-adrs/ADR-0105-admission-check-publication-authority.md:46,132-134`,
`evidence/0-61-rc1/HANDOFF-TO-CERTIFIER.md:164`.

This has a direct consequence for the classification and it is the single most
important row in it:

> Because PRODUCT-GATE demands "CI real del SHA" and no CI exists, **no red UAT
> can be promoted or demoted by appealing to PRODUCT-GATE.** PRODUCT-GATE is
> already blocked on a condition unrelated to these eight tests. A red UAT does
> not block a release by failing PRODUCT-GATE, because PRODUCT-GATE cannot pass
> for any candidate.

Therefore the only gate with live teeth for this question is **STEP-CERT**, and
a STEP-CERT verdict on any of these eight requires a *Step* certification whose
C01..C19 matrix is sustained by that test. Where the test asserts a Step
contract, it is BLOCKING. Where it asserts something else — an architecture
law, a characterisation of a known defect, a corpus fixture, a security
property — the honest answer is that **no currently-in-force gate requires it
green today**, and it is non-blocking with a named owner.

## The classification

| # | Test class | Gate that requires it | Required by (file:section) | Gate in force? | Current state | Verdict |
|---|---|---|---|---|---|---|
| 1 | `ExternalPluginFourFamilyAdmissionTest` | **STEP-CERT** | `CERTIFICATION_PROTOCOL.md` §4 (STEP-CERT: "sin bypass del registro") + §3 rows C05/C08; `AGENTS.md` STEP CONSTITUTION §6 "Fail-closed admission is registry-driven" | Yes | RED. Fail-closed admission of an external JAR contributing Step+Directive+Event+Capability is not observed to hold | **BLOCKING** — see §5.1, likely a genuine product defect |
| 2 | `Lpr011r2SecretRedactionAtRestUatTest` | **PRODUCT-GATE** (security) + STEP-CERT C15 | `CERTIFICATION_PROTOCOL.md` §4 (PRODUCT-GATE "seguridad del perfil") + §3 row C15; maps to `PRODUCTION_READY_UAT_MATRIX.md` row `UAT-RP-015` (Secretos) | PRODUCT-GATE **NO** (`BLOCKED_EXTERNAL`). STEP-CERT yes only if a Step's certification claims the secret-handling contract through this suite | RED | **BLOCKING** for any release that claims a secret-handling contract; otherwise **NON-BLOCKING** under `BLOCKED_EXTERNAL`. See §5.2 |
| 3 | `Lfc2WaitUntilCanonicalReentryFitnessTest` | **STEP-CERT** | `CERTIFICATION_PROTOCOL.md` §4 (STEP-CERT: "sin bypass del registro", C01..C19) + §3 rows C06, C07, C14. Asserts `core.waitUntil` is registry-primary | Yes | RED. The suite's own KDoc declares its condition-evaluating loop is **deferred to WU-LPR-302** and the handler "emits a single polled/completed pair" | **BLOCKING** for the `core.waitUntil` STEP-CERT only. **NON-BLOCKING** for a release that does not certify `core.waitUntil` at this SHA. See §5.3 |
| 4 | `FArchS3PureBuilderPurityWitnessTest` | **STEP-CERT** (architecture half of a Step law) | `CERTIFICATION_PROTOCOL.md` §4 (STEP-CERT "sin bypass del registro") + §2 layer **T2** "fitness dependencia/registry/body-policy"; `AGENTS.md` SEMANTIC CONSTITUTION §3 "Pure builders are referentially transparent" | Yes | RED on the **installed distribution** (`@EnabledIf("isRealHostAvailable")`) | **BLOCKING** for any STEP-CERT that relies on PURE_BUILDER purity. **NON-BLOCKING** for a candidate that certifies no builder-adding Step. See §5.4 |
| 5 | `CompatibilityCorpusTest` | **PRODUCT-GATE** | `CERTIFICATION_PROTOCOL.md` §4 (PRODUCT-GATE "todas las UAT obligatorias de PRODUCTION_READY_UAT_MATRIX.md" + "instalación limpia") + §2 layer **T2** "corpus completo" | PRODUCT-GATE **NO** (`BLOCKED_EXTERNAL`) | RED. ~30 fixtures run against the real CLI produce an EMPTY observed transcript | **NON-BLOCKING** under `BLOCKED_EXTERNAL`, but the single highest-risk row here — see §5.5 |
| 6 | `DirScopeEndToEndTest` | **STEP-CERT** (Step semantics for `dir`) | `CERTIFICATION_PROTOCOL.md` §4 (STEP-CERT "Step completo y ejecutable en el SHA") + §3 rows C09, C16, C18; `AGENTS.md` STEP SEMANTICS §1 (`dir` Jenkins parity) | Yes | RED end-to-end through the installed CLI | **BLOCKING** for a `dir` STEP-CERT. **NON-BLOCKING** for a release that does not certify `dir` at this SHA |
| 7 | `ObsPc2LevelAUatTest` | **STEP-CERT** (output-plane Step semantics) | `CERTIFICATION_PROTOCOL.md` §4 (STEP-CERT "sin regressiones conocidas del perfil") + §3 row C11 | Yes | RED. Guards `returnStdout` non-duplication, pump-flush at child exit, volume throughput, timeout prefix retention | **BLOCKING** for the `core.sh` / output-plane STEP-CERT. **NON-BLOCKING** otherwise. See §5.6 |
| 8 | `ObsAOutputStreamingCharacterisationTest` | **NONE** (characterisation by its own declaration) | Nothing. Its KDoc states "Each row asserts a defect" — it pins a KNOWN-BROKEN behaviour so that fixing it becomes a deliberate inversion | No gate requires a characterisation of a defect to be green | RED **by design** | **DOC-ONLY** — see §5.7 |
| 9 | `SegmentOutputStoreTest` row OUT-01 (line 693) | **NONE** (durability format defect; tracked but not gate-required) | The `safe(runId) + "_"` prefix-based prune is not collision-safe (`run_` collides with `run_x_`'s stream dir). No `CERTIFICATION_PROTOCOL.md` row requires this; AGENTS.md does not cite this defect in STEP-CERT C01..C19; `PRODUCTION_READY_UAT_MATRIX.md` does not name this class | PRODUCT-GATE **NO** (`BLOCKED_EXTERNAL`). STEP-CERT does not require this format invariant | RED. `@Disabled` with reason pointing at this row | **NON-BLOCKING** under `BLOCKED_EXTERNAL`; migration to harness awaits issue `pipelinek-release-harness#<n>` opening per AGENTS.md §2 |
| 10 | `SegmentOutputStoreTest` row OUT-02 (line 754) | **NONE** (durability format defect; tracked but not gate-required) | `safeStreamName` is not injective: `run/abc` and `run_abc` both sanitise to `run_abc`, aliasing on disk. Same shape: no gate cites it | Same as row 9 | RED. `@Disabled` with reason pointing at this row | **NON-BLOCKING** under `BLOCKED_EXTERNAL`; migration to harness awaits `pipelinek-release-harness#<n>`. Root cause is the durable-format migration policy referenced in rows 9 and 10's shared `WIP4_OUT01_OUT02_REPRODUCED.md` |

| 11 | `UatS2R0RunOwnershipCliTest` | **NONE** (harness defect, not a gate property) | The suite exercised run ownership correctly; its own reader was wrong. No gate row cites it | n/a | **GREEN** @`1405b21a` | **RESOLVED.** See §7 |
| 12 | `ObsPc208LevelBUatTest$Falsifiers` row f09 | **NONE** (harness defect) | The falsifier sampled a mid-drain commit and reported it as storage corruption. No gate cites f09 | n/a | **GREEN** @`c3c77fa0` | **RESOLVED.** See §7 |
| 13 | `ObsBJvmDeathOutputRecoveryUatTest` | **NONE** (UAT design question) | No `CERTIFICATION_PROTOCOL.md` row or `PRODUCTION_READY_UAT_MATRIX.md` entry cites it | n/a | **RED, not a product defect.** Diagnosis closed at `3ee1bad8` | **NON-BLOCKING.** See §7 and `OBSB_JVM_DEATH_DIAGNOSIS.md` |
| 14 | `UatEvt001ReplayTest`, `UatEvt002MultiStepReplayTest`, `UatDsl005TimeoutGrammarTest` | **NONE** until the contract decides | The order they assert is promised by no accepted document; the order the product emits satisfies the one promise that exists | **UNDECIDED** | **RED** — escalated, not fixed | **NON-BLOCKING** pending decision. See §7 and `EVENT_TIMELINE_CONTRACT_DRIFT_ESCALATION.md` |
| 15 | `DirectivePluginContractSuiteTest` | **STEP-CERT** (plugin admission) | The digest pin is the identity half; compatibility is the other seven rows, all green | Yes | **GREEN 8/8** @`3ee1bad8` | **RESOLVED.** See §7 |

### Totals

| Verdict | Count | Classes |
|---|---|---|
| **BLOCKING** (a gate in force requires them green) | **3** | `ExternalPluginFourFamilyAdmissionTest`, `FArchS3PureBuilderPurityWitnessTest`, `Lpr011r2SecretRedactionAtRestUatTest`* |
| **BLOCKING-conditional** (required only by the STEP-CERT of a named Step) | **4** | `Lfc2WaitUntilCanonicalReentryFitnessTest` (`core.waitUntil`), `DirScopeEndToEndTest` (`dir`), `ObsPc2LevelAUatTest` (output plane / `core.sh`) — plus `FArchS3PureBuilderPurityWitnessTest` against builder-adding Steps |
| **NON-BLOCKING** (under `BLOCKED_EXTERNAL`; pre-existing defects awaiting harness migration) | **3** | `CompatibilityCorpusTest`, `SegmentOutputStoreTest` OUT-01 (prefix collision), `SegmentOutputStoreTest` OUT-02 (injective identity collapse) |
| **DOC-ONLY** | **1** | `ObsAOutputStreamingCharacterisationTest` |
| **Genuine product defects (escalated, not fixed)** | **2** | §5.1, §5.5; plus the durable-format migration policy the segment-store OUTPUT defect is parked behind |
| **RESOLVED in the rc2 remediation** | **3** | rows 11, 12, 15 — `UatS2R0RunOwnershipCliTest`, `ObsPc208LevelBUatTest$Falsifiers` f09, `DirectivePluginContractSuiteTest` |
| **RED, awaiting a decision rather than a fix** | **2** | rows 13, 14 — `ObsBJvmDeathOutputRecoveryUatTest` (UAT design under a 64 KiB window) and the three event-timeline classes (contract decision) |

\* `Lpr011r2SecretRedactionAtRestUatTest` is counted BLOCKING because a security
property is not made optional by the absence of CI: `CERTIFICATION_PROTOCOL.md`
§6 names "un caso de aislamiento inseguro" as an outright stop condition, and
that clause sits above the gate separation.

## §5 — Findings

### 5.1 `ExternalPluginFourFamilyAdmissionTest` — likely a genuine product defect (ESCALATED, NOT FIXED)

Its own KDoc names the failure mode precisely:

> a cross-check that was **green while proving nothing** […] a check whose green
> comes from the absence of its subject has not run.

It contains `theUnmutatedExternalPluginIsAdmitted` as an explicit
non-vacuity control. A red **non-vacuity control** is not a stale expectation —
it means the suite previously could not detect its subject's absence, which is
the exact defect class AGENTS.md STEP CONSTITUTION §9 ("A new external construct
built from existing shapes MUST require zero semantic changes to core") exists
to prevent.

This is fail-closed admission, which `AGENTS.md` STEP CONSTITUTION §6 makes
mandatory on EVERY run path, and which `CERTIFICATION_PROTOCOL.md` §3 rows
C05/C08 require as real tests. **Verdict: BLOCKING, and it should be treated as
a product defect rather than a stale test.** Not fixed here — out of scope for
this work unit, and per `CERTIFICATION_PROTOCOL.md` §6 it needs root-cause,
reproduction and impact rerun, not a one-line change.

### 5.2 `Lpr011r2SecretRedactionAtRestUatTest` — blocking, and its migration marking is inconsistent

Two separate problems, both recorded rather than fixed:

1. It asserts the law "No unsanitized byte of process output may reach durable
   storage", which is a **security** property → `CERTIFICATION_PROTOCOL.md` §3
   row C15 and §4 PRODUCT-GATE "seguridad del perfil". `AGENTS.md` §6 names "un
   caso de aislamiento inseguro" as a hard stop. It cannot be waved through as
   pre-existing.
2. **Process inconsistency.** `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md:71`
   catalogues it `MIGRA | WU-HARNESS-006`, which per AGENTS.md
   ("Frontera de responsabilidad" rule 2) requires it to carry
   `@Disabled("migrated to harness; see pipelinek-release-harness#<issue>")`. It
   carries **no `@Disabled`**. So the migration was catalogued but never
   performed, and the class is simultaneously "leaving" and "still running".

   Consequence for a certifier: a harness substitute may be assumed to exist and
   covered, while the local row is still red. **Whoever owns WU-HARNESS-006 must
   reconcile this.** Not fixed here: `@Disabled`ing it is explicitly out of scope
   for this task and would also hide a blocking security row.

### 5.3 `Lfc2WaitUntilCanonicalReentryFitnessTest` — red against a DEFERRED contract

Its KDoc states its own scope boundary:

> Out of scope: the actual condition-evaluating loop (RepeatUntil policy) is
> deferred to WU-LPR-302 […] the handler emits a single polled/completed pair.

So this row is red either because the registry routing is wrong (a defect) or
because the test was written against the post-WU-LPR-302 contract before
WU-LPR-302 exists (a premature test). Those require opposite responses and the
document cannot tell them apart from the KDoc alone. **A named owner must
determine which**, and until then the honest verdict is: required by the
`core.waitUntil` STEP-CERT, not required by any release that does not certify
that Step.

### 5.4 `FArchS3PureBuilderPurityWitnessTest` — red on the installed distribution, not in-process

`@EnabledIf("isRealHostAvailable")`, and it exercises `installDist`. Its KDoc is
explicit about why the in-process variant is insufficient:

> a classpath is not a behaviour […] A purity fitness without that middle test is
> the classic vacuous green.

The structural sibling `FArchS3PureBuilderPurityTest` (in
`:pipeline-architecture-tests`) covers the source-level half. A red witness on
the real binary with a green structural test is the signature of a
**behaviour/runtime defect that the structural check cannot see** — the same
"green while proving nothing" class as §5.1, one layer up. Treated as
BLOCKING for any STEP-CERT relying on builder purity.

### 5.5 `CompatibilityCorpusTest` — the highest-risk NON-BLOCKING row (ESCALATED)

Classified NON-BLOCKING only because PRODUCT-GATE is `BLOCKED_EXTERNAL` and
`CompatibilityCorpusTest` is not named in the normative matrix. That is a
procedural fact, not a safety judgement, and it should not be read as one.

The suite's own inner KDoc describes the failure as an **empty observed string
with the bytes present elsewhere** ("their `sh` transcript sits in the plane"),
and then draws the conclusion this document is making:

> the daily gate passes `-PexcludeSlowTests`, which is read by PRESENCE […]
> these fixtures were the first thing the release gate ran, and the first thing
> the release gate found RED. **A gate that only exercises a suite when someone
> remembers to is not the gate it claims to be.**

Independently verified in this tree: the `release-scale` exclusion in
`v2/pipeline-application/build.gradle.kts` is gated on
`providers.gradleProperty("excludeSlowTests").isPresent`, that property is
**not** set in `v2/gradle.properties`, and it appears nowhere in `justfile` or
`ci/`. So `CompatibilityCorpusTest` **does run** inside `./gradlew check`.

`AGENTS.md` (Responsabilidad, rule 3) keeps `CompatCorpusTest*` here — but only
"la parte estructural, no ejecución". As written, `CompatibilityCorpusTest`
runs the binary, so it sits in **neither** bucket: not "stays structural" and not
the catalogued `MIGRA` set. That classification gap needs an owner.

**Treated as a possible genuine product defect** (a whole corpus reading an empty
transcript, where the bytes demonstrably exist in the Output Plane, is a
read-path defect — not a stale expectation). Not fixed here.

### 5.6 `ObsPc2LevelAUatTest` — guards correct-today behaviour, which is exactly why a red is serious

> these behaviours are correct today, so every […] both are the kind of property
> that a plausible later change breaks.

and

> a test that truncated the transcript to zero passes the suite today. **That is
> the worst kind of green.**

A red here is not a held-in-place defect (contrast `ObsAOutputStreamingCharacterisationTest`,
whose rows deliberately assert a defect). It means a property believed correct is
**not** correct. Treated as BLOCKING for the output-plane STEP-CERT.

Note the contrast with row 8, which is the reason the two are classified
differently: `ObsA` rows assert a defect by design and are pinned green-then-red;
`ObsPc2LevelA` rows assert correctness and are expected green. Only one of those
two shapes is safe to carry.

### 5.7 `ObsAOutputStreamingCharacterisationTest` — DOC-ONLY, by its own declaration

> **Each row asserts a defect.** They are written so the defect is named […]
> Each row's message names the work that closes it. When OBS-B or OBS-E land […]
> INVERTED and becomes a non-regression test; **that transition is explicit in
> […] than a silent rewrite of what was expected.**

This is a characterisation, not a certification. Under HARNESS FIDELITY LAW §1
it is `model`/`spike` in kind: it pins measured behaviour so a future fix must be
a deliberate inversion. No gate in force requires a characterisation of a known
defect to be green. **DOC-ONLY.** It must not be "fixed" by deleting or
weakening it — that would destroy the baseline the inversion depends on.

## §6 — Evidence used

- `docs/v2/07-uat/CERTIFICATION_PROTOCOL.md` §2 (T0..T5 layers), §3 (C01..C19),
  **§4 (the two gates — the basis of every row above)**, §6 (STOP conditions).
- `docs/v2/07-uat/PRODUCTION_READY_UAT_MATRIX.md` table rows `UAT-RP-001`..`027`
  (only 019/020/021 name a test class), "Criterio de obligatoriedad"
  (001–024 mandatory for the local profile).
- `docs/v2/08-production-readiness/CURRENT_UAT_STATUS.md` — **stale**
  (generated 2026-09-27, `git log HEAD 69368bc`) and contains **no `FAIL_PROVEN`
  and no `BLOCKED` row**, while `UAT-RP-015` is marked `COVERED` citing the red
  `Lpr011r2Secret…`. Regenerating it is a separate task; this document does not
  claim it as current.
- `AGENTS.md` — "Política de verificación (2026-10-03)" (PRODUCT-GATE
  `BLOCKED_EXTERNAL`), STEP CONSTITUTION §3/§6/§9, SEMANTIC CONSTITUTION §3,
  HARNESS FIDELITY LAW §1, "Frontera de responsabilidad" rules 2–3.
- `evidence/0-60-rc2/HANDOFF-TO-CERTIFIER.md` … `evidence/0-61-rc1/HANDOFF-TO-CERTIFIER.md`
  — the carry-over list this document replaces. **The carry-over, not the
  classification, is what those eight bullets record.**
- `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md:71` — the `MIGRA`
  cataloguing of `Lpr011r2SecretRedactionAtRestUatTest.kt`.
- `v2/pipeline-application/build.gradle.kts` — the `excludeSlowTests`
  presence-gated tag exclusion.
- Per-class KDoc, read in full, for each of the eight.

## §7 — The rc2 remediation findings (2026-10-15)

These five rows come from the external harness's diagnostic note on the
`v0.61.0-rc2` `releaseGate`, not from the eight carried-forward classes above.
Each was re-derived against this worktree before being classified, because the
note's conclusions did not survive contact with the code in three cases out of
six.

| Note's claim | What the evidence showed | Disposition |
|---|---|---|
| CLI run ownership is a **product defect** — the CLI ignores a live lease | `FileBackedRunExecutionLeaseStore` is correct (`:83-90` takes the `FileLock` first and returns `AlreadyOwned`), and `Main.kt:708-717` maps it to exit 2. The suite's own `runIdOf()` used `.trim()` on a **two-line** record, so it locked `lease-<runId>_<corrId>.lock` while the CLI locked `lease-<runId>.lock` — two files, no contention | **Test defect.** Fixed `1405b21a`. RED reproduced on clean main, GREEN after, mutation reverting to `.trim()` reproduces exactly 1 failure |
| Live transcript may expose unsanitized output | No evidence of a leak: the "no raw secret" assertion passed. The suggested fix (`storeForReading`) was tried and **moved the symptom** instead of resolving it, so it was reverted. Recovery is not on the read path — `read` serves from `cur.cmt` (`SegmentOutputStore:320-326`) and `reconcile()` only truncates past the commit | **Not a product defect.** Diagnosis in `OBSB_JVM_DEATH_DIAGNOSIS.md`. The row's own 1 KiB-window premise died with `419f9192` (now 64 KiB) and was never updated |
| Committed extent grows after the agent exits (f09) | Real, and a sampling bug: `awaitNoAgentProcess` returns the first time it sees **no** agent, which is indistinguishable from "the agent has not started". The row measured a mid-drain commit — reproducing the note's exact `extent1=61, extent2=121` | **Test defect.** Fixed `c3c77fa0`. Mutation removing the seal wait reproduces the reported symptom 1:1 |
| `RunCorrelated` before `CompilationStarted` is contract drift | True, and undecidable by the harness: `PipelineIds.kt:33-34` promises only "before the first `RunStarted`". The product complies. The three tests over-assert an intent ("durable spine") no document states | **Escalated** to the events/API owner. `EVENT_TIMELINE_CONTRACT_DRIFT_ESCALATION.md`, with two costed options |
| Plugin JAR digest mismatch | One artifact carried **three** digests: the pin `38b70854…`, the harness's `5ddfaf4d…`, and what this revision actually builds. Reproducible: two independent `--rerun-tasks --no-build-cache` builds both produced `4add6e38…` | **Stale pin, legitimately.** Recertified `3ee1bad8`; 8/8 green |

One item remains from the note and is not yet classified: the `Lpr011r2SecretRedactionAtRestUatTest`
row is row 2 above and is unrelated to these five.

### What "not a product defect" did not cost

The ObsBJvmDeath investigation did not end at "the suggestion did not work". It
established, with a 60 000-case instrumented harness, that the
`StreamingRedactor` ring-full spin it hypothesised is **unreachable**:
`StreamingRedactor.kt:441` refills whenever `inputOffset >= inputLimit &&
!sourceExhausted`, and `InputStream.read` is contractually ≥1 or −1. One
low-severity hardening item remains open — `relieveFullRing` is nested inside
`if (inputOffset < inputLimit)`, so the ring-full escape depends on a JDK
contract the redactor does not own. That is filed as debt, not as a defect.

## §8 — What this document does not establish

- **No fresh failure XML for rows 1-10.** This worktree carries no
  `build/test-results/test/*.xml` for the eight carried-forward classes; they
  were not re-run here. The classification is based on each class's asserted
  contract and on the normative gate documents, **not** on a fresh red at this
  SHA. A per-SHA receipt with argv, XML and digests is still owed before any of
  these is promoted or demoted on evidence rather than on contract.
  **Rows 11-15 are the exception**: each carries a fresh canary-verified XML from
  this session, quoted in §7.
- **`./gradlew check` is not green at this SHA** and cannot be: `detekt` fails
  (see the delivery-quality work item for the four in-scope findings) and the
  corpus runs red. That is stated, not worked around.
- **This is not a certification.** Per `CERTIFICATION_PROTOCOL.md` §1, a
  certification is bound to `{repo, commit SHA, build inputs, artefacto+sha256,
  contract, UAT set and results}`. Nothing here carries that tuple.
- **The PRODUCT-GATE row set is untouched.** Its mandatory UATs
  (`UAT-RP-001`..`024`) are a different question from these eight, and
  `BLOCKED_EXTERNAL` means that question cannot be answered here at all.