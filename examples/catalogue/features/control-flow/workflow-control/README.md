# Workflow Control

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `control-flow`

**Expected outcome:** `unstable`

## What it exercises

Which arm of a branch runs, and how many times the body of a loop runs

*The example says this about itself:*

> THE GUARANTEE UNDER TRIAL

## The stages and the steps

### 1. `workspace-control`

- `dir(workflow-test)` — a directory block
  - `sh` — `echo 'inside dir'`
  - `sh` — `pwd > where.txt && test -s where.txt && grep -q 'workflow-test' where.txt`
  - `sh` — `touch marker`
- `deleteDir`
- `sh` — `test ! -e workflow-test/marker && test ! -e workflow-test/where.txt`

### 2. `retry-block`

- `timeout(30, "SECONDS")` — a timeout block
  - `retry(3)` — a retry block
    - `sh` — `echo 'step-A'`
    - `sh` — `echo 'step-B'`

### 3. `error-handling`

- `catchError` — a block of recovery steps
  - `sh` — `exit 1`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `unstable` |
| stdout contains | `step-A` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/control-flow/workflow-control/workflow-control.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/workflow-control/workflow-control.pipeline.kts
```

## Its lineage

- **Historical name:** `11-workflow-control.pipeline.kts`
- **Origin path:** `examples/control-flow/11-workflow-control.pipeline.kts`
- **Body digest:** `sha256:c737e6ae45c487f80b31097b7da09010c5711368f3112fc748f62708ac02f8fc`
