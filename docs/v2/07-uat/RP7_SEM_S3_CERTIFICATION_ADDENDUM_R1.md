# RP7-SEM-S3-R1 — Certification Addendum

**This document does not modify** `RP7_SEM_S3_CERTIFICATION_RECEIPT.md`. That receipt is
the historical record of the S3 certification and stays exactly as published at
`bbf6d2ed`. A receipt is evidence for its own SHA and this one has not been re-run, so
editing it would destroy the only honest account of what was actually certified.

What this addendum does is state the corrected classification and carry the evidence for
it.

| | |
|---|---|
| **Subject** | S3 certification status after `RP7-SEM-S3-R1` |
| **Original certification** | `CERTIFIED_AT_SHA c8ec9b737e764261f19dea13e88a49d7453b9723`, receipt at `bbf6d2ed` |
| **Corrective SHA certified here** | `a06c9160017bb5781908bd2e0df1e75f7be4a961` |
| **Status** | **CERTIFICATION EVIDENCE VALID for every executed witness, with THREE universal claims falsified after certification and now corrected** |
| **Gate log digest** | `sha256:2e7ae45e4bd4a9f01ffb421dd7c762339957023d718394adc7c57daf2954d3d6` |

---

## 1. The corrected classification, stated plainly

The S3 receipt was not wrong about what it executed. Its 6/6 installed agent witnesses,
its 7/7 environment witnesses, its S3.3 mutation and its full gate all really ran and
really passed. That evidence stands.

What was wrong is the **completeness claim** attached to it. Three sentences in the
certified artifact assert properties across the whole surface — totality of a codec,
deletion of an overflow, and a surface description — and the code did not have those
properties. A gate runs the tests that exist; it cannot find a defect whose discriminating
input was never written, and none of these three had one.

The receipt's own §10, which lists five open findings, mentions `codec`, `overflow`,
`total`, `private` and `setter` **zero times**. So this is not "we knew and did not fix
it". The certification asserted a completeness it had not verified. That distinction is
the whole point of this addendum.

---

## 2. The three falsified claims, cited against the certified artifact

### 2.1 "Total, and it never throws on user input" — FALSE

`ExecutionTargetRequirementCodec.kt` at `c8ec9b73`, line **35**:

> `*  - **Total, and it never throws on user input.** Every failure is a value.`

and line **74**:

> `* Decode a requirement. TOTAL: returns [DirectiveDecodeResult.Malformed] for every
> malformed input and never throws`

Three throwable paths existed, each on a number read from the payload. The first two were
found by review; the third was found only by the canary sweep written afterwards, which is
itself worth recording:

| Payload | Path | Throwable |
|---|---|---|
| `L 0`, `C 0` | `readStrings(0)` → empty set → `require(isNotEmpty())` | `IllegalArgumentException` |
| `L 2147483647` | `ArrayList<String>(count)` sized from the claimed arity | `OutOfMemoryError` |
| `L 1 2147483647:x` | `if (index + declared > bytes.size)` wraps `Int` | `StringIndexOutOfBoundsException` |

`BeforeStageDirectiveEngine.decode` called `definition.decodeAny(...)` with no guard, so
each crossed the BEFORE_STAGE seam as an exception instead of a denial —
`DirectiveRegistry.decodeAny` already claimed the engine "fails closed on it without
exception-based control flow" and nothing enforced the claim.

### 2.2 "Five failure modes are now deleted rather than handled: … overflow" — FALSE

`RP7_SEM_S3_CERTIFICATION_RECEIPT.md:183`, committed at `bbf6d2ed`:

> `now **deleted rather than handled**: absent, duplicate, unparseable, non-positive, overflow.`

Overflow was moved, not deleted. `OptionsScope.timeout` checked only positivity,
`OptionsSpec` had no `init` at all, and a public `var timeout` sat beside the validating
function as a second unguarded door. `options { timeout(Long.MAX_VALUE) }` compiled and
later raised `ArithmeticException` inside the compiler — naming neither the option nor
the author. The `OptionsSpec` KDoc's "Invalid surface is unrepresentable" was true of the
DSL facade and false of the published model.

### 2.3 "A stage declaring `core.agent` is denied fail-closed by the registry" — FALSE

`CoreDirectiveKeys.kt:36` still stated the definition, resolver and interpreter "do not
exist" and that "the DSL still throws", long after all three did. A stale capability claim
is the same defect class as a stale capability.

---

## 3. What was corrected, and how it was falsified

