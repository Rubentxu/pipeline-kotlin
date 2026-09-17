# LFC-2E3-T2 — `core.junit` STEP (GREEN)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T2 — `core.junit` StepDefinition (first Step of the testing coordinate) |
| Status | GREEN (plugin 18/18, application 148/148 across 9 suites; 0 failures, 0 errors) |
| Predecessors | T0 `106703a6`, T1 `3848955d` |
| Plugin coordinate | `pipeline.testing@0.1.0-SNAPSHOT` (second OFFICIAL_PLUGIN, separate dimension) |
| StepKey | `core.junit` |
| Capability token | `testing.filesystem.operations` (exactly one, new) |
| Production core changes | ZERO (only Gradle wiring for the external plugin JAR) |
| SDK changes | ZERO |
| External dependencies added | ZERO |

## 1. What landed

**Plugin side (`examples/testing-plugin`)**
- `TestingContributor` — the `StepDefinitionContributor` for the `pipeline.testing`
  coordinate; registers `core.junit`. One contributor per JAR (not one per family),
  matching the utilities pattern so R1's `publishHTML` joins the same contributor.
- `JunitStepPlugin.kt` — typed failure ADT `JunitStepError` (3 cases:
  `ReportNotFound`, `ReportIoFailure`, `ReportUnparseable`), the typed exception
  carrier `JunitStepException`, the narrow capability port
  `JunitFilesystemOperations` (single `readBytes(path)` method), the default
  FS-backed implementation, and the typed `JunitStepInput` / `JunitStepOutput`.
- `JunitStepDefinition.kt` — the StepDefinition, both codecs, the handler, and the
  typed DSL facade (`StageScope.junit(paths)`).
- `META-INF/services/...StepDefinitionContributor` — ServiceLoader descriptor.
- `@Serializable` added to the three sealed root types (`TestReport`, `TestStatus`,
  `ParseFailureReason`) — required for the codec round-trip; the subclasses already
  carried it. `TestReport.suites` now defaults to `emptyList()` on the interface so
  `Unparseable` need not declare a constructor parameter it does not have.

