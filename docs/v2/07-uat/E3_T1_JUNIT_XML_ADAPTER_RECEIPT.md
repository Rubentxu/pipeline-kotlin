# LFC-2E3-T1 — JUNIT XML ADAPTER (GREEN)

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T1 — JUnit XML adapter (GREEN) |
| Status | GREEN (18/18, 0 failures, 0 errors, 0 skipped) |
| T0 commit | `106703a6` (RED characterization + typed domain) |
| Production core changes | ZERO |
| SDK changes | ZERO |
| External dependencies added | ZERO (pure JDK `javax.xml.parsers`) |
| RED → GREEN delta | +1 file (JunitXmlAdapter.kt), 1 test rename (non-XML), 1 test fixture format fix |

## 1. What landed

- `pipeline.testing.junit.JunitXmlAdapter` — pure-JDK implementation of the
  T0 SPI (`TestReportAdapter`). Uses `javax.xml.parsers.DocumentBuilderFactory`
  with the fail-closed XML feature set (DOCTYPE disallowed, external entities
  disabled, external DTD loading disabled, FEATURE_SECURE_PROCESSING enabled).
- `JunitAdapterFactory` returns the real adapter; the T0 RED marker
  `NotImplementedInT0` is removed.
- Two contract-test fixes (none of which weakened assertions):
  - `non-XML input is rejected as XmlMalformed` — the T0 test name was wrong:
    plain text is **not** well-formed XML, so the closed `ParseFailureReason`
    routes it through `XmlMalformed`, not `SchemaMismatch`. The new name
    documents the contract precisely.
  - `large fixture of 1000 cases ...` — the synthetic fixture used a Kotlin
    triple-quoted string with mixed indent levels (cases had 2-space indent
    while the wrapper had 12-space indent). `trimIndent()` strips only the
    minimum indent, leaving 10 leading spaces before `<?xml`. XML 1.0 forbids
    whitespace before the XML declaration, so the parser correctly rejected
    it as malformed. The fixture is now constructed with explicit `buildString`
    so the `<?xml ?>` is at column 1.

## 2. Architectural claims (E3-T1)

- **B1 (no external dependency)**: the JUnit XML adapter is implemented
  with the JDK's own `DocumentBuilderFactory`. This was the deliberate
  choice per the cycle's "no external dependency without spike" rule and
  mirrors the U7 TAR decision (pure JDK beats Apache Commons Compress for
  one family). A future E3 family may revisit if a more expressive schema
  (xUnit, NUnit, TAP-with-attachments) demands it.
- **B2 (fail-closed XML features)**: the parser enables
  `FEATURE_SECURE_PROCESSING` and disables external entities, DOCTYPE, and
  external DTD loading — the canonical XXE-prevention set. A test report
  is data, not a program; it MUST NOT quietly expand an attacker-supplied
  entity.
- **B3 (explicit UTF-8 InputSource)**: the parser consumes the bytes via
  an `InputSource(InputStreamReader(bytes, UTF_8))` rather than
  `parse(bytes.inputStream())`. The explicit UTF-8 declaration is a
  robustness improvement against JDK 21's auto-detection quirks on
  no-BOM synthetic bytes.
- **B4 (deterministic parsing)**: the adapter is stateless and produces
  the same `TestReport` for the same input bytes — verified by the
  `parse result is deterministic for the same input` test.
- **B5 (closed failure taxonomy honoured)**: each `ParseFailureReason`
  case is reached through a specific path:
    - `XmlMalformed` — bytes cannot be parsed as XML (well-formedness
      failure; includes the non-XML text case)
    - `SchemaMismatch` — well-formed XML but wrong root element
      (anything other than `<testsuite>` / `<testsuites>`)
    - `AmbiguousIdentity` — fqtn(suite, case) collision across the
      parsed document
    - `SourceMissing` — reserved for the filesystem-capability boundary
      in E3-T2 (the adapter does not perform I/O)

## 3. Test evidence

```text
$ ./gradlew -p examples/testing-plugin test

BUILD SUCCESSFUL
18 tests, 0 failures, 0 errors, 0 skipped
- 10 JunitXmlAdapterContractTest
-  8 TestReportDomainContractTest
```

The full matrix from the directive is GREEN:

| RED case | Fixture | Result |
| --- | --- | --- |
| single suite, failed case | `single-suite-failure.xml` | GREEN — typed `Failed(failure)` with `failureType` + non-empty `stackTrace` |
| multiple suites, skipped | `multi-suite-skipped.xml` | GREEN — 2 `TestSuiteResult` entries; 1 skipped |
| errored case | `single-suite-error.xml` | GREEN — `TestStatus.Errored` distinct from `Failed` |
| missing optional fields | `missing-optional-fields.xml` | GREEN — nulls not defaults; totals derived from cases |
| duplicate / ambiguous IDs | `duplicate-testcase-id.xml` | GREEN — `Unparseable(AmbiguousIdentity)` |
| malformed XML | `malformed-xml.xml` | GREEN — `Unparseable(XmlMalformed)` |
| non-XML input | `non-xml.txt` | GREEN — `Unparseable(XmlMalformed)` (was wrongly `SchemaMismatch` in T0; fixed) |
| large fixture (1000 cases) | synthetic | GREEN — correct totals, ~10ms parse (well under 5s ceiling) |
| determinism | `single-suite-failure.xml` | GREEN — same bytes → equal typed report |

## 4. Counter rollup (E3-T1)

| Indicator | Before E3-T1 | After E3-T1 |
| --- | --- | --- |
| `TestReportAdapter` implementations in plugin | 0 | 1 (JUnit XML) |
| External XML dependencies | 0 | 0 |
| RED tests remaining | 10 | 0 |
| Total plugin tests | 8 (domain only) | 18 (domain + adapter) |
| Tests passing | 8 | 18 |
| Tests failing | 10 (RED expected) | 0 |

## 5. Files added / changed

```text
examples/testing-plugin/src/main/kotlin/pipeline/testing/junit/JunitXmlAdapter.kt   (new)
examples/testing-plugin/src/main/kotlin/pipeline/testing/results/JunitAdapterFactory.kt   (GREEN rewire)
examples/testing-plugin/src/test/kotlin/pipeline/testing/results/JunitXmlAdapterContractTest.kt   (synthetic XML whitespace fix; non-XML contract rename)
```

## 6. Known limitations / next slice

- The skipped-status reason (the `message` attribute on `<skipped/>`)
  is still NOT carried through the typed model. The T0 test asserts the
  status exists but does not yet assert the reason. E3-T2 will refine
  `TestCaseResult` if needed (most likely a typed extension on the case);
  if it does, the matrix above is amended in T2's receipt, not T1's.
- The `SourceMissing` `ParseFailureReason` case is currently unreachable
  from the adapter (the adapter does not perform I/O). It will become
  reachable in E3-T2 when the Step handler reads the report file via
  the declared filesystem capability.
- E3-T2 introduces the `core.junit` StepDefinition, the declared
  filesystem capability, and the `StepDefinitionContributor` SPI
  registration. Production core is still expected to change ZERO.
