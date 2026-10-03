# S4-DATA — The scripted front end carries the author's argument EXPRESSION

**Status: STEP-CERT candidate on the block gate in §1.**
**Not a PRODUCT-GATE certification** — see §7.

Closes the largest remaining functional defect of the scripted path, and the one
the S4-A0 characterization named first: `readFile`/`fileExists` were unusable
because the lowering did not carry their argument.

---

## 1. Verification

```text
base commit  292ee788d096d867cb246c4b7527efb0a9ef237a  (S4-C4)
argv         cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
             && ./gradlew :pipeline-scripting-api:test
                :pipeline-scripting-kotlin24:test
                :pipeline-application:test
                :pipeline-architecture-tests:test
                --rerun-tasks --console=plain
exit         0
duration     29m
tasks        89 actionable, 89 executed
result       BUILD SUCCESSFUL
modules      405 test classes, 2737 tests, 131 skipped, 0 failures, 0 errors
             pipeline-scripting-api        16 classes,   89 tests
             pipeline-scripting-kotlin24   14 classes,   56 tests
             pipeline-application         291 classes, 2191 tests, 121 skipped
             pipeline-architecture-tests   84 classes,  401 tests,  10 skipped

code+test    5 files (3 production, 2 test)
             sha256:7cce84cee67ac497380d9e528dd67724740545b4826cd30cb5cf017997c812da
             (staged diff vs base 292ee788, i.e. the bytes the gate compiled)
log          /var/home/rubentxu/.local/state/pipelinek-gates/s4data-block.log
             sha256:8d14ef1a3273a1f387faf40e241e9eac1d86f7033356e90da849c3317adeec17
```

**A fingerprint that was wrong, and what it hid.** The first fingerprint computed for
this slice was `2796c3e7…` over four files, taken with `git diff` — which compares
the working tree against HEAD and therefore **omits untracked files**. The new
`S4DataArgumentExpressionTest.kt` was untracked at that moment, so the hash did not
cover the very test that proves the slice. Comparing that hash against the staged
diff is what exposed it: the two disagreed.

The content was verified either way — the test is in the compiled set and its XML
carries `tests="10" failures="0" errors="0"` with a timestamp inside the gate window
— but a fingerprint that silently excludes a file is exactly the kind of evidence
that must not be quoted. The number above is the staged diff, which covers all five
files.

The log contains **zero** `FAILED` matches — the five that appear in a full-suite
log are test names containing the word, and those tests are not in this module
set.

This is the **block** gate, not the full suite: the four modules the change
touches, plus the architecture fitness that guards the seam, re-executed from
scratch with `--rerun-tasks` so no result is inherited from an up-to-date task.
The full `./gradlew check --rerun-tasks` is reserved for S4-PRODUCT.

**The log lives outside `/tmp` on purpose.** The first attempt at this gate wrote
to `/tmp` and that log was lost when the directory was cleaned, leaving a
half-finished run whose only surviving trace was two stale XML files from a
mutation run. A gate whose evidence cannot be read is not evidence; the run was
repeated and the log is now durable.

---

## 2. The defect

`readFile`/`fileExists` were payload-free `data object`s. The lowering had nothing
to put in the call and wrote a literal `""`:

```text
authored : val c = readFile("config.yaml")
generated: val c = steps.readFile(ScriptedCallSiteId("s4a0:1:9:readFile"), "")
                                                                       ^^^^^
```

Two defects came out of that one gap. The path was **dropped**, and — combined
with the span hardcoded to the length of the empty-argument form — the argument's
own text survived *past* the rewritten call, so the generated source was not
valid Kotlin. S4-A1b fixed the span; nothing had fixed the payload.

The mapper accepted `readFile(anything)` while the lowering could express only
`readFile("")`. **In practice the only form that could work was the one whose
length happened to equal the hardcoded span.**

---

## 3. The fix

### D1 — the kinds carry the argument

```kotlin
data class ReadFile(val pathExpression: String) : ScriptedCallKind
data class FileExists(val pathExpression: String) : ScriptedCallKind
```

The payload is the author's **expression as PSI text**, not its value. That
distinction is the whole fix: re-scoping the same expression is what makes
`readFile(file)`, `readFile("$dir/config.yaml")` and
`readFile(resolve(p).toString())` work, because the expression is evaluated
where the author put it. It is the rule `ScriptedCallKind.Shell` already
followed for its `script`, so the front end now has ONE rule rather than two.

### D2 — the span and the arguments come from the same node

