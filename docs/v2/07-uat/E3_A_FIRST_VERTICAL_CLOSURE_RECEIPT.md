# LFC-2E3-A — FIRST VERTICAL CLOSURE: TEST RESULTS + HTML REPORTING

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | E3-A — closure of the TEST RESULTS + HTML REPORTING vertical |
| Status | **CLOSED** for this vertical (T0..T4, R1, R2) |
| Closing HEAD | `002a23ea` |
| Scope firewall | junit + publishHTML only. Coverage / analyzers were explicitly deferred. |
| Production core changes | host composition only (T4); no Step-specific routing |
| External dependencies added | ZERO (pure JDK XML + NIO throughout) |

## 1. What this vertical delivered

A complete, independent OFFICIAL_PLUGIN coordinate that turns test output into typed data and
publishes HTML reports, with the platform's central reporting invariant enforced structurally.

```text
JUnit XML  --core.junit-->  typed TestReport  --core.publishHTML-->  typed PublishedReport
                                   |
                                   +-- TestingEventDerivation --> 3 typed testing events
```

## 2. Architectural claims (A1..A12)

**A1 — A second OFFICIAL_PLUGIN coordinate exists and is independent.**
`pipeline.testing@0.1.0-SNAPSHOT` ships alongside `pipeline.utilities.json@1.0.0`. Production core
names neither. Independence is proven by both JARs registering through the same single
`ServiceLoader` site without interfering.

**A2 — Test results are a typed ADT, never a `Map<String, Any>`.**
`TestReport` (sealed), `TestSuiteResult`, `TestCaseResult`, `TestFailure`, `TestStatus` (sealed with
five cases). Totals are computed from the contained cases, so the model is the single source of
truth and cannot disagree with itself.

**A3 — "tests failed" ≠ "Step execution failed" — structurally enforced, then proven end-to-end.**
The Step cannot collapse test failures into its own outcome:

| Situation | Typed outcome | Handler throws? |
| --- | --- | --- |
| report parses, N tests failed | `TestReport.Successful`, `hasTestFailures = true` | no |
| report parses, all pass | `TestReport.Successful`, `hasTestFailures = false` | no |
| file missing / unreadable | `JunitStepError.ReportNotFound` / `ReportIoFailure` | yes |
| XML malformed | recorded in `JunitStepOutput.parseFailures` | no |
| capability absent | rejected at prepare-time, handler never runs | n/a |

Proven through the real installed distribution: a pipeline consuming a report with 1 failed and 1
errored testcase finishes **SUCCESS** with 0 step failures (`E3_T4` §3.1, `E3_R2` §2.2).

**A4 — Partial parse success is surfaced faithfully.**
Well-formed inputs contribute their data; each malformed input contributes one typed
`ParseFailureReason`. An entirely unparseable input set becomes `Unparseable` with **zero fabricated
tests**.

**A5 — Capability discipline: one narrow port per concern, separately grantable.**
`testing.filesystem.operations` (read-only parser side) and `testing.publish.operations` (writing
publisher side) are distinct tokens. Granting read access to reports does not grant permission to
write published copies; the distinction is asserted.

**A6 — Fail-closed admission on every path.**
Both Steps declare exactly the capabilities they use; missing admission rejects before the handler
runs, asserted both ways for both Steps.

**A7 — Testing events are summary + references, never a per-case explosion.**
The invariant is arithmetic, not convention:

```text
|derive(report)| == 1 + suites + (if hasTestFailures 1 else 0)
```

Pinned at a 1000-case boundary (1000 cases / 2 suites → exactly 4 events) and by asserting that case
identity cannot leak into the event stream **even after JSON serialisation**.

**A8 — Events carry no forgeable execution identity.**
No `runId` / `stepIndex` / `eventId` field exists on the event ADT; those are authority facts
stamped at transport time. A Step that could assert them could inject events into another run.

**A9 — The event *transport* gap is classified, not improvised.**
External plugins have no typed event-emission seam: the public SDK is `pipeline-domain` +
`pipeline-scripting-api`, while `EventSink` lives in `pipeline-events` and `EVENT_SINK_CAPABILITY` in
`pipeline-application`; `CommonExecutionResult` carries no sidecar events. This is **inherited** —
the 17 CERTIFIED E2 utilities Steps emit no custom events either. The model and its pure derivation
are delivered; a minimal authority-stamped, namespaced seam is specified for a future milestone
(`E3_T3` §5.3).

