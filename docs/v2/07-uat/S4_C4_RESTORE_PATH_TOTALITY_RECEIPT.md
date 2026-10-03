# S4-C4 — The restore path is total: no durable payload escapes as an exception

**Status: STEP-CERT candidate. Full local gate green on the tree fingerprint in §1.**
**Not a PRODUCT-GATE certification** — see §6.

Closes S4-A0 §3.4, the only open defect on the list with a security shape: a
`ClassCastException` crossing a typed seam.

---

## 1. What was verified, exactly

Bound to a **tree fingerprint**, because this is a pre-commit receipt.

```text
base commit  be8b9c3c661179299a6af12538012018714414c1  (S4-A1b)
argv         cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin/v2
             && ./gradlew check --rerun-tasks --console=plain
exit         0
duration     29m 45s
tasks        289 actionable, 289 executed
result       BUILD SUCCESSFUL
test classes 689
tests        4535
skipped      140
failures     0
errors       0

code+test    2 files (1 production, 1 test), +94 / -27
             sha256:c25114915538d5eb7de0c1eef7e6ae92b2badda27d67a72819638f6931323353
build log    sha256:d56c9c729b5cd732211ca94de1c0fbae84877787a7bcedee7537c4d6985774cf
```

`4535` against the previous slice's `4534` is the arithmetic of this change, not
drift: two `CHARACTERIZED DEFECT` tests were removed and three parameterised
invocations were added, so the net is +1.

The log contains five `FAILED` matches. All five are test *names* containing the
word (e.g. `MEMOIZED journaled FAILED with subprocess effect returns RERUN()`) and
each terminates in `PASSED`.

`GRADLE_EXIT` is captured immediately after the build and never through a pipe —
see S4-A1b §1 for why that is not a formality.

---

## 2. The defect

`ScriptedRegistryInvoker.restoredOutput` ended in:

```kotlin
else -> ScriptedRegistryResult.Success(EncodedStepValue((raw as JsonPrimitive).content))
```

`OperationOutput.result` is a `JsonElement`. Every other malformed-durable-state
case in that method — a null output, a RUNNING row, a FAILED row, a fingerprint
divergence, a capability rejection — was already a typed
`ScriptedRegistryResult.Failed` carrying a `FailureKind`. This one was an
exception. S4-A0 §3.4 measured it by priming a real invocation and replacing only
the payload, so the id and fingerprint stayed valid and the scenario modelled what
it actually is: durable state written by a different codec version.

---

## 3. The fix

```kotlin
private fun restoredOutput(output: OperationOutput?): ScriptedRegistryResult =
    when (val raw = output?.result) {
        null -> ScriptedRegistryResult.Failed(/* REPLAY_COMPATIBILITY */)
        is JsonPrimitive -> if (raw is JsonNull) {
            unreadablePersistedOutput("a JSON null")
        } else {
            ScriptedRegistryResult.Success(EncodedStepValue(raw.content))
        }
        is JsonObject -> unreadablePersistedOutput("a JSON object")
        is JsonArray -> unreadablePersistedOutput("a JSON array")
    }
```

Three properties, each load-bearing:

1. **The cast is gone, not guarded.** There is no unchecked cast to reach, so the
   function is total: every `JsonElement` shape has a declared meaning.
2. **The match is exhaustive over the closed `JsonElement` ADT.** `when` used as an
   expression over a sealed hierarchy with no `else` is a compile error if a shape
   is added later, so a future `JsonElement` subtype cannot fall through to a
   fabricated value by accident.
3. **`REPLAY_COMPATIBILITY` is the exact kind** — "A persisted operation cannot be
   safely replayed by this runtime". A foreign payload *is* that, and using it
   keeps the classification honest instead of inventing a kind for the occasion.

### 3.1 The case S4-A0 never exercised: `JsonNull`

`JsonNull` **is** a `JsonPrimitive`, and its `content` is the four-character string
`"null"`. So the old code did not throw on it — it took the success branch and
handed the caller a fabricated value for a step that persisted nothing. That is a
worse outcome than the exception the characterization was written about, and it
was invisible: no test drove it, because no test wrote a `JsonNull` payload.

The characterization that produced this slice measured two shapes and generalised
to "the same gap". The generalisation was incomplete, and the gap it missed was
the one where the old code appeared to work. `JsonNull` is now rejected with the
same typed failure, and the test enumerates all three shapes independently of the
production `when`.

---

## 4. Fitness, and proof it is not vacuous

`S4A0ScriptedRestorePathCharacterizationTest` — 4 tests, all green: the
pre-existing control plus three parameterised invocations, one per unreadable
shape, asserting *no throw*, a typed `Failed`, `REPLAY_COMPATIBILITY`, and that the
message names the rejected shape so an operator reading a real journal can tell a
codec mismatch from a missing output.

Both mutations were run and each produced **exactly one RED**, with one-to-one
attribution:

```text
M-s4c4-1  restore the unchecked cast on the JsonObject branch
          -> 1 RED  (a persisted JSON object …)

M-s4c4-2  drop the `is JsonNull` guard, accept any JsonPrimitive
          -> 1 RED  (a persisted JSON null …)
```

M-s4c4-2 is the one that matters. It is red only because the `JsonNull` guard
exists; nothing in the S4-A0 characterization would have caught its absence.

A repository-wide sweep for `as JsonPrimitive` / `as? JsonPrimitive` found no
remaining unchecked cast in production code. The survivors are all `as?` (which
yields `null` rather than throwing) in `HttpCodecs` and
`CoreShellCodecException`, or assertions inside tests.

---

## 5. What remains open, unchanged

| Defect (S4-A0) | State | Destination |
|---|---|---|
| §3.1 `readFile`/`fileExists` empty payload | **open** | S4-A2 / S4-B2 |
| §3.5 `Unstable` cannot cross the registry seam | **open question**, not a proven defect | S4-C |
| §3.6 `PLUGIN_LOCK_DIGEST` is a constant | **open** | S4-C5 |
| §3.3 invocation ordinal is a count, not a position | **constraint**, not a proven defect | S4-C2 |

`JournaledScriptedOperationRuntime` remains `LEGACY_UNREACHABLE` from production.

---

## 6. What is deliberately NOT claimed

- **No CI.** `.github/workflows/` is empty since `754ddda0`. "CI green" is not an
  available evidence class here and may be neither asserted nor denied from it.
- **No UAT against an installed distribution.** This is a STEP-CERT, not a
  PRODUCT-GATE; a new Step-CERT does not turn the Product-Gate green.
- **Evidence is bound to a tree fingerprint, not a SHA**, until the commit lands.
- **The change is not proven against a live multi-codec journal.** It is proven
  against a primed real invocation whose payload is replaced, which is the
  scenario S4-A0 defined and the one the characterization suite models. A genuine
  cross-version journal fixture is still missing.
