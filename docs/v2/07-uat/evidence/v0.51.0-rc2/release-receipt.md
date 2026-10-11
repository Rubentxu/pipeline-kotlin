# PipelineK v0.51.0-rc2 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y limpia
(`5666242` para la build; el tag `v0.51.0-rc2` apunta a ese commit), material en
`dist/candidates/v0.51.0-rc2/`. Pendiente: `CERTIFIED` por el release harness tras
la batería de certificación real (el harness reconstruye la imagen desde el ZIP
publicado, no reutiliza una imagen previa identificada solo por nombre).

> **Origen y procedencia.** Esta candidata consolida los tres trains CRIC-M1/M2/M3
> sobre la rama `consolidate/cric-m3-plus-main` y los integra en `main`. El
> estado de entrada era:
>
> | Elemento | Valor |
> |---|---|
> | `main` (entrada) | `a0f84e48` (con `e5867dab` M6 audit revertido) |
> | common ancestor con las release branches | `91579c66` |
> | `release/cric-m1-v0.49.0-rc1` | tag `v0.49.0-rc1` preservado |
> | `release/cric-m2-v0.50.0-rc1` | tag `v0.50.0-rc1` preservado |
> | `release/cric-m3-v0.51.0-rc1` | tag `v0.51.0-rc1` preservado (huérfano) |
> | `consolidate/cric-m3-plus-main` | `5fc7ab6b` con M1/M2/M3 + PK-R1 fixes |
> | Gate :pipeline-application:test entrada | 283 fallos |
> | Gate :pipeline-application:test salida | **0 fallos (2832/0/0, 123 skipped)** |
>
> Los tags publicados `v0.49.0-rc1`, `v0.50.0-rc1` y `v0.51.0-rc1` no se
> reescriben. La candidata rc2 reemplaza a rc1 con un set de pruebas que pasa
> 100% y un nuevo gate fail-closed de admisión de plugins.

- **Tag:** `v0.51.0-rc2` (anotado, peel = `566624259e03732ffb332718a92f76020e1c0a07`).
- **Commit de build:** `566624259` (registrado en `candidate-handoff.json` como
  `sourceCommit`; verificado por `SourceProvenanceProbe.probe` → `Clean`).
- **Train:** 0.51.0 (MINOR). Sequence dentro del train: **2** (rc1 fue
  sequence 1, su gate falló).
- **Push:** pendiente de decisión del operador.
- **Provenance decider:** `SourceProvenance.evaluate(...)` corre sobre
  `(headCommit=566624259, modifiedTrackedFiles=0, stagedButUncommitted=0)` y
  devuelve `Clean`.

## Cambios respecto a v0.51.0-rc1

### R1-B: Eliminación de los 283 fallos del gate

`HttpPluginDeclaration.API_RANGE` (y los de los 3 plugins oficiales restantes)
estaba fijado en `[0.47.0, 0.49.0)`, lo que excluía el runtime 0.51.0 y
provocaba que `PluginAdmissionGate` rechazara cada artefacto. La extensión a
`[0.47.0, 0.52.0)` admite la serie 0.51.x sin tocar contratos ni ABI.

Plugins con apiRange extendido (commits `94299aa2`, `4a17fcbf`):

- `pipeline-step-sdk/http` (`HttpPluginDeclaration`)
- `pipeline-step-sdk/scm-git` (`ScmGitPluginDeclaration`)
- `pipeline-step-sdk/junit` (`JUnitPluginDeclaration`)
- `pipeline-step-sdk/utilities` (`UtilitiesPluginDeclaration`)
- `examples/example-uppercase-plugin` (`UppercasePluginDeclaration`)
- `examples/example-block-plugin` (`RepeatPluginDeclaration`)
- `examples/example-directive-plugin` (`LockPluginDeclaration`)

Plugins que NO se modificaron porque su apiRange ya cubría 0.51.0:
`files`, `runtime`, `api`, `workflow-control`.

### R1-B: 6 correcciones de tests residuales (commit `a520b92a`)

1. `UatEvt001ReplayTest`, `UatEvt002MultiStepReplayTest`,
   `SelfHostedPipelineScriptHonestyTest` filtran ERROR/FATAL en lugar de
   `diagnostics.isEmpty()`, tolerando el INFO "outdated JAR FS" que el
   compilador Kotlin 2.4.10 emite como confirmación observable de
   `-Xuse-fast-jar-file-system=false` (M1-F.1).
2. `RetentionAuthorityFitnessTest` regex con `\s*` dentro del negative
   lookahead (en lugar de fuera) para evitar que el motor de regex
   retroceda más allá del marcador `*` de un bloque KDoc.