Every change below was preceded by a canary observed **RED**, and every green state was
then re-proven by a mutation that was reverted and re-verified.

| Slice | Defect | Correction | Canary RED | Mutation RED |
|---|---|---|---|---|
| **R1-A** | 3 throwable codec paths; unguarded `decodeAny` | arity 0 → `Malformed`; arity proven against the remaining byte budget before any sizing; bounds check subtracts; `decodeThrough` guard, fault classified `PLUGIN` | 5/8 | `M-r1-a-1` guard removed → 3/6 |
| **R1-B** | overflow reachable; model door open; unguarded property | one `StageTimeout` authority consulted by `OptionsSpec.init` **and** the validating setter; `multiplyExact` now unreachable | 6/10 | `M-r1-b-1` upper bound dropped → 5/10 |
| **R1-C** | two `agent` declarations resolved sequentially | `ResourceCompositionPlanner`, reading the declared `Resource` policy and never the key, checked before any resolution | 2/5 | `M-r1-c-1` → 2/5; `M-r1-c-2` (the forbidden `key ==` fix) → 1/6 |
| **R1-D** | `agentWithCapabilities` claimed `STABLE` with no production success path; KDoc drift | manifest → `PARTIAL`; KDoc rewritten | — | — |

`M-r1-c-2` is the one worth keeping. "Two different Resource keys are legitimate" **cannot**
distinguish a structural law from `if (key == "core.agent")`, because in that scenario
neither key is duplicated — both readings accept it. The discriminating case is a
duplicate of a key the engine has never heard of, and it is pinned.

### 3.1 One correction of my own, found by the gate

The first R1-B fix made `OptionsScope.timeout` **private**. The full gate on `297e0ec4`
failed with 4 tests across 3 suites, both causes mine:

```
Cannot access 'var timeout: Long?': it is private in 'OptionsScope'
```

The certified `UatLocal004TimeoutTest` fixtures write `options { timeout = 2L }` and
`timeout = 3L`. The door was not theoretical, and removing it was a **source break in a
published DSL surface wearing a hardening's clothes**. The property stays; the **setter
validates** through the same authority and the function delegates to it. Two syntaxes, one
authority, no unguarded assignment, no break.

`FArchS1DirectiveKernelFitnessTest` also flagged `ResourceCompositionPlanner` for
containing a namespaced directive literal — in a KDoc, quoting the concrete-key fix the
planner exists to forbid. The fitness scans source text and is right to: a forbidden fix
written next to the law forbidding it invites the shortcut.

---

## 4. The certifying gate, on the exact SHA

```
cd v2 && ./gradlew check --rerun-tasks
```

| | |
|---|---|
| SHA | `a06c9160017bb5781908bd2e0df1e75f7be4a961`, tree clean |
| Result | **BUILD SUCCESSFUL in 29m 19s** |
| Tasks | 289 actionable, 289 executed |
| Tests | **684 suites / 4507 tests / 0 failures / 0 errors / 140 skipped** |
| Coverage | `koverVerify` ran (`:pipeline-application:koverVerify` and root); **0 violations** |
| Log | `sha256:2e7ae45e4bd4a9f01ffb421dd7c762339957023d718394adc7c57daf2954d3d6` |

Fully accounted against the S3.4 baseline (`c8ec9b73`: 680 / 4476 / 140):

```
+4 suites   = S3R1AgentCodecTotalityTest, S3R1DirectiveDecodeBoundaryTest,
              S3R1ResourceMultiplicityTest, S3R1StageTimeoutBoundaryTest
+31 tests   = 8 + 6 + 6 + 11
  0 skipped = unchanged
```

CI remains `NOT_AVAILABLE` and is not a claim. The gate above is the local substitute
required by the 2026-10-03 verification policy.

### 4.1 The failed gate, kept as evidence

| | |
|---|---|
| SHA | `297e0ec43d4a2b5b0aa77cae63f7ca9efe1f34e7` |
| Result | **BUILD FAILED in 29m 23s**, 279 tasks executed |
| Failures | 4 tests / 3 suites, both causes mine (§3.1) |
| Log | `~/.local/state/pipelinek-gates/s3_r1/r1_gate_ATTEMPT1_FAILED.log` |

It is retained rather than deleted because it is the evidence for the compatibility law
now written into `11-release-cut.md`: **a hardening that removes a published surface is
breaking, however clean it looks.**

---

## 5. Installed-distribution behaviour

