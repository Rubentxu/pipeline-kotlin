# WU-RP-053R / C3.2+C3.6 — `core.pwd` & `core.cleanWs` canonical envelope fix

**Slice:** C3.2 + C3.6 (unified commit)
**Author:** WU-RP-053R worker (delegated by orchestrator; AUTO mandate in force)
**Date:** 2026-09-25
**Branch:** `wu/rp-053r-red-fixtures`
**Worktree:** `/var/home/rubentxu/Proyectos/kotlin/wt/wu-rp-053r-red-fixtures`
**Base SHA:** `71098216` (C3.1 deleteDir fix)
**Target SHA:** see git rev-parse HEAD post-commit

## 1. Operator mandate

Operator granted continuous GO for C3→C6 (see AGENTS.md / SESSION_POINTER):
no STOP between subfases, atomic commits, only STOP on
irreversible actions (push/merge to main, tag, public contract change
unanticipated, design-invalidating finding). On C3.1 closure the operator
granted AUTO mode with full autonomy for prioritisation (rule 6: group
coherent small changes; avoid trivial micro-releases).

## 2. Decision: unify C3.2 and C3.6 into one commit

After C3.1 closed with the canonical `StepSpec.DeleteDir` envelope branch,
two structurally identical defects remained uncovered:

- `StepSpec.Pwd` (C3.2): `encodePayload` fell to the legacy
  `else -> put("declarativeValue", step.toString())` branch. The first
  `put("kind", step.name)` ensured `{"kind":"pwd",...}` shape, so the
  `core.pwd` registry step **was** resolved (REGISTRY_PRIMARY flipped at
  S2-A6 / G3R), but the envelope was `{"kind":"pwd","declarativeValue":"StepSpec.Pwd(tmp=…)"}`,
  carrying NO `tmp` field. The `CorePwdStep.inputCodec` decode
  default-tolerates absent `tmp` (it adds the `tmp` field as `false`),
  which silently disabled the `PWD_TMP_TRUE_DISPOSITION` fail-closed
  branch — a semantic guarantee that was therefore unenforceable from
  the DSL.

- `StepSpec.CleanWs` (C3.6): identical defect. Envelope became
  `{"kind":"cleanWs","declarativeValue":"StepSpec.CleanWs(...)"}`. The
  `CoreCleanWsStep.inputCodec` requires `kind="cleanWs"` (OK from
  the outer `put`) and reads `deleteDirs` + `patterns`; with both
  defaulted (deleteDirs=true, patterns=[]) the handler ran with
  empty-pattern wipes silently, erasing all user-supplied globs.
  C2 RED-WS-CLEANED coincidentally passed because `patterns=[]` is
  its own valid default; the defect was structurally identical to
  deleteDir and pwd but C2's RED was not discriminative enough to
  expose it.

These three defects — `deleteDir`, `pwd`, `cleanWs` — share **one**
structural root: `encodePayload`'s missing `when` branch makes the
typed input codec decode fall back to defaults, hiding the user's
typed fields. Fixing each individually would emit 3 small atomic
commits reproducing the same one-line edit three times.

Per operator mandate rule 6 (group coherent small changes), C3.2 and
C3.6 are unified into a single commit (C3.1 already separated as the
first-of-class fix that proved the diagnostic). The diagnostic intent
is preserved; the structural class of defect is closed at the source.

**Why not also fix Checkout / WithEnv / AnsiColor / Load / NodeNoOp in
the same commit?** StepSpec.Checkout carries a sealed `Scm`
parameter that requires projection to `GitCheckoutInput`'s
flat fields. That is a different-shape change with its own
diagnostic surface; it does not share the one-line `put("name", value)`
pattern. C3.7 (checkout) is deferred and will land as a distinct
slice with its own characterization. The same reasoning applies to
WithEnv / AnsiColor / Load / NodeNoOp: their typed payloads carry
seal/dict-shape orbs that the simple `put(field, value)` idiom does
not handle. They each need their own ADR-0070 instrumentation
investigation before committing.