**A10 — `publishHTML` fails closed against traversal and symlinks.**
Seven dedicated guards: `..` traversal and absolute entries → `EntryEscapesReportDir`; symlinked
report directory, in-containment symlink entry, and any link in the tree → `SymlinkRejected`; a
symlink escaping containment is classified as the stronger *escape*. The whole tree is validated
before any copy, so **no partial publication** is possible, and the rejection test asserts the
destination stays absent.

**A11 — No `ReportStore` mega-abstraction.**
Mechanically guarded by scanning for `interface|class|object|typealias` declarations of
`ReportStore` / `QualityPlatform` / `ResultStore` across both plugin modules. Prose that explains the
absence is permitted; declaring one fails the suite.

**A12 — The plugin platform gained a generic capability-contribution seam.**
`StepCapabilityContributor` (new public SDK SPI) plus `ExternalStepPluginDiscovery` composition
closes a gap that made **17 Steps across 2 coordinates unrunnable from the installed CLI**. It is
additive, preserves the single ServiceLoader site, and preserves binary compatibility: the first
attempt added a defaulted method to `StepDefinitionContributor`, which the new fitness test caught as
an `AbstractMethodError` for an already-built plugin JAR.

## 3. Counter rollup

| Indicator | Cycle start | Closing |
| --- | --- | --- |
| OFFICIAL_PLUGIN coordinates | 1 | **2** |
| Registered StepKeys | 17 | **19** |
| Steps in the `pipeline.testing` coordinate | 0 | **2** |
| Capability tokens (testing) | 0 | **2** |
| Public SDK SPIs | 1 | **2** |
| Reporting mega-abstractions | 0 | **0** |
| Steps unrunnable from the installed CLI | 17 | **0** |
| Legacy residual | 0 | **0** |
| Step-specific core routing | 0 | **0** |
| Provider drift | 0 | **0** |
| Certification drift | 0 | **0** |
| Capability drift | 0 | **0** |
| External dependencies added | 0 | **0** |

Registry composition at closing: 16 utilities + 1 `example.uppercase` + `core.junit` +
`core.publishHTML` = **19**.

## 4. Test evidence (fresh at `002a23ea`)

| Suite set | Tests | Failures | Errors |
| --- | --- | --- | --- |
| `examples/testing-plugin` (3 suites) | 34 | 0 | 0 |
| `:pipeline-application` plugin suites (13 suites) | 208 | 0 | 0 |
| **Total** | **242** | **0** | **0** |

The 208 comprise: `TestingJunit` 20, `TestingPublishHtml` 22, `ExternalStepCapabilityContribution` 6,
`Lfc2E2ExpansionGateFitness` 22, `Lfc2E2PrepFitness` 10, `Uppercase` 14, and the six utilities
suites totalling 114.

### Installed-CLI acceptance (real distribution, real plugin JAR)

| Fixture | Exit | Steps | Step failures | Outcome | Log sha256 |
| --- | --- | --- | --- | --- | --- |
| `junit-success.pipeline.kts` | 0 | 1 | 0 | SUCCESS | `d4e08713f22c0d90882ed35bca178e915eb223e249c811dea8c8494d5be2f084` |
| `junit-failures.pipeline.kts` | 0 | 1 | 0 | SUCCESS | `b4d45725f22891679417ee843578c2d4f57b6e8d1d4655fce4227670a2188eeb` |
| `junit-then-publish.pipeline.kts` | 0 | 2 | 0 | SUCCESS | `ac5d4c801dd3ee142610ef0686efda1bf24c8924d2828a5d73511bcb3c482c2b` |
| `01-json-roundtrip.pipeline.kts` (utilities control) | 0 | 3 | 0 | SUCCESS | `073f8d9140698849980ef6ea755bb24339f7f7340caae797f207da87703f6b85` |

The published artifact was verified **on disk**, not merely reported:
`build/testing-published/unit-tests/index.html` + `assets/summary.css`.

## 5. Round gate

