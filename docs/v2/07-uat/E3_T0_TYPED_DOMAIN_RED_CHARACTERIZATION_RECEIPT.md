# LFC-2E3-T0 — TESTING-REPORTS / T0 typed-domain + RED characterization

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T0 — typed domain discovery + RED characterization |
| Status | RED (parser intentionally absent); domain GREEN |
| Authoring surface | NEW plugin coordinate `pipeline.testing@0.1.0-SNAPSHOT` |
| Plugin JAR | not produced yet (T0 produces only `jar` of the typed model + RED tests) |
| Production core changes | ZERO |
| SDK changes | ZERO |
| Dependencies added | none (kotlinx-serialization-json already used by sibling utilities-plugin) |
| Receipt author | generated for E3-T0 |

## 1. Why a NEW plugin coordinate?

LFC-2E2 proved the `pipeline.utilities.json@1.0.0` coordinate scales for filesystem / archive / codec families. LFC-2E3 deliberately ships under a separate coordinate (`pipeline.testing@0.1.0-SNAPSHOT`) because:

- E3 is a **different dimension** (structured test results + HTML report publication),
- keeping the OFFICIAL_PLUGIN coordinates independent preserves the property
  "any single OFFICIAL_PLUGIN can ship, retract, or be retired without affecting
  the others",
- a future third dimension will pick its own coordinate.

Production core is unaware of either coordinate.

## 2. Architectural claims (E3-T0)

- **A1 (typed domain)**: `TestReport`, `TestSuiteResult`, `TestCaseResult`,
  `TestFailure`, `TestStatus` are a closed ADT hierarchy. No `Map<String, Any>`
  appears in the public model. Sealed `TestStatus` makes impossible states
  unrepresentable (a case is **exactly one** of `Passed`, `Skipped`, `Disabled`,
  `Failed(failure)`, `Errored(failure)`).
- **A2 (separation of infrastructure failure vs typed result)**:
  `TestReport.Successful` carries typed data; `TestReport.Unparseable` carries
  an explicit `ParseFailureReason`. The directive's central distinction
  ("tests failed" ≠ "Step execution failed") is now structural: a downstream
  Step can dispatch on `is TestReport.Successful` vs `is TestReport.Unparseable`
  without inspecting strings.
- **A3 (no Map-shaped contract)**: the SPI is `TestReportAdapter` — a single
  `parse(bytes, source) -> TestReport` function. There is no `Map<String, Any>`
  public contract for adapters.
- **A4 (adapter isolation)**: each framework family (JUnit XML today; future
  TAP/xUnit/NUnit only after a third family proves a need for an abstract
  `ReportStore`) supplies its own `TestReportAdapter` implementation. The SPI
  is what makes the LFC-2E3 dimension open; the existing U1..U8 capability
  discipline (one capability token per port) will be mirrored for testing.
- **A5 (deterministic replay)**: the SPI contract requires deterministic
  parsing for the same input bytes — the test `parse result is deterministic
  for the same input` pins this invariant.

## 3. Counter rollup (E3-T0)

| Indicator | Before E3-T0 | After E3-T0 |
| --- | --- | --- |
| Certified Steps (E2 legacy) | 17 | 17 |
| Steps in NEW `pipeline.testing` coordinate | 0 | 0 (T0 is domain only; T2 creates the Steps) |
| Production core routes Step-specific code | 0 | 0 |
| Capability tokens (utilities surface) | 6 | 6 (T0 introduces NO capability yet) |
| Capability tokens (testing surface) | 0 | 0 (T2 introduces them) |
| Test report adapter types in plugin | 0 | 1 SPI + 0 implementations (T1) |
| Contract tests for testing-plugin | 0 | 18 (8 domain + 10 JUnit XML adapter) |
| RED failures with expected reason | n/a | 10/10 (adapter not implemented) |
| GREEN passes (domain only) | n/a | 8/8 |