## 3. Change (one file, +29 LOC)

`v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt`

Two new `is` branches were added inside `encodePayload`, immediately
after the `StepSpec.DeleteDir` branch added in C3.1:

```kotlin
// WU-RP-053R / C3.2
is StepSpec.Pwd -> {
    put("kind", "pwd")
    put("tmp", JsonPrimitive(step.tmp))
}

// WU-RP-053R / C3.6
is StepSpec.CleanWs -> {
    put("kind", "cleanWs")
    put("deleteDirs", JsonPrimitive(step.deleteDirs))
    put("patterns", JsonArray((step.patterns ?: emptyList()).map { JsonPrimitive(it) }))
}
```

`StepSpec.Pwd.tmp` is non-null `Boolean = false`. `StepSpec.CleanWs.patterns`
is nullable `List<String>?` — normalised through `?: emptyList()` so
the JSON envelope carries a real array instead of `null`. The
canonical codec reads `JsonArray` and would decode `null` as no-op
on patterns but emit a malformed envelope under the typed-input
contract; normalising prevents future regressions there.

No other files touched. The vertical encoding-decoding contract is
preserved end-to-end because `CorePwdStep.inputCodec` and
`CoreCleanWsStep.inputCodec` already accept the new canonical
envelope (verified by their `CorePwdStepContractSuiteTest` (23 tests,
`codec input — unit input encodes to the canonical legacy envelope
and round-trips()`) and `CoreCleanWsStepContractSuiteTest` (24 tests,
1 pre-existing skip) which exercise exactly this shape).

## 4. Verification

### L0 — Compile

```
timeout 600 v2/gradlew -p v2 :pipeline-application:compileKotlin
BUILD SUCCESSFUL in 1s
```

### L1 — Direct characterization

```
timeout 600 v2/gradlew -p v2 :pipeline-application:test \
    --tests 'WURp053rExecutionContextCharacterizationTest' --rerun-tasks
BUILD SUCCESSFUL in 1s ; BUILD SUCCESSFUL in 40s

WURp053rExecutionContextCharacterizationTest:
  tests="5" failures="0" errors="0" skipped="0"
  XML sha256: aa202defcb05c775a14d2e7cec872c8b45e0bfc3781bc66836e6884a8c26aaa3
```

All five C2 RED fixtures remain PASS after the fix:
- RED-WS-CLEANED (cached result reused; canonical envelope now carries
  patterns:JsonArray even when `patterns` is empty).
- RED-PWD (canonical envelope now carries `tmp`; decode reads it).
- RED-WS-CONTENT-DESCRIBED (unchanged in scope).
- RED-WS-CONTENT-CHANGED (unchanged in scope).
- RED-DELETEDIR (covered by C3.1 receipt).

### L2 — Affected contracts and unit tests

```
timeout 600 v2/gradlew -p v2 :pipeline-application:test \
    --tests 'CorePwdStepContractSuiteTest' \
    --tests 'CoreCleanWsStepContractSuiteTest' --rerun-tasks

CorePwdStepContractSuiteTest:
  tests="23" failures="0" errors="0"
  XML sha256: 42f4d46ab83e6a10f6fe7ec8f38c02eaaff541ce66be7a50a4d8a1755b5915a7

CoreCleanWsStepContractSuiteTest:
  tests="24" skipped="1" failures="0" errors="0"
  XML sha256: 9e76be8f3149eef684cc95d4465b01d389e65d59895edbfea3aed7965ab71faa

CorePwdStepUnitTest:
  tests="24" skipped="3" failures="0" errors="0"
  XML sha256: f4b9b30395172c64c606501e38db23328ccdd77c6aaf8c4fe02f1cf8a2c9f2c8
```

