# S4-A0 — Scripted runtime characterization

**Status: CHARACTERIZED. No production code changed by this slice.**

The point of S4-A0 is to establish what the scripted path actually does before
S4-A1 replaces it and S4-B2 rewrites it. Everything below was measured by running
the real `ScriptedSourceMapper` + `ScriptedSourceLowering` over real source text,
not inferred by reading.

Evidence: `S4A0ScriptedLoweringCharacterizationTest`, 10/10 green, pinned in
`v2/pipeline-application/src/test/…/scripted/`.

---

## 1. The real chain

```
.pipeline.kts body (Kotlin text)
  → ScriptedSourceMapper              (pipeline-scripting-kotlin24, PSI, KtTreeVisitor)
      emits ScriptedMappedCall(LOCATION, kind)
  → ScriptedSourceLowering            (same module)
      LOCATION → line/column → character offset
      overwrites a FIXED character span with the façade text
  → generated Kotlin: object : CompiledScriptedEntryPoint
  → Kotlin24ScriptingHost             compiles + evaluates, ONE evaluation
  → CompiledScriptedEntryPoint.execute(steps: ScriptedStepFacade)
  → ScriptedRegistryInvoker           (pipeline-application) → registry / journal / capabilities
```

The hand-off is a **location**, and the lowering turns it back into text surgery.
That is the whole architecture of the defect class below.

Call-site identity is `sourceId:line:column:kind` — no loop ordinal, no structural
path. See defect 3.

---

## 2. The baseline that works

These are the cases S4-A1 must preserve, and they are correct today:

| Authored | Classified | Generated |
|---|---|---|
| `pwd()` | `Pwd(tmp=false)` | `steps.pwd(ScriptedCallSiteId("s4a0:1:9:pwd"), tmp = false)` |
| `pwd(tmp = true)` | `Pwd(tmp=true)` | rewritten, variant preserved |
| `isUnix()` | `IsUnix` | `steps.isUnix(ScriptedCallSiteId("s4a0:1:9:isUnix"))` |
| `sh("echo hi")` | `Shell` | untouched — correct, an eager sh has no runtime value |

So: **two of the six runtime-returning kinds are sound, and the eager/eager-vs-
returning distinction for plain `sh` is right.**

---

## 3. Three characterized defects

Each is asserted as a *pinned* characterization: the test asserts the broken
behaviour, so the defect cannot change silently, and it goes RED the day someone
fixes it — which is the signal to rewrite it to assert the correct behaviour.

### 3.1 The rewritten span is the length of the EMPTY-argument form

**Destination: S4-B2 (exact PSI matching).**

```
authored : val c = readFile("config.yaml")
generated: val c = steps.readFile(ScriptedCallSiteId("s4a0:1:9:readFile"), "")nfig.yaml")
                                                                                       ^^^^^^
```

```
authored : val e = fileExists("config.yaml")
generated: val e = steps.fileExists(ScriptedCallSiteId("s4a0:1:9:fileExists"), "")nfig.yaml")
```

`rewriteRuntimeReturningCalls` hardcodes the span as the length of the
empty-argument form — `readFile("")` = 12 characters, `fileExists("")` = 14 —
while the mapper accepts **any** non-empty argument list. The author's argument
survives past the rewritten call and **the generated source is not valid Kotlin**.

`offsetOfAt` does not compare the text at the offset. Its own KDoc says so:

> "We can't always match the literal text… This is a permissive offset locator;
> the rewriting strategy is offset-locked, so mis-attribution is bounded by the
> line/column uniqueness within the file."

The only form that works is `readFile("")`, whose length happens to equal the
hardcoded span. **In practice `readFile(path)` and `fileExists(path)` — the only
two useful forms — cannot work through the scripted path at all**, and
`FACADE_SCHEMA_VERSION = "facade-r4-pwd-readFile-fileExists-shReturnStdout-v1"`
advertises a capability the code cannot deliver.

This is the strongest argument for S4-B2: matching the callee, PSI node, source
range and argument expressions exactly would make a span mismatch a
`COMPILATION_REJECTED` rather than a mangled artifact.

### 3.2 `ScriptedCallKind.ShellReturnStdout` is unreachable

**Destination: S4-A2 (migration) with S4-B2 (classification).**

```
authored : val out = sh("echo hi", returnStdout = true)
classified: Shell                      ← eager, not ShellReturnStdout
generated: val out = sh("echo hi", returnStdout = true)     ← never rewritten
```

The mapper's `when` has the eager `sh` arm **first**, and it tests only
`calleeExpression?.text == "sh" && !isDotQualified` — with no `returnStdout`
guard. Kotlin's `when` takes the first matching arm, so the runtime-returning
arm 40 lines below can never be selected. Pinned by a sweep over four spellings:
no authoring of `sh` reaches it.

Consequence: the author asked for stdout as a typed value and received an eager
step, so `out` binds to whatever the eager call yields. Under Semantic
Constitution §2 that is a **semantic drop** — a user intent with no typed
carrier and no fail-closed path.

The mapper's own KDoc asserts the opposite:

> "Unqualified runtime-returning `sh(…, returnStdout = true)`: … Distinct from the
> eager `sh(...)` branch above — both compile-time legal."

The code does not implement that distinction. This is a falsified source claim in
shipped code — the same class as the S3 claims this repository just corrected in
RP7-SEM-S3-R1.

### 3.3 Call-site identity has no loop ordinal

**Destination: S4-C1.**

```
for (i in 0 until 3) { val v = pwd() }
```

lowers to exactly **one** `ScriptedCallSiteId`, so all three iterations share one
identity. The identity is `sourceId:line:column:kind`; there is no ordinal and no
structural path. Replay cannot distinguish iteration 0 from iteration 2, and the
durable fingerprint that should tell them apart does not exist yet.

This is exactly the `callsite-X / iteration-N` shape S4-C1 is specified to
produce, so the characterization names the gap it must close.

### 3.4 Observed alongside: the plugin-lock dimension of the artifact identity is a constant

Not a separate test, but measured while reading the same file and recorded here
because S4-C5 depends on it.

`ScriptedSourceLowering` computes:

```kotlin
pluginLockDigest = PLUGIN_LOCK_DIGEST      // private const val PLUGIN_LOCK_DIGEST = "r3-plugins-v1"
```

A constant, not a computed digest. Two artifacts compiled against **different
plugin sets** therefore carry the **same** compatibility identity. The
`ScriptedArtifactIdentity` record has a field for it and does not fill it.

This is the `DomainEvent`-four-pins class of defect one level up: a declared
dimension that no code produces. S4-C5's compatibility fingerprint must not
inherit it.

---

## 4. What this changes for the S4 plan

Nothing here reopens S3 and nothing here is a regression — all three defects are
pre-existing and were never exercised, because no test ever drove `readFile` or
`fileExists` with a real argument, and no test drove `sh` with `returnStdout =
true`. The characterization suite exists so that is no longer true.

Concretely, for S4-A1:

- The single `invokeTyped<I, O>` authority has **four** call kinds to migrate, not
  six. `ShellReturnStdout` has no producer and must be built, not migrated.
- `readFile`/`fileExists` cannot be "migrated" in place: their current lowering
  produces invalid Kotlin, so the payload must come from the PSI argument
  expressions rather than from a character span. That is a prerequisite for the
  migration, not part of it.
- The eager/returning distinction must be decided in the **mapper**, before
  lowering, because today the first `when` arm swallows it.

For S4-B2, the evidence is now concrete rather than predicted: an exact PSI match
has to cover the argument expressions, or defect 3.1 recurs in a more expensive
form.
