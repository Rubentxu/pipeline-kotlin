# SH-VAR-SCOPE-CONTRACT — Spec (gap-by-gap decision matrix)

This is the executable contract. Each row picks one of three stances:

- `DOC only` — the contract says "user responsibility"; no reproduction test
  is produced, and the limitation is recorded with the byte-level why.
- `CONTRACT TEST` — the contract says "guaranteed this exact behaviour", and
  an in-process test reproduces it.
- `OUT OF SCOPE` — explicitly not guaranteed by PipelineK; user must know.

For every stance that is not `OUT OF SCOPE`, evidence is required
(by `design.md`).

All file paths below are relative to the repo root.

---

## Reading order

1. The six numbered rows = the six gaps from
   `v2/docs/f5-2/sh-var-scope/CHARACTERISATION.md §2.3`, plus the operator's
   2026-09-20 extension of Gap #4 to cover raw triple-quoted literals.
2. All "forms" referenced (A..F) are part of the existing
   `S2ThreePhaseProbeTest` (commit `83882467`) — Form F is added in F1.

---

## Decision matrix

### Gap #1 — Kotlin local with same name as a shell var

**Reference.** CHARACTERISATION.md §2.3 item 1; §5.2 (S1-05).

**Decision.** `DOC only`.

**Why `DOC only` (not `CONTRACT TEST`).** The runtime behaviour is already
locked by Kotlin string-template semantics: Kotlin compiles the literal value,
`sh` never sees `$VAR`. Reproducing the test in-process requires constructing
two distinct `.pipeline.kts` files and running them through the installed
binary — that is exactly the S1 evidence already captured
(`v2/docs/f5-2/sh-var-scope/CHARACTERISATION.md §5.2`, raw terminal output
embedded). The contract is "Kotlin wins without escape, bash wins with
escape"; an in-process Kotlin assertion does not add anything the S1 capture
did not already prove.

**Contract (author-facing).**
- `sh("echo $USERNAME")` with `val USERNAME = "alice"` in scope -> emits
  `echo alice`. User can rely on Kotlin's ordinary string-template rules.
- `sh("echo \${USERNAME}")` with the same local in scope -> emits
  `echo ${USERNAME}`. bash expands, ignoring the Kotlin local.

**Failure modes.**
- None at this layer. The Kotlin compiler is the contract.

**Evidence pointer.** CHARACTERISATION.md §5.2, table s1-05a/05b/05c.

---

### Gap #2 — `$VAR` outside any `withCredentials` block

**Reference.** CHARACTERISATION.md §2.3 item 2.

**Decision.** `CONTRACT TEST`.

**Why.** Without `withCredentials`, the escaper is a no-op (empty envVars set
in §1.1). The Kotlin compiler then sees `$VAR` as a Kotlin template —
either resolving to an in-scope Kotlin local (Kotlin wins) or failing to
resolve (Form A error). Both paths must be tested in-process because they are
the everyday authoring pattern outside credential scopes.

**Contract (author-facing).**
- `$VAR` with no Kotlin local and no `withCredentials` binding -> compile
  error `Unresolved reference 'VAR'`. The user is forced to escape.
- `$VAR` with a Kotlin local in scope -> Kotlin resolves and substitutes.
- `\$VAR` or `\${VAR}` in any literal type -> literal `$VAR` survives into
  the script; bash expands.

**Test plan.**
- New `ShVarScopeGap02Test` in `v2/pipeline-scripting-kotlin24/src/test/...`.
  Use `String` concatenation technique from
  `S2ThreePhaseProbeTest.kt:60-83` to construct the input bytes.
- Three cases: success-with-Kotlin-local, success-with-Kotlin-escape,
  compile-error without escape and without local.

**Evidence pointer.** New file under
`docs/v2/07-uat/evidence/sh-var-scope-contract/`.

---

### Gap #3 — Shell-specific expansions inside `sh("...")`

**Reference.** CHARACTERISATION.md §2.3 item 3.

**Decision.** `CONTRACT TEST`.

