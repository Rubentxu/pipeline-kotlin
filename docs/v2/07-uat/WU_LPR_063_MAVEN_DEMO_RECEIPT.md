# WU-LPR-063 Receipt — Real Maven project via installed distribution

## Fixture: `integration/maven-demo/`
Real Maven project (pom.xml, JUnit 5, surefire 3.2.5, jar packaging):
`App.java` (add), `AppTest.java` (pass + deliberate-break switch via
`-Ddemo.brokenProp=true` → surefire `systemPropertyVariables` → `demo.broken`).
`pipeline.kts` (`mvn -q -B package` + jar check + MAVEN-DEMO-OK) and
`pipeline-fail.kts` (`mvn -q -B test -Ddemo.brokenProp=true`).

## Change
No production change required: WU-LPR-062's `--workspace` is generic.
Maven 3.9.9 installed via asdf (`.tool-versions`).

## Evidence (OBSERVED)
- Success: installed `pipeline-application run --db ... --workspace . pipeline.kts`
  → exit 0, RunFinished success, MAVEN-DEMO-OK, `target/maven-demo-1.0.0.jar`.
- Failure: `pipeline-fail.kts` → exit 1, RunFinished failure, surefire failure
  (deliberateBreakSwitch assertion).
- Baselines: `mvn -B package` green; `-Ddemo.brokenProp=true` red (Failures: 1).

## Counters
Certified Steps unchanged: 15 SUPPORTED_CERTIFIED (+1 EXPERIMENTAL).