3. `FArchS0CPureBuilderConsumptionTest` ahora verifica contra
   `outcome.diagnostics` (no truncado) en lugar de `outcome.summary`
   (truncado a 600 chars) — el mensaje de rechazo del
   `rejectUnconsumedCarriers` vive en el evento `StepFailed` pasado
   la ventana de truncación.
4. `DirectivePluginContractSuiteTest` re-certificado al SHA
   `175cb77178…` (era `283f89d7…`) tras la reconstrucción del JAR
   `example-directive-plugin-0.1.0.jar` por la extensión de apiRange.
   SHA reproducible a través de dos builds `--rerun-tasks --no-build-cache`.

### R1-C: Nuevo gate fail-closed de admisión de plugins (commits `9a16c786`, `5666242`)

`BundledPluginCompatibility` escanea el ZIP de distribución en busca de JARs
bajo `lib/` que contengan `META-INF/pipelinek/plugin-manifest.json`, parsea
el campo `apiRange` de cada uno, y rechaza la candidata si el rango
excluye la versión del host. La regla de contención es:

- lower bound inclusivo, upper bound exclusivo;
- `0.51.0` cae en `[0.47.0, 0.52.0)` → admitido;
- `0.51.0` NO cae en `[0.47.0, 0.51.0)` → rechazado;
- rango malformado → rechazado (fail-closed, no se asume compatibilidad);
- distribución sin manifiestos → rechazada (INCOMPLETE, no green por
  defecto).

La verificación es mecánica y reproducible desde el ZIP solo. No
requiere un consumer externo, ni un recibo de CI.

`v0.51.0-rc2` se admite con este gate. La candidata lleva 4 plugins
oficiales descubiertos, todos con `[0.47.0, 0.52.0)`.

### R1-D: Validación de la experiencia de ejecución

8 scripts UAT ejecutados contra la distribución instalada (binary path
`v2/pipeline-application/build/install/pipelinek/bin/pipelinek`):

| # | Script | Exit | Verdict |
|---|---|---|---|
| 1 | `01-hello.pipeline.kts` | 0 | PASS |
| 2 | `03-shell.pipeline.kts` (real sh: uname, pwd, for) | 0 | PASS |
| 3 | `04-kotlin-control-flow.pipeline.kts` (real `script {}`) | 0 | PASS |
| 4 | `09-retry.pipeline.kts` (real sh dentro de `retry(count=3)`) | 0 | PASS |
| 5 | `10-timeout.pipeline.kts` (sh sobre 2s budget) | 1 | PASS (failure es el contrato) |
| 6 | `05-failing-step.pipeline.kts` (sh con exit 3) | 1 | PASS (failure es el contrato) |
| 7 | probe sh+sh | 0 | PASS |
| 8 | probe echo+uname | 0 | PASS |

- Sin warnings `Unsafe` / `xlog:os` en stderr.
- Sin ruido de session-leader (`setsid`, `nohup`).
- Los 4 plugins oficiales admitidos por ServiceLoader en cada run.
- Fallos reales visibles con clasificación tipada `[SCRIPT]` / `[TIMEOUT]`.
- Sin byte-loss, sin huérfanos, sin timeouts.

Un transient `DanglingCommit` apareció en una captura inicial pero no
se reprodujo en 5+ ejecuciones posteriores del mismo script. Flagged,
no es bloqueante.

### R1-E: Consumer externo contra SDK publicado

`examples/example-external-consumer` (commit `5fc7ab6b`):

- Resuelve 4 coordenadas SDK desde `v2/build/sdk-repo` (NO mavenLocal).
- 50/50 tests verdes: `output.follow.v1`, `events.follow.v1`, runtime
  read ports (`OutputReadPort`, `OutputFrameIndex`,
  `EventRecordReadPort`), range read APIs (`PipelineKApiRange`,
  `SemVer`, `Digest`, `Capability`).
- JAR reproducible: SHA `c6deada2…` a través de dos builds
  `--rerun-tasks --no-build-cache`.
- Cada test separa **contract** (forma del tipo) de **capability**
  (invocación del método documentado) en filas distintas del matrix.

### T6: Política de no-reproducibilidad del SBOM (preservada)

`v0.48.0-rc3` documentó que el SBOM CycloneDX no es reproducible byte-a-byte
entre builds (causa: `metadata.timestamp`, `serialNumber: urn:uuid:…`, orden
de `components[]`). Esta política sigue vigente para rc2; el certifier
re-deriva el SBOM desde los bytes certificados y el SHA publicado es
histórico. La sección de reproducibilidad lo documenta.

## Material inmutable