**Why.** The Kotlin compiler may or may not parse `${VAR}` as a template
depending on whether the identifier is in scope. Form D (`\${USER}`) is
confirmed safe in CHARACTERISATION.md §6.2; `${VAR:-default}`, `$(cmd)`,
`${VAR##pattern}` need to be tested once at the seam to lock the
*which-Kotlin-string-form-fences-this* contract.

**Contract (author-facing).**
- Any expansion starting with `$` and continuing with `{` MUST be escaped by
  the user via the backslash-before-the-dollar convention so that the
  opening `{` is not a Kotlin template opener.
- `\$` and `\${...}` are the only two safe escape forms.
- The Kotlin fragment `${'$'}` is a TRAP; documented as such in
  `14-credentials-bindings.pipeline.kts:7` (fixed in commit `83882467`).

**Test plan.**
- New `ShVarScopeGap03Test` exercising:
  - `${USER:-default}` and `${USER:-${HOSTNAME}}` with backslash escapes.
  - `$(hostname)` and `$(echo $USER)` nested.
  - `${VAR##pattern}` glob-strip and `${VAR%suffix}` suffix-strip.
- Negative fixture (`${'$'}{USER}`) is already locked as `Form B` in
  `S2ThreePhaseProbeTest`. Do NOT duplicate — reuse.

**Evidence pointer.** New file alongside Gap #2.

---

### Gap #4 — Multi-line strings + `"""..."""` raw triples with `$VAR`

**Reference.** CHARACTERISATION.md §2.3 item 4 (multi-line) — extended
by the operator on 2026-09-20 to specifically require coverage of the raw
triple-quoted form.
**Decision.** `CONTRACT TEST` (Form F).

