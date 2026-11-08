# Live Process Output

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `output`

**Expected outcome:** `success`

## What it exercises

The output plane: what a step produced, and how it is read back

*The example says this about itself:*

> The output of a PROCESS has to reach the terminal, not only the output of
> a script that echoes a value back.

## The stages and the steps

### 1. `live`

- `sh` — `"echo RAW_PROCESS_MARKER", isScriptBlock = false, returnStdout = false`
- `sh` — `"echo STDERR_ONLY_MARKER >&2", isScriptBlock = false, returnStdout = false`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `RAW_PROCESS_MARKER` |
| transcript contains | `STDERR_ONLY_MARKER` |

## What it needs

- nothing beyond the product itself

## Running it by hand

```bash
pipelinek validate examples/catalogue/features/output/live-process-output/live-process-output.pipeline.kts
pipelinek run      examples/catalogue/features/output/live-process-output/live-process-output.pipeline.kts
```

## Its lineage

- **Historical name:** `33-live-process-output.pipeline.kts`
- **Origin path:** `examples/output/33-live-process-output.pipeline.kts`
- **Body digest:** `sha256:c317f8301f2461f10dc5c7315be0ce38d6c5afe0a8af81981573c6ceb2ea2f76`
