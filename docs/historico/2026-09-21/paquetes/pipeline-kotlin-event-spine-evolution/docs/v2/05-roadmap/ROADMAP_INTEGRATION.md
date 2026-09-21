# Roadmap integration plan — EVT/POL

## Why this is an intercalated program, not M11

The existing roadmap already has:

- E1 / M1: basic event spine and local stores;
- M4 / E5: protocol, ACK/replay and remote worker/controller separation;
- M6: Jenkins `event→FlowNode` reducer and live visibility;
- M8: graph/provenance from execution history;
- M9: policy engine/hardening.

The CTX-P4-EX gate at `d0ccf4b5` already proves 10 real examples. EVT is the missing evolutionary bridge that generalizes that evidence and makes the same event model usable for local history/live remote consumers. It should be inserted after the current local/LFC-2 execution
foundation and before resuming the remote controller path. It extends E1 rather than replacing it and
provides value locally before M4/M6 exist.

## Traceability mapping

| New work | Existing roadmap anchor | Relationship |
|---|---|---|
| P4-EX baseline | CTX-P closure / examples | 10/10 real CLI gate is input evidence, not new EVT work |
| EVT-1 envelope/identity | E1-01/E1-04 | evolve typed IDs + EventEnvelope |
| EVT-2 ports/local history | E1-05..E1-07 | separate contract from SQLite adapter |
| EVT-4 live relay/cursor | E5-03..E5-05 | precondition/evidence for commands/events, ACK/replay |
| EVT-5 transport spike | E5-08/E5-09 | informs transport/gateway; does not preselect |
| EVT-6 controller mapping | M4 | handoff only |
| Jenkins live event mapping | M6 | prepares `event→FlowNode`; implementation remains M6 |
| policy shadow/simulation | M9 | pulls low-risk policy learning earlier |
| policy enforcement | M9 | stays hardening/enforcement milestone |
| ResourceRef/provenance | M8 | common identity foundation, graph still M8 |

## Recommended roadmap insertion

Add `## EVT — Event Spine evolution + executable verification` after the active EM/LFC-2 consolidation
section and before M5, with an explicit note:

> EVT is intercalated before resuming E5-02..E5-10. It does not reopen M4 or implement controller/Jenkins;
> it freezes the event/identity semantics and proves local history, post-run verification and detached live relay.

Keep M4..M10 numbering unchanged.

## Definition of Done amendment

Add to the global milestone DoD:

- every newly-supported observable DSL/runtime feature has at least one real installDist `.pipeline.kts` example or an explicit reason why a real example is impossible;
- example success means expected execution outcome **and** event contract, not compilation only;
- event/observer additions include failure-isolation evidence;
- changes to event identity/order include compatibility/migration decision;
- global non-green baseline must use existing Rule-16 evidence discipline; no false PASS.
