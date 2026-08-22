# UAT-M0-001 Evidence

## Purpose

UAT-M0-001 validates the `HelloPipelineFixture` baseline — a deterministic, value-only fixture that seeds the `:pipeline-testkit` module with a two-step pipeline definition (`PipelineDefinition(name="hello")` + two `StepDescriptor` seeds). This cycle closes the M0-R5 UAT baseline work and confirms the M1 boundary is preserved (no Custom Scripting host, no `pipeline-steps-system` jar, no domain field additions).

## How to Run

```sh
./gradlew -p v2 :pipeline-testkit:test --tests '*UatM0001*'
```

For the full aggregate check (all modules, including 12 architecture tests):

```sh
./gradlew -p v2 clean check
```

## Expected Output

```
BUILD SUCCESSFUL in Xs
2 tests completed, 0 failures

UatM0001HelloPipelineTest > UAT-M0-001 — fixture has exact shape PASSED
UatM0001HelloPipelineTest > UAT-M0-001 — rebuild is deterministic PASSED
```

## Fixture Listing

| id          | type   | configRef          |
|-------------|--------|--------------------|
| `hello-echo` | `echo`  | `hello.echo.config` |
| `hello-sleep`| `sleep` | `hello.sleep.config` |

## PASS Criteria

- [ ] `./gradlew -p v2 clean check` exits with code 0 (12 arch tests + UAT test green)
- [ ] `./gradlew -p v2 :pipeline-testkit:test --tests '*UatM0001*'` shows 2/2 tests green
- [ ] `HelloPipelineFixture.build()` produces a holder whose `definition.name == "hello"` and `steps.size == 2`
- [ ] Two successive calls to `HelloPipelineFixture.build()` produce equal holders (determinism)

## Cross-links

- [ROADMAP (M0 row)](../../05-roadmap/ROADMAP.md) — M0 exit criteria and UAT baseline
- [UAT_SCENARIOS.md (UAT-M0-001)](./UAT_SCENARIOS.md) — scenario definitions for UAT-M0-001
- [UAT_MASTER_PLAN.md (M0)](./UAT_MASTER_PLAN.md) — UAT master plan and progression
