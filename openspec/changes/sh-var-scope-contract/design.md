# SH-VAR-SCOPE-CONTRACT — Design (evidence + gating)

This document is the bridge from `spec.md` (decisions) to `tasks.md` (work).
For each gap with a non-`OUT OF SCOPE` decision, it specifies:

1. **Where** the evidence is captured.
2. **What** artefact proves a stance is sustained.
3. **How** a `CONTRACT TEST` is constructed when that is the stance.

No production code change is described; F1 is documentation + tests only.

---

## Evidence directories

All new evidence under:

```
docs/v2/07-uat/evidence/sh-var-scope-contract/
├── gap02-no-creds-block.txt
├── gap03-shell-expansions.txt
├── gap04-formF-raw-triples.txt
├── gap05-diagnostics.txt
└── S2-three-phase-probe-extended.txt
```

Plus updated `S2ThreePhaseProbeTest` source plus new files:

```
v2/pipeline-scripting-kotlin24/src/test/kotlin/dev/rubentxu/pipeline/v2/scripting/
├── ShVarScopeGap02Test.kt
├── ShVarScopeGap03Test.kt
├── ShVarScopeGap04FormFProbeTest.kt
└── ShVarScopeGap05Test.kt
```

Plus one new fixture family used by Gap #5:

```
v2/compatibility/
├── sh-var-scope-gap02-{a,b,c}.pipeline.kts
├── sh-var-scope-gap03-{default,cmd-subst,glob-strip}.pipeline.kts
└── sh-var-scope-gap04-form-F-raw-{f1,f2,f3}.pipeline.kts
```

(`gap05` does not need new fixtures — the test reads the diagnostic output
of `Kotlin24ScriptingHost.compile(...)` directly.)

---

## Per-gap evidence design

### Gap #1 (`DOC only`) — evidence reuse

- The S1 capture in `CHARACTERISATION.md §5.2` already lists s1-05a, s1-05b,
  s1-05c with byte-level compile outputs and bash emissions.
- F1 adds a **direct quote** of that section into the contract document
  `SH_VAR_SCOPE_CONTRACT.md` (under "Same-name collisions"), with a
  SHA-256 of the section's text and the date the section was last
  validated against `main`.

No new test. Gate: `CHARACTERISATION.md §5.2` byte-equivalence after the
contract is published.

### Gap #2 (`CONTRACT TEST`) — `$VAR` outside `withCredentials`

**Test class.** `ShVarScopeGap02Test.kt`, JUnit 5, `@Timeout(60)`.

**Construction.** Reuse the `String` concatenation technique from
`S2ThreePhaseProbeTest.kt:60-83` to build source bytes that are NOT Kotlin
templates when this test itself compiles. Build three .pipeline.kts bodies:

- `gap02a.pipeline.kts`: declares `val USER="alice"; sh("echo $USER")`.
  Expect `CompilationFinished`, `RunStarted`, `sh` outcome `StepFinished`
  with `capturedStdout = "alice"`.
- `gap02b.pipeline.kts`: `sh("echo \${USER}")`. Expect bash expansion
  (USER set in env by env-var-shim test fixture) -> `capturedStdout` matches
  the env var value.
- `gap02c.pipeline.kts`: `sh("echo $NOPE_NO_BINDING")`. Expect
  `CompilationFinished.diagnostics` non-empty with the message
  `Unresolved reference 'NOPE_NO_BINDING'`.

**Why `String` concatenation.** To byte-construct the test inputs without
the Kotlin compiler of the test source trying to interpolate `$VAR`.

**Evidence capture.** `gap02-no-creds-block.txt` with the three run logs,
SHA-256 of each.

### Gap #3 (`CONTRACT TEST`) — shell-specific expansions

**Test class.** `ShVarScopeGap03Test.kt`.

Three scenarios:

- `gap03default.pipeline.kts`: `withEnv(listOf("OPTIONAL=")) { sh("echo \${OPTIONAL:-fallback}") }`
  -> bash sees `${OPTIONAL:-fallback}` and prints `fallback`.
- `gap03cmdsubst.pipeline.kts`: `sh("echo host=\$(hostname)")` -> bash sees
  `echo host=$(hostname)` and prints the actual hostname.
- `gap03globstrip.pipeline.kts`: `withEnv(listOf("PATH_STR=/a/b/c")) { sh("echo \${PATH_STR##*/}") }`
  -> bash prints `c`.

**Coverage.** These cover the three patterns enumerated in
CHARACTERISATION.md §2.3 item 3. Negative (`${'$'}{USER}`) is already in
`S2ThreePhaseProbeTest` Form B; do NOT duplicate.

**Evidence capture.** `gap03-shell-expansions.txt`.

### Gap #4 (`CONTRACT TEST`) — Form F raw triple-quoted

**Test class.** `ShVarScopeGap04FormFProbeTest.kt`.

This is the operator's specific extension. Three Form F probes (run via
the same three-phase hook as A..E) measured against bash in-process
(without the installed `pipelinek` binary), to verify the four layers
per Guard G2:

- **F1** (Kotlin escape inside raw triple):
  - Source bytes (built by concatenation):
    `sh("""echo user=\${USER}""")`
  - PHASE_1_SOURCE — captured.
  - PHASE_2_ESCAPED — byte-equivalent (escaper does not touch raw
    triple inside the script's domain).
  - PHASE_3_COMPILE — measured against `bash -c` with the same bytes
    that a successful compile would have produced
    (`echo user=\${USER}` -> bash sees `echo user=\${USER}` and refuses
    to expand `\${USER}` because it does not parse as bash).  Recorded
    as a deviation: in raw triples the Kotlin-side fail at compile
    time prevents this from ever reaching bash.
- **F2** (the safe form inside a raw triple):
  - Source bytes:
    `sh("""echo user=${'$'}USER""")`
  - PHASE_3 (bash -c): `echo user=$USER` -> bash expands USER.
- **F3** (the trap inside a raw triple):
  - Source bytes:
    `sh("""echo user=\$USER""")`
  - PHASE_3 (bash -c): `echo user=\$USER` -> bash sees literal
    `\$USER` and refuses to expand (no such variable).

**Why bash in-process and not the installed binary.** Per Guard G2
("bytes, not aspect"), the contract is asserted on the **actual byte
sequence** at each layer. `bash -c "<bytes>"` is the cheapest faithful
way to verify layer 4 (bash expansion semantics) without paying the
`installDist` cost in F1. The byte sequence we pass to bash is the
exact sequence the production core.sh handler would feed forward if
Kotlin accepted the corresponding source; the test asserts what bash
does with those bytes given a real `USER` env.

**Why F3 is included.** It is the operator's literal-type distinction
applied: the trap is *literal-type-specific* and must be documented.

**Evidence capture.** `gap04-formF-raw-triples.txt` + an extension row in
the regenerated probe log.

### Gap #5 (gated) — diagnostic positions when escaper has run

**Test class.** `ShVarScopeGap05Test.kt`.

Construction:

- Build a `.pipeline.kts` source containing `sh("echo cred=\$WITH_CREDS")`.
- Run through `EnvVarNameExtractor.extract + ScriptTextEscaper.escape` with
  `envVars = {"WITH_CREDS"}` (simulating a `withCredentials` binding).
- Run the *escaped* source through `Kotlin24ScriptingHost.compile`.
- Force a deliberate compile error (e.g. add a broken statement after the
  sh) so there is something to map back.
- Read `result.diagnostics` line/column.
- Assert that the line/column is **exactly the line/column the operator
  will see in their editor**: i.e. the offset mapping is currently NOT
  done; mapDiagnostic reports against the *escaped* source.

**Predicate for F2 trigger.**

```text
IF Gap05Test reproduces a real deviation where:
  editor_position != mapDiagnostic_position
  AND that deviation causes user-perceivable friction in at least one of:
    (a) `$VAR` mid-line with a credential binding active,
    (b) `${'$'}` escape where the operator reads a `col=` that does not
        match the on-screen cursor position
THEN F2 is opened with a proposal for an offset map, gated on
re-running Gap05Test with the offset map in place and demonstrating the
positions match.
ELSE F1 closes Gap #5 as `DOC only` with the byte-level diagnostic
delta documented as a known, measured limitation that does not block
authoring.
```

**Evidence capture.** `gap05-diagnostics.txt` with the current
behaviour's exact output and a verdict line: `F2_TRIGGER=YES|NO`.

### Gap #6 (`DOC only` — by operator decision) — `withEnv`

No new test. F1 re-states the operator's decision verbatim in
`SH_VAR_SCOPE_CONTRACT.md` "withEnv overrides" section, referencing
the existing s1-13/s1-14 capture in `CHARACTERISATION.md §5.5`.

If a future change (`EnvVarNameExtractor` extension to `withEnv`) is ever
proposed, this gate must be re-opened by a new proposal — not folded into
F2 of this contract.

---

## Evidence format

All `*.txt` files begin with a header:

```
=== sh-var-scope-contract evidence ===
Generated: 2026-09-20
Base SHA: 1fdd3dce
Spec: openspec/changes/sh-var-scope-contract/spec.md
Reproduction cmd: <the exact gradle invocation + JUnit selector>
Run mode: --rerun-tasks (per AGENTS.md rule 25)
JUnit XML SHA-256: <sha256 of TEST-ShVarScope*.xml>
JUnit XML: build/test-results/test/TEST-ShVarScope*.xml
Content SHA-256: <sha256 of THIS file>
```

This format is what AGENTS.md rule 14 (zero-fabrication) + rule 25
(result truth = JUnit XML) require for any evidence file.

---

## What F1 does NOT do

For absolute clarity (operator 2026-09-20):

- NO change to `ScriptTextEscaper`.
- NO change to `EnvVarNameExtractor` to read `withEnv`.
- NO change to `Kotlin24ScriptingHost.mapDiagnostic` (only used by Gap #5
  reproduction).
- NO new `sh` API.
- NO mock or test-only override of any production class.

---

## What gates F2

Only Gap #5's predicate, fully laid out above. Any other "improvement" the
team might want (e.g. `\$VAR` rewrite) is a NEW proposal, not an F2 of
this change.
