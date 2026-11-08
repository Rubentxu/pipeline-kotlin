# Egress Blocked

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `egress`, `security`, `release`

**Expected outcome:** `success`

## What it exercises

What happens when a step tries to reach the network

*The example says this about itself:*

> Caso negativo (seguridad): el aislamiento de red del container bloquea
> cualquier intento de egress. Un escenario que intente curl a Internet debe
> fallar rapido por timeout/refused, NO por timeout largo.

## The stages and the steps

### 1. `no-egress`

- `sh` — `"mkdir -p /workspace/out && (timeout 10 curl -sS -o /dev/null -w '%{http_code}\\n' http...`

### 2. `no-dns`

- `sh` — `"mkdir -p /workspace/out && (timeout 5 getent hosts example.com 2> /workspace/out/har02...`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| files | `out/har022-egress.stderr`, `out/har022-egress.exit`, `out/har022-dns.stderr`, `out/har022-dns.exit` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- la imagen se ejecuta con --cpus 2 --memory 2g --pids-limit 256 --cap-drop ALL --network none --read-only (aislamiento de H0.3)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/egress-blocked/egress-blocked.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/egress-blocked/egress-blocked.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

**Covers:** `HAR-022`

## Its lineage

- **Historical name:** `har022-egress-blocked.pipeline.kts`
- **Origin path:** `scenarios/smoke/har022-egress-blocked.pipeline.kts`
- **Body digest:** `sha256:45d18b44efca4fef73d6feb14ddc7726afe7121b477334cbbfd3d44a2da9a751`
