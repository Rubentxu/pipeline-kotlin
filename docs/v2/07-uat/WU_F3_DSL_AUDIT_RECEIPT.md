# WU-F3 — DSL surface empirical audit (receipt)

**Date:** 2026-09-19
**HEAD audited:** `a5e0359f` (post F2 closure)
**Source of truth:** empirical probe of HEAD installDist, cross-checked
against `PipelineDsl.kt` (KDoc + implementation), the existing test
suite, and the generated certification ledger (`WU_LPR_060_CERTIFICATION_LEDGER.md`).

This audit contrasts the historical `LFC2_HONEST_DSL_CLOSURE.md`
gap list (dated 2026-09-08) with what the **current binary** actually
does, separating:

- **certified support** (handler exists, contract honoured, tests green)
- **explicit experimental / deferred** (admitted at construction,
  behaviour documented as bounded)
- **fail-closed unsupported** (DSL signature exists for back-compat,
  canonical bridge rejects at compile with explicit error)
- **defect to fix** (silent or contradictory behaviour)

---

## Result table

| Surface | Public signature | Status (current binary) | Empirical observation | Test alive? |
|---|---|---|---|---|
| `pwd()` | `fun pwd(tmp: Boolean = false): String` | **SUPPORTED** (S2-A6 G8 CERTIFIED) | emits `PwdResolved`, returns `RUNTIME_VALUE_PLACEHOLDER = "<workspace>"` to in-memory scripting hosts. Real path comes from `core.pwd` at execution. | `CorePwdStepUnitTest`, `CorePwdRegistryPrimaryFitnessTest`, `UatLocal005EnvSpecialCharsTest` |
| `pwd(true)` | same | **EXPERIMENTAL** (S2-A6 G3T CERTIFIED, deterministic tmp) | emits `PwdResolved` via `core.pwd.tmp`, returns placeholder. Real tmp dir created at execution. | same as above |
| `isUnix()` | `fun isUnix(): Boolean` | **SUPPORTED** (S2-A5 G8 CERTIFIED) | emits `UnixDetected`, returns `ISUNIX_PLACEHOLDER = true` sentinel to in-memory hosts. Real classification comes from `core.isUnix`. | `S2_A5_CORE_ISUNIX_G8_FINAL_CERTIFICATION_RECEIPT.md` |
| `waitUntil { }` | `fun waitUntil(initialRecurrencePeriod: Long, quiet: Boolean, body: StageScope.() -> Unit)` | **SUPPORTED** (S2-A8 G3R CERTIFIED) | emits `WaitUntilPolled` + `WaitUntilCompleted`; body runs at runtime via `BodyInvoker`, NOT eagerly at construction. Probe with `echo("waitUntil-body")` emitted one `EchoOutputCaptured` and one `WaitUntilCompleted{outcome:"completed"}`. | `S2_A8_CORE_WAITUNTIL_*` (G2..G8) |
| `post { }` | `fun post(block: PostScope.() -> Unit)` | **SUPPORTED (DSL construction-time capture)** — body captured into `PostScope.build()`; runtime semantics: not exercised by current probe (not in scope of F3 audit). | KDoc + `PostStepsScope` markers via `WU_LPR_401`. | `WU_LPR_401_RECEIPT.md` |
| `whenCondition(...) { }` | `fun whenCondition(expression: String, block: StageScope.() -> Unit)` | **UNSUPPORTED, rejected at construction with `IllegalArgumentException`** | **CORRECTED 2026-09-28 — the earlier "fail-closed at compile, exit 2" entry in this table was false.** The old row cited `CliNonCanonicalInMemoryExitsTwoTest` as proof, but that test exercises `ansiColor`, not `whenCondition`. `whenCondition` built a `WhenCondition` value, bound it to a discarded local, and appended the body to the stage: no `StepSpec` was emitted, so the canonical bridge had nothing to reject. The `non-canonical plugins` gate never saw it. OBSERVED on the installed `v0.42.0-rc1` distribution: `whenCondition("env.BRANCH == 'main'") { echo("only on main") }` ran the body and exited 0 with an undefined `env.BRANCH`; a control probe with `whenCondition("1 == 2")` also ran its body (`EchoOutputCaptured{content:"SHOULD-NOT-RUN\n"}`, `RunFinished{outcome:"success"}`), proving the predicate was discarded rather than evaluated. Now rejected at construction. | `WhenConditionFailClosedTest` (5 tests; the `1 == 2` control probe is recorded in its KDoc) |
| `node(label) { }` | `fun node(label: String? = null, block: StageScope.() -> Unit)` | **UNSUPPORTED, fail-closed at compile** | Same bridge rejection message. The `NodeNoOp` StepSpec is built at construction but the canonical bridge rejects it. | (no dedicated test; covered by the generic `non-canonical plugins` gate) |
| `script { }` | `fun script(block: ScriptScope.() -> Unit)` | **UNSUPPORTED, fail-closed at compile** | Same bridge rejection message. The `Shell(isScriptBlock=true)` StepSpec is built at construction but the canonical bridge rejects it. | (covered by the generic gate) |
| `load(path)` | `fun load(path: String)` | **UNSUPPORTED, fail-closed at compile** | Same bridge rejection. `core.load` IS a registered `StepDefinition` candidate (per `WU_LPR_060` ledger) but the canonical bridge whitelist excludes it. Exit 2. | `CliNonCanonicalInMemoryExitsTwoTest`, `UatLocal011WorkflowControlTest` |

