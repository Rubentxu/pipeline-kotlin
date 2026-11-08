# Artifact Query Bridge

<sub>Este fichero es GENERADO. El catalogo de ejemplos vive en el arnes de releasing, y el arnes es quien lo escribe aqui.</sub>

**Categorias:** `output`

**Resultado esperado:** `success`

## Que ejercita

El plano de salida: que produjo un paso, y como se vuelve a leer

*El ejemplo dice de si mismo:*

> E2E archive->query cross-step data path.
> Pipeline shape (real binary, real coordinator, real process side-effects):
> stage("seed-then-archive"):
> sh seeds a tree at <stage-workspace>/build/utils/mix/...
> archiveArtifacts archives the tree AND records the handle under
> Name "mix" into the per-run ArtifactIndexCapability
> stage("query-by-name"):
> artifactQuery("mix") looks it up via the registered Step. Because
> Wires the artifact index into the runtime context, the query
> Sees the handle and resolves it. Before, this fixture was RED
> (registry boundary failed closed because ARTIFACT_INDEX_CAPABILITY
> was not admitted).
> seed and archive must live in the SAME stage: per the WorkspaceResolver,
> each stage has its own workspace directory, so the archive glob has to
> run against the same workspace the seed wrote into. Splitting them
> across stages makes the glob return 0 matches and the artifact handle
> records an empty file set — still a valid archive (with empty files),
> but the integration evidence is weaker than the same-stage layout.
> No fabricated runtime values, no DSL-time construction of state.

## Las etapas y los pasos

### 1. `seed-then-archive`

- `sh` — `"mkdir -p build/utils/mix && echo 'alpha' > build/utils/mix/a.txt && echo 'beta' > buil...`
- `archiveArtifacts` — `artifacts = "build/utils/mix/**", // `allowEmptyArchive = true` means an archive step t...`
- `sh` — `test -s build/utils/mix/a.txt && test -s build/utils/mix/b.txt`

### 2. `query-by-name`

- `artifactQuery` — `mix`

## Que espera

| Canal | Expectativa |
|---|---|
| codigo de salida | `0` |
| conclusion | `success` |

## Que necesita

- nothing beyond the product itself

## Como se ejecuta a mano

```bash
pipelinek validate examples/catalogue/features/output/artifact-query-bridge/artifact-query-bridge.pipeline.kts
pipelinek run      examples/catalogue/features/output/artifact-query-bridge/artifact-query-bridge.pipeline.kts
```

## Su linaje

- **Nombre historico:** `30-artifact-query-bridge.pipeline.kts`
- **Ruta de origen:** `examples/output/30-artifact-query-bridge.pipeline.kts`
- **Huella del cuerpo:** `sha256:f126804637baa1d34b83575baef25b66b31779588ef76517d659774087f509e9`
- **Huella del origen:** `sha256:f126804637baa1d34b83575baef25b66b31779588ef76517d659774087f509e9`
