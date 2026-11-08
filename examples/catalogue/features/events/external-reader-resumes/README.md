# External Reader Resumes

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `events`

**Expected outcome:** `success`

## What it exercises

The event lane: what a run tells a consumer, and in what order

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `parallel-branches`

- `parallel` — a parallel block
  - `branch(a)` — a branch of a parallel block
    - `echo` — `a`
  - `branch(b)` — a branch of a parallel block
    - `echo` — `b`
  - `branch(c)` — a branch of a parallel block
    - `echo` — `c`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |

## What it needs

- durable-once
- events-resume

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/events/external-reader-resumes/external-reader-resumes.pipeline.kts
pipelinek run      examples/catalogue/features/events/external-reader-resumes/external-reader-resumes.pipeline.kts
```

## Its lineage

- **Historical name:** `01-external-reader-resumes.pipeline.kts`
- **Origin path:** `examples/events/01-external-reader-resumes.pipeline.kts`
- **Body digest:** `sha256:44ad295f484ec7053c5f02a4af6187d4cdc8a91cc71d3c88f1e48356d1a5e1c5`
- **Origin digest:** `sha256:cf520454bc67842f43e2d97920b259c6d130db2b32e1094b98993d516d39106a`
