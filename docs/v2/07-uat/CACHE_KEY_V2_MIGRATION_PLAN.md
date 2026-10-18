# Migration plan — `CacheKey` algorithm label `v1` -> `v2`

**Status:** enacted
**Authority:** `ADR-0021-adapter-isolation.md` §"Cache-key versioning contract" (Accepted 2026-08-23)
**Declared work:** `IMPLEMENTATION_BACKLOG.md:30` E2-05 ("compilation cache key/storage", Epic E2)
**Shape declared by:** `SCRIPTING_COMPILER_SPEC.md` §7 "Compilation cache"
**Date:** 2026-10-18

## Why this document exists

`ADR-0021` did not refuse `v2`; it **pre-authorised** it under a condition:

> `CacheKey.V2 = "v2"` is reserved. … The v2 algorithm will be introduced in a
> future milestone with a migration plan.

So the missing deliverable was never an ADR. It was this document. `ADR-0021` is
accepted history and is **not** rewritten: its "reserved" statement was true when
adopted, and a receipt is evidence for its own moment.

## What changed in the product

`CacheKey.Companion.v2.compute(CompilationIdentity)` went from
`throw UnsupportedOperationException("v2 reserved …")` to the algorithm that
`SCRIPTING_COMPILER_SPEC.md` §7 declares, and `Kotlin24ScriptingHost` now emits it.

Two properties of the new algorithm, both of which the old one lacked:

1. **The pre-image is length-framed.** Each dimension contributes
   `<charCount>:<part>`. An unframed `joinToString("|")` is not injective on the
   assignment: `("a", "b", "c|d", "")` and `("a", "b", "c", "d|")` are the same
   pre-image. The collision is reachable by putting a `|` inside a value, and
   `pipelineSource` is arbitrary script text.
2. **`compilerOptions` is a dimension, each entry its own framed part.** Before
   this, the compiler flags were covered *de facto* by `hostVersion`, defended only
   by a KDoc asking the author to bump a version by hand. That is the "dead
   semantic parameter" the Semantic Constitution §2 forbids, and the mechanism —
   not the comment — is the correct defence.

`CacheKey.Companion.v1.compute(...)` is **byte-identical to before** and still
returns the `"v1"` label. It was deliberately not "improved".

## The question a migration plan must answer: what happens to values already written?

**Nothing has to be migrated, and nothing was.** Measured 2026-10-18: **no
compilation cache exists in this repository**, so no durable store has ever read,
written or compared a `CacheKey` produced by a previous binary.

