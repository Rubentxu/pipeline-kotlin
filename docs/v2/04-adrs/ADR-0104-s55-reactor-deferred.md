---
type: adr
id: ADR-0104
title: "S5.5 Reactor: deferred, because no productive consumer needs one and the need is already met by the journal"
status: accepted
date: 2026-10-06
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0103
  - ADR-S4-R1
  - docs/v2/05-roadmap/ROADMAP.md
  - docs/v2/07-uat/S5_4_OBSERVATION_VERTICAL_RECEIPT.md
  - docs/v2/surface/DSL_SURFACE_MANIFEST.md
---

# ADR-0104 — S5.5 Reactor: deferred

## Context

The roadmap schedules S5.5 as a Durable Reactor, shaped like this:

```
Event
  ↓
pure decide()
  ↓
ReactorDecision
  ├── Ignore
  └── EmitCommand
             ↓
       canonical admission
             ↓
       canonical runtime
```

The decision it is meant to serve was fixed in advance, and it was the right
one: **decide by need, not by roadmap**. Find a real productive consumer that
needs `event + reactor state → pure decision → normal admission`. If one
exists, build the minimum durable reactor. If none exists, record S5.5 as
`DEFERRED` and do not build an ornamental API.

This ADR is the second branch, and the evidence for it is a search, not a
preference.

### What the search found

Every production consumer of the Event Plane is observation. The complete set:

| Side | Consumer | What it does |
|---|---|---|
| write | `EnvelopeProjectingEventSink`, `RedactingEventSink` | publish |
| read | `EventHistoryReader`, `EventPageDrain`, `EnvelopeProjector`, `EventViewProjection` | project |
| inspect | `EventInspection`, `EventHarness`, `EventPayloadAccessor` | verify |
| external | `MainEventsCli`, `FabricProjection` | observe |

A search for anything shaped like a listener across all production sources —
`Reactor`, `EventListener`, `EventHandler`, `EventSubscription`, `onEvent`,
`reactTo`, `subscribe` — returns exactly one hit, and it is an unrelated HTTP
body subscriber:

```bash
grep -rnE '\b(Reactor|EventListener|EventHandler|EventSubscription|onEvent|reactTo|subscribe)\b' \
  --include='*.kt' v2/ | grep /src/main/
# v2/pipeline-step-sdk/http/.../BoundedBodySubscriber.kt:62: * `request(1)` on subscribe, ...
```

`-E` is load-bearing in that command, and getting it wrong is a trap worth
naming: without it, GNU `grep` treats `|` as a literal pipe rather than as
alternation, the whole pattern becomes a string that appears nowhere, and the
search reports **zero hits** — indistinguishable from a real absence. This ADR's
first verification run did exactly that and was about to be read as evidence
that nothing in the product is shaped like a listener. It is the same failure as
an unverified mutation script: a verifier that finds nothing because its pattern
did not mean what it was supposed to mean is worse than no verifier, because
absence is what it appears to report.

**Nothing in this product takes an event and produces a control decision.**

### The stronger reason: the need is already met, by a different authority

This is the part that decides the ADR, and it is not visible from the roadmap.

The need a reactor would serve is precisely `state → pure decision → canonical
admission`. **That already exists**, four times over, and none of it is fed by
the event stream:

| Reconciler | Input | Output |
|---|---|---|
| `WaitUntilReconciliationDecision` | persisted control rows | `ScheduleAttempt` / `ResumeAttempt` / … |
| `RetryReconciler` | control rows + fingerprint | `RetryReconciliationDecision` |
| `ParallelReconciler` | branch rows + aggregate fingerprint | `ParallelDecision` |
| `StepReconcilerL1` | journal rows for RUNNING steps | `Classification` |

Each is a pure function from durable state to a typed decision, and each decision
is consumed through canonical admission rather than applied as an effect. That is
the reactor's exact shape. It is fed by the `OperationJournal`, which is the
authority for what actually happened to an operation.

A reactor over events would therefore not fill a gap. It would be a **second
route to the same decision**, fed by a plane that ADR-by-ADR is observation-only.

## Decision

**S5.5 is `DEFERRED`.** No reactor API is built.

The three reasons, in the order they actually decided it:

1. **No consumer.** The search above found none. An API with no caller is a
   promise nobody asked for.
2. **No gap.** The need is met by the reconcilers, from the authority that owns
   the facts. Building a reactor would not improve correctness.
3. **It would break a law S5.4 just established.** The Observer is a consumer and
   cannot change an outcome, execute a Step, or write the EventStore. A durable
   reactor that emits a command into canonical admission is an observer that
   changes outcomes. S5.4's law and S5.5's shape are in direct contradiction, and
   S5.4's law is the one with a certified vertical behind it.

Point 3 is why this is not merely "later". The roadmap's own S5.4 section states
the constraint — *"Nunca: Observer → cambia outcome"* — and a reactor exists
precisely to make an observer change an outcome.

## Consequences

**What is deferred.** The Durable Reactor, and with it the durable reactor state
that would have accompanied it.

**What is not deferred, and stays true.** Observation is complete. S5.4 delivered
a durable, cursorable, refusal-honest read side that an external process can
consume across a restart. S5.6's agent/MCP projection builds on that read side
and is also observation, so it creates no reactor consumer either — and should
not be expected to.

**The cost of being wrong.** If a real consumer appears, the work is not wasted
in the sense that matters: the read side it would need is already durable and
certified. What would be new is the decision layer, which is additive at that
point.

## Reopening condition

This ADR is reopened by evidence, not by schedule. Any of the following
**reopens it**, and each is stated so the reopening cannot be argued from
roadmap momentum:

- A named productive consumer in this repository that must react to an event and
  whose reaction **cannot** be expressed as a decision over journal control rows.
- A plugin-contributed contribution that requires reacting to an event. That is
  a real seam, and it belongs to **S6**, not here: it is a plugin capability
  question, and it should be answered where the plugin identity and admission
  authority are being defined, not by a core event API.
- An external consumer that has to drive the runtime from observation alone
  because it cannot reach admission. If that appears, the fix is admission
  access, not a reactor.

Until one of those exists with a named consumer, the honest state of S5.5 is
`DEFERRED`, and this ADR is its receipt.