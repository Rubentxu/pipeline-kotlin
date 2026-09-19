# SH-VAR-SCOPE WU — Characterization (S0)

**Status**: Active cycle. Independent WU, sibling of WC-SCM and SH-classpath.
**Authority**: extends from `WC_SCM_CLOSURE_RECEIPT.md` (commit 9be1cdbf) and
`SH-classpath/CLOSURE_RECEIPT.md` (commit e76700ac).
**Scope**: define and certify how the script compiler
(Kotlin string templates, `${'$'}VAR` escapes), the typed Input
(`StepSpec.Shell.command` → `CoreShellInput.command.script`), the
canonical `core.sh` handler, and the process executor
(`ShExecution` → `DurableShellExecutor`) interact. The focus is
the seam **between Kotlin interpolation (compile-time) and shell
expansion (process-time)** when a name like `$USER` could be
either or both.

This is **S0 — characterisation**. The WU only closes after S3
lands with evidence on the acceptance gate. Untouched surface: the
compiler, the `core.sh` handler, and the public DSL API MUST NOT
be modified without a reproduced root cause and a chosen contract
(S2 + S3). Caracterización + alternative-design is permitted.

---

## 1. Transformation layers observed

### 1.1 Source text on disk → Kotlin compiler input

Source: `v2/pipeline-scripting-kotlin24/src/main/kotlin/.../Kotlin24ScriptingHost.kt:78-91`.

```
scriptText               (raw file / inline content)
  └─ EnvVarNameExtractor.extract(scriptText)
     → Set<String> envVars                (env-var names from withCredentials bindings)
  └─ ScriptTextEscaper.escape(scriptText, envVars)
     → escapedText                        (rewrites $VAR → ${'$'}VAR when VAR ∈ envVars
                                          outside any ${...} block, anywhere in source)
  └─ StringScriptSource(escapedText)      (sent to the Kotlin scripting compiler)
```

Cache key: computed from `scriptText` (original), not `escapedText`
(`Kotlin24ScriptingHost.kt:160`). Diagnostics: derived from the
**escaped** source by `mapDiagnostic` (`Kotlin24ScriptingHost.kt:204-225`),
which means a compile error referencing a token inserted by the
escaper will report line/column in the **escaped source**, not the
**original source**. (The escaper only inserts tokens inside
double-quoted Kotlin strings and only at brace depth 0; inserted
length is bounded by the size of `${'$'}` literals.)

### 1.2 DSL `sh("…")` → typed Step input

Source: `v2/pipeline-scripting-api/src/main/kotlin/.../dsl/PipelineDsl.kt:1066-1089`
(`StageScope.sh(String)`, `StageScope.sh(String, Boolean, Boolean)`).
Both forms add a `StepSpec.Shell(command, isScriptBlock, returnStdout)`
to the stage steps. **No interpolation, substitution, or escaping
happens here.** `command` is the Kotlin `String` value at
construction time, including whatever Kotlin template expansion
the user wrote into the string literal.

Compiled: `v2/pipeline-application/.../DslCompiledPipelineCompiler.kt:560-590`
(`buildShellScript`) only operates on **inside-script-block** `sh(...)`
calls. Plain `sh(...)` at stage level passes through to
`StepSpec.Shell` whose `command` is the literal Kotlin string.

Encoded payload: `DslCompiledPipelineCompiler.kt:638-649` puts the
string into `command`. The `CoreShellInput` codec encodes the
script as JSON `JsonPrimitive` (`CoreShellStep.kt:93-102`).
**No mutation of the string content** between DSL construction and
typed Input.

Decoded payload: `CoreShellStep.kt:104-138` decodes either via the
canonical codec field names (`"script"`, `"returnMode"`) or via
the DSL compiler field names (`"command"`, `"returnStdout"`,
`"isScriptBlock"`). The decoded `script` becomes
`CoreShellInput.command.script` verbatim. The string byte stream
is unchanged through the codec round-trip.

