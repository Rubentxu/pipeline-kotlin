# WU-RP-032 Receipt (partial — DSL-008 post fail-closed) — r1

Base: `0d51dfe5`. Date: 2026-09-22.

## Finding (P1, real defect)

`post { }` is accepted DSL surface (`StageScope.post`, `PostScope`,
`PostConditionSpec` at `PipelineDsl.kt`) but `StageSpec` does not carry it:
`toStageBuilder()` dropped the value silently. A script declaring post
conditions compiled and ran with the post block **silently never executing**
— exactly the "fallback ficticio" WU-RP-032 forbids.

## Change

- `StageScope.toStageBuilder()` now throws `IllegalStateException` with a
  localized diagnostic (names stage, names `post`, states non-support,
  suggests catchError/warnError path) when a post block is declared.
- New test `PostDslFailClosedTest` (pipeline-scripting-api): RED verified for
  the expected reason before the fix (absence of rejection), GREEN after.

```text
Reference implementation consulted: Jenkins post { } (declarative);
                                    behaviour adopted = fail-closed rejection,
                                    not emulation
Intentional deviations:             rejection instead of implementation
                                    (semantics deferred; no fake fallback)
Security implications reviewed:     n/a (compile-time only)
Tests demonstrating the contract:   pipeline-scripting-api/.../PostDslFailClosedTest.kt
```

## Verification (fresh, this working state)

| Check | Result |
| --- | --- |
| scripting-api module suite | 47/47 GREEN |
| UatDsl + corpus + compatibility (application) | 66/66 GREEN |
| architecture fitness | 313/313 GREEN |

## Status

WU-RP-032 PARTIAL: DSL-008 post-rejection closed. Remaining clauses
(declaration-vs-execution audit, cancellation semantics, external plugin
Single/Named bodies DSL-004/005 proof) tracked for WU-RP-032 r2.
