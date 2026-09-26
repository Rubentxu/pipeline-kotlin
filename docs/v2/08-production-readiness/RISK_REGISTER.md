# Execution Risk Register

| Risk | Probability | Impact | Trigger | Mitigation |
|---|---:|---:|---|---|
| 41 failures contain real defects rather than stale tests | Medium | Critical | classification finds mandatory behavior failure | freeze feature work; open defect WUs; candidate not cut |
| HAR-007 fix changes wider body semantics | High | High | timeout/retry/parallel or Jenkins parity tests regress | typed contract first; surgical tests + full L5 + harness |
| PR reconciliation drops required code | Medium | High | superseded branch contains unique semantic change | compare patch-id + affected tests + receipt trace before closure |
| Required admission check depends on unavailable external service | Medium | High | branch protection cannot observe harness verdict | publish signed/digested result back to GitHub; fail closed |
| Current-state generator becomes stale | Low | High | manually edited generated file | generator validation in CI/local admission; source digest embedded |
| Dependency batches introduce incompatibility | Medium | Medium | Kotlin/Gradle/action update breaks build | batch by ecosystem; separate security-sensitive upgrades |
| Kover instrumentation perturbs performance | Known | Medium | throughput floor regresses only under coverage | never use coverage-instrumented run as performance oracle |
| Mutation run becomes too expensive | Medium | Low | runtime exceeds budget | target decision packages only |
| Memory SLO cannot be reproduced reliably | Medium | High | high variance across runs | pin machine/JDK/load; use median + max budget; mark UNKNOWN otherwise |
| Coordinator extraction changes durable identity | Medium | Critical | journal/event/replay diff | golden journal/event/fingerprint tests after each extraction |
| Compatibility seams migrate incompletely | Medium | High | one legacy construction path bypasses new engine | architecture test banning seam outside composition root |
| Phase 3 contaminates release candidate | Medium | High | candidate branch includes refactor commits | hard candidate freeze; Phase 3 separate branch until RP-5 GO |

## Stop conditions

Stop production-readiness promotion immediately if any of the following appears:

- mandatory test failure;
- candidate SHA mismatch;
- artifact hash mismatch;
- HAR-007 regression;
- replay duplicates a memoized side effect;
- secret visible in an observable surface;
- external harness evidence does not bind to the candidate artifact;
- memory/performance status is UNKNOWN where the gate requires PASS.
