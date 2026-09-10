# examples/README integration note — EVT

At baseline `d0ccf4b5`, `examples/README.md` is **already updated** with all 10 real examples and honest durable semantics. Do not append a second table and do not reintroduce stale "planned" wording.

When EVT-3 lands, make only the incremental documentation change needed to explain the reusable Event Harness, for example:

```markdown
## Event-contract acceptance (EVT)

`examples/run.sh` remains the top-level real-distribution acceptance gate. For semantic scenarios,
its event assertions are implemented by the reusable POST_RUN Event Harness and companion
`*.events.yaml` contracts. The harness verifies persisted structured history; expected non-zero
outcomes remain successful acceptance cases when declared by the example.
```

The existing 07–10 examples stay authoritative fixtures:

- 07 nested catchError;
- 08 durable parallel reuse;
- 09 retry fail→success;
- 10 timeout expected failure.

Migration requirement: old `run.sh` assertion and new harness constraint must have equal verdicts before the old assertion is removed.
