# Smoke E2e

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `smoke`

**Resultado esperado:** `success`

## Que ejercita

El camino mas corto por el motor: un pipeline que arranca, corre y termina

*El ejemplo dice de si mismo:*

> Compatibility fixture 10: smoke end-to-end (checkout → build+archive).
> Note: under the Single Runtime Spine each stage gets its own
> workspace, so archiveArtifacts lives in the SAME stage as the build step
> (Jenkins-faithful workspace continuity).
> The fixture repository used to be created at /tmp/smoke-repo and cloned from
> that absolute path. Two rules were broken at once: AGENTS.md section 3 gives
> every example a private temporary directory and forbids shared marker paths,
> so two concurrent runs fought over one directory; and the 'rm -rf' aimed at a
> shared location would have deleted whatever else was sitting there. Both are
> gone with a relative path -- 'repo' resolves inside this stage's own
> workspace, so the clone exercises the same git operation without leaving the
> authorised root.

## Las etapas y los pasos

### 1. `checkout`

- `sh` — `"rm -rf repo && mkdir -p repo && cd repo && git init -q . && git config user.email \"$(...`
- `sh` — `git clone repo checkout`
- `sh` — `"test -s checkout/README.md && test \"$(cat checkout/README.md)\" = smoke"`

### 2. `build`

- `sh` — `mkdir -p build/libs && echo jar > build/libs/smoke.jar`
- `archiveArtifacts` — `artifacts = "build/libs/*.jar", allowEmptyArchive = false`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/smoke/smoke-e2e/smoke-e2e.pipeline.kts
pipelinek run      examples/catalogue/features/smoke/smoke-e2e/smoke-e2e.pipeline.kts
```

## Su linaje

- **Nombre historico:** `10-smoke-e2e.pipeline.kts`
- **Ruta de origen:** `examples/smoke/10-smoke-e2e.pipeline.kts`
- **Huella del cuerpo:** `sha256:65b3293cf78aae1f34caf192ce0cf0d8be8270f814fb4aa39bbf4404c5262c1d`
- **Huella del origen:** `sha256:65b3293cf78aae1f34caf192ce0cf0d8be8270f814fb4aa39bbf4404c5262c1d`
