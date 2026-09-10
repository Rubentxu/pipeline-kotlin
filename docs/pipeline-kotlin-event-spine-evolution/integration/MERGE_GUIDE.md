# Merge guide

## Grounding anchors

This pack was prepared against the current V2 roadmap where:

- M1 already introduced the basic event spine and in-memory/SQLite reference stores;
- E1 backlog already contains typed IDs, EventEnvelope and EventStore work;
- M4/E5 owns protocol, ACK/replay, reconnect and gateway;
- M6 owns Jenkins Workflow adapter and `event→FlowNode`;
- M8 owns graph/provenance;
- M9 owns policy engine/hardening;
- CTX-P is already closed and protects explicit immutable execution context ownership.

## Safe integration order

1. Merge design docs and PROPOSED ADRs only.
2. Add the EVT/POL program section to `ROADMAP.md` using `ROADMAP_INSERT.md`.
3. Append backlog entries from `IMPLEMENTATION_BACKLOG_APPEND.md`.
4. Replace/merge the small `MILESTONES.md` using `MILESTONES.replacement.md`.
5. Add UAT design and example contract proposals.
6. Keep `AGENTS_CANDIDATE.md` out of normative AGENTS until implementation evidence closes each law.
7. Start only EVT-0 in the first code cycle.

## ADR numbering

`ADR-0077..0080` were unused in the repository search at preparation time. Rebase numbers if another
change occupies them before merge; keep titles/content as the stable identity.

## Important non-changes

This documentation package intentionally does **not**:

- alter production source;
- change EventStore schema;
- change journal/fingerprint/replay semantics;
- select NATS/Kafka;
- implement a controller/Jenkins plugin;
- add Cedar dependencies;
- update AGENTS with unproven laws.
