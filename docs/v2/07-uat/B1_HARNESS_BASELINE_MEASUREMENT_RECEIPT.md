# B1 — Reproducible baseline measurement for Gradle test tasks

- **Date:** 2026-10-15
- **HEAD at authorship:** `be0699cf98b606bdc30e14d3e114595e1d8e0097`
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** new `scripts/measure-harness-baseline.py` + suite, `just baseline` recipe.
  Measurement tooling only; no gate and no threshold is introduced.

The mandate's B1 asks for a reproducible baseline (P50/P95, configuration,
compilation, execution, processes, forks, skips, failures, cache). This tool is
the instrument for it, with the honesty rules the repository demands: no green
over red samples, no cache-warm run presented as a measurement.

---

## 1. What it records per run

| Field | Source |
|---|---|
| `wall_s` | monotonic clock around the invocation |
| `reported_s`, `result` | Gradle's own `BUILD SUCCESSFUL/FAILED in Xs` |
| `executed` / `from_cache` / `up_to_date` | the `N actionable tasks: …` tail, parsed generically |
| `profile.*` | newest `v2/build/reports/profile/profile-*.html` (startup, settings+buildSrc, loading, configuring, execution, total) |
| `xml.*` | JUnit totals for the module plus **how many XML files this run rewrote** |

Exit codes: `0` all samples succeeded, `1` at least one FAILED (recorded in the
summary verdict), `2` refusal (`--require-work` with no executed task, or bad
arguments). `runs < 5` is labelled a small-sample median, not a statistical P50.

## 2. Real measured sample (this tree, `v2/gradlew -p v2 :pipeline-domain:test`)

```text
$ just baseline ':pipeline-domain:test' 3
run 1: exit=0 SUCCESS wall=28.277s gradle=27.0s  [profile startup=5.933 config=4.987 exec=14.467 total=27.83]
run 2: exit=0 SUCCESS wall=2.666s  gradle=2.0s  executed=0 cache=0 up-to-date=8  [profile startup=0.975 config=1.073 exec=0.123 total=2.566]
run 3: exit=0 SUCCESS wall=2.521s  gradle=2.0s  executed=0 cache=0 up-to-date=8  [profile startup=0.897 config=1.048 exec=0.132 total=2.419]
median=2.666s min=2.521s max=28.277s verdict=PASS work_executed=False
xml: files=137 rewritten=0 tests=735 failures=0 errors=0 skipped=0 unreadable=0
```

Read correctly, this says: a cold-ish invocation costs ~28 s (config ~5 s, task
execution ~14.5 s) and warm invocations ~2.5 s with `executed=0`, `rewritten=0`.
The tool reports `work_executed=False` for the warm median, so the 2.5 s figure
cannot be quoted as the module's test time.

## 3. A defect this measurement found in itself

The first parser only understood the full and up-to-date-only tail forms. The
real cold run printed `8 actionable tasks: 8 executed` (Gradle omits the zero
categories), which came back as `executed=None`, `from_cache=None`,
`up_to_date=None` — the sample then said nothing about what ran. Fixed by parsing
the tail generically and pinned by `test_two_category_split_is_parsed`. The cold
sample above was captured before the fix; its `None` split is left as recorded
rather than re-derived.

## 4. Evidence

```text
python3 scripts/test_measure_harness_baseline.py
Ran 16 tests in 0.053s
OK

python3 scripts/fitness-harness-coverage.py   # the new tool is registered (R1)
OK: every tool has a suite, every suite has a subject and tests, every entry point is declared
```

## 5. What this does NOT claim

- This is one module, three runs. A full P50/P95 for the release gate needs N≥5
  over the full task set and is an operator cost decision (order of tens of
  minutes per round), not taken here.
- No threshold became a gate; the tool reports, it does not adjudicate.
- Process counts / fork counts are visible in the JUnit files and in
  `maxParallelForks`/`forkEvery` configuration, not measured by this tool.

## End-of-work-unit closure

```text
Reference implementation consulted: none applicable; Gradle console/profile
    output formats (verified against real runs, which is how §3 was found).
Behaviour adopted:                  measure with the same clock and the same XML
                                    reader the evidence tooling uses; refuse to
                                    call a cache-warm sample a baseline.
Intentional deviations:             reuses run-scoped-junit-report.py for XML
                                    reading instead of a second implementation.
Security implications reviewed:     none; runs local Gradle and reads local files.
Tests demonstrating the contract:    scripts/test_measure_harness_baseline.py
                                    (16 methods, injected runner, real temp XML dir).
```