That measurement is what makes this safe, and it is the load-bearing sentence of
this plan. Without it the same change would be a `DR-10` durable-compatibility
failure. If a cache is ever added (E2-05's other half), this plan no longer holds
and the cache must be versioned by the label it stores.

### The instrument, because a claim without one is not a claim

The first sweep was scoped to the two scripting modules and returned "no cache".
That scope was too narrow to publish, so the measurement was redone over the whole
denominator — every `src/main` directory in `v2/` — and it found two files the
narrow sweep had missed. Both turned out to be *different things that happen to be
called `cacheKey`*:

| File | What its `cacheKey` actually is |
|---|---|
| `CredentialMaterializer.kt:65` | a local `Pair<credentialId, kind>` memoising credential materialisation |
| `PluginArtifactIdentity.kt:190` | `ReusedCompiledArtifact(val cacheKey: String)` — a **plugin artifact** namespace digest, a different type in a different package |
| `AdmissionControlJournal.kt:161` | only renders the above as `"cache:$cacheKey"` |

Over all of `v2/**/src/main`, the files that mention `CacheKey` are the carriers
(`CacheKey`, `ScriptCompilationResult`, `ScriptingHost`, `Kotlin24ScriptingHost`,
`DomainEvent`) and the serialisers (`EventJsonWriter`/`Decoder`, `EventJsonFields`,
`JsonEventLog`). **None of them consults a cache**; they only carry and serialise
the value. A sweep for a persisted compiled artifact (`Files.write` / `*.class` /
cache-named output) over the same denominator returns nothing.

The near-misses are recorded here on purpose: `PluginArtifactIdentity.cacheKey`
being a `String` while `scripting.CacheKey` is a versioned pair is precisely the
kind of coincidence that makes a future "there is a cache" claim wrong, and this
table is what a later reader can check instead of re-deriving.

### For a reader of an existing event log

`CompilationFinished.cacheKey` is the only surface that carries the value. A
consumer must branch on `version`, never on the shape of `value` — both versions
produce 64-char hex, so the label is the only thing that distinguishes them:

```text
version == "v1" -> pre-image was scriptText|sortedClasspath|kotlinVersion|hostVersion
version == "v2" -> pre-image was the length-framed concatenation of the
                   CompilationIdentity dimensions, one per option
```

A consumer that does not branch and simply treats `value` as an opaque token is
unaffected: it never needs to decode it.

## What was NOT changed, and why

| Left alone | Reason |
|---|---|
| `CacheKey.Companion.v1.compute` | A value written under `v1` is only interpretable if the recipe that produced it still reproduces. Framing it "better" would silently redefine every `v1` digest ever emitted. |
| `ADR-0021` | Accepted history. |
| `docs/v2/07-uat/UAT_*_EVIDENCE.md`, `pk-rc2/**` | Receipts are evidence for their own SHA; a later change never rewrites one. |
| `SqliteEventStoreRoundTripTest`, `JsonEventLogRoundTripTest`, `DurableDecodeInventsNoValueTest` | They construct their own literal `CacheKey("a".repeat(64), "v1")` fixture and assert it round-trips. They are store tests, not host tests, and they are correct unchanged. |

## Normative surfaces updated

Because the host now emits `"v2"`, every spec that pinned the label had become
false. Left alone, the authority set would have contradicted the product.

- `v2/specs/pipeline-events-spine/spec.md` — the `version` field is now specified as
  *the producing algorithm tag* (`"v1"` or `"v2"`) rather than the constant `"v1"`.
- `v2/specs/scripting-kotlin24-vertical/spec.md` — "v2.compute throws" is replaced
  by the two properties the new algorithm actually guarantees; the three scenarios
  that pinned `"v1"` from the host now pin `"v2"`.
- `v2/specs/uat-evt-001-replay/spec.md` — `events[2].cacheKey.version == "v2"`.

`scripting-kotlin24-vertical/spec.md` line 78 ("`CacheKey.v1.compute(...)` returns
`CacheKey(sha256Hex(...), \"v1\")`") was **deliberately kept**: it is still true.

## Evidence (SHA-bound, this change)

All results read from the JUnit XML after deleting the canary first; `^e:` count
was `0` on every run.

```text
CacheKeyIdentityTest               tests=4 skipped=0 failures=0 errors=0   GREEN
ScriptingHostEmitsEventsTest       tests=2 skipped=0 failures=0 errors=0   GREEN
pipeline-scripting-kotlin24:test    17 classes, 68 tests, 0 failures        GREEN
```

Two mutations, each attributed to the row it flips:

```text
M1  framedDigest -> joinToString("|")     -> 1 failure, only "a separator inside one dimension…"
M2  remove addAll(compilerOptions)        -> 1 failure, only "the compiler options are part of…"
```

Two CONTROL rows pass before and after (`v1` pre-image unchanged; identical
identity -> identical key), so "every change moves the digest" cannot pass `M1` by
seeding randomness.

## Divergence found and closed

Four `@Disabled("migrated-pending")` UATs asserted the `"v1"` label:

```text
UatEvt001ReplayTest, UatEvt002MultiStepReplayTest      (pipeline-application)
UatComp001ScriptCompilesTest, UatComp002ErrorSourceMappedTest  (pipeline-scripting-kotlin24)
```

All four are disabled, so none of them would have turned red. They were updated
anyway: a disabled test that asserts a label the product stopped emitting is a
trap for whoever re-enables it. **Correction to an earlier working assumption in
this cycle**: these four were first recorded as "live tests that will go RED". They
are not live. The live coverage of the label is `ScriptingHostEmitsEventsTest`
(happy path) and `CacheKeyIdentityTest` (the algorithm itself).

## Follow-up this plan does NOT cover

The cache **store** is E2-05's other half and remains unimplemented. When it lands,
it must be keyed by `(version, value)` and must not read a `v2` value with `v1`'s
recipe. That constraint is stated here so the future implementer inherits it rather
than rediscovering it.

## Commands actually executed

```text
./gradlew :pipeline-scripting-api:test --tests '*CacheKeyIdentityTest*'
    exit=0  ^e:=0  tests=4 skipped=0 failures=0 errors=0

./gradlew :pipeline-scripting-kotlin24:test :pipeline-application:test \
          --tests 'dev.rubentxu.pipeline.v2.application.UatEvt001*' \
          --tests 'dev.rubentxu.pipeline.v2.application.UatEvt002*'
    exit=1  ^e:=0  (BUILD failure: "No tests found for given includes")

./gradlew :pipeline-application:test --tests '*UatEvt001ReplayTest*' \
                                     --tests '*UatEvt002MultiStepReplayTest*'
    exit=1  ^e:=0  (same: no tests found — both classes are @Disabled)
```

Both non-zero exits are the **selector** failing, not the product: the four UATs
are `@Disabled("migrated-pending")`. They are reported here rather than omitted,
because a run that "passed" after quietly matching nothing would be exactly the
false green this repository keeps paying for. The measurement that matters is the
XML above, read after deleting the canary first.

Not executed: the full `check` round gate, and the UAT battery. The UAT battery is
the harness's (`AGENTS.md` §"Frontera de responsabilidad" items 1 and 6); a
repo-local `check` is the L5 internal-contract gate and is the correct escalation
point, not part of this WU's impact closure.

## End-of-work-unit closure check

```text
Reference implementation consulted:  none applicable — a cache key is an identity
                                      function, not a ported behaviour. The only
                                      external reference consulted was
                                      SCRIPTING_COMPILER_SPEC.md §7 (internal spec,
                                      the declared 7-dimension contract).

Behaviour adopted:                   the host's cache key is the length-framed
                                      digest of the five dimensions it can
                                      actually read, with the compiler options
                                      as real dimensions; emitted under a new label.

Intentional deviations:              two of the spec's seven dimensions
                                      (dslApiVersion, pluginLockDigest) are NOT
                                      included, because no such version or lock
                                      exists in the product to read. Inventing
                                      them would be a fabricated dimension — the
                                      "dead semantic parameter" the Semantic
                                      Constitution forbids. The gap is named in
                                      the `v2` KDoc table so it cannot be forgotten.

Security implications reviewed:      n/a — no credential, path or process input is
                                      read by this change. Digest framing was
                                      reviewed for injectivity only, since an
                                      identity function that collides would serve
                                      the wrong artifact, not leak one.

Tests demonstrating the contract:    v2/pipeline-scripting-api/src/test/kotlin/
                                      dev/rubentxu/pipeline/v2/scripting/
                                      CacheKeyIdentityTest.kt
                                      v2/pipeline-scripting-kotlin24/src/test/
                                      kotlin/dev/rubentxu/pipeline/v2/scripting/
                                      ScriptingHostEmitsEventsTest.kt
```

## Receipt binding

Measured against `b8694734be3071441816484884634a5115e063a9` (HEAD at the time the
evidence above was captured). This document binds to the commit that lands it.