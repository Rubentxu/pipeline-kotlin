# SH-VAR-SCOPE-CONTRACT — Tasks

Two phases, separated explicitly per the operator's 2026-09-20 decision:

- **F1 (this change).** Documentation and contract tests. No production
  code change. Closes gaps 1..6 + Form F (operator extension).
- **F2 (gated, future change).** Only triggered by Gap #5 reproduction.
  No F2 work in this change.

Tasks are numbered for traceability. Each task lists its preconditions,
its deliverable, and the evidence it produces.

---

## F1 — Documentation + Contract Tests (this change)

### T1. Author the contract document

**Deliverable.** `docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md`.
A single source of truth, structured as the seven-section summary in
`spec.md` "Author-facing summary".

**Preconditions.**
- `CHARACTERISATION.md` byte-equivalent on `main` (verify with the SHA-256
  that `eff39dbe` recorded).
- This change's `proposal.md` + `spec.md` + `design.md` approved by the
  operator.

**Sections.**
1. Safe forms (ordinary `"..."` + raw `"""..."""`).
2. Trap forms with byte-level reproductions.
3. Same-name collisions.
4. `withCredentials` bindings.
5. `withEnv` overrides (verbatim operator decision: not auto-protected).
6. Multi-line / raw triples — Gap #4 evidence.
7. Diagnostics — Gap #5 evidence (current behaviour, gated F2).

**Evidence.** A copy of the spec/receipt SHA-256s at the top of the
document.

### T2. Extend `S2ThreePhaseProbeTest` with Form F

**Deliverable.** Updated
`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/S2ThreePhaseProbeTest.kt`
adding `phase1_3(...)` invocations for **F1, F2, F3** as described in
`design.md Gap #4`.

**Preconditions.** T1.

**Evidence.** `docs/v2/07-uat/evidence/sh-var-scope-contract/S2-three-phase-probe-extended.txt`
captured once and re-run when this slice lands on `main`. Include the
SHA-256 of the captured log.

**JUnit XML.** `build/test-results/test/TEST-dev.rubentxu.pipeline.v2.scripting.S2ThreePhaseProbeTest.xml`.

### T3. `ShVarScopeGap02Test` — `$VAR` outside `withCredentials`

**Deliverable.** New
`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/ShVarScopeGap02Test.kt`.

Three cases: with-Kotlin-local-success, Kotlin-escape-success,
compile-error-without-escape. JUnit 5, `@Timeout(60)`,
`--rerun-tasks` for canary freshness per AGENTS.md rule 25.

**Preconditions.** T1.

**Evidence.** `gap02-no-creds-block.txt`.

### T4. `ShVarScopeGap03Test` — shell-specific expansions

**Deliverable.** New
`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/ShVarScopeGap03Test.kt`.

**Preconditions.** T1.

**Evidence.** `gap03-shell-expansions.txt`.

### T5. `ShVarScopeGap04FormFProbeTest` — Form F end-to-end

**Deliverable.** New
`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/ShVarScopeGap04FormFProbeTest.kt`.

**Distinct from T2.** T2 extends the three-phase probe (compile-time
byte trace). T5 runs the *installed binary* with a `pipeline.kts` that
contains each Form F variant, captures `capturedStdout`, and asserts the
expected bash emission.

**Preconditions.** T2 (compile bytes known).

**Evidence.** `gap04-formF-raw-triples.txt`.

### T6. `ShVarScopeGap05Test` — diagnostic-position reproduction

**Deliverable.** New
`v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/ShVarScopeGap05Test.kt`.

**Predicate outcome.** A line `F2_TRIGGER=YES|NO` in the captured
evidence. The test itself does NOT change `mapDiagnostic`. It just
characterises the current behaviour.

**Preconditions.** T1.

**Evidence.** `gap05-diagnostics.txt`.

### T7. Fixtures + corpus registration

**Deliverable.** New `.pipeline.kts` fixtures under `v2/compatibility/`:
- `sh-var-scope-gap02-{a,b,c}.pipeline.kts`
- `sh-var-scope-gap03-{default,cmd-subst,glob-strip}.pipeline.kts`
- `sh-var-scope-gap04-form-F-raw-{f1,f2,f3}.pipeline.kts`