The distribution was rebuilt from the certifying SHA and the R1 classes verified inside
it before any witness ran (`ResourceCompositionPlanner` in the domain jar;
`StageTimeout` referenced from scripting-api and application; the setter message
`"must be between 1 and 9223372036854775 seconds"` present in the installed
`pipeline-scripting-api-0.46.0.jar`).

**S3.1 agent witnesses, re-run on these bytes: 6/6 PASS** — identical to the S3 receipt,
including the `capability` case that still refuses.

**R1 witnesses** (`~/.local/state/pipelinek-gates/s3_r1/witness-r1.sh`): **17 PASS,
0 FAIL, 1 known gap**, script exit `3`.

| Property | Result |
|---|---|
| `options { timeout = 30 }` still compiles and runs | PASS |
| `options { timeout(30) }` still compiles and runs, same carrier | PASS |
| overflow refused, no body, **no `ArithmeticException`** | PASS |
| two `agent` declarations refused before any target is granted | PASS |
| the conflict diagnosis does not blame the remote allocator | PASS |
| one `agent` declaration unaffected | PASS |
| `agentWithCapabilities` still refuses (the `PARTIAL` basis) | PASS |

---

## 6. Finding raised BY this certification: builder diagnostics are discarded

While proving R1-B on the installed binary, the witness showed the author-facing message
never reaches the product:

```
cause [SCHEMA]: Kotlin compilation failed        with  "diagnostics":[]
```

R1-B raises `options { timeout } must be between 1 and 9223372036854775 seconds, was …`.
The installed CLI replaces it with a generic schema failure.

**This is not a lost channel.** Probed on this same build, a genuine Kotlin error does
come through with message and path:

```
Unresolved reference 'noSuchStep'.    path=probe/b.kts
```

The channel exists and works. What is missing is a path for an exception thrown while the
**script runs**: a `require()` in a pure DSL builder fires during evaluation, after the
Kotlin compiler's diagnostic phase has closed.

Scope, measured rather than guessed: **8 `require(` sites** in `pipeline-scripting-api`'s
DSL surface, **5 of them carrying an author-facing message**, and every one of those 5 is
reduced to "Kotlin compilation failed" with no reason. The same probe shows the surviving
diagnostics carry `"line":0,"column":0` even where a position exists.

**Destination: S4-B3** (source-map diagnostics back to `pipeline.kts:line:column`). It is
pinned in the witness as a known gap: the check passes while the defect is present and the
script exits non-zero, so an outstanding gap is never reported as a clean run. It is NOT
fixed here — that would be compiler work inside a boundary-hardening slice, which is the
scope discipline this whole addendum exists to defend.

---

## 7. Carried forward, not fixed

| # | Finding | Destination |
|---|---|---|
| R1-1 | `WhenPredicateCodec` shares the untrusted-arity preallocation (`ArrayList(count)` before proving the elements exist). Pre-existing, S2-era, uncertified. Its arity-0 case is a **deliberate, documented** vacuous-truth decision and is NOT the same defect as R1-A's — only the allocation hazard is shared. | S4.0 characterization |
| R1-2 | Builder exceptions lose their diagnostic on the installed path (§6) | S4-B3 |
| R1-3 | `WULpr402` token loophole | S4-B1 |
| R1-4 | `ContextOverlay` dead / stringly cases | S4-B5 |
| R1-5 | `DomainEvent` count pinned in four places | S5 |
| R1-6 | SDDK `/tmp` scratch leak | SDDK tooling |
| R1-7 | `pipeline-scripting-api` has **no API dump**. The DSL is the largest published surface and the only one the binary-compatibility validator does not track — the same blind spot `DomainEvent`'s four pins have, one level up. | S4-B |

---

## 8. What this addendum does NOT certify

- **The S3 architecture.** Nothing here reopens it. The carrier, the registry seam, the
  policy-driven engine and the witnesses are unchanged and remain sound.
- **The original S3 gate.** It is not re-run and not re-scored. Its evidence is valid for
  its SHA; it was not sufficient for the claims made about it.
- **The product gate.** UAT obligations, coverage thresholds, SAST and dependency
  scanning remain `PRODUCT-GATE` items, `BLOCKED_EXTERNAL` while no CI surface exists.
- **`Remote` execution.** Carried, decodable, refused by design. Still RP-8.
- **A new stable release.** S3-R1 changes the S3 surface, so the next release candidate
  must be taken from a SHA carrying this correction, not from `c8ec9b73`.
