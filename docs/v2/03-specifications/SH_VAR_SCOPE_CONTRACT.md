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

The following table is the SHIP-CYCLE byte-level capture of the
contract. Each row was probed in-process by the Form A..F probe in
`S2ThreePhaseProbeTest` (T2 evidence, sha256 below). The byte-level
truth supersedes any earlier hypothesis on the literal-type
distinction; the trap-form table in §3 lists the forms where the
contract is FAIL.

| Outer Kotlin literal | Author intent | Author types | Kotlin compiles | bash sees | Notes |
|---|---|---|---|---|---|
| `"…"` ordinary | expand shell var | `"\$USER"` | `"$USER"` | `$USER` | The backslash IS an escape in `"…"`. |
| `"…"` ordinary | braced shell var | `"\${USER}"` | `"${USER}"` | `${USER}` | Inside `"…"`, `\$` neutralises `${`. |
| `"…"` ordinary | Kotlin local value | `"$USERNAME"` with `val USERNAME` | `"alice"` | `alice` | Kotlin wins; user chose this. |
| `"…"` ordinary | unbound UPPER | `"$NOPE_NO_BINDING"` | (Kotlin compile error: `Unresolved reference 'NOPE_NO_BINDING'`) | (won't compile) | Author must escape. |
| `"""…"""` raw triple | braced shell var | `"""\${USER}"""` | **COMPILE FAILS** (`Unresolved reference 'USER'.` at L1:C53, see T2 evidence F1) | (won't reach bash) | `\$` is NOT an escape in raw triples. The backslash survives as a literal byte, Kotlin still sees `${...}` and tries to expand. |
| `"""…"""` raw triple | braced shell var | `"""${'$'}USER"""` | `"$USER"` | `$USER` | **`${'$'}` is the only safe form inside raw triples when `\$IDENT` is desired.** |
| `"""…"""` raw triple | unbraced shell var | `"""\$USER"""` | **COMPILE FAILS** (`Unresolved reference 'USER'.` at L1:C52, see T2 evidence F3) | (won't reach bash) | Same as F1: `\$` is not escape in raw triples. |
| `"…"` + `withCredentials(…) { … }` | expand credential var | `"$USER_PASS"` | (escaper rewrites `$USER_PASS` → `${'$'}USER_PASS` in source; Kotlin then compiles to `$USER_PASS`) | `$USER_PASS` | Escaper output is correct in every literal type. |

**Author rule of thumb.**
- Inside ordinary `"…"`: use `\$IDENT` or `\${IDENT}`. Both compile to
  literal `$IDENT` / `${IDENT}`. bash expands as expected.
- Inside raw `"""…"""`: use `${'$'}IDENT` if you want a literal `$`
  to survive into bash. The `\$` form is also rejected (compile
  fail), because raw triples do not honour backslash escapes on `$`.
- When Kotlin should win (Kotlin local shadowing): bare `$IDENT`
  (no escape) in any literal type.

> Byte-level evidence (Form A..F three-phase probes): see
> `docs/v2/07-uat/evidence/sh-var-scope-contract/S2-three-phase-probe-extended.txt`
> (sha256
> `896e5f1dd5003a608ca9ef043a3053eac9c3b32c8ec509c096c29ab9c17951ca`).
> Layer-4 (bash in-process) confirmation: see
> `gap04-formF-raw-triples.txt`.

## 3. Trap forms (author-avoidance table)

These compile but produce invalid output. Some fail Kotlin compile
before reaching bash; others silently fail at runtime.

| Form | What the author wrote (literally) | Kotlin verdict | bash verdict | Notes |
|---|---|---|---|---|
| Trap T1 — `\${'$'}USER` outside raw triple | `"echo ${'$'}USER"` | OK (compile) | bash rejects: `sustitución errónea` / `bad substitution` | Locked by `S2ThreePhaseProbeTest` Form B; negative fixture `99-trap-form-dollar-dollar-quote.pipeline.kts` + `TrapFormNegativeFixtureTest` (commit `83882467`). |
| Trap T2 — `\${'$'}{USER}` outside raw triple | `"echo ${'$'}{USER}"` | OK (compile) | bash rejects: same as T1 with extra `{` | Same lock as T1, captured byte-level in CHARACTERISATION.md §5.3. |
| Trap T3 — bare unbraced credential with no escape and no `withCredentials` | `"echo $USER_NO_BINDING"` | FAIL (`Unresolved reference`) | (won't compile) | Author must escape, or scope a Kotlin local. |
| Trap T4 — bare unbraced shell var when Kotlin local shadows it | `val USERNAME="alice"; sh("echo $USERNAME")` | OK (Kotlin resolves) | (bash never sees `$USERNAME`) | Kotlin wins. Author's responsibility to choose. |
| Trap T5 — `\${USER}` inside raw triple | `"""echo user=\${USER}"""` | **FAIL** (`Unresolved reference 'USER'.` at L1:C53) | (won't compile) | NEW: cycle T2 measurement. `\$` is not escape in raw triples. |
| Trap T6 — `\$USER` inside raw triple | `"""echo user=\$USER"""` | **FAIL** (`Unresolved reference 'USER'.` at L1:C52) | (won't compile) | NEW: cycle T2 measurement. Same property as T5. |
| Trap T7 — `\$USER` in raw triple, compile-passes-only | n/a in raw triples | (always fails compile per T5/T6) | n/a | The compile-time fail is the protective side of raw triples. |

> Byte-level evidence: `S2-three-phase-probe-extended.txt` Form B
> for T1; Forms F1, F3 for T5, T6 (sha256
> `896e5f1dd5003a608ca9ef043a3053eac9c3b32c8ec509c096c29ab9c17951ca`).

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

`Kotlin24ScriptingHost.mapDiagnostic` currently reports positions
against the *escaped* source, not the original. The escoper inserts
a trap-form guard (`\${'\$'}`, **5 chars net delta**) before each
credential-bound `$VAR` reference, so any diagnostic emitted by the
Kotlin compiler on the ESCAPED source points at a column that is
**shifted +5 chars per credential-bound $VAR upstream** of the
diagnostic's column on the original source.

This cycle's byte-level measurement (`ShVarScopeGap05Test` T6,
evidence file `gap05-diagnostics.txt`, sha256
`9395c1476b37f5fc4a922e78162a4dbe5c26152eecc64212683c689eac53d3e8`):

```text
F2_TRIGGER_DATA_C2: author_column=123 escaped_column=128 escape_shift=5
```

The user's editor cursor sits at column 123; the Kotlin diagnostic
currently points at column 128.

The opening of F2 is **gated** by the operator's UX judgement
(not-yet-made: is +5 characters of diagnostic drift on every
credential-bound `$VAR` reference user-perceivable friction?). The
predicate for opening F2 is exactly:

```text
F2_TRIGGER = (Gap05Test reproduces a deviation where
              editor_position != mapDiagnostic_position
              AND that deviation causes user-perceivable friction)
```

If the operator judges YES, a NEW cycle (under its own GO) opens
with a proposal for an offset map in `mapDiagnostic`. The byte-level
data above is durable evidence for that future decision.

Until then, the contract is: **diagnostic columns follow the
escaped source, not the original**. With no `withCredentials`
active, the escoper is byte-identity and the columns match the
author's; this baseline is locked by the third sub-case of
`ShVarScopeGap05Test`.

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
