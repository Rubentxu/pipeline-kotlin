# Read Only Root

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `security`, `workspace`

**Expected outcome:** `success`

## What it exercises

The boundaries a run may not cross

*The example says this about itself:*

> The subject is the container's ROOT filesystem: it has to refuse a
> write.

## The stages and the steps

### 1. `root`

- `sh` — `"mkdir -p /workspace/out; if printf 'x' > /etc/canary-readonly-probe 2>/dev/null; then...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/canary-readonly.txt` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/canary/read-only-root/read-only-root.pipeline.kts
pipelinek run      examples/catalogue/regressions/canary/read-only-root/read-only-root.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `read-only-root.pipeline.kts`
- **Origin path:** `scenarios/canary/read-only-root.pipeline.kts`
- **Body digest:** `sha256:0a9d3df3b6cdfd6185c03635162555159f12d564033cbc1e4360e5a6e2deee02`
