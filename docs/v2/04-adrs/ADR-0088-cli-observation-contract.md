---
type: adr
id: ADR-0088
title: "CLI observation contract separates view, format and query"
status: proposed
date: 2026-09-18
deciders: "Rubentxu (product owner)"
supersedes: null
superseded_by: null
related:
  - ADR-0085
  - ADR-0086
  - docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md
---

# ADR-0088 — CLI observation contract

## Decision

The CLI uses three orthogonal concepts:

- **View:** `normal | events | full | console | quiet`.
- **Format:** `text | jsonl | json`.
- **Query/projection:** filters, cursor/tail/context and `--fields`.

Default is `normal + text`; `-v` aliases `full`.

No combination changes execution semantics or persistence. Machine formats keep stdout clean and send CLI diagnostics to stderr.

Agentic usage is composition, not a special mode. `inspect --failed --context --log-tail --fields --format json` is the primary bounded diagnostic surface.

Filter semantics: AND across dimensions, OR within repeated values of the same dimension. LPR does not add an arbitrary expression language.