**Application side (`v2`)**
- `v2/build.gradle.kts` — new `buildTestingPlugin` task (Lane R: independent Gradle
  build consuming THIS revision's SDK from the build-local Maven repo).
- `v2/pipeline-application/build.gradle.kts` — the testing JAR on the test
  classpath, ordered before `compileTestKotlin` and `test`.
- `TestingJunitStepContractSuiteTest` — 20-row StepContractSuite.

## 2. The central architectural invariant (E3's whole point)

```text
"tests failed"  !=  "Step execution failed"
```

This is enforced structurally, not by convention:

| Situation | Typed outcome | Handler throws? |
| --- | --- | --- |
| Report parses, N tests failed | `TestReport.Successful` with `hasTestFailures == true` | NO |
| Report parses, all tests pass | `TestReport.Successful`, `hasTestFailures == false` | NO |
| Report file missing | `JunitStepError.ReportNotFound` | YES (`JunitStepException`) |
| Report file unreadable | `JunitStepError.ReportIoFailure` | YES |
| Report is malformed XML | `JunitStepOutput.report = Unparseable`, recorded in `parseFailures` | NO |
| Capability not admitted | rejected at prepare-time, handler never runs | n/a |

The Step reports faithfully; a *later policy layer* decides whether failing tests
should abort the build. That policy Step does not exist yet and is deliberately not
invented here.

Per-file parse failures do **not** throw: partial success is surfaced as a
`Successful` report for the well-formed inputs plus one `ParseFailureReason` per
malformed input. When *every* input is unparseable the aggregate becomes
`Unparseable` with zero fabricated tests.

## 3. Cross-file identity guard (new in T2)

The T1 adapter enforces fqtn uniqueness **within** one document. T2 additionally
checks fqtn collisions **across** the input paths and routes them to
`ParseFailureReason.AmbiguousIdentity` rather than silently merging two different
suites that happen to share `suiteName::caseName`.

## 4. Capability discipline (LB-02 / G3-A4.2)

- The handler declares `testing.filesystem.operations` and reaches the filesystem
  only through `JunitFilesystemOperations`.
- The handler never touches `CanonicalRuntimeContext`, `ProcessBuilder`, or any
  engine class.
- Admission is fail-closed: `RegistryExecutionPreparation.prepare` rejects
  `core.junit` when the token is absent, before the typed handler runs. Both the
  accepted and rejected paths are asserted.
- The token is **new**: it does not reuse `utilities.json.operations` or
  `utilities.archive.operations`, so the three ports cannot satisfy each other.
- The source-scan fitness row asserts the plugin declares **exactly one**
  `StepCapability("…")` literal.

## 5. Test evidence

### Plugin side — `examples/testing-plugin` (18/18)

| Suite | Tests | Result |
| --- | --- | --- |
| `JunitXmlAdapterContractTest` | 10 | 0 failures |
| `TestReportDomainContractTest` | 8 | 0 failures |

### Application side — `:pipeline-application:test` (148/148 across 9 suites)

| Suite | Tests | Result |
| --- | --- | --- |
| `TestingJunitStepContractSuiteTest` | 20 | 0 failures (NEW) |
| `UtilitiesJsonStepContractSuiteTest` | 26 | 0 failures |
| `UtilitiesTarStepContractSuiteTest` | 17 | 0 failures |
| `UtilitiesYamlStepContractSuiteTest` | 17 | 0 failures |
| `UtilitiesArchiveStepContractSuiteTest` | 15 | 0 failures |
| `UtilitiesChecksumsStepContractSuiteTest` | 15 | 0 failures |
| `UppercaseStepContractSuiteTest` | 14 | 0 failures |
| `UtilitiesFilesystemStepContractSuiteTest` | 12 | 0 failures |
| `UtilitiesPropertiesStepContractSuiteTest` | 12 | 0 failures |

The new suite covers: identity, registry coexistence, contract completeness
(effects / replay policy / capabilities / pluginId+version), the one-capability
source scan, `JunitStepError` exhaustiveness, input+output codec round-trips
(both `Successful` and `Unparseable`), capability admission (accept + reject),
handler semantics (happy path, tests-failed-but-Successful, missing file,
malformed XML, mixed valid+invalid, all-invalid), registry resolution, canonical
envelope shape, observability descriptor consistency, and a real DSL end-to-end
run through the canonical durable spine.

### Registry-composition count updates (6 pre-existing suites)

Adding `core.junit` moves the discovered-key count from 17 to 18. Six existing
suites asserted the old count; they were **updated, not weakened**:

```text
UtilitiesArchive / Checksums / Filesystem / Properties / Tar / Yaml
  assertEquals(17, r.keys().size)  ->  assertEquals(18, r.keys().size)
```

Composition is now: 16 utilities + 1 `example.uppercase` + 1 `core.junit` = 18.
The receipt records the arithmetic so the next family updates it deliberately.

## 6. Pre-existing failures (NOT regressions) — base-vs-head evidence

The full `:pipeline-application:test` run surfaced two failures. Both were
reproduced on the **cycle base commit `440fc7ca`** with the unmodified tree:

| Test | Base `440fc7ca` | Head (this slice) |
| --- | --- | --- |
| `UatLocal005CheckoutGitTest > SC-007` | FAIL (`IllegalStateException`, git-wrapper refuses to sign: "repo NO CLASIFICADO") | FAIL (identical) |
| `UatLocal007SandboxProfileTest > SB-S-010` | FAIL (`AssertionFailedError`) | FAIL (identical) |

Base evidence: `git rev-parse HEAD` = `440fc7caf7c4bab0817d094e5f07ccbc9401e554`,
log digest `sha256 = 8c383cf953b00d342530d179559dafac0ac007d5f9b29eafe77d7d2fc718f443`.
`SC-007` fails inside the host git wrapper's fail-closed commit-signing guard
(environmental); `SB-S-010` is a sandbox-profile assertion. Classified
PRE-EXISTING and out of LFC-2E3 scope, per the cycle convention already applied
to `UatLocal008` / `UatLocal009`.

## 7. Counter rollup (E3-T2)

| Indicator | Before T2 | After T2 |
| --- | --- | --- |
| Registered StepKeys | 17 | 18 |
| OFFICIAL_PLUGIN coordinates | 1 (`pipeline.utilities.json`) | 2 (+ `pipeline.testing`) |
| Steps in testing coordinate | 0 | 1 (`core.junit`) |
| Testing capability tokens | 0 | 1 (`testing.filesystem.operations`) |
| Step-specific core references | 0 | 0 |
| Legacy residual | 0 | 0 |
| Provider drift | 0 | 0 |
| Certification drift | 0 | 0 |
| Capability drift | 0 | 0 |
| Production core semantic changes | 0 | 0 |

## 8. Files added / changed

```text
examples/testing-plugin/src/main/kotlin/pipeline/testing/TestingContributor.kt          (new)
examples/testing-plugin/src/main/kotlin/pipeline/testing/junit/JunitStepPlugin.kt       (new)
examples/testing-plugin/src/main/kotlin/pipeline/testing/junit/JunitStepDefinition.kt   (new)
examples/testing-plugin/src/main/resources/META-INF/services/…StepDefinitionContributor (new)
examples/testing-plugin/src/main/kotlin/pipeline/testing/results/TestResults.kt         (@Serializable on sealed roots; suites default)
v2/build.gradle.kts                                                                     (buildTestingPlugin)
v2/pipeline-application/build.gradle.kts                                                (test classpath + task ordering)
v2/pipeline-application/src/test/kotlin/…/TestingJunitStepContractSuiteTest.kt          (new, 20 rows)
v2/pipeline-application/src/test/kotlin/…/Utilities{Archive,Checksums,Filesystem,Properties,Tar,Yaml}StepContractSuiteTest.kt  (count 17 → 18)
```

## 9. Known limitations / next slice

- The `<skipped message="…"/>` reason is still not carried into the typed model.
  It is a deliberate deferral: no consumer needs it yet, and adding a nullable
  field to `TestCaseResult` to hold an optional string would be exactly the
  state-space widening the strict-typing rules discourage. If E3-T3 events or a
  later consumer needs it, it arrives as a typed extension with its own case.
- No `policy.failOnTestFailures` Step exists. The invariant is *enforced* (the
  Step cannot collapse failures into its outcome) but the *policy* that consumes
  `hasTestFailures` is a separate, later decision — deliberately not invented here.
- E3-T3 (testing events: `TestReportPublished` / `TestSuiteCompleted` /
  `TestFailuresDetected`, summary-level references, not per-testcase) is next.
