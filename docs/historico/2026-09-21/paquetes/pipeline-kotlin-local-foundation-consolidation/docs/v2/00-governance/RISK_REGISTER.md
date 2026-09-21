# Risk register

| ID | Risk | Probability | Impact | Mitigation / evidence |
|---|---|---:|---:|---|
| R-001 | Big-bang model/runtime refactor destabilizes working durable behavior | Medium | Critical | vertical strangler slices; characterization tests; delete one bridge at a time |
| R-002 | Kotlin scripting/K2 upgrade semantics break compile diagnostics/cache | Medium | High | formal script definition spike + compilation corpus before API freeze |
| R-003 | Context parameters complicate KSP/reflection/plugin ABI | Medium | High | prototype external plugin; generated capability metadata fallback if KSP cannot inspect reliably |
| R-004 | Plugin API becomes too broad before real plugins exist | High | High | start with core + one external-style JUnit plugin; capability admission requires proven use cases |
| R-005 | Jenkins familiarity drives recreation of Groovy/CPS weaknesses | Medium | High | F-level policy; Kotlin-safe divergences with mechanical migration recipes |
| R-006 | Output store introduces ordering/backpressure bugs | Medium | Critical | sequence/cursor contract + stress/fault-injection UAT |
| R-007 | Jlink image misses modules needed by Kotlin compiler/scripting | Medium | High | LFC-9 spike on clean hosts before distribution lock; keep Java Binary fallback during RC only |
| R-008 | Package-manager metadata diverges | Medium | Medium | JReleaser as single release metadata source; install UAT for each channel |
| R-009 | Legacy paths survive indefinitely "for compatibility" | High | High | two-milestone removal budget + fitness tests + deletion gate |
| R-010 | Strong sandbox work distracts from local runtime semantics | High | Medium | hardened isolation explicitly deferred behind LFC-7 spike |
| R-011 | Plugin supply-chain complexity grows too early | Medium | Medium | immutable lock + checksum first; signatures/provenance added in release milestone |
| R-012 | Windows differences slow core consolidation | Medium | Medium | Linux primary development baseline; platform-specific distribution gates only when advertised |