The 4 skipped test entries are pre-existing (1 in cleanWs contract
suite, 3 in pwd unit suite). Discovered from a fresh build of the
same compiler branch — they are not new skips introduced by this
change and they are not regressions.

### L3 — Owning component

```
timeout 1500 v2/gradlew -p v2 :pipeline-application:test --rerun-tasks
BUILD SUCCESSFUL in 15min

Aggregate across all TEST-*.xml produced by this run:
  tests = 1748
  failures = 0
  errors = 0
  skipped = 121 (pre-existing; includes uats, blocks, RedFilter classifier skips)
```

Per-test failure scan: zero failing files, zero failing cases. The
`"Pipeline finished with FAILURE"` JSON event visible in the L3 log is
a UAT event from a test that exercises the binary's typed failure
classification (`outcome=failure` is intentional in that suite);
JUnit-level it is `tests=passed`. This is **not** a regression; it
was already present before this commit.

### L4 — Module check

```
nohup timeout 1700 v2/gradlew -p v2 :pipeline-application:check --rerun-tasks &
[await completion]
```

CAPTURED GREEN: 2026-09-25T13:28Z

```
timeout 1700 ./gradlew -p v2 :pipeline-application:check --rerun-tasks

(launched 13:07:34Z, finished 13:23:13Z)
BUILD SUCCESSFUL in 15m 39s

Aggregate across all TEST-*.xml produced by this L4 run:
  tests = 1748
  failures = 0
  errors = 0
  skipped = 121 (pre-existing; includes uats, blocks, RedFilter classifier skips)
```

Per-test failure scan: zero failing files, zero failing cases.
L4 = `:check` (compileTestKotlin + test + check-tasks). PASS.

**C3 ROUND-GATE CLOSED GREEN.** The structural defect class
(encodePayload missing branches for deleteDir/pwd/cleanWs) is closed
at the compiler level; regressions: zero (1748/0/0 unchanged
between L3 and L4 runs).

## 5. Acceptance criteria

| # | Criterion | Status | Verification |
|---|---|---|---|
| 1 | L0 compile green | ✅ | BUILD SUCCESSFUL in 1s |
| 2 | L1 green (5 REDs remain PASS) | ✅ | sha256 `aa202def…` (5/5/0/0) |
| 3 | L2 green (Pwd + CleanWs + Pwd unit) | ✅ | sha256 `42f4d46a`, `9e76be8f`, `f4b9b303` (47 tests pass + 4 pre-existing skips) |
| 4 | L3 green (1748 tests, 0 failures) | ✅ | 15min wall, aggregate XML scan |
| 5 | L4 green (`:check` round-gate) | ✅ | 15m 39s, 1748/0/0/121 |
| 6 | No regressions outside the touched branches | ✅ | L3 + L4 identical aggregate counters |
| 7 | No new skips introduced | ✅ | L2 XML counts unchanged vs C3.1 baseline |
| 8 | Atomic commit single subject | ✅ | `62d2abd5` (production + receipt bundled) |
| 9 | Trazabilidad: tiap branch doc cites WU/C3 subphase | ✅ | code comments + commit body + receipt |
| 10 | Existing canonical envelope contracts (codec tests) unchanged | ✅ | L2 23+24 contract tests; codec shapes intact |
| 11 | Conventional Commits strict | ✅ | `fix(pipeline-application): canonical envelope for ...` |
| 12 | No destructive actions (push/merge/tag) | ✅ | Local branch only |

**All 12 criteria met. C3 BLOCK ACCEPTED.**

## 6. Out of scope (deferred for later slices)

- C3.7 `StepSpec.Checkout`: requires `Scm -> GitCheckoutInput`
  projection (sealed-class unwrap to flat fields). Distinct diagnostic.
- C3.8 `StepSpec.WithEnv`: similar compound payload (List<EnvEntry>).
- C3.9 `StepSpec.AnsiColor`: string-mode enum; needs JsonPrimitive
  mapping reconciliation.
