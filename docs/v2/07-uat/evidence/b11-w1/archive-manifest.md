# Archive manifest — B11 / W3b context-only blocks

## Identity

| Field | Value |
| --- | --- |
| Cycle | LFC-2 E1 S2 B11 / W3b companion fix |
| Pull request | [#53](https://github.com/Rubentxu/pipeline-kotlin/pull/53) |
| Base | `a66d7f6c28ea5aa5e9c0c81b3a55f5d4ac06fb12` |
| Release candidate | `c186a02fc8314f00955720f7723cd5b20792122f` |
| Merge method | fast-forward `main` from the exact frozen candidate |
| Merge commit | `c186a02fc8314f00955720f7723cd5b20792122f` |
| Remote tag | [`v0.38.0`](https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.38.0) → `c186a02fc8314f00955720f7723cd5b20792122f` |
| Finalized at | `2026-09-14T14:20:45Z` |
| Archive status | FINAL |

## Transient CAS authorities (payload purged)

The original phase CAS payloads are no longer present in `openspec/changes/`.
Their historical digest and content-address retain provenance, but they are not
reconstructed or represented as present.

| Artefact | SHA-256 | Original CAS id | Availability | Authority |
| --- | --- | --- | --- | --- |
| proposal.md | `72df5a2b…f02a98a` | `art-72df5a2bd272-2ccdb7ee` | PURGED | historical digest only |
| spec.md | `3fc1a098…438d9c4` | `art-3fc1a098a76b-3d1b69b7` | PURGED | historical digest only |
| tasks.md | `8a171c92…f063ce` | `art-8a171c9222c7-73f24fc3` | PURGED | historical digest only |

## Durable canonical evidence

| Path | SHA-256 at archive preparation |
| --- | --- |
| `docs/v2/07-uat/B11_W123_CONTEXT_BLOCKS_RECEIPT.md` | `324b265fce06962588c69caf8d2ff96f3aa014752b5222c95599bdaf61a754b1` |
| `docs/v2/07-uat/evidence/b11-w1/G0-baseline.txt` | `e0a76241c2a6e8af198767ac819211900677a18b08f2be31390ee0a8d48b0cae` |
| `docs/v2/07-uat/evidence/b11-w1/W3b-compiler-fix.txt` | `7e9a36ac8c1adff39b3e3969c9273776acaa769c3733bf6ab2f324963b24d24b` |
| `docs/v2/07-uat/evidence/b11-w1/verify-report.md` | `6c869267a3baa734f9629be80d828128c52fa38900882f330eb79c4fc8c523e7` |
| `.agent/TESTING-STATE.md` | `cfd8151863e394254383172593b4f3277b35df00ed9b52e2868944cb3b705152` |

## Merge-train evidence

| Gate | Result |
| --- | --- |
| PR base/head | #53: `main@a66d7f6c` ← `refactor/lfc2-e1-b11-context-blocks@c186a02f` |
| PR state immediately before merge | `OPEN`, `CLEAN`, no pending status checks |
| Ancestry immediately before merge | merge-base = base, behind/ahead = `0/11` |
| RC working tree | detached, clean, exact `c186a02f` |
| Certified vs RC `v2/` diff | empty: `git diff 0b3d4c6b..c186a02f -- v2/` |
| Detached targeted canary | 99 tests / 0 failures / 0 errors |
| Real DSL smoke | withEnv, timestamps, dir+withEnv+timestamps: PASS with inner StepStarted/StepFinished and observable output files |
| Post-merge minimum canary on `main` | 14 tests / 0 failures / 0 errors |

## Verified delivery state

| Gate | Result |
| --- | --- |
| Full B11/W3b verify matrix | 137 tests / 0 failures / 0 errors |
| sddk-verify | PASS |
| sddk-debt-verify | PASS |
| Routing debt | 0 |
| Historical ceiling | 18 (immutable) |
| Body child-loop inventory | (1, 1) |
| Pre-existing red set | 26/26 reproduced at base; 0 new regressions |

## W3b scope firewall

The W3b companion-fix cherry-pick (`cf541f40`) changes exactly:

1. `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/DslCompiledPipelineCompiler.kt` (+2 branches: `WithEnv`, `Timestamps`)
2. `v2/pipeline-architecture-tests/src/test/kotlin/dev/rubentxu/pipeline/v2/architecture/Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest.kt` (new 316-line architecture test)

No coordinator, journal, retry, timeout, withCredentials, parallel, BodyInvoker engine,
or ADR source was changed by W3b.

## Finalization attestation

`main` was fast-forwarded directly to the frozen release candidate only after the
independent PR, detached-canary, and archive-preparation lanes were green. The annotated
remote tag `v0.38.0` resolves to that same release SHA. This manifest is the durable archive
record of the cycle; it distinguishes purged transient CAS payloads from the present,
hashed canonical receipt and evidence.
