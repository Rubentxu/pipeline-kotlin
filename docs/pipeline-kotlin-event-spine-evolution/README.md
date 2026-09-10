# pipeline-kotlin — Event Spine, Verification & Policy evolution pack

Status: **PROPOSED / merge-ready documentation**  
Grounded against current `main` after CTX-P closure (2026-09-10).

This pack turns the conversation around event verification, live observers, local-first history,
future controller/Jenkins integration and Cedar policies into a sequenced evolution that can be
merged into the existing V2 roadmap without renumbering M4..M10.

## Product thesis

The goal is **not** to build a generic event platform. The goal is to make pipeline-kotlin able to:

1. keep a compact, structured, queryable history of real executions locally;
2. verify real `.pipeline.kts` examples from that history;
3. stream the same events live to detached consumers without putting their failures/latency on the execution path;
4. preserve a migration path to CloudEvents and a remote controller/Jenkins adapter;
5. later evaluate Cedar policies in audit/shadow mode, and only introduce enforcement through an explicit pre-effect admission seam;
6. correlate events, journal entries, future controller state and policy entities through one typed resource reference model.

## 80/20 scope

**Build first:** `PipelineEventEnvelope`, `ResourceRef`, transport-agnostic EventLog/EventTail ports,
local durable adapter behind those ports, post-run Event Harness, real examples with event contracts,
and a detached live relay proof.

**Defer until evidence:** NATS/Kafka selection, generic observer/plugin runtime, controller protocol,
Jenkins implementation, Cedar enforcement, organization policy distribution, CloudEvents SDK dependency.

**Reject for now:** events embedded in console logs, synchronous network publication from the coordinator,
stdout/stderr as DomainEvents, one JVM/process per observer, and a universal temporal-logic DSL.

## Files

- `docs/v2/06-design/EVENT_SPINE_GROUNDING.md` — architecture and value grounding.
- `docs/v2/06-design/RESOURCE_REF_MODEL.md` — resource identity with the `ResourceRef` model.
- `docs/v2/06-design/EVENT_HARNESS_DESIGN.md` — protocol grammar, partial ordering and replay checks.
- `docs/v2/06-design/POLICY_GUARDRAILS_DESIGN.md` — Cedar audit/shadow/enforcement model.
- `docs/v2/04-adrs/ADR-0077..0080` — proposed architecture decisions.
- `docs/v2/05-roadmap/EVENT_SPINE_EVOLUTION.md` — sequenced EVT/POL program.
- `docs/v2/07-uat/UAT_EVT_REAL_EXAMPLES.md` — measurable UAT and real examples.
- `openspec/changes/*` — proposal/design/tasks for implementation cycles.
- `integration/*` — exact roadmap/backlog snippets and merge guidance.

## Naming decision

The earlier acronym-based working name is intentionally retired. The domain concept in this pack is **`ResourceRef`**: a typed,
hierarchical reference to an addressable pipeline entity. Its canonical wire serialization is intentionally
**not frozen yet**. CloudEvents adapters need a URI-reference, but the domain must not pretend that an
unregistered custom URI/URN scheme is a standard.
