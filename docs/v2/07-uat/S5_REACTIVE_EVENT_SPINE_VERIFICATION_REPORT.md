# S5 Reactive Event Spine — VERIFICATION REPORT

**Cycle:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine`
**Phase:** `Verify` · **Frontier:** `phase.verify.complete`
**Base:** `main` = `569a088c` · **Candidate:** `527ca770` (implementation) + `c1bb784d` (receipt)
**Gates required:** `tests-pass` · `policy-compliant` · `debt-severity-assigned` · `debt-priority-assigned`
**Requirement:** `verification-report` — this document.

---

## 1. Exit criteria, each with its evidence

The cycle's exit is not "all units implemented". It is six properties. Every one is
answered by evidence bound to a SHA, not by a claim.

| # | Criterion | Verdict | Evidence |
|---|---|---|---|
| 1 | Local consumer works | **MET** | `EventPageDrainTest` 6/6 · `MainEventsCliRefusalVisibilityTest` 5/5 over real `SqliteEventStore` |
| 2 | External consumer works | **MET** | `S54ExternalVerticalRestartUatTest` parts 1–2, on the **installed binary** |
| 3 | Cursor and restart work | **MET** | process A stops, process B resumes from its token; refusals survive the process boundary |
| 4 | Built-in and plugin both work | **MET** | `S54DurableRefusalContractTest`: `RunStarted` and `PluginEventEmitted` both decode |
| 5 | Malformed / unknown fail closed | **MET** | `UnknownKind` and `MalformedPayload` stay distinguishable; neither becomes an envelope |
| 6 | No log scraping | **MET** | every consumer reads the durable store; `MainEventsCli` never parses stdout or the transcript |

**Cycle verdict: `S5 = CLOSED`.**

## 2. Units, and what each actually delivered

| Unit | Status | Receipt |
|---|---|---|
| S5.1 EventRegistry / envelope | delivered earlier in the cycle | — |
| S5.2 Plugin events | delivered earlier in the cycle | — |
| S5.3 cursor / read / sequence authority | delivered earlier in the cycle | — |
| **S5.4 Observation Vertical** | **CERTIFIED**, R1–R8 | `S5_4_OBSERVATION_VERTICAL_RECEIPT.md` @ `c1bb784d` |
| **S5.5 Durable Reactor** | **`DEFERRED`** | **ADR-0104** @ `f5cfb367` |
| **S5.6 agent/MCP projection** | **not started, precondition absent** | this report, §3 |

## 3. Why S5.6 does not start, stated precisely

It is not deferred "because it is hard" and not deferred "because of the roadmap".
Two measured facts:

1. **There is no MCP surface in this repository.** A search for `mcp` across all
   Kotlin sources returns zero files. There is no protocol, no server, no adapter,
   and no consumer.
2. **There is no agent consumer of events.** The `agent*` code is
   `ExecutionTargetRequirement` — local label selection and remote-target refusal —
   which is target *resolution*, not observation.

An "agent/MCP projection" with no agent and no MCP would be an adapter with no
adapter target, and its protocol shape would be a guess at something the next
programme defines with a real consumer in hand. Building it is the same defect
ADR-0104 was written to prevent, in a different costume.

**What S5.6 does have is its base, and the base is delivered.** The certified read
side — durable, cursorable, refusal-honest, consumable across a process restart —
is exactly what such a projection would read from. When a real agent consumer
exists, the projection is additive over S5.4's output.

**The constraint S5.6 will owe its eventual implementer**, from ADR-0104's
measured finding that *every* production consumer of the Event Plane is
observation: it stays an adapter/consumer of that plane. Agent-specific protocol
does not enter the events core. That is recorded here so the constraint exists
before the code does, not after.

## 4. Gate evidence

`tests-pass` is the full gate on the exact candidate:

| Field | Value |
|---|---|
| argv | `cd v2 && ./gradlew --no-daemon check --rerun-tasks` |
| SHA | `527ca77019f33e9c2eafd20c53c721880feb1a15` |
| started | `2026-10-06T17:04:53Z` |
| result | `BUILD SUCCESSFUL in 37m 23s` · 329 tasks executed · `EXIT=0` |
| tests (bounded by that instant, over `v2/**`) | **5077** · 0 failures · 0 errors · 140 skipped · 766 classes |
| `apiCheck` / `detekt` | 6 / 27 modules green |
| tree fingerprint (12 files) | `3d35eaea34437c9fdc281cf47889b2354f961c23fc96dd3fb19abfc43266fcb0` |

`policy-compliant` is this report plus the fitness results that stand behind it:
architecture 516 tests / 0 failures, the contract-maturity fitness 16/16 after
`65b643b6`, and the mutation record D-M1..D-M6, M-A and M-B, each attributed 1:1
and restored with a verified `sha256`.

**One gate did not pass on the first attempt, and that is recorded rather than
smoothed over.** `CompiledScriptedEntryPointHostTest` failed the first full gate
run with `TimeoutException` after 30 s, then passed the second with no code change
between them. Measured cause: the body takes 12.59 s against a class-level
`@Timeout(30)` — a 2.4× margin that a parallel gate can consume. That is an HF3
duration assertion in a test outside this cycle's scope, carried as debt D1 below.
It was not relaxed and not retried away.

## 5. Debt: severity and priority

The two remaining gates require debts to be **assigned**, not merely listed.

| ID | Debt | Severity | Priority | Owner block | Why this severity |
|---|---|---|---|---|---|
| **D1** | `CompiledScriptedEntryPointHostTest` asserts on wall-clock (`@Timeout(30)`, body 12.59 s) | **MEDIUM** | **P1** | S7 | A certified-green claim that flips on machine load is a certification that cannot be trusted; S7's whole purpose is trustworthy verdicts |
| **D2** | `CoreWaitUntilStep.kt:69` reconstructs `StepOutcome` from a `String` token; allowlisted by file and line | **HIGH** | **P1** | **S7, not S8** | It is a published Step surface whose outcome is decided by string comparison. S8 freezes compatibility — resolving it *during* the freeze would entrench the allowlist instead of removing it |
| **D3** | `UatDurableDefaultReuseCliTest` uses `waitFor(45, SECONDS)`, a duration assertion | **MEDIUM** | **P2** | S7 | Same class as D1; same reasoning, smaller blast radius |
| **D4** | `AGENTS.md` prose still names 3 `UNSUPPORTED_FAIL_CLOSED` and an `agent` that became `STABLE` in S3.1 | **LOW** | **P3** | block with authority over `AGENTS.md` | The two machine authorities agree and are fitness-verified; only a narrator is stale. One line of prose, by whoever owns the file |
| **D5** | `PRODUCT-GATE = BLOCKED_EXTERNAL` because no remote CI exists, while policy has decided remote CI must not exist | **MEDIUM** | **P2** | before S7 | Redefining a gate is a governance decision, not a receipt edit. S7 is the certification harness, so it must be decided before S7 rather than inside it |
| **D6** | Cycle `rp7-sem-s4-scripted-runtime-v2` is `OPEN` although its work is integrated | **LOW** | **P3** | operator decision | `cycle.supersede` needs human approval (`surface.cycle_state#cycle_supersede`). Untouched here by instruction |

**Priority rationale.** `P1` is assigned to anything that can make a certification
lie — D2 because it is a real semantic decision on a published surface, D1 because
a load-dependent green is not a green. `P2` is governance debt that does not
falsify a claim today. `P3` is bounded and owned elsewhere.

## 6. What closing S5 does not mean

- It does not certify a **plugin-contributed reactor**; ADR-0104 records that
  question as belonging to S6, where plugin identity and admission are defined.
- It does not certify the **agent/MCP projection**; §3 records why it has not
  started and what it owes when it does.
- It does not make `PRODUCT-GATE` green. This is a `STEP-CERT`. Remote CI has not
  existed since `754ddda0`, and no STEP-CERT can make that gate pass.
- It does not promote any published module out of `EXPERIMENTAL`. That is S8, and
  `pipeline-domain`'s own rationale names the work that must land first — which
  S5.4 is part of.
