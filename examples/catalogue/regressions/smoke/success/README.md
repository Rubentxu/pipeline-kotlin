# Success

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `process`

**Expected outcome:** `success`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Fixture: real Python unittest with test report + output marker in workspace.
> Do not use unsupported steps { } wrapper in a PipelineK version that rejects it.

## The stages and the steps

### 1. `python-unit`

- `sh` — `"mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1 && print...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/test-ok.txt` |
| test log | `out/tests.log` |
| minimum tests | `1` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/success/success.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/success/success.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `success.pipeline.kts`
- **Origin path:** `scenarios/smoke/success.pipeline.kts`
- **Body digest:** `sha256:95541fd183cbd4c1feec4baf6328d8fa920ce71427a5868b41d1d50505547724`
