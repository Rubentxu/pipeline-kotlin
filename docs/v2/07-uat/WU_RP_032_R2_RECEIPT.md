# WU-RP-032 Receipt (r2 — stage options surface slimmed) 

Base: `ad4cd996` (r1). Date: 2026-09-22.

## Finding

Stage-level `options { retry(count) }` and `options { skip }` were accepted
DSL surface lowered to `OptionSpec("retry")` / `OptionSpec("skip")` rows in
`StageNode.options` with **no runtime interpreter** (verified: no consumer of
those option names outside the compiler itself; only stage `timeout` is
projected via `projectShellOptions` → `ShOptions.timeoutMs`). Accepted and
silently dropped = fake fallback (forbidden by WU-RP-032).

## Decision (operator-aligned)

Per operator direction, stage options should carry only parameters useful in
this project's domain, not inherit the Jenkins surface. Rather than accept +
runtime-reject (invalid state represented, then refused), the dead surface was
**removed**: invalid surface is unrepresentable.

```text
Reference implementation consulted: Jenkins declarative post/options;
Behaviour adopted:                  timeout only in stage options; retry lives
                                    exclusively in the retry Block Step (durable
                                    control row, ADR-0075); skip removed (not a
                                    durable-engine concept)
Intentional deviations:             surface removal instead of rejection or
                                    emulation
Security implications reviewed:     n/a (declarative surface only)
Tests demonstrating the contract:   UatDsl008StageOptionsFailClosedTest (structural
                                    shape assertions + compile-positive)
```

## Change

- `OptionsSpec`: `timeout` only (`retry: RetrySpec?`, `skip: Boolean` removed).
- `OptionsScope`: `timeout(seconds)` only (`retry(...)`, `skip(...)` removed).
- Orphaned `RetrySpec` data class removed.
- `DslCompiledPipelineCompiler.toOptions`: guards gone (nothing to guard);
  lowers only `timeout`.
- New `UatDsl008StageOptionsFailClosedTest` (3 tests): structural shape
  assertions (no retry/skip fields) + compile-positive for timeout-only.

## Verification (fresh, this working state)

| Check | Result |
| --- | --- |
| scripting-api module suite | 47/47 GREEN |
| UatDsl + corpus + compatibility + grammar (application) | 70/70 GREEN |
| architecture fitness | 313/313 GREEN |

Fixtures `grammar-full` / `timeout-retry` already used the certified
`retry(count) { }` Block Step form; no fixture used `options { skip/retry }`.

## Status

WU-RP-032 r2 closed for stage-options. Remaining: external plugin Single/Named
bodies proof (DSL-004/005) and cancellation semantics audit.