### 1.3 Typed Input → process execution

```
CoreShellInput.command.script
  → ShellCommand.script      (typed value, byte-equivalent)
  → ShExecution.invokeShell(...)::scriptContent
  → DurableShellExecutor.launch(...):
      Files.writeString(scriptFile, scriptContent)       ← writes to disk
      Files.writeString(wrapperFile, buildWrapperContent(... scriptFile ...))
      ProcessBuilder("setsid", "bash", wrapperFile.toString())
```

(P2 invariant: `User script content NEVER appears in argv`,
verified by `verifyScriptNotInArgv` self-test in
`DurableShellExecutor.kt:110-111`.)

Env: `EnvModel.apply(shOptions.env)` runs at line 192/206 of
`ShExecution.kt`, transforming user-provided env then is piped
into `pb.environment().putAll(...)` (`DurableShellExecutor.kt:283-286`,
typed via `SecretHandle.materialize()` once at the seam, before
the OS process starts). **Env values NEVER enter argv.**
SecretHandle is wiped after `WithCredentialsExecutor` consumes it.

### 1.4 Net effect

The script string the user writes in `sh("...")` reaches the
`bash` process verbatim, once it has been through the
`ScriptTextEscaper` rewrite of **only those `$VAR` references
whose `VAR` is in the set of names extracted from
`withCredentials(...)` blocks**. `$VAR` references with no
matching credential binding are untouched. Kotlin string template
interpolation happens **at Kotlin compile time** (inside double-
quoted `"..."` literals), affecting what literal characters the
script string contains.

`withEnv` overrides: `PipelineDsl.kt:1445-1469` constructs
`StepSpec.WithEnv(overrides = listOf("VAR=value"|"PATH+X=/dir"))`,
folded by the dispatcher into `EnvModel.apply` at execution time.
PATH+/PATH+= prepend semantics are handled there.

Diagnostics: any compile error from the Kotlin compiler reports
against the **escaped** source. Tokens inserted by the escaper
(`${'$'}`, plus the `}` close) shift the line/column mapping
nominally. Concretely, the escaper only inserts inside string
literals, so the only diagnostics affected are those that
target the script body of `sh("...")`. No diagnostic ever
reports column 0; the cost is bounded and predictable (each
`$VAR` → `${'$'}VAR` adds 6 characters; line/column delta is
6 for any token past the insertion point in the same line).

---

## 2. The Kotlin ↔ shell variable problem today

### 2.1 What an end user must know (empirical)

To deliberately mix Kotlin string interpolation with shell variable
expansion, the user has **two equivalent Kotlin-string escape
mechanisms**, both producing a literal `$` in the compiled string
that bash then expands:

| Kotlin source | Kotlin-compiled | bash sees | bash result |
|---|---|---|---|
| `"echo $USER"` (no escape) | (compile error: unresolved `USER` if no Kotlin local) | (won't compile) | (won't compile) |
| `val USER="alice"; sh("echo $USER")` | `"echo alice"` | `echo alice` | literal `alice` (Kotlin wins) |
| `"echo \$USER"` | `"echo $USER"` | `echo $USER` | bash expands `USER` env var |
| `"echo \${USER}"` | `"echo ${USER}"` | `echo ${USER}` | bash expands `USER` env var |
| `"echo \${USER:-fallback}"` | `"echo ${USER:-fallback}"` | `echo ${USER:-fallback}` | bash expands with default |
| `"echo \$(hostname)"` | `"echo $(hostname)"` | `echo $(hostname)` | bash command substitution |
| `"echo \${USER}"` (with `val USER = "alice"`) | `"echo ${USER}"` (Kotlin leaves alone) | `echo ${USER}` | bash expands USER env (NOT the Kotlin local `alice`) |

