# No Secret Leak

<sub>This file is GENERATED. The catalogue of examples lives in the release harness, and the harness is what writes it here.</sub>

**Categories:** `security`, `secret`

**Expected outcome:** `success`

## What it exercises

The boundaries a run may not cross

*The example says this about itself:*

> Regresion de no-filtracion de secretos en logs de pipeline-kotlin.
> El canario emite contenido "normal" (mensajes de compilacion, paths,
> resultados de tests) y exige que el detector de secretos (harness/secrets.py)
> NO encuentre ningun patron reconocible. Si PASARA con hallazgos, seria un
> DEFECTO DE SEGURIDAD: el log esta filtrando un secreto.

## The stages and the steps

### 1. `clean-build`

- `sh` — `echo 'compiling rust-canary v0.1.0'`
- `sh` — `echo 'all 2 tests passed'`
- `sh` — `ls -la /workspace | head -3`

## What it expects

| Channel | Expectation |
|---|---|
| exit | `0` |
| outcome | `success` |
| stdout contains | `compiling rust-canary` |

## What it needs

- certify-root explicito que no fija pipelinek (la preflight del canal escanea los pins de version)
- candidata registrada antes con receive-candidate contra el mismo sha256 del ZIP del que salio la imagen
- pin de pipelinek declarado en .tool-versions (CERTIFY_VERSION)
- imagen de la candidata disponible en el motor local (podman)
- H0.* NO monta SecretProvider: el canario solo comprueba que el shell no filtra patrones (un vault real es H4)

## Running it by hand

```bash
pipelinek validate examples/catalogue/regressions/smoke/no-secret-leak/no-secret-leak.pipeline.kts
pipelinek run      examples/catalogue/regressions/smoke/no-secret-leak/no-secret-leak.pipeline.kts
```

**State of the instrument:** `PASS` — the coverage of the instrument that judges it, not the verdict of its last run.

## Its lineage

- **Historical name:** `har023-no-secret-leak.pipeline.kts`
- **Origin path:** `scenarios/smoke/har023-no-secret-leak.pipeline.kts`
- **Body digest:** `sha256:28b0d1f46a78383b6194fc4083d5c02bbc0e88618762b6ba66b71ffddce8633a`