- C3.10 `StepSpec.Load`: requires a `Script` reference. Different surface.
- C3.11 `StepSpec.NodeNoOp`: should not need typed fields; will be
  investigated separately.

Each will land as its own commit with its own characterization
fixture once the diagnostic surface is examined. The unified pattern
established by C3.1 + this commit (single `put(name, typed)` line per
`StepSpec` subtype) does NOT generalise to those steps; they each
need their own small refactor pass.

## 7. Receipt references

- C3.1 receipt: `docs/v2/07-uat/WU_RP_053R_C3_1_DELETE_DIR_FIX_RECEIPT.md`
- C2 RED receipt: previous cycle (under `docs/v2/07-uat/`)
- C1 reference semantics: `docs/v2/07-uat/WU_RP_053R_C1_REFERENCE_SEMANTICS_RECEIPT.md`
- C0 evidence integrity: `docs/v2/07-uat/WU_RP_053R_C0_EVIDENCE_INTEGRITY_RECEIPT.md`
- Updated session pointer: `.agent/SESSION_POINTER.md`
- Updated work journal: `.agent/WORK_JOURNAL.md` (C3.2 entry)

## 8. Next slice (operator pre-approved continuity)

C4 (workspace-identity resolution under typed input from
CanonicalWorkspaceContextProvider) — the moment C3 is closed the
C4 contract suite for the workspace-identity seam becomes runnable.
Operator pre-approved continuous GO through C3→C6.

**C3 ROUND-GATE STATE (final, 2026-09-25T13:28Z):**

- L0 compile: GREEN in 1s.
- L1 WURp053rExecutionContextCharacterizationTest: 5/5/0/0, sha256
  `aa202defcb05c775a14d2e7cec872c8b45e0bfc3781bc66836e6884a8c26aaa3`.
- L2 Pwd + CleanWs + Pwd contract: 23+24+24 tests PASS, sha256
  `42f4d46a…`, `9e76be8f…`, `f4b9b303…` (4 pre-existing skips).
- L3 `:pipeline-application:test --rerun-tasks`: BUILD SUCCESSFUL in
  15min, aggregate 1748/0/0/121.
- L4 `:pipeline-application:check --rerun-tasks`: BUILD SUCCESSFUL in
  15m 39s (13:07:34Z → 13:23:13Z), aggregate 1748/0/0/121
  (unchanged from L3 — zero regressions).
- Defect class (encodePayload missing-branches + default-tolerance)
  closed structurally for deleteDir, pwd, cleanWs (3 StepSpec
  subtypes).
- Receipt follow-up docs commit: `ff9603bf` (no code/test changes,
  L4 pin only).

**Defects deferred with documented shape (C3.7+):**

- **C3.7 StepSpec.Checkout**: requires `Scm -> GitCheckoutInput`
  projection AND change of emission path from `OpaqueStepNode` to
  `RegistryStepSpec` (plugin step id `scm-git.checkout`). Larger
  architectural commitment than the drop-in branches above. Will
  land as a dedicated slice with its own characterization.
- **C3.8+ StepSpec.WithEnv / AnsiColor / NodeNoOp / etc**: block-form
  Steps that go through `blockPayload`, not encodePayload;
  structurally distinct from the C3.1/C3.2/C3.6 class.
- **C3.9 StepSpec.Load**: registry step `core.load` is
  LEGACY_REMOVED at WU-LPR-301/G5; the DSL surface remains for
  forward-compat but no runtime execution.

**Next slice candidates (operator pre-approved priority list):**

1. **C4 (workspace-identity contract)** — isolated, small surface,
   no architectural commitment beyond typed decode. Highest value
   per LOC.
2. **C3.7 (Checkout RegistryStepSpec emission)** — bigger change
   touching the `compile()` flow. Requires diagnostics first.
3. **C6 (resume / replay finalisation)** — pre-approved by mandate;
   orthogonal to C3.x Class-of-defect.