`ScriptedMappedCall.sourceLength` is the call expression's own `textRange`, and
the argument text is read from that same `KtCallExpression`. The two cannot
disagree because they are the same node. No length is fabricated anywhere.

### D3 — fail-closed, where it used to be fail-open

`recordPathArgument` decides three cases at the call expression:

| Case | Before | Now |
|---|---|---|
| exactly one path argument | mapped, payload dropped | mapped, expression carried |
| **no** argument | **not mapped** — the call survived as a bare `readFile` and failed with `Unresolved reference` | **rejected** with a diagnostic |
| **more than** one | **not mapped** the same way | **rejected** — the façade takes one path, so a second would be dropped in the generated call |

The middle and bottom rows are the ones worth noting. "Not mapped" is not
validation: the author still wrote a call, and an unmapped call is a call the
lowering declines to rewrite, which is the same fail-open that made the bare `sh`
fail at compile time. And a second argument being silently dropped is a semantic
drop, which the Semantic Constitution forbids outright.

### D4 — ordering and nesting

Unchanged mechanism, now pinned. The lowering walks calls in descending position
order precisely because the first replacement changes the offsets the second is
resolved against; carrying a variable-length expression widened every
replacement, so that ordering is now load-bearing in a way it was not before.

---

## 4. The corpus, and proof it is not vacuous

`S4DataArgumentExpressionTest` — 10 tests. The D3 set is the point of the slice:
every one of those forms is **impossible** for a literal-only implementation to
produce.

```text
val c = readFile(file)                                  // bare variable reference
val c = readFile("$dir/config.yaml")                    // template, known only at run time
val e = fileExists(path.resolve("x").toString())        // nested method calls
val c = readFile(file = "a.txt")                        // named-argument form
val out = sh(command(), returnStdout = true)            // sh obeys the same rule
```

D4: a `readFile` nested inside an `if` whose guard is itself a `fileExists`; two
calls on one line, where the first replacement shifts the second's offsets; and a
call inside a loop, asserting the call-site identity stays `sourceId:line:column`
with no loop ordinal folded in — the ordinal is a runtime property and carrying
the expression must not have changed it.

**Two mutations, both with one-to-one attribution:**

```text
M-s4data-1  lowering writes the literal "" again
            -> 7 RED   (2 characterisation + 5 corpus)

M-s4data-2  mapper uses the CALLEE span instead of the call-expression span
            -> 13 RED  (7 corpus + 6 pre-existing lowering/classification tests)
```

M-s4data-2 is the stronger signal: shortening the span is exactly the mistake
that produced `steps.fileExists(..., "")nfig.yaml")`, and it is caught by tests
that existed before this slice as well as by the new ones.

The two obsolete `CHARACTERIZED DEFECT` tests were replaced rather than left to
fail, per the S4-A0 §3 discipline: a pinned characterization that goes RED because
someone fixed it is the signal to rewrite it.

---

## 5. What this does NOT establish

The generated body is compared **as text**. This suite proves the rewrite is
faithful; it does not prove the result compiles, because the generated
`execute(steps)` body references the surrounding locals and is only well-formed
in the scope the author wrote it. That is the installed-distribution question,
and it belongs to S4-PRODUCT.

Asserting only "it compiled" would have been worse than useless here: an
implementation that dropped the argument and emitted valid Kotlin would pass.

---

## 6. Still open after this block

| Item | State | Destination |
|---|---|---|
| Generated source compiles in scope, against a real distribution | **open** | S4-PRODUCT |
| Structural replay/ordinal under resume | **constraint**, not a proven defect | S4-IDENTITY (BLOQUE 2) |
| `PLUGIN_LOCK_DIGEST` is a constant | **open** | S4-COMPAT (BLOQUE 3) |
| `Unstable` cannot cross the registry seam | **open question**, not a proven defect | S4-COMPAT (BLOQUE 3) |
| `WULpr402` token hole, source maps, `ContextOverlay` disposition, K2 spike | **open** | S4-B (BLOQUE 2) |
| External plugin returning a typed scripted value | **open** | S4-PRODUCT |

---

## 7. What is deliberately NOT claimed

- **No CI.** `.github/workflows/` is empty since `754ddda0`; remote verification
  is unavailable by construction, and may be neither asserted nor denied.
- **No full `./gradlew check --rerun-tasks`.** Deliberate, per the block cadence:
  that is S4-PRODUCT's gate. Claiming it here would be a false green.
- **No UAT against an installed distribution.**
- **This is a STEP-CERT, not a PRODUCT-GATE.**
- **Bound to a tree fingerprint, not a SHA**, until the commit lands.
