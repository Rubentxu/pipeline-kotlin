# Installed-contract lane — cost baseline

> code-under-test: `3ff84405e29e93aefba7244fee802e0ecee6850c` (`main`)
> artifact: installed distribution `pipelinek 0.62.0`, launcher sha256 `21d3ecbd6e5e0ba8…`
> corpus: `v2/compatibility/*.pipeline.kts` — 31 fixtures
> question: *is the installed-contract lane slow because of harness overhead?*

## 1. Verdict

**No. The cost is product-side, and it is almost entirely Kotlin script compilation.**

Running the 31 fixtures bare against the installed binary costs **184.2 s**. Running 30 of
them through Gradle and a JUnit test JVM costs **172.5 s** (measured from the class's own XML).
Harness overhead is therefore **negative** — **−0.39 s per fixture**. The harness is not the
thing to trim; trimming tests would remove coverage without touching the wall clock.

## 2. Where the 5 seconds go

A trivial one-`echo` fixture and a real run differ by an order of magnitude, and the
difference is compilation, not execution:

| measurement | ms | what it isolates |
|---|---:|---|
| `pipelinek version` (no script compiled) | 203 | JVM start + CLI bootstrap |
| `pipelinek run 01-basic.pipeline.kts` | 5024 | full: compile + execute one step |
| same, `-XX:TieredStopAtLevel=1` | 4147 | removes C2 JIT warmup |
| same, `-XX:TieredStopAtLevel=1 -XX:+UseSerialGC` | 4289 | serial GC is not the cost |

So a 5.0 s invocation decomposes as roughly:

```text
  0.20 s   JVM startup            (`version` — the floor)
  ~0.9 s   JIT / classload warmup (recoverable: ~0.9 s under C1-only)
 ~4.0 s   Kotlin script compilation, EVERY invocation, no cache
```

## 3. Distribution

Per-fixture wall clock, bare binary, no Gradle and no test JVM:

```text
n=31   total=184.2s   p50=5.13s   p95=9.18s   min=4.92s   max=17.64s
26 of 31 fixtures land under 5.5 s
above 6 s:  31-stash-unstash=17.6  18-cleanWs=11.9  30-artifact-query-bridge=9.2  16-sleep=6.2
```

The cost is a **flat floor, not a tail**: top-5 fixtures are 21 % of total. There is no slow
fixture to optimise and no outlier to quarantine.

## 4. The finding: the identity exists, the reuse does not

V2 computes a stable, content-addressed identity for **every** compilation and then never
reads it back.

The key is real and fully wired on the *produce* side:

- `Kotlin24ScriptingHost.kt:232` computes
  `CacheKey(CacheKey.sha256Hex(scriptText, sortedClasspath, kotlinVersion, hostVersion), V1)`;
- `ScriptCompilationResult` carries `cacheKey: CacheKey` on both `Success` and `Failure`;
- `DomainEvent.cacheKey` carries it, and `EventJsonWriter.encodeCacheKey` /
  `EventJsonFields.parseCacheKey` **persist and restore it** in the event store;
- `CompilationFinished` publishes it, so it is observable externally.

What does not exist is any **consumer**. Searching V2 for a lookup — `lookup`/`find`/`get`/
`read`/`load`/`fetch`/`reuse` applied to a `cacheKey` — returns nothing. The identity is
produced, journalled, serialised and round-tripped, and no code path ever asks "have I already
compiled this script?"

The only working compile cache in the repository is
`core/src/main/kotlin/dev/rubentxu/pipeline/compilation/ScriptCompilationCache.kt`, which
belongs to the **V1 `core` module**. `core/` is not part of the V2 build at all (`v2/settings.gradle.kts`
does not include it), and even there the companion test is
`core/src/test/.../ScriptCompilationCacheTest.kt.disabled`.

So the ~4.0 s per invocation is paid unconditionally, on a key the system already knows how to
compute. Note the scope honestly: a cache would **not** shrink this lane — all 31 fixtures are
distinct scripts, each compiled once. Its value is to the **consumer** re-running the same
pipeline, which is the ordinary case, and to any future compile that wants memoisation.

## 5. Scope, caveats, and what is NOT claimed

Stated plainly, because a timing receipt that overstates its own scope is worse than none:

- **The bare loop is not invocation-equivalent to the test for every fixture.** Five fixtures
  exited non-zero bare (`10-smoke-e2e`, `11-workflow-control`, `14-credentials-bindings`,
  `15-error`, `28-zip-slip-defense`). `CompatibilityCorpusTest` asserts `exitCode == 0` for the
  first two and passes an explicit `environment` map via `ProcessBuilder`; the bare loop did
  not. Their timings therefore characterise *compile + failed run*, not the tested path. The
  floor measurement rests on fixtures that exited 0 (`01-basic`, `03-stages`, `06-loop` …),
  which are equivalent.
- **The measurement was taken with an unisolated cwd, and that was a real defect in the
  method.** Running the fixtures from the repository root made several of them *write into
  the repository* (`src/lpr104.txt`, `reports/lpr090`, `out.txt`, `docs/readme.md`,
  `.cleaned`, `abcdefghijklmn`, `artifact.txt`, `lpr104-readme.txt`). All were untracked and
  were removed; `git status` was restored to its session-start state. Timing is unaffected
  (the compile dominates), but the harness's own discipline is the thing that prevented this:
  `CompatibilityCorpusTest` gives each fixture its own workspace. This is HARNESS FIDELITY
  LAW §4 (`no ambient cwd`) failing in the *measurement*, not in the product.
- **No full-lane verdict is claimed.** `installedContractTest` end-to-end has still not been
  observed inside a 600 s budget; that remains `NOT_ESTABLISHED`, unchanged.
- **No compile cache was implemented.** A stale cache is a wrong-semantics defect in a system
  whose durable identity laws are load-bearing, and it cannot be validated inside one work
  unit against a shipped distribution. That is a product change with its own gate, not a
  harness-quality fix.

## 6. Reproduction

```bash
cd v2 && ./gradlew installDist

BIN=$PWD/v2/pipeline-application/build/install/pipelinek/bin/pipelinek   # from repo root
for f in v2/compatibility/*.pipeline.kts; do
  ( cd "$(mktemp -d)" && /usr/bin/time -f "%e $f" timeout 120 "$BIN" run "$PWD/$f" ) 2>&1
done
```

Run it from a temporary directory, not the repository root — several fixtures write into the
current working directory.

## 7. What this changes

HITO 2's bullet "eliminar las reconstrucciones innecesarias y medir p50/p95 sobre ejecuciones
comparables" is now measured, and its answer is uncomfortable: there are no unnecessary
rebuilds to eliminate on the harness side, and the p50/p95 are dominated by a per-invocation
product cost. The remaining lever is the product's compile path, not the test lane. Any future
proposal to make `check` cheaper by removing installed-contract coverage should be measured
against this baseline first, and should be expected to save nothing.