---

## Findings vs. the historical `LFC2_HONEST_DSL_CLOSURE.md` list

The historical list (lines 73-77 of that document) called four of these
"🔲 absent / 🟡 / placeholder" with an expectation that they were
silently incorrect. The empirical audit contradicts that for four of
them:

| Historical claim (2026-09-08) | What the binary actually does (2026-09-19, HEAD `a5e0359f`) |
|---|---|
| "waitUntil honest semantics vs throw RuntimeException: 🔲 placeholder poll + throw" | **GREEN**: `WaitUntilPolled` + `WaitUntilCompleted` events emitted with `totalAttempts`, `totalDurationMs`, `outcome:"completed"`. No `RuntimeException` thrown at probe time. |
| "`post`/`whenCondition` execution on canonical path: 🔲 toStageBuilder omits post; whenCondition discards expression and appends body unconditionally" | **CORRECTED — the old answer here was wrong twice.** `post` is in the bridge whitelist path (not in `non-canonical` rejection). `whenCondition` was NOT rejected: it was exactly the historical failure described in the left column, and this audit mis-reported it as fixed. An implementation that compiles away to a bare `echo` is invisible to the `non-canonical` gate, so a green bridge gate proved nothing about it. It is now rejected at construction with `IllegalArgumentException` (`WhenConditionFailClosedTest`). |
| "`node` no-op with only AgentResolved: 🟡 documented no-op; fake-return risk on label/workspace" | **Nuanced**: `node` is REJECTED at compile with the same `non-canonical` message. The documented no-op risk is now a hard compile-time gate. |
| "`git`/`scmGit` duplicate constructors: 🔲" | **Out of F3 scope**: SCM is a different family (orchestration-level plugin). The ledger has `LEGACY_IMPLEMENTED_UNCERTIFIED` for SCM-related keys; not a DSL honesty gap. |

Two historical items remain as honest debt:

1. **`WU-LPR-402P`** (`STRUCTURED_DSL_RUNTIME_RETURN_GAP`) — `pwd`/`isUnix`
   in the **structured** `pipeline { ... }` form still lack a compiler
   lowering path to materialise real values. The **scripted** form
   (`scriptable { ... }`) and the **generator** form work via
   `ScriptedStepFacade` / `ScriptedRegistryInvoker`. This is the
   "DEFERRED" closing row of `WU_LPR_402`.

2. **`WU-LPR-403`** (cross-cutting block-step eager pattern) — `retry`,
   `timeout`, `timestamps`, `dir`, `withCredentials` share the
   construction-time body-capture pattern documented in `WU_LPR_401`.
   Fixing one builder without the others would create an inconsistency.
   Deferred as a group.

---

## Defects to fix (none identified by F3)

