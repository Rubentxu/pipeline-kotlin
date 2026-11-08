# Broken Test

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `workspace`, `process`

**Expected outcome:** `failure`

## What it exercises

The working directory a run works in, and what survives from one stage to the next

*The example says this about itself:*

> Canary test roto a proposito. El unittest debe fallar (>0 failures), el
> pipeline debe terminar en failure y dejar el XML de resultados fresco.

## The stages and the steps

### 1. `python-broken`

- `sh` — `mkdir -p out && python3 -m unittest discover -s tests -v > out/tests.log 2>&1; exit \\$?`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `1` |
| outcome | `failure` |
| files | `out/tests.log` |
| test log | `out/tests.log` |

## What it needs

- el fixture negativo esta vendoreado (fixtures/small-python-broken) y el escenario scenarios/smoke/broken-test.pipeline.kts existe
- no forma parte de la bateria: se ejecuta a mano para HAR-016

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/broken-test/broken-test.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/broken-test/broken-test.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

**Covers:** `HAR-016`

## Its lineage

- **Historical name:** `broken-test.pipeline.kts`
- **Origin path:** `scenarios/smoke/broken-test.pipeline.kts`
- **Body digest:** `sha256:0bf5dba2940cc182636879f6de9e4676850ea36c68a89c9e2cd6a6b7c7a7b1c2`