**Why.** The operator explicitly opened this distinction:
`"\$VAR"` is literal-`$` in an ordinary `"..."` string, but `\` does NOT
escape `$` inside `"""..."""` raw triples. The byte-level behaviour at the
seam differs by literal type. Without a test, future Steps that emit
multi-line shell scripts (e.g. Java/Maven toolchain blocks) will produce
unpredictable output.

**Contract (author-facing).**
- Ordinary `"..."` literal: `\$VAR` works; `${'$'}VAR` also works (because
  both produce the byte sequence `$VAR`).
- Raw `"""..."""` literal: `\$VAR` produces the LITERAL TWO BYTES
  `\` `$` followed by `VAR`. The backslash is NOT an escape. To get a bare
  `$` in the script bytes, the user MUST write `${'$'}VAR` (or break the
  raw triple into pieces).
- The escaper currently emits `${'$'}VAR` for env-bound vars in
  `withCredentials` blocks (Form E); this output is correct in BOTH types
  of literal, because Kotlin compiles `${'$'}` to a literal `$` regardless
  of outer string flavour.

**Test plan.**
- Add **Form F** to `S2ThreePhaseProbeTest`:
  - F1: `"""echo user=\${USER}"""` -> compiled bytes should contain
    `echo user=${USER}` (with the `\` having effect).
  - F2: `"""echo user=${'$'}USER"""` -> compiled bytes should contain
    `echo user=$USER` (the safe form in raw triples).
  - F3: `"""echo user=\$USER"""` -> compiled bytes should contain
    literally `echo user=\$USER` -> bash sees `echo user=\$USER` and
    fails (`\$USER` is not a bash expansion).
- For each F case, add a runtime end-to-end (installed binary) probe
  verifying the actual `sh` receives the expected bytes. Use the
  `s2-formB-with-local.pipeline.kts` technique from
  `CHARACTERISATION.md §6.4`.

**Evidence pointer.** Form F entries in the regenerated probe log
under `docs/v2/07-uat/evidence/sh-var-scope-contract/`.

---

### Gap #5 — Diagnostic line/column when the escaper has shifted positions

**Reference.** CHARACTERISATION.md §2.3 item 5.

**Decision.** Gated by F1 reproduction.

**Why gated.** The operator explicitly said:

> E —mapa de offsets— queda condicionada a que las pruebas demuestren una
> desviación real de los diagnósticos.

We do NOT pre-emptively implement an offset map. F1 first writes a contract
test that REPRODUCES a real deviation (if any). Only if reproduction succeeds
does F2 add the offset map.

**Contract (author-facing).**
- Until reproduction, the contract is exactly what `mapDiagnostic`
  currently does: positions are reported against the *escaped* source, not
  the original source, when the escaper has rewritten tokens. Concretely:
  each `$VAR` -> `${'$'}VAR` adds 6 characters per token past the insertion
  point in the same line.
- If reproduction shows that this is a real DX problem (not a contrived one),
  F2 introduces an offset map.

**Test plan (in F1, no code change).**
- New `ShVarScopeGap05Test` constructs a string with a `$WITH_CRED_BINDING`
  reference, runs the escaper with that env-var in the set, and asserts that
  the diagnostic positions `LineSlashColumn` reported by
  `Kotlin24ScriptingHost.mapDiagnostic` are off by exactly the inserted
  length.
- The test PASSES if positions match the *escaped* source, FAILING if they
  match the *original* source (because the current behaviour is the
  opposite — that would be a no-change-needed signal).

**Outcome predicate.**
- If the test reproduces a user-perceivable deviation AND that deviation
  blocks a real authoring flow -> F2 adds the offset map (gated change).
- Otherwise -> the contract stays as documented, and the deviation is
  recorded as known/measured, not as a defect.

**Evidence pointer.** Test run log under
`docs/v2/07-uat/evidence/sh-var-scope-contract/gap05-diagnostics.txt`.

---

### Gap #6 — `$VAR` in `withEnv` (separate layer from `sh` source text)

**Reference.** CHARACTERISATION.md §2.3 item 6.

**Decision.** `DOC only`.

**Why `DOC only`.** The operator on 2026-09-20 explicitly decided
"conocer el nombre de una variable de entorno no autoriza a reescribir todas
las referencias homónimas de Kotlin". Translating:

- `withEnv("DEPLOY_ENV=STAGING") { sh("echo \$DEPLOY_ENV") }` is fully
  functional. The user must escape explicitly. `EnvVarNameExtractor` does
  NOT touch `withEnv`.
- `withEnv("DEPLOY_ENV=STAGING") { sh("echo \${DEPLOY_ENV}") }` is the
  Jenkins-convention form, also fully functional.
- Anything that prevents the Kotlin compiler from confusing shell vs Kotlin
  is the user's responsibility. The escaper does not protect automatically
  — by operator decision.

**Contract (author-facing).**
- `withEnv` overrides affect runtime env inside the `core.sh` handler
  (per `CHARACTERISATION.md §5.5`, s1-13/s1-14).
- The user is responsible for the escape form (`\$`, `\${...}`).
- The escaper does NOT auto-protect `withEnv` overrides. This is **the
  contract**, not a gap.

**Test plan.** None new; reuse s1-13/s1-14 captured in §5.5.

**Evidence pointer.** CHARACTERISATION.md §5.5 raw terminal output.

---

## Author-facing summary (one page)

After the F1 contract lands, the single source of truth will be
`docs/v2/03-specifications/SH_VAR_SCOPE_CONTRACT.md`, structured:

1. **Safe forms.** Ordinary `"..."` -> `\$VAR`, `\${VAR}`. Raw
   `"""..."""` -> `${'$'}VAR`. (Two columns; the operator's
   literal-type distinction is the rule.)
2. **Trap forms.** `${'$'}VAR` written by hand outside of a raw triple.
   `\$VAR` written inside a raw triple. Each with a `FAIL` example and
   the reproduced bash error.
3. **Same-name collisions.** `val USERNAME = "alice"` + `sh("echo $USERNAME")`
   -> Kotlin wins. `sh("echo \${USERNAME}")` -> bash wins. User picks.
4. **`withCredentials` bindings.** Escaper emits `${'$'}VAR` (correct in any
   literal type). User can also write `\${VAR}` and skip the escaper.
5. **`withEnv` overrides.** No auto-protection. User must escape.
6. **Multi-line / raw triples.** See Gap #4 row.
7. **Diagnostics.** Currently off by inserted length. If gated F2
   reproduces a real deviation, an offset map is added.
