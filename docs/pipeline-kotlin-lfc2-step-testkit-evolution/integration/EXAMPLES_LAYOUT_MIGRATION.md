# Examples/corpus migration

## Responsibilities

```text
examples/                 user-facing
v2/compatibility/         Jenkins/DSL corpus
test-fixtures/scenarios/  durability/security/stress
plugin-fixtures/          independent plugin builds
```

## Migration phases

### A — no renames
- ScenarioRunner supports current flat examples.
- current run.sh becomes wrapper/delegator.
- expected exit comes from metadata.

### B — sidecars

```text
03-shell.pipeline.kts
03-shell.scenario.yaml
```

### C — bundles for complex cases

```text
20-retry-timeout/
  pipeline.pipeline.kts
  scenario.yaml
  expected/events.json
```

### D — categorize compatibility

Only after runner preserves IDs/reference links.

## Proposed curated examples

1. hello
2. multi-stage
3. real shell
4. Kotlin scripted control flow
5. typed failing step
6. durable/replay
7. environment precedence
8. nested withEnv
9. workspace/dir
10. files
11. git checkout
12. artifacts
13. retry eventual success
14. timeout
15. catchError/warnError
16. credentials redaction
17. composable parallel
18. parallel failure/cancel
19. declarative when
20. post lifecycle
21. scripted shStdout
22. kill/resume
23. stdout/stderr
24. external atomic plugin
25. external block plugin

## High-value composition scenarios

- credentials + dir + withEnv + sh;
- retry + sh with attempt counter;
- timeout + child process;
- parallel + withEnv + serial siblings;
- catchError + archive;
- plugin atomic inside retry;
- plugin block containing core sh;
- kill/resume after one parallel branch completed;
- credential cleanup during timeout/cancel.

Estos escenarios combinatorios son esenciales para descubrir coupling/context leakage.
