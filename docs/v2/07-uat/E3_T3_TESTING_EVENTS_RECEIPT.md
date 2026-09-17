# LFC-2E3-T3 — TESTING EVENTS + SDK-GAP CLASSIFICATION

| Field | Value |
| --- | --- |
| Cycle | LFC-2E3-TESTING-REPORTS |
| Slice | T3 — typed testing-event model + pure derivation + observability-seam classification |
| Status | Model + derivation GREEN (plugin 34/34); transport classified as an SDK gap |
| Predecessors | T0 `106703a6`, T1 `3848955d`, T2 `e660e404` |
| Production core changes | ZERO |
| SDK changes | ZERO (the required seam is CLASSIFIED and PROPOSED, not improvised) |
| External dependencies added | ZERO |

## 1. What landed

`pipeline.testing.events` — the testing-event model and its pure derivation.

```text
TestingEvent (sealed, 3 cases — exactly the directive's three)
├─ TestReportPublished(summary, sourcePaths)      — once per successful parse
├─ TestSuiteCompleted(summary, suite)             — once per suite
└─ TestFailuresDetected(summary, failingSuites)   — at most once per report

TestingEventDerivation.derive(report, sourcePaths): List<TestingEvent>   — pure, total
TestingEventDerivation.summaryOf(report | suite): summary                — single projection
```

The directive's discipline — **summary + references, not per-testcase** — is
enforced mechanically rather than by convention:

```text
|derive(report)| == 1 + report.suites.size + (if (report.hasTestFailures) 1 else 0)
```

The event count is a function of the **suite** count, never the **case** count.

## 2. The non-explosion guarantee (the load-bearing assertion)

Two tests pin it, one arithmetically and one at a boundary:

| Test | Fixture | Assertion |
| --- | --- | --- |
| `event count is a function of SUITES not CASES` | 4 cases / 2 suites | exactly 3 events (no failures) |
| `1000 cases across 2 suites produce at most 4 events` | 1000 cases / 2 suites | exactly 4 events; `events < total/100` |

Two further tests pin that case identity cannot leak into the event stream:

- `failing suites are reported as NAMES — references, not payloads` (no `::` in the reference list);
- `serialised event payload does not embed case-level identity` — the JSON of the whole
  derived stream contains the suite name `"A"` but neither `passCaseName` nor `failCaseName`.

That last one is the strongest form: even after serialisation, no case-level payload
crosses the event boundary.

## 3. Boundary discipline: events speak about TESTS, output speaks about EXECUTION

`TestReport.Unparseable` derives an **empty** event stream.

```text
Unparseable  -> []                      (no test-domain facts exist)
```

Publishing "report published with 0 tests" or "failures detected" for an
unparseable input would be a lie. The parse failure is an *execution* observation
and is carried by `JunitStepOutput.parseFailures` — the correct channel for a
step-infrastructure fact. The separation established in T0/T2 is preserved.

## 4. No execution-authority fields (anti-forgery)

The event ADT deliberately has **no** `runId`, `stepIndex`, or `eventId` field, pinned
by `no event carries execution-authority identity`. Rationale: those are authority
facts stamped by the runtime when it transports an event. A Step that could assert
them could inject events into another run. When the transport seam lands, the
authority stamps them; the Step never supplies them.

## 5. SDK GAP (classified, not worked around)

### 5.1 The finding

An external plugin **cannot** emit into the durable run event stream today.

| Fact | Evidence |
| --- | --- |
| Public SDK surface = 2 modules | `v2/build.gradle.kts`: `publishSdkForExternalPlugin` depends on exactly `:pipeline-domain:publishSdkPublicationToSdkRepository` and `:pipeline-scripting-api:publishSdkPublicationToSdkRepository` |
| `EventSink` is NOT in the SDK | declared in `v2/pipeline-events/.../EventStore.kt:21`; `pipeline-events` is not published |
| `EVENT_SINK_CAPABILITY` is NOT in the SDK | declared in `v2/pipeline-application/.../Capabilities.kt:15`; `pipeline-application` is not published |
| `pipeline-domain` does not re-export it | its only `pipeline.v2.events` mentions are KDoc links, not a compile dependency |
| No sidecar-event channel exists | `CommonExecutionResult(outcome, encodedOutput)` — two fields, neither an event list |
| No external plugin emits today | `grep eventSink\|EventSink examples/utilities-plugin/src/main/kotlin/` returns nothing; the 17 CERTIFIED E2 Steps emit no custom events |
| `core.emit.event` is not a plugin seam | it is an internal core Step with an ADR-0054 whitelist of 4 kinds |

### 5.2 Why this is INHERITED, not an E3 regression

LFC-2E2 closed with **17 CERTIFIED Steps that emit no custom events**. The
observability they satisfy is the generic registry envelope
(`StepStarted` / `StepFinished` / `StepFailed`) plus the durably journaled typed
output. `core.junit` inherits exactly the same level, so E3-T3 does not regress
anything — it makes an inherited limitation explicit and proposes its fix.

Per AGENTS.md: *"If a plugin needs an internal import for a legitimate feature:
classify it as an SDK gap; do not work around it."* The two available workarounds
are both rejected:

