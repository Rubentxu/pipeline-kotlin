# `sh` Variable Scope Contract — PipelineK

> Single source of truth for what PipelineK guarantees, what it does not, and
> what is left to the author, at the seam between Kotlin source-level string
> templates and shell-time variable expansion inside `sh(...)` calls.
>
> **Authority.** Continuation of SH-VAR-SCOPE.S0..S2 already on `main`
> (commits `eff39dbe`, `83882467`). Empirical capture lives in
> `v2/docs/f5-2/sh-var-scope/CHARACTERISATION.md` (620 lines, six gaps).
> The gap-decision matrix lives in
> `openspec/changes/sh-var-scope-contract/spec.md`.
>
> **Cycle.** `cycle/sh-var-scope-contract-s1`. Operator GO at
> 2026-09-20T08:16Z with three precision guards (G1, G2, G3). The guards are
> anchored verbatim in `proposal.md §Operator guard addendum`.
>
> **Scope.** F1 — no production code change. F2 (offset map) is gated, only
> triggered if Gap #5 reproduction shows a concrete diagnostic deviation.

---

## 1. Author's mental model in one paragraph

A string passed to `sh("…")` is compiled by Kotlin first (it sees the
literal type — ordinary `"…"` or raw `"""…"""`), and the resulting byte
sequence is what the shell (`bash`) finally expands with its own rules.
PipelineK protects two layers between those two: an **escaper** that runs
over the source text to keep `withCredentials`-bound variable names out of
Kotlin's template scanner, and a **runtime env** that exposes credential
bindings and `withEnv` overrides to the spawned process. Everything outside
those protections is ordinary Kotlin + ordinary bash; escape forms must be
chosen by the author.

## 2. Safe forms (operator-known table)

| Outer Kotlin literal | Author intent | Author types | Kotlin compiles | bash sees | Notes |
|---|---|---|---|---|---|
| `"…"` ordinary | expand shell var | `"\$USER"` | `"$USER"` | `$USER` | The backslash IS an escape in `"…"`. |
| `"…"` ordinary | braced shell var | `"\${USER}"` | `"${USER}"` | `${USER}` | Inside `"…"`, the `${` opens a Kotlin template if the next chars form `$IDENT`; `\$` neutralises it. |
| `"…"` ordinary | Kotlin local value | `"$USERNAME"` with `val USERNAME` | `"alice"` | `alice` | Kotlin wins. |
| `"""…"""` raw triple | expand shell var | `"""\${USER}"""` | `${USER}` | `${USER}` | Same as above: `\$` neutralises `${`. |
| `"""…"""` raw triple | expand shell var | `"""${'$'}USER"""` | `$USER` | `$USER` | **Only safe form in raw triples when `\$` is unwanted**. `${'$'}` evaluates to `$` regardless of outer literal type. |
| `"…"` + `withCredentials(…) { … }` | expand credential var | `"$USER_PASS"` | (escaper rewrites `$USER_PASS` → `${'$'}USER_PASS` in source; Kotlin then compiles to `$USER_PASS`) | `$USER_PASS` | Escaper output is correct in every literal type. |

**Author rule of thumb.** Prefer `\$IDENT` in `"…"` and `\${IDENT}` in
matching cases. Switch to `${'$'}IDENT` only inside `"""…"""` raw triples
where you actually want the literal `$` to survive into bash.

## 3. Trap forms (author-avoidance table)

These compile but produce invalid bash. The compile error is silent.

