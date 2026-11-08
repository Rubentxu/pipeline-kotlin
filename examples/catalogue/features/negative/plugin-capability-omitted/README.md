# Plugin Capability Omitted

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `negative`

**Expected outcome:** `failure`

## What it exercises

The refusals: what the engine says no to, and what it says when it says no

*The example says this about itself:*

> The subject is a plugin whose manifest forgets to declare a capability its own code demands.

## The stages and the steps

### 1. `Never runs`

- `sh` — `echo this stage must never run`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `2` |
| outcome | `failure` |
| stderr contains | `Plugin admission refused for`, `capabilities that differ from the contract`, `example.uppercase.announcedCased`, `declared=[example.uppercase.case-table]`, `contract=[example.uppercase.case-table, plugin.event-emission]` |

## What it needs

- distribution

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/negative/plugin-capability-omitted/plugin-capability-omitted.pipeline.kts
pipelinek run      examples/catalogue/features/negative/plugin-capability-omitted/plugin-capability-omitted.pipeline.kts
```

## Its lineage

- **Historical name:** `plugin-capability-omitted.pipeline.kts`
- **Origin path:** `examples/negative/plugin-capability-omitted.pipeline.kts`
- **Body digest:** `sha256:470fd0ae860f3c82a2fdce5c8d77b58cd8e9d1cd47d72feb7e8711ceb9b57eaa`
- **Origin digest:** `sha256:1aa1fc8bad6eb24b12974101c61f82ffbfa58161e371d52820cd912ce8ed5724`