```text
dist/candidates/v0.51.0-rc2/
├── pipelinek-0.51.0.zip
├── pipelinek-0.51.0.sbom.json
├── candidate-handoff.json
├── distribution-manifest.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 | Origen |
|---|---:|---|---|
| `pipelinek-0.51.0.zip` | 88 306 489 | `b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72` | `:pipeline-application:distZip` desde `5666242` |
| `pipelinek-0.51.0.sbom.json` | 1 386 608 | `f9fba314654f6c314919483a8bbef63b14e5dbb5d7b75308899f908be3de10dd` | `:pipeline-application:cyclonedxBom` |
| `candidate-handoff.json` | 948 | `22318774f9183b5de12ea36d140beaf8f1ef7383a3012d247c962c33e206f871` | `:pipeline-release:candidateAdmission` |
| `distribution-manifest.json` | 647 | `30a1c682fbd47007d8e2d8dde0747f51642c095253e76e1dfcc0589f970fd48f` | `:pipeline-release:candidateAdmission` |
| `SHA256SUMS` | n/a | cubre los 4 assets anteriores | n/a |

## Reproducibilidad del build

- **ZIP reproducible** entre dos construcciones independientes con
  `--rerun-tasks --no-build-cache` desde `5666242` (verificación en este
  mismo revision, no registrada cross-worktree por economía de tiempo).
- **SBOM no reproducible byte-a-byte** entre construcciones (causa
  upstream del plugin CycloneDX: timestamp, serialNumber, orden de
  components[]). Documentado en el recibo rc3 previo; no se re-deriva
  aquí. La política se mantiene.
- **Handoff y manifest no reproducibles** porque incluyen el SHA del
  SBOM (cascada de la no-reproducibilidad del SBOM).

## Gap documentado (no bloqueante para rc2)

`R1-E` requería probar "runtime ports" contra el SDK publicado. El consumer
externo sólo pudo probar los puertos de LECTURA
(`OutputReadPort`, `OutputFrameIndex`, `EventRecordReadPort`). Los
puertos de PUBLICACIÓN/SUSCRIPCIÓN (`RuntimeRecoverPort`,
`RuntimeControlPort`, `RuntimeIntrospectionPort`) viven en
`v2/pipeline-runtime/`, que está deliberadamente FUERA de la lista
`publishedContractModules` y por tanto no se puede resolver desde un
consumer externo.

Por decisión del operador (selección explícita antes de T9), esta
candidata rc2 se publica con el gap documentado. El certifier decidirá
si requiere `v0.51.0-rc3` que extienda el SDK para incluir los puertos
publish/subscribe.

## Cómo el harness verifica la candidata

```bash
# 1. Inmutabilidad del tag.
git fetch origin --tags --prune
git rev-parse v0.51.0-rc2^{commit}
# Debe imprimir 566624259e03732ffb332718a92f76020e1c0a07

# 2. Re-descargar el ZIP y verificar SHA-256 byte a byte.
rm -rf /tmp/rc2-verify && mkdir -p /tmp/rc2-verify
gh release download v0.51.0-rc2 --pattern 'pipelinek-0.51.0.zip' --dir /tmp/rc2-verify
sha256sum /tmp/rc2-verify/pipelinek-0.51.0.zip
# Debe imprimir b5b72636c4e913bf7f9f9660f367fdaf1a2425c5f20add42efad21ce71d5bd72

# 3. Confirmar que el binario reporta 0.51.0 (no -rc2, no -rc1).
unzip -p /tmp/rc2-verify/pipelinek-0.51.0.zip \
  'pipelinek-0.51.0/lib/pipeline-application-0.51.0.jar' \
  | strings | grep 'Implementation-Version' | head -1
# Debe imprimir Implementation-Version: 0.51.0

# 4. Confirmar el SHA del handoff y del manifest coincide con SHA256SUMS.
sha256sum dist/candidates/v0.51.0-rc2/candidate-handoff.json
sha256sum dist/candidates/v0.51.0-rc2/distribution-manifest.json
```

## Resultado esperado del certifier

- `decision: CERTIFIED`
- `candidate_id == sha256:b5b72636…`
- `artifact_sha256 == b5b72636…`
- `source_commit: 566624259e03732ffb332718a92f76020e1c0a07`

## Lo que NO se ha hecho en el repositorio (lo hace el harness)

- **Certificación (`CERTIFIED`)** y promoción a estable. La release
  model v2 §3/§5: el repositorio entrega candidatas inmutables;
  `pipelinek-release-harness` decide cuándo promover, sin reconstrucción,
  reutilizando los mismos bytes del ZIP publicado. GA se haría como tag
  `v0.51.0` con assets renombrados a `pipelinek-0.51.0.{zip,sbom.json}` +
  `SHA256SUMS`, según el patrón de
  `docs/pipelinek-release-evolution/shared/02-release-model-v2.md` §6.
- **Push a origin** del tag y de la rama. Pendiente de autorización
  explícita del operador; el agente productor no asume esa decisión.