Full `check` at HEAD: **28 distinct failures, byte-identical to cycle base `440fc7ca`**
(no additions, no removals in either direction). Base log sha256
`8c383cf953b00d342530d179559dafac0ac007d5f9b29eafe77d7d2fc718f443`. The two
`:pipeline-application` UAT failures (`UatLocal005CheckoutGitTest > SC-007`,
`UatLocal007SandboxProfileTest > SB-S-010`) were independently reproduced at base with identical
signatures. All gate failures are **pre-existing**; there are **zero regressions** from this cycle.

## 6. Receipt map

| Slice | Commit | Receipt |
| --- | --- | --- |
| T0 | `106703a6` | `E3_T0_TYPED_DOMAIN_RED_CHARACTERIZATION_RECEIPT.md` |
| T1 | `3848955d` | `E3_T1_JUNIT_XML_ADAPTER_RECEIPT.md` |
| T2 | `e660e404` | `E3_T2_JUNIT_STEP_RECEIPT.md` |
| T3 | `ca14d3b8` | `E3_T3_TESTING_EVENTS_RECEIPT.md` |
| T4 (fixtures) | `01084882` | `E3_T4_REAL_FIXTURES_CLI_ACCEPTANCE_RECEIPT.md` |
| T4 (seam) | `4be4acaf` | same receipt, §4 |
| R1 + R2 | `002a23ea` | `E3_R1_PUBLISH_HTML_RECEIPT.md`, `E3_R2_LINKAGE_RECEIPT.md` |
| A | this file | `E3_A_FIRST_VERTICAL_CLOSURE_RECEIPT.md` |

Deliverables:

```text
examples/testing-plugin/                       new independent plugin build
examples/testing/                             4 fixtures + HTML report tree
docs/v2/07-uat/E3_*.md                        8 receipts (this one included)
v2/pipeline-domain/.../StepCapabilityContributor.kt    new SDK SPI
v2/pipeline-application/.../ExternalStepPluginDiscovery.kt + Main.kt   host composition
v2/pipeline-application/src/test/...           4 new suites (6 + 22 + 20 + 22 rows)
```

## 7. Open items (explicit, NOT silently deferred)

1. **Event transport seam** (`E3_T3` §5.3). Model + pure derivation delivered; transport needs a new
   milestone (SDK publication + runtime adapter). Distinct from the capability seam closed in T4.
2. **Step-output value piping.** No mechanism lets one Step consume another Step's typed output as
   input, so `publishHTML` is pointed at the report directory rather than handed the `TestReport`
   value. Three workarounds were considered and rejected (`E3_R2` §4); it is a generic platform
   capability belonging to its own slice.
3. **Pre-existing core capability gaps.** `core.milestone`, `core.cleanWs`, `core.deleteDir` and
   `core.archiveArtifacts` declare capabilities the minimal canonical bridge does not expose. The new
   fitness row scopes itself to *external* Steps precisely so this stays visible instead of being
   folded in and hidden. Carried from LFC-2E2, unchanged by this cycle.
4. **`<skipped message>` reason** is not carried into the typed model. Deliberate: no consumer needs
   it yet, and adding a nullable field to widen the case state space would violate the strict-typing
   rules. It should arrive as a typed extension with its own case when a consumer exists.
5. **Report retention.** `keepAll` / `alwaysLinkToLastBuild` are absent because V2 has no
   build-history store. Modelled as absent, not as accepted-and-ignored.
6. **Coverage / analyzers** were out of scope for this vertical by the cycle directive.

## 8. Closure assertion

The LFC-2E3 first vertical (TEST RESULTS + HTML REPORTING) is **CLOSED**:

- both Steps execute end-to-end through the installed distribution with real plugin JARs;
- the central invariant (`"tests failed" ≠ "Step execution failed"`) is enforced structurally and
  proven on the linked chain;
- the typed domain, the events, the publish model and the security guards are all covered by
  contract suites (242 tests, 0 failures);
- production core gained **no** Step-specific knowledge; the only core change is host composition
  that makes plugin-declared capabilities reachable, and it is generic;
- the round gate shows **zero regressions** against the cycle base.

Do **not** reopen this vertical without a demonstrated regression.
