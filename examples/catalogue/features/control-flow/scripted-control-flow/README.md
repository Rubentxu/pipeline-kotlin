# Scripted Control Flow

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `control-flow`

**Expected outcome:** `success`

## What it exercises

Which arm of a branch runs, and how many times the body of a loop runs

*The example says this about itself:*

> Two scripted control flows in one scenario: a branch that takes one arm, and
> a loop whose body must run a known number of times.
> The branch is observable in what the pipeline PRODUCED: the other arm would
> print a different sentence, so a branch that took the wrong way is visible
> rather than merely a script that ran.
> The loop count is observed by counting the lines the body wrote. A bare
> echo per iteration would stay green for a body that ran once, or not at
> all, and a loop that ran three times when it should have run five is
> exactly the regression the count is there to catch.

## The stages and the steps

### 1. `scripted-control-flow`

- `script` — a block of Kotlin
  - `echo` — `x is positive`
  - `echo` — `x is not positive`
- `sh` — `"""for i in 1 2 3; do echo "iteration ${'$'}i"; done > iterations.txt""", isScriptBlock...`
- `sh` — `"""test "${'$'}(wc -l < iterations.txt)" -eq 3"""`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `x is positive` |
| transcript contains | `x is positive` |

## What it needs

- storage-agnostic

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/control-flow/scripted-control-flow/scripted-control-flow.pipeline.kts
pipelinek run      examples/catalogue/features/control-flow/scripted-control-flow/scripted-control-flow.pipeline.kts
```

## Its lineage

- **Historical name:** `05-scripted-control-flow.pipeline.kts`
- **Origin path:** `examples/control-flow/05-scripted-control-flow.pipeline.kts`
- **Body digest:** `sha256:e8e81b23af0eb4b38f0458c4dfb04715116e7d39498e7e3d7a4b82a2097df15c`
- **Origin digest:** `sha256:0a79bc32d4fd2d4abc5460c2df27dff45e050ff8e1c39852296db15d32654e84`