| Form | What the author wrote (literally) | Bash receives | Bash result |
|---|---|---|---|
| Trap T1 — trap inside ordinary `"…"` | `"echo ${'$'}USER"` (treating `${'$'}` as escape) | `${'$'}USER` (8 chars literal: `$ { ' $ ' } U S E R`) | `sustitución errónea` / `bad substitution` |
| Trap T2 — Kotlin-escape inside raw triple | `"""echo \$USER"""` (thinking `\` is escape) | `\$USER` literally (6 chars: `\` `$` `U` `S` `E` `R`) | `\$USER: command not found` |
| Trap T3 — unbraced credential with no escape and no `withCredentials` | `"echo $USER_NO_BINDING"` | (compile error: `Unresolved reference 'USER_NO_BINDING'`) | (won't compile) |
| Trap T4 — unbraced shell var when Kotlin local shadows it | `val USERNAME="alice"; sh("echo $USERNAME")` | (Kotlin wins, substitutes `alice`) | output `alice`, not env value |

Trap T1 is the historical S1 discovery; locked as a negative fixture by
commit `83882467` at `v2/pipeline-application/src/test/resources/broken/99-trap-form-dollar-dollar-quote.pipeline.kts` plus `TrapFormNegativeFixtureTest`. Reproductions of T1..T4 are in `CHARACTERISATION.md §5.3` and §6.4.

## 4. Same-name collisions (author's responsibility, documented)

When a Kotlin local has the same name as a shell variable:

```kotlin
val USERNAME = "alice"
sh("echo kotlin=$USERNAME")        // Kotlin wins, output:  kotlin=alice
sh("echo bash=\${USERNAME}")       // bash wins, output:  bash=<env USERNAME or empty>
```

**Author rule.** Choose the form deliberately. `\$` or `\${…}` makes bash
expand from the env injected by `withCredentials` / `withEnv`; bare `$` makes
Kotlin expand from scope.

The byte-level reproduction is in `CHARACTERISATION.md §5.2` (s1-05a/05b/05c).
This contract links to that evidence; no new in-process test is added
(Guard G1 — DOC only is not abandonment).

## 5. `withCredentials` bindings (escoper-protected)

`withCredentials(StepSpec.CredentialsBinding.<factory>("id", "ENV_VAR")) { sh("…") }`
causes `EnvVarNameExtractor.extract` to capture `ENV_VAR`, and
`ScriptTextEscaper.escape` to rewrite **unbraced** `$ENV_VAR` references in
source to `${'$'}ENV_VAR`. The escoper's output is safe in production flow
because Kotlin compiles `${'$'}` to `$` regardless of outer literal type.

Two author-visible consequences:

1. **The escoper never rewrites `${ENV_VAR}` braced references.** Authors
   who write the braced form are not protected by the escoper, but they
   do not need to be: Kotlin emits `${ENV_VAR}` literally and bash expands.
2. **`$ENV_VAR` (unbraced) inside `withCredentials`** compiles to the
   escoper's `${'$'}ENV_VAR` bytes at the Kotlin level; bash then expands.
   This is the **production-safe** path and matches the existing Jenkins
   Groovy semantics without adopting them.

**The trap inside `withCredentials` is NOT `${'$'}ENV_VAR` written by an
author**. It is the author writing `${'$'}ENV_VAR` themselves outside a
raw triple — see Trap T1 above. The escoper itself produces that form,
but the escoper's output is fed to a fresh Kotlin compilation where
`${'$'}` is correctly evaluated.

## 6. `withEnv` overrides (NOT protected — by operator decision)

`withEnv(listOf("DEPLOY_ENV=STAGING") { sh("…") }` sets a runtime env
override inside the `core.sh` handler. **The escoper does not, and must
not, read `withEnv` blocks.** This is the contract per Guard G1 — extending
`EnvVarNameExtractor` to `withEnv` is **NOT pre-authorised** in this or any
later slice of this change (operator decision, 2026-09-20).

**Author rule.** Escape `\$`/`\${…}` explicitly when the reference is
inside `withEnv`. This was demonstrated end-to-end at
`CHARACTERISATION.md §5.5` (s1-13, s1-14).

## 7. Multi-line scripts + `"""…"""` raw triples (Gap #4 evidence)

The author-facing table in §2 covers both literal types. The byte-level
evidence of the operator's literal-type distinction is in this cycle's
Form F probes (`S2ThreePhaseProbeTest` extended with F1/F2/F3 cases,
captured in `docs/v2/07-uat/evidence/sh-var-scope-contract/S2-three-phase-probe-extended.txt`).

The four layers measured for each Form F case:

1. **Kotlin source literal type** — ordinary `"…"` vs raw `"""…"""`.
2. **Post-Kotlin-compile bytes** — string value Kotlin produces after
   template expansion (Stage 2 of the probe).
3. **Bytes the shell receives** — captured from `capturedStdout` of an
   installed-binary run of `pipelinek run` against a fixture
   (Stage 3 of the probe).
4. **Shell expansion semantics** — what bash does with the bytes
   (expands, ignores, errors).

If a probe finds the bytes diverge from expectation at layer 2 or 3, the
divergence is **recorded** and not repaired in F1. **`ScriptTextEscaper`
MUST remain untouched** during F1 (Guard G2).

## 8. Diagnostics (Gap #5, gated F2)

`Kotlin24ScriptingHost.mapDiagnostic` currently reports positions against
the *escaped* source, not the original. Each `$VAR` → `${'$'}VAR`
insertion adds exactly **6 characters** per token past the insertion point
in the same line. The byte-level delta is bounded and predictable.

A reproduction test (`ShVarScopeGap05Test`, T6 of the cycle) measures this
delta in-process. The predicate for opening F2 is:

```text
F2_TRIGGER = (Gap05Test reproduces a deviation where
              editor_position != mapDiagnostic_position
              AND that deviation causes user-perceivable friction)
```

Until `F2_TRIGGER = YES`, this is documented as a known, measured
limitation. No offset map is added (Guard G3).

## 9. Layered invariants already in the receiver (do NOT re-derive)

These are out of scope for this contract; covered elsewhere:

- `P2`: script content never appears in argv
  (`DurableShellExecutor.kt:110-111`).
- `WS-S-005`: env injected via `pb.environment().putAll`, never argv.
- `WS-S-006 / WS-S-007`: `JAVA_HOME` / `M2_HOME` prepend semantics.
- `ML-R3`: sandbox profile env denials + `PATH` rewrites.
- `ML-R4`: `SecretHandle` typed through layers; masked propagation.
- `WU-LPR-011R2`: console transcript redaction at the write boundary.

Reference: `v2/docs/f5-2/sh-var-scope/CHARACTERISATION.md §2.4`.

## 10. Reading map

- This document = the contract. Cite it.
- `CHARACTERISATION.md` = the empirical evidence per gap. Cite sections.
- `openspec/changes/sh-var-scope-contract/spec.md` = the decision matrix
  per gap (which are guaranteed, which are user responsibility).
- `openspec/changes/sh-var-scope-contract/design.md` = how the decisions
  were evidenced and what gates F2.
- `openspec/changes/sh-var-scope-contract/tasks.md` = the work breakdown
  (T1..T8 this change, F2 gated).
- `docs/v2/07-uat/SH_VAR_SCOPE_CONTRACT_CLOSURE_RECEIPT.md` = the cycle
  closure with sha256 of each captured log.

---

## Author-facing summary (one sentence per gap)

- **Gap #1** — Same-name collisions: **DOC only**, link to §5.2 evidence.
- **Gap #2** — `$VAR` outside `withCredentials`: **CONTRACT TEST** (T3).
- **Gap #3** — `${VAR:-default}`/`$(cmd)`/`${VAR##pat}`: **CONTRACT TEST** (T4).
- **Gap #4** — Multi-line + `"""…"""` raw triples: **CONTRACT TEST** with
  Form F1/F2/F3 (T2 compile trace + T5 installed-binary end-to-end).
- **Gap #5** — Diagnostics: **GATED** by F2 trigger (T6 reproduction).
- **Gap #6** — `withEnv` not auto-protected: **DOC only**, link to §5.5
  evidence; **`EnvVarNameExtractor` MUST NOT be extended**.
