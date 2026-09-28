# PipelineK Semantic Evolution Pack

Status: PROPOSED EVOLUTION
Date: 2026-09-28
Target: current V2 architecture, not a parallel rewrite

## Goal

Resolve the recurring class of DSL defects where user intent is accepted syntactically but is dropped, flattened, silently ignored, or misrepresented at runtime. Extend the same correctness model to directives, Steps, block Steps, scripted runtime values, events/reactivity, and external libraries.

This evolution preserves and builds on the existing strengths:

- canonical durable coordinator and journal;
- `StepRegistry` / `StepDefinition<I,O>` / codecs / capabilities;
- `StepBody` and `BodyExecutionPolicy`;
- typed `RunOutcome` / `StageOutcome` / `StepOutcome`;
- event spine and typed events;
- open external Step plugin seam;
- functional/ADT-first rules already present in `AGENTS.md`.

It does **not** create a second execution engine.

## Central law

Every public DSL construct must belong to exactly one semantic category:

1. `DECLARATIVE_DIRECTIVE`
2. `ATOMIC_STEP`
3. `BLOCK_STEP`
4. `PURE_BUILDER`
5. `SCRIPTED_RUNTIME_CALL`

No construct may partially belong to two categories.

For every construct, user intent must be carried by one explicit semantic carrier:

```text
DSL declaration
    -> typed carrier
    -> validation/admission
    -> interpreter
    -> capability/effect
    -> typed outcome
    -> typed event(s)
    -> durable evidence
```

If no carrier exists, the construct must fail closed before effects.

## Documents

- `01-semantic-constitution.md` — language and runtime laws.
- `02-directive-model.md` — extensible Declarative directives (`when`, `post`, `agent`, `environment`, `options`).
- `03-scripted-runtime.md` — typed suspend runtime and removal of semantic source-rewrite hacks.
- `04-events-reactivity.md` — event spine, reactive consumers and plugin events.
- `05-step-plugin-sdk-v2.md` — Step/directive/event extension contracts.
- `06-certification-harness-v2.md` — certification protocol and external harness.
- `07-AGENTS-patch.md` — normative rules to merge into `AGENTS.md`.
- `08-roadmap.md` — staged implementation plan.
- `09-UAT.md` — acceptance matrix and adversarial tests.
- `10-ADRs.md` — ADR set to adopt/supersede existing decisions.
- `11-release-cut.md` — how this interacts with the next release candidate/stable.

## Current baseline warning

The public GitHub `main` observed while preparing this pack was `f3203e65db10ea28636489c4c6fdadac1fa9b7f0`. The agent reported later local/branch fixes such as `d0a1da4a`. Before implementation, reconcile SDDK + Git + remote branches/tags and bind the evolution to the actual starting SHA. Do not move published tags.
