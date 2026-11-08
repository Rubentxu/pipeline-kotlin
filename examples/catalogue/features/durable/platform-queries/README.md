# Platform Queries

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `durable`

**Expected outcome:** `success`

## What it exercises

The journal: what a run leaves behind when it is killed, and how it is resumed, cancelled or recovered

*The example says this about itself:*

> Two platform queries in one scenario: the boolean the platform reports, and
> the directory it hands out.

## The stages and the steps

### 1. `platform-queries`

- `isUnix` — a step this example declares
- `echo` — `is unix: true`
- `echo` — `is unix: false`
- `pwd` — `tmp = true` — a step this example declares
- `echo` — `"pwd(tmp=true) is the workspace placeholder: " + (dir == "<workspace>")`
- `pwd` — a step this example declares
- `echo` — `"pwd() is the workspace placeholder: " + (legacy == "<workspace>")`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `is unix: true`, `pwd(tmp=true) is the workspace placeholder: true`, `pwd() is the workspace placeholder: true` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/durable/platform-queries/platform-queries.pipeline.kts
pipelinek run      examples/catalogue/features/durable/platform-queries/platform-queries.pipeline.kts
```

## Its lineage

- **Historical name:** `19-platform-queries.pipeline.kts`
- **Origin path:** `examples/durable/19-platform-queries.pipeline.kts`
- **Body digest:** `sha256:2a1755da4b132d9560c7d4a8b9da587bea43dd9f0cbd8589c39e62094fcfa905`
