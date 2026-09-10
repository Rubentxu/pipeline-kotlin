# Merge guide

## Grounding anchors

This revised pack is grounded against `main` at **d0ccf4b5**, where CTX-P4-EX is already closed. At that point:

- M1 already introduced the basic event spine and in-memory/SQLite reference stores;
- E1 backlog already contains typed IDs, EventEnvelope and EventStore work;
- M4/E5 owns protocol, ACK/replay, reconnect and gateway;
- M6 owns Jenkins Workflow adapter and `event→FlowNode`;
- M8 owns graph/provenance;
- M9 owns policy engine/hardening;
- CTX-P is closed and protects explicit immutable execution context ownership;
- P4-EX is closed: `examples/run.sh` is 10/10 GREEN through the real installDist CLI, with event contracts already asserted for examples 07–10.

## Safe integration order

1. Merge design docs and PROPOSED ADRs only.
2. Add the EVT/POL program section to `ROADMAP.md` using `ROADMAP_INSERT.md`.
3. Append backlog entries from `IMPLEMENTATION_BACKLOG_APPEND.md`.
4. Replace/merge the small `MILESTONES.md` using `MILESTONES.replacement.md`.
5. Add UAT design and candidate normalized sidecar contracts; do **not** recreate examples 07–10 or duplicate the current examples README.
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
