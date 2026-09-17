# LFC-2E3-R2 — LINKAGE: junit → TestReport → publishHTML → PublishedReport

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | R2 — linkage fixture and installed-CLI acceptance of the full first vertical |
| Status | GREEN — both Steps execute in one run; published artifact verified on disk |
| Predecessors | T0..T4 (`106703a6`, `3848955d`, `e660e404`, `ca14d3b8`, `4be4acaf`), R1 |
| Production core changes | ZERO |
| SDK changes | ZERO |

## 1. What landed

```text
examples/testing/html-report/index.html                        HTML report entry document
examples/testing/html-report/assets/summary.css                HTML report asset
examples/testing/junit-then-publish.pipeline.kts               the linkage fixture
```

The fixture runs the complete first vertical of LFC-2E3 inside **one** pipeline:

```text
stage("testResults")    -> core.junit       : JUnit XML        -> typed TestReport
stage("publishReport")  -> core.publishHTML : HTML report tree -> PublishedReport
```

## 2. Installed-CLI acceptance

```bash
BIN=v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application
$BIN run --plugin-jar examples/testing-plugin/build/libs/testing-plugin-0.1.0-SNAPSHOT.jar \
         examples/testing/junit-then-publish.pipeline.kts
```

| Measurement | Value |
| --- | --- |
| Exit code | 0 |
| Stages started | 2 (`testResults`, `publishReport`) |
| Steps started / finished | 2 / 2 |
| Step failures | 0 |
| Outcome | **SUCCESS** |
| Log sha256 | `ac5d4c801dd3ee142610ef0686efda1bf24c8924d2828a5d73511bcb3c482c2b` |

Observed event sequence:

```text
CompilationStarted → CompilationFinished → RunStarted
  → StageStarted(testResults)    → StepStarted → StepFinished → StageFinished
  → StageStarted(publishReport)  → StepStarted → StepFinished → StageFinished
  → RunFinished(SUCCESS)
```

### 2.1 The artifact is real, not merely reported

```text
$ find build/testing-published -type f | sort
build/testing-published/unit-tests/assets/summary.css
build/testing-published/unit-tests/index.html
```

The entry document and the nested asset are both present, so `PublishedReport.Published` describes a
publication that actually happened. The typed result and the filesystem agree.

### 2.2 The invariant holds across the whole chain

The JUnit XML consumed by this fixture contains **1 failed and 1 errored testcase**, and the run
still finishes SUCCESS with 0 step failures. Parsing test outcomes faithfully and publishing a
report about them is not the same thing as failing the build — the central LFC-2E3 invariant, now
demonstrated on the linked chain rather than on a single Step.

## 3. What "linkage" means here, precisely

The two Steps are linked by:

- **run identity** — one `RunId` spans both stages, so both typed outputs are correlated in the
  durable journal and the event stream;
- **stage ordering** — `testResults` completes before `publishReport` begins, so a consumer reading
  the run sees parse-then-publish;
- **one plugin coordinate** — both resolve through the same `pipeline.testing` contributor, so a
  single JAR and a single discovery pass cover the vertical;
- **two separately declared capabilities** — `testing.filesystem.operations` (read-only) and
  `testing.publish.operations` (writing). Granting one does not grant the other, and both were
  admitted through the CLI path closed in T4.

There is deliberately **no controller semantics** here: no orchestrating service, no registry of
report types, no cross-Step channel. The linkage is the run itself.

## 4. Known limitation (stated, not worked around)

**There is no value piping between Steps.** A Step cannot consume another Step's typed output as its
own input, so `publishHTML` is pointed at the HTML report directory rather than being handed the
parsed `TestReport` value.

Options considered and rejected for this slice:

- a `ReportStore`-style shared bus — explicitly forbidden by the cycle directive until a third
  reporting family demonstrates the need;
- smuggling the report value through a capability — would make the capability a channel rather than
  a port, and would put a Step's output inside another Step's port surface;
- reading the previous Step's journal row from the handler — would hand a handler runtime/persistence
  access it must never have (LB-02 / G3-A4.2).

Value piping is a genuinely generic platform capability (it would benefit every Step, not just
testing), so it belongs to its own slice with its own evidence rather than being improvised here.
The typed `TestReport` is nevertheless fully available in the durable journal, so an observer can
already correlate the two outputs by `RunId`.

## 5. Counter rollup (R2)

| Indicator | Before R2 | After R2 |
| --- | --- | --- |
| Testing `.pipeline.kts` fixtures | 2 | 3 |
| Steps executing in one CLI run (testing) | 1 | 2 |
| Steps unrunnable from the CLI | 0 | 0 |
| Production core changes | 0 | 0 |
| SDK changes | 0 | 0 |
| Registered StepKeys | 19 | 19 (R2 adds no Step) |

## 6. Files added

```text
examples/testing/html-report/index.html
examples/testing/html-report/assets/summary.css
examples/testing/junit-then-publish.pipeline.kts
```

## 7. Next slice

E3-A closes the TEST RESULTS + HTML REPORTING vertical: consolidated architectural claims, the
counter rollup across T0..R2, the receipt map, and the explicit list of what remains open (the
event-transport seam from T3 §5.3, Step-output value piping from §4 here, and the pre-existing core
capability gaps).