## 4. Test evidence

L1 run against `examples/testing-plugin`:

```text
18 tests completed, 10 failed
- 10x JunitXmlAdapterContractTest FAILED with NotImplementedInT0 (E3-T0 RED marker)
-  8x TestReportDomainContractTest PASSED (domain invariants hold)
```

The RED discipline is intact: every RED fails for the EXPECTED reason
("parser intentionally absent in E3-T0") rather than a NullPointerException,
build error, or unrelated assertion. The `NotImplementedInT0` carrier will be
removed in E3-T1 once the JUnit XML adapter is implemented.

## 5. RED characterization matrix

The directive required REDs for: single suite, multiple suites, failed testcase,
skipped testcase, malformed XML, missing optional fields, duplicate / ambiguous
identifiers, large fixture. The matrix below maps each to a fixture and the
contract it asserts.

| RED case | Fixture | Asserted contract |
| --- | --- | --- |
| single suite, failed case | `single-suite-failure.xml` | `Successful` with 1 case `Failed(failure)` carrying `failureType` + non-empty `stackTrace` |
| multiple suites, skipped | `multi-suite-skipped.xml` | 2 `TestSuiteResult` entries; 1 skipped case |
| errored case | `single-suite-error.xml` | distinct from failed: `TestStatus.Errored` |
| missing optional fields | `missing-optional-fields.xml` | nulls not defaults; totals derived from cases |
| duplicate / ambiguous IDs | `duplicate-testcase-id.xml` | `Unparseable(AmbiguousIdentity)` — never a silent merge |
| malformed XML | `malformed-xml.xml` | `Unparseable(XmlMalformed)`, 0 fabricated cases |
| non-XML input | `non-xml.txt` | `Unparseable(SchemaMismatch)` |
| large fixture | synthetic 1000-case XML | correct totals + < 5s parse (regression guard against O(n^2) bugs) |
| determinism | `single-suite-failure.xml` | same bytes -> equal typed report |

## 6. Files added (T0)

```text
examples/testing-plugin/build.gradle.kts
examples/testing-plugin/settings.gradle.kts
examples/testing-plugin/src/main/kotlin/pipeline/testing/results/TestResults.kt
examples/testing-plugin/src/main/kotlin/pipeline/testing/results/TestReportAdapter.kt
examples/testing-plugin/src/main/kotlin/pipeline/testing/results/JunitAdapterFactory.kt
examples/testing-plugin/src/test/kotlin/pipeline/testing/results/TestReportDomainContractTest.kt
examples/testing-plugin/src/test/kotlin/pipeline/testing/results/JunitXmlAdapterContractTest.kt
examples/testing-plugin/src/test/resources/junit/single-suite-failure.xml
examples/testing-plugin/src/test/resources/junit/multi-suite-skipped.xml
examples/testing-plugin/src/test/resources/junit/single-suite-error.xml
examples/testing-plugin/src/test/resources/junit/missing-optional-fields.xml
examples/testing-plugin/src/test/resources/junit/duplicate-testcase-id.xml
examples/testing-plugin/src/test/resources/junit/malformed-xml.xml
examples/testing-plugin/src/test/resources/junit/non-xml.txt
```

## 7. Known limitations / next slice

- E3-T1 GREEN: implement `JunitXmlAdapter` in `pipeline.testing.junit`,
  replace `JunitAdapterFactory.junitAdapter()` with the real instance. The
  10 RED tests should turn green without any contract change.
- The skipped-status reason field is not yet asserted at the contract level.
  E3-T1 may refine `TestCaseResult` to expose the `<skipped message="..."/>`
  reason; if it does, the matrix above is amended in T1's receipt, not T0's.
- Capability tokens for the future `core.junit` / `core.publishHTML` Steps
  will be introduced in T2 / R1 respectively — NOT in T0. T0 stays free
  of capability design until a real Step handler needs it.