**Preconditions.** T1 (decisions stable).

**Fixtures MUST be valid Kotlin.** Source bytes are constructed by
`String` concatenation in tests (per the technique used in
`S2ThreePhaseProbeTest.kt:60-83`) and written to disk by the test
fixture infrastructure, NOT by hand-written Kotlin literals containing
`$` that would template-expand when the test source itself compiles.

**Register in `CompatibilityCorpusTest`.** Add the six fixtures so
their compile and `durability` re-runs under the corpus gate. Add
`CompatibilityCorpusTest.allCorpusFixturesAreDiscoverable` if the
fixture inventory changes.

**Evidence.** Capture the JUnit XML of the regenerated corpus run.

### T8. Single contract receipt

**Deliverable.** `docs/v2/07-uat/SH_VAR_SCOPE_CONTRACT_CLOSURE_RECEIPT.md`
structured per AGENTS.md rule 25 + 27:

- `Changed` (files: 1 spec doc, 1 design, 4 test classes, 9 fixtures).
- `Affected SUT` (`pipeline-scripting-kotlin24` only).
- `Verification executed` (per task T2..T7 with `--rerun-tasks`).
- `Evidence reused` (`eff39dbe` + `83882467` SHA-256s).
- `Verification deliberately not executed` (the W1-style fitness scope —
  not applicable because no production code changed).
- `Unknown impact` (`EnvVarNameExtractor` extension to `withEnv` —
  excluded by operator decision).
- `Result` (PASS / FAIL / BLOCKED + binary verdict).
- `Full verification required now` (NO; targeted tests suffice).

---

## F2 — Gated (NOT in this change)

F2 is opened only by Gap #5's `F2_TRIGGER=YES` outcome.

The F2 proposal will:

1. Add an offset map in `Kotlin24ScriptingHost.mapDiagnostic`.
2. Update `Gap05Test` to demonstrate the map closes the deviation.
3. NOT touch any other seam.

Until F2 is opened by predicate, F2's scope is contained in a single
sentence here and the body remains empty.

---

## Sequencing and dependencies

```text
T1 (contract doc)
 ├── T2 (Form F probe extension)
 │    └── T5 (Form F end-to-end)
 ├── T3 (Gap02 test)
 ├── T4 (Gap03 test)
 ├── T6 (Gap05 reproduction)
 └── T7 (fixtures + corpus)
T8 (closure receipt) <- requires T2..T7 all green
```

T2..T7 are independent and may be parallelised within the apply phase.
T8 is single-threaded last.

---

## SLDC (sddk) cycle shape

This change is **A-lite** in the sddk routing table:

```
propose (this file + proposal.md)  -> DONE
spec    (spec.md)                  -> DONE
design  (design.md)                -> DONE
tasks   (tasks.md — this file)     -> DONE
apply   (cycle/sh-var-scope-contract-s1, in worktree off main)
verify  (targeted tests only — L0 + L1; no L5 because no production code
          change per AGENTS.md rule 23 + scope firewall in proposal)
debt-verify
release (FF-merge to main after operator approval)
archive (the receipt becomes the durable artefact)
```

The "apply" phase T2..T7 produces ONLY documentation + test files.
No production code in `pipeline-application`, `pipeline-domain`, or
`pipeline-step-sdk` is touched. No capability declaration is added.
No shell handler is modified.

---

## Out-of-scope declarations

- Any modification of `ScriptTextEscaper.escape` (operator 2026-09-20).
- Any extension of `EnvVarNameExtractor` to `withEnv` (operator 2026-09-20).
- Any change to `Kotlin24ScriptingHost.mapDiagnostic` (gated F2 only).
- Any new `shellScript { ... }` API.
- Any adoption of Jenkins Groovy semantics (option D).

---

## Operator GO gate

Before `cycle/sh-var-scope-contract-s1` branch is cut and T2..T7 work
begins, the operator must approve:

1. The decision matrix in `spec.md`.
2. The evidence format in `design.md`.
3. The tasks list above.
4. The SH_VAR_SCOPE_CONTRACT.md outline (the seven sections in T1).

If any of those is contested, the change is amended; no work begins.