- importing `pipeline-application` / `pipeline-events` → breaks the plugin public-API boundary;
- a reflective/local-interface cast onto the runtime's `EventSink` → exactly the
  type-erasing hack the strict-typing rules forbid, and it would let a plugin forge
  arbitrary core events.

### 5.3 Proposed minimal seam (for a future approved milestone — NOT implemented here)

```text
1. Publish a narrow plugin-facing observability facade as part of the SDK
   (NOT the whole pipeline-events model).

2. SDK port (fun interface, typed):
     interface PluginEventPublisher {
         fun publish(event: PluginDomainEvent)
     }

3. SDK value type (closed, namespaced):
     PluginDomainEvent(
         pluginId: String,        // stamped by the authority, NOT supplied by the plugin
         runId: RunId,            // stamped by the authority
         stepIndex: Int,          // stamped by the authority
         type: String,            // plugin-owned, within the plugin's namespace
         summaryJson: String,     // bounded, typed payload the plugin owns
     )

4. Capability token at a neutral owner:
     PLUGIN_EVENTS_CAPABILITY = StepCapability("plugin.events")

5. Runtime adapter implements PluginEventPublisher over the durable EventSink,
   stamping identity from the single authority. A plugin can publish ONLY inside
   its own namespace; it cannot forge runId/stepIndex and cannot emit core kinds.
```

Design constraints this proposal satisfies:

- hexagonal direction preserved (SDK defines the port, runtime implements it, the
  plugin depends only on the SDK);
- identity cannot be forged (authority-stamped);
- namespacing prevents cross-plugin collisions;
- the payload is bounded and typed, so the non-explosion guarantee is enforceable
  at the seam rather than merely by convention;
- `EVENT_SINK_CAPABILITY` remains internal; no arbitrary core-event emission.

**This requires an SDK publication change plus a runtime adapter**, i.e. it is
outside the `pipeline.testing` plugin boundary. It is therefore recorded here and
NOT implemented, consistent with the cycle's zero-core-change target and with the
AGENTS.md rule that SDK/milestone-level changes need explicit approval.

## 6. What is delivered instead (no gap left unstated, no capability faked)

Because the Step's typed output **is** durably journaled (T2), the faithful event
stream is fully recoverable by any external observer:

```text
durable typed output (JunitStepOutput.report)
    + pure TestingEventDerivation.derive(...)
    -> the exact TestReportPublished / TestSuiteCompleted / TestFailuresDetected stream
```

The derivation is pure, total, deterministic, and snapshot-tested, so the events
exist as a first-class typed value today and acquire a transport when the seam
lands. Nothing about the model needs to change at that point.

## 7. Test evidence

`examples/testing-plugin` — 34/34 GREEN (0 failures, 0 errors):

| Suite | Tests | Result |
| --- | --- | --- |
| `TestingEventsContractTest` | 16 | 0 failures (NEW) |
| `JunitXmlAdapterContractTest` | 10 | 0 failures |
| `TestReportDomainContractTest` | 8 | 0 failures |

The new suite covers: sealed-3-case exhaustiveness, absence of authority fields,
the count invariant with and without failures, the 1000-case boundary, references
not payloads, no-failures ⇒ no failure event, errored-counts-as-failure,
`Unparseable` ⇒ empty, single-definition summary projection, suite-summary
metadata exclusion, determinism, `sourcePaths` echo, empty-report behaviour, JSON
round-trip of all three kinds, and case-identity non-leakage after serialisation.

## 8. Counter rollup (E3-T3)

| Indicator | Before T3 | After T3 |
| --- | --- | --- |
| Testing event kinds | 0 | 3 |
| Event kinds carrying per-case payload | n/a | 0 |
| Event kinds carrying forgeable authority identity | n/a | 0 |
| SDK gaps classified with a seam proposal | 0 | 1 (plugin event transport) |
| Production core changes | 0 | 0 |
| SDK changes | 0 | 0 |
| Registered StepKeys | 18 | 18 (T3 adds no Step) |
| Capability tokens (testing) | 1 | 1 (T3 adds no token) |

## 9. Files added / changed

```text
examples/testing-plugin/src/main/kotlin/pipeline/testing/events/TestingEvents.kt   (new)
examples/testing-plugin/src/test/kotlin/pipeline/testing/events/TestingEventsContractTest.kt (new, 16 rows)
```

## 10. Decision point for the human

Two coherent readings of AGENTS.md's "every step MUST emit its own typed domain
events":

- **(A) Inherit, as E2 did.** The generic per-Step envelope + durable typed output
  is the observability contract for external plugin Steps. E2 closed on this basis
  with 17 CERTIFIED Steps. No further action; T3 is complete as delivered.
- **(B) Close the seam.** Approve the §5.3 proposal as a new milestone (SDK
  publication + runtime adapter), after which every plugin Step can emit typed,
  namespaced, authority-stamped domain events. This is an SDK change and therefore
  needs explicit approval per the AGENTS.md exceptions clause.

The delivered work is correct and complete under reading (A), and is exactly the
model a future (B) transport will consume unchanged.