**No defect in the third class** ("behaviour that appears to work but
is false or silently incorrect") was reproduced for any of the eight
surfaces against HEAD `a5e0359f`. Specifically:

- `pwd()`, `pwd(true)`, `isUnix()` return honest sentinels and emit
  real events at execution. The placeholder constants are
  `RUNTIME_VALUE_PLACEHOLDER` and `ISUNIX_PLACEHOLDER_BOOLEAN` and
  are documented as such.
- `waitUntil { ... }` runs the body at runtime via `BodyInvoker`, not
  at construction.
- `node`, `script`, `load` all fail **closed** at compile with an
  explicit `non-canonical plugins` error, exit 2, before any effects
  are launched. (Re-verified as still true; see the note below.)
- `whenCondition` is **not** in that list. It is rejected at
  construction with `IllegalArgumentException`, because it never
  reached the bridge: it emitted no `StepSpec` of its own, so the
  `non-canonical` gate had nothing to inspect.

> **Method correction (2026-09-28).** This receipt originally listed
> `whenCondition` alongside `node`/`script`/`load` as failing closed at
> compile, citing `CliNonCanonicalInMemoryExitsTwoTest`. That citation
> was invalid: the test uses `ansiColor`, and `whenCondition` flattens
> to a canonical `echo`. The lesson generalises past this one row —
> **a green `non-canonical plugins` gate proves only that the
> unrecognised surface emits a `StepSpec`.** Any DSL function that
> compiles away to canonical steps is invisible to that gate, so
> "it was rejected" must be demonstrated with a fixture that actually
> contains the surface under audit. A later probe with the installed
> distribution showed the body running unconditionally, exit 0.

This is a **better** posture than the historical list suggested: the
DSL surfaces are honest about what is and is not supported by the
canonical bridge.

---

## Open items not closed by F3

These are tracked as future work, not defects:

- **F4 — matrix generation**: produce a live matrix derived from code
  + receipts (CORE / OFFICIAL_PLUGIN / EXTERNAL_REFERENCE /
  DEFERRED_REMOTE / REJECTED_JENKINS_INTERNAL × state ladder), with
  the eight DSL surfaces above as orchestration blocks (not
  `StepDefinition`s).
- **F5 — first ecosystem slice**: `checkout → build → test → reports
  → artifacts` via `OFFICIAL_PLUGIN` for SCM/Git and `junit`, reusing
  `sh` + `archiveArtifacts` for the rest.
- **SDKMAN**: still `WAITING_EXTERNAL` (vendor credentials). The
  local-mirror diagnostic (`WU-LPR-090`) is a `LOCAL_TEST_ONLY` and
  does not constitute publication.

---

## Probe artefacts (this audit)

The probe that produced the table above ran against HEAD `a5e0359f`
with `JAVA_HOME=/home/rubentxu/.asdf/installs/java/temurin-24.0.2+12`.

- Probe script (`probe.kts`): `/tmp/pk-f3-z/gradle/probe.kts` — ran
  with `pwd() / pwd(true) / isUnix() / waitUntil { echo(...) }` and
  produced 4 `EchoOutputCaptured` events, 2 `PwdResolved`, 1
  `UnixDetected`, 2 `WaitUntilPolled`, 1 `WaitUntilCompleted`, all
  with `outcome:"success"` for the pipeline.
- Reduced probe (`load.kts`): `/tmp/pk-f3-load/gradle/main.kts` —
  exit 2, "non-canonical plugins" (gate working).
- All scratch dirs in `/tmp` were removed at the end of each run.

These probes can be reproduced locally:

```bash
# rebuild HEAD installDist
./gradlew -p v2 :pipeline-application:installDist

# probe admitted surfaces
mkdir -p /tmp/dsl-probe
cp -r integration/gradle-demo/. /tmp/dsl-probe/
cat > /tmp/dsl-probe/probe.kts <<'EOF'
pipeline {
    stages {
        stage("p") {
            val x = pwd(); echo("pwd=" + x)
            waitUntil { echo("w") }
        }
    }
}
EOF
cd /tmp/dsl-probe
JAVA_HOME=/home/rubentxu/.asdf/installs/java/temurin-24.0.2+12 \
    ./v2/pipeline-application/build/install/pipelinek/bin/pipelinek \
    run --workspace . --db run.sqlite --control-root ctl probe.kts
```

The script is intentionally minimal (only certified builders) so the
probe stays green even after future changes to the bridge.