Two valid Kotlin escapes exist for the same outcome:
- `\$VAR` — minimal Kotlin escape, produces literal `$VAR`
- `\${VAR}` — Kotlin escape before the brace, produces literal `${VAR}`

They are semantically equivalent for bash. The first is shorter and
is the natural Kotlin way; the second matches Jenkins Groovy
convention (which is why `v2/compatibility/14-credentials-bindings.pipeline.kts:48`
uses `\${API_KEY}`).

**Trap form — DO NOT USE**: `\${'$'}VAR` or `\${'$'}{VAR}`.
The Kotlin fragment `${'$'}` evaluates to the literal string `$`,
so `\${'$'}VAR` compiles to `${$VAR}` (bad bash: "sustitución
errónea"), and `\${'$'}{VAR}` compiles to `${$}{VAR}` (also bad bash).
This trap was hit on the first iteration of S1 because `WithCredentialsCompileIntegrationTest`
and `14-credentials-bindings.pipeline.kts` use the `\${...}` form but
several early WU drafts wrote the `${'$'}` form. The trap is real
and is the most likely source of confusion for new users.

**Same-name collision**: with `val USER = "alice"; sh("echo $USER")`,
Kotlin interpolates `alice` into the string and bash never sees a
`$USER`. With `val USER = "alice"; sh("echo \${USER}")`, Kotlin
leaves the literal `${USER}` and bash expands it from env (if set)
to the env value, NOT `alice`. So **Kotlin wins for the no-escape
form, bash wins for the escape form**. The user must know which
form they want.

### 2.2 What the escaper protects (and what it doesn't)

`ScriptTextEscaper.escape` only knows about env-var names from
`withCredentials` bindings. Concretely:

- For `withCredentials(CredentialsBinding.string("id", "USER"))`,
  `EnvVarNameExtractor.extract` returns `{"USER"}`, and the escaper
  rewrites **any unbraced `$USER` reference** in source to `${'$'}USER`
  so Kotlin compiles literal `$USER`. (Note: the escaper's rewrite
  is the **broken `${'$'}` form** for unbraced names; the user must
  not rely on this — for any name with `${...}` shape, the user
  should write `\${...}` directly, bypassing the escaper.)
- For names NOT in `withCredentials` bindings, the escaper does
  not touch them. Kotlin string-template rules apply.

What this means in practice: today, only `withCredentials` vars
get auto-protection. Any other shell var (`$USER` from system env,
`$HOME`, `$PATH` etc.) is the user's responsibility to escape
correctly using the Kotlin `\$` convention.

The trap form `\${'$'}VAR` is exactly what the escaper **produces**
for `withCredentials`-bound names. **The escaper's own output is
invalid bash** for unbraced `$VAR` references. This is a known
limitation that has been hidden because (a) `core.sh` re-exec
usually fails the wrapper script anyway, and (b) `withCredentials`
integration tests do not assert the actual bash behaviour of the
rewritten command. The escaper's rewrite should arguably use the
simpler `\$` form (which is correct bash); or the escaper should
only rewrite when the next character would otherwise form a Kotlin
template — i.e., when the rewrite is actually needed.

The user's WU is about the **whole contract**, not just the
escaper. The escaper is one component; the contract must describe
what every layer guarantees.

### 2.3 What's not covered today

The existing test suite:

- ✓ `ScriptTextEscaperTest` covers the escaper's pure cases
  (variants, edges).
- ✓ `WithCredentialsCompileIntegrationTest` covers the
  cred-bound env-vars case end-to-end.
- ✗ **No test covers a Kotlin local variable whose name happens to
  be a shell variable name** (e.g. `val USER = "alice"; sh("echo
  $USER")`).
- ✗ **No test covers `$VAR` in `sh(...)` outside any `withCredentials`
  block** — neither the success path (when no Kotlin var shadows
  the name) nor the failure path (when one does).
- ✗ **No test covers shell-specific expansions** like
  `${'$'}{VAR:-default}`, `${'$'}(cmd)`, `${'$'}VAR:-x}`,
  `${'$'}VAR##pattern}` inside `sh("...")`. Each will either be
  parsed or rejected by Kotlin depending on whether `{` opens a
  Kotlin template or a shell expansion — without a clear contract
  the result is positional.
- ✗ **No test covers multiline strings with `$VAR`** line-by-line,
  including line-end `$VAR` followed by newline plus indent at next
  line.
- ✗ **No test verifies the diagnostic line/column for the source the
  user wrote** when the **escape** has shifted the column index.
  Kotlin reports escaped-source positions; the user edits
  original-source positions.
- ✗ **No test covers `$VAR`-prefixed env names in `withEnv`** vs
  Kotlin literal-dollar escapes inside `sh(...)`. They are
  separate layers (env-entry form is `"VAR=value"`, not
  `"$VAR=value"`).

### 2.4 Invariants already locked in

- P2: script content never appears in argv. (`DurableShellExecutor:110-111`)
- WS-S-005: env injected via `pb.environment().putAll`, never argv.
- WS-S-006/WS-S-007: `JAVA_HOME`/`M2_HOME` prepend semantics.
- ML-R3: sandbox profile denies certain env entries and rewrites
  PATH.
- ML-R4 typed env: `SecretHandle` carried through typed layers;
  masked entries propagated without un-masking.
- WU-LPR-011R2: console transcript redaction happens BEFORE
  persistence (streaming pump at the write boundary).

None of these cover the same-name conflict; the user's WU is
about behaviour at the seam between scripting and shell semantics.

---

## 3. The decision space

The next slice (S2) must fix the contract. The matrix the
characterisation commits to filling (S1) — same-name vars,
withEnv nesting, credentials, multiline strings, `\$VAR`,
`${'$'}VAR`, `${'$'}{VAR`, `${'$'}{VAR:-default}`, `${'$'}(cmd)`,
spaces and special chars, errors and their source mapping — must
all be answered before a contract can be proposed. The decisions
open in S2:

**Option A — Status quo + docs.** Keep `ScriptTextEscaper` as-is,
make the convention (`${'$'}VAR` for shell) explicit in the user
guide, document the same-name conflict cases. No code change.

**Option B — Expand the escaper's scope.** Include env names from
`withEnv` blocks too, not just `withCredentials`. Treat any
all-caps identifier that matches an env override as a candidate
for escape. This closes one narrow hole (withEnv collisions),
but is not general.

**Option C — Add an explicit shell-string API.** New API
`shellScript { lines { ... } }` that constructs a plain
`StepSpec.Shell(command)` whose body is built from Kotlin string
concatenation, NEVER passed through a Kotlin double-quoted string
literal at construction time. Compiler sees only `sh(s)` where
`s` is a single-token expression of type `ShellScriptBody`, not
a Kotlin String. This avoids Kotlin string templates entirely.

**Option D — Adopt the Jenkins Groovy semantics.** Treat all
`$IDENT`-style references in `sh(...)` as shell expansions
unconditionally (Kotlin string templates would never match, even
when an identifier is in scope). Force the user to use bracket
form `"${'$'}{IDENT}"` for Kotlin interpolation. This is the
closest to what real users want but it requires breaking Kotlin
semantics in a small bounded way (escape any `$IDENT`-style ref
that would otherwise be a Kotlin template).

**Option E — Keep the escaper's invariant, but track source
positions.** After the rewrite, store an offset→offset map for
diagnostics, so line/column reports match the original source.
This fixes the diagnostic-mapping gap but doesn't fix any shell
semantics — only the developer's reading experience.

The contract is S2's job; the user said "no automatic `$VAR`-to-
shell rewrite" and "no global rewrite". **Option D is excluded**
unless explicitly approved. **Options C and E** are the most
plausible: C avoids the collision by changing the API; E fixes the
diagnostic-mapping without changing semantics.

What S1 will record empirically is what the current behaviour
looks like for the user's listed scenarios. S2 will draft a
contract from that evidence and present it back before any code
touches the compiler, the handler or the public API.

---

## 4. Out of scope (preserved from prior receipts)

- The kotlin-scripting classpath composition (closed in e76700ac).
- The coordinator / dispatcher / capability-routed handler shape.
- `core.echo`, `core.sh`, `core.error`, core Steps overall.
- The `BUILD_STRING` flag negotiation.
- Anything outside `sh(...)` semantics (`echo`, `writeFile`,
  file-based Steps do not run an OS shell).

These surfaces stay frozen until S3 chooses a contract and the
implementation has its own receipt and certification trail.

---

## 5. Evidence captured in S1 (empirical corpus)

All scenarios run against installed `pipelinek` binary on
JDK temurin-24.0.2+12 with `USER=rubentxu` (system env).

### 5.1 Core matrix (corrected convention `\${VAR}` / `\$VAR`)

| # | Scenario source | Kotlin compiled | Bash sees | Outcome |
|---|---|---|---|---|
| s1-01 | `sh("echo hello-\${USER}-login")` | `echo hello-${USER}-login` | `echo hello-${USER}-login` | success: `hello-rubentxu-login` (bash expands) |
| s1-02 | `sh("echo name=[\${USER}]")` | `echo name=[${USER}]` | `echo name=[${USER}]` | success: `name=[rubentxu]` (bash expands) |
| s1-03 | `sh("echo fallback=[\${USER:-fallback-user}]")` | `echo fallback=[${USER:-fallback-user}]` | `echo fallback=[${USER:-fallback-user}]` | success: `fallback=[rubentxu]` (USER set, default not used) |
| s1-04 | `sh("echo host=\$(hostname)")` | `echo host=$(hostname)` | `echo host=$(hostname)` | success: `host=bazzite-rubentxu` (cmd subst) |
| s1-05a | `val USERNAME="alice"; sh("echo kotlin-$USERNAME")` | `echo kotlin-alice` | `echo kotlin-alice` | success: `kotlin=alice` (Kotlin wins) |
| s1-05b | `val USERNAME="alice"; sh("echo bash=\${USERNAME}")` | `echo bash=${USERNAME}` | `echo bash=${USERNAME}` | success: `bash=` (empty, no env USERNAME) |
| s1-05c | Same as 5b but with `USERNAME=from-env` set in process env | same | same | success: `bash=from-env` (bash wins) |
| s1-06 | `sh("echo only=[\${USER}]")` (no Kotlin var) | `echo only=[${USER}]` | `echo only=[${USER}]` | success: `only=[rubentxu]` |

### 5.2 Same-name collision proof (S1-05)

With `val USERNAME = "alice"`:
- **Without escape** (`sh("echo kotlin-$USERNAME")`): Kotlin string
  template resolves `USERNAME` to `"alice"`, bash never sees `$USERNAME`.
  Output: `kotlin-alice`.
- **With escape** (`sh("echo bash=\${USERNAME}")`): Kotlin compiles
  literal `${USERNAME}`, bash expands from env (empty in default
  process). Output: `bash-`. With `USERNAME=from-env` set in process
  env, output becomes `bash-from-env`.

**Decisive finding**: when a Kotlin local has the same name as a
shell var, **Kotlin wins for the no-escape form, bash wins for the
escape form**. The user must consciously choose.

### 5.3 Trap form (S1-11)

| Source | Compiled | Bash result |
|---|---|---|
| `sh("echo trap=\${'$'}USER")` | `echo trap=${$USER}` | bash: `sustitución errónea` (bad substitution) |
| `sh("echo trap=\${'$'}{USER}")` | `echo trap=${$}{USER}` | bash: `sustitución errónea` (bad substitution) |

The Kotlin fragment `${'$'}` evaluates to the literal string `$`,
which then collides with adjacent `{`. This trap was discovered
empirically on the first S1 iteration. It is the **same form that
`ScriptTextEscaper` itself produces** for unbraced `$VAR` references
in `withCredentials` bindings — meaning the escaper's output for
unbraced names is currently invalid bash. (For braced names
`${VAR}`, the escaper skips them entirely — Kotlin handles them
natively.)

### 5.4 Credentials (S1-08)

```kotlin
withCredentials(StepSpec.CredentialsBinding.usernameColonPassword("test-creds", "USER_PASS")) {
    sh("echo user=\${USER_PASS}")
}
```

Result: **compiles**, but execution fails with `No WithCredentialsExecutor configured;
credential scope cannot be acquired`. This is an **environmental**
failure (no credential store configured), not a contract failure.
The script compiled and the escaper rewrote `$USER_PASS` references
to the trap form; with a credential executor configured, the
credential would resolve and bash would expand `$USER_PASS` to
the secret value (with `capturedStdout` redacted by `StreamingRedactor`).

### 5.5 withEnv (S1-13, S1-14)

| Source | Compiled | Bash result |
|---|---|---|
| `withEnv(listOf("DEPLOY_ENV=STAGING")) { sh("echo deploy-env=\${DEPLOY_ENV}") }` | `echo deploy-env=${DEPLOY_ENV}` | success: `deploy-env=STAGING` (bash expands from runtime env) |
| `val deploy_env = "DEV"; withEnv(...) { sh("echo kotlin=$deploy_env"); sh("echo bash=\${DEPLOY_ENV}") }` | (Kotlin string template resolves lowercase; bash sees uppercase) | success: `kotlin=DEV`, `bash=STAGING` |

**Important**: `withEnv` bindings are NOT extracted by
`EnvVarNameExtractor`, so the escaper does NOT auto-protect them.
The user must escape `\$` themselves. With the correct escape,
bash expands from runtime env set by `withEnv`.

### 5.6 Diagnostic positions (S1-12)

Script `sh("echo user=$USER")` (no Kotlin local, no credentials):

- Diagnostic: `Unresolved reference 'USER'.`
- Position: `line=6, column=28, path=/tmp/sh-var-scope/s1-12-compile-error.pipeline.kts`
- Position is **original source** (no escaper rewrite happened
  because no `withCredentials` extracted USER).

### 5.7 Multiline (S1-10)

Triple-quoted Kotlin strings with embedded escapes work as
expected. bash sees a multi-line script and executes it line by line.
The script content flows through `Files.writeString(scriptFile, ...)`
byte-equivalent — no line-stripping, no escape interpretation by
the runtime (just byte-level pass-through to disk).

### 5.8 Summary of empirical findings

1. **The convention is `\$` or `\${...}`** in Kotlin source. Both
   produce correct bash. The `\${'$'}VAR` form is a trap that
   produces invalid bash.
2. **`ScriptTextEscaper` produces the trap form** for unbraced
   `$VAR` references in `withCredentials` bindings. This is a
   latent bug in the escaper that has been hidden because the
   `withCredentials` integration tests do not assert bash
   behaviour. The fix is for the escaper to emit `\$VAR` (which
   is the correct Kotlin escape) instead of `${'$'}VAR`.
3. **Same-name Kotlin vs shell**: Kotlin wins without escape,
   bash wins with escape. This is the user's responsibility.
4. **`withEnv` is not auto-protected** by the escaper. User must
   escape explicitly.
5. **No auto-rewrite happens** for `$VAR` references that don't
   match a `withCredentials` binding. So plain `$USER` (no
   binding) fails at Kotlin compile if there's no Kotlin local.
6. **Diagnostic positions** map to original source when the
   escaper did not run, and to escaped source when it did. The
   current `mapDiagnostic` does not transform positions back.

---
