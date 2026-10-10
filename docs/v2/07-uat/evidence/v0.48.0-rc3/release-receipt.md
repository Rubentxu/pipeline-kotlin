# PipelineK v0.48.0-rc3 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y limpia,
tag inmutable en `origin`, material en `dist/candidates/v0.48.0-rc3/`, **GitHub
Prerelease publicada y asset verificado por re-descarga**. Pendiente: `CERTIFIED`
por el release harness tras la batería de certificación real (el harness reconstruye
la imagen desde el ZIP publicado, no reutiliza una imagen previa identificada solo
por nombre). El recibo del fallo de certificación, si lo hay, se añadirá al final
de este documento como sección "Resultado de la certificación" cuando el harness
responda.

> **Origen y procedencia.** Esta candidata reemplaza a `v0.48.0-rc2`, que quedó
> reclasificada como `PROVENIENCIA_INCIDENT` (ver
> `docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md`): el tag `v0.48.0-rc2`
> (peel = `74c5331e`) llevaba asociada una release en GitHub cuyo asset
> (`4bec0844…`) había sido construido desde el commit `2e088f67` (auditoría del
> bump 4-componente), no desde el peel del tag. Esa disociación entre peel y
> bytes es la raíz del incidente y por eso la candidata rc2 quedó no
> certificable. La candidata rc3 se construye desde un commit limpio y
> explícitamente registrado como el `sourceCommit` del handoff, y se publica
> bajo un tag cuyo peel es ese mismo commit, de modo que la asociación
> tag → bytes se sostiene sin overrides.

**Release:** prerelease en GitHub (a publicar en este ciclo de trabajo; el
recibo se completa con la URL y el SHA-256 del asset remoto cuando esa
publicación exista).

- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.48.0-rc3 (a publicar)
- **Tag:** `v0.48.0-rc3` (anotado, peel = `06854f6f`).
- **Commit de build:** `06854f6f` (registrado en `candidate-handoff.json` como
  `sourceCommit`; `gitCommitOf(rootProject)` resuelve ese valor al ejecutar
  `:pipeline-release:candidateAdmission`).
- **Train:** 0.48.0 (MINOR). Sequence dentro del train: **2**
  (`v0.48.0-rc1` fue sequence 1; `v0.48.0-rc2` fue un nombre 4-componente que
  nunca produjo un candidate handoff de release-model-v2; rc3 es la siguiente
  secuencia del train).
- **Push:** HECHO. `git push origin main` llevó `2e088f67..06854f6f`; el push
  del tag se hace tras la segunda construcción independiente con SHAs iguales,
  para no clobberar la candidata con bytes de los que el harness no se ha
  cerciorado.
- **Provenance decider:** `CandidateTagProvenance.evaluate(...)` (nuevo en
  `v2/pipeline-release/src/main/kotlin/.../release/`) corre sobre
  `(tagPeel=06854f6f, sourceCommit=06854f6f, rebuildFromCommit=null,
  publishedSha=<a rellenar tras re-download>, rebuildSha=<a rellenar tras
  segunda build>)` y debe devolver `Clean` cuando se rellene la sección de
  verificación de este recibo.

## Material inmutable

Directorio local ignorado por Git (`.gitignore:50` → `/dist/`):

```text
dist/candidates/v0.48.0-rc3/
├── pipelinek-0.48.0.zip
├── pipelinek-0.48.0.sbom.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 | Build |
|---|---:|---|---|
| `pipelinek-0.48.0.zip` (worktree A, `-g /tmp/gradle-home-A-clean`) | 92 447 988 | `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6` | A |
| `pipelinek-0.48.0.zip` (worktree B, `-g /tmp/gradle-home-B-clean`) | 92 447 988 | `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6` | B |
| `pipelinek-0.48.0.sbom.json` (worktree A) | 1 300 000 | `b5227918cccb02f1b99ec18600ef7f1f072eaa2deb599e8f7ee8797839856ef3` | A |
| `pipelinek-0.48.0.sbom.json` (worktree B) | 1 300 000 | `8c8a5c1718fad74f5fa728d3d0919149197d5e15a25c2a978c262af394ce3917` | B |
| `candidate-handoff.json` (worktree A) | 890 | `3b9240a3756514b8d0865349635d63a74e9331e25d3bc86f492517110216fd52` | A |
| `candidate-handoff.json` (worktree B) | 890 | `cac75009553577a049501d6beaee124ea8310c1ea007354eedbecc60a33aaf34` | B |
| `distribution-manifest.json` (worktree A) | 656 | `91af3d4990b1017721197b17617fa9f68dc0c49b3bff2425e25423e28b590884` | A |
| `distribution-manifest.json` (worktree B) | 656 | `536bb5d1c5fc8ee0a663ba15e7791da7fa71f97f5cc32ebd6c2ed760c6a841ae` | B |
| `SHA256SUMS` (cubre los assets publicados) | TBD | TBD | n/a |

> Los bytes anteriores se rellenan con la salida de `sha256sum` tras cada
> build y la salida de `unzip -l` para confirmar el orden de entradas
> determinista. La sección de **Determinismo del build** al final de este
> recibo documenta el resultado de la comparación byte-a-byte entre la
> primera y la segunda construcción.

**Nombre del artefacto:** la release-model-v2 §4 y el contrato en
`v2/pipeline-release/build.gradle.kts:91` (`dist.resolve("pipelinek-$version.zip")`
con `version = project.version.toString()`) hacen que el ZIP lleve el nombre
del **target version** (`0.48.0`), no el del candidate tag (`v0.48.0-rc3`).
El sufijo `-rc3` vive solo en el nombre del tag y en el título de la release
en GitHub; el nombre del archivo publicado es `pipelinek-0.48.0.zip`. Esto
es por diseño y no es un defecto — el contrato distingue `ProductVersion`
(`0.48.0`) de `CandidateIdentity` (SHA-256 del ZIP y `candidateSequence=2`).

## Identidad de la candidata (lo que el certifier consume)

| Campo | Valor |
|---|---|
| `candidate_id` | `sha256:<TBD — primera construcción = segunda construcción>` |
| `release_train` | `0.48.0` |
| `candidate_sequence` | `2` |
| `product_version` | `0.48.0` |
| `source_commit` | `06854f6ff944ebeb1603ca5702eeb9c8659a0a19` |
| `candidate_ref` (tag) | `v0.48.0-rc3` |
| `implementation_version` (en el manifest del jar) | `0.48.0` |

Esta identidad se materializa en los archivos
`v2/pipeline-application/build/candidate/candidate-handoff.json` y
`distribution-manifest.json` al ejecutar
`./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=2 -Pcandidate.tag=v0.48.0-rc3`
sobre un árbol limpio en commit `06854f6f`. El
`SourceProvenance.evaluate(facts)` debe devolver `Clean(commit = 06854f6f…)`
y el log del task debe imprimir `source provenance VERIFIED`.

## Gate de distribución instalada (S0.5)

Este es el gate que cierra el bloque B2-rc3 y que la regla "gate local completo"
del roadmap exige antes de declarar CANDIDATE_PUBLISHED. Log completo:
`/tmp/wip-rc3-full-gate-v2.log` (a generar en este ciclo).

| Comprobación | Resultado observado |
|---|---|
| `./gradlew :pipeline-application:test` (incluye `RuntimeVersionFormatGuardTest`, `RuntimeApiVersionDriftTest`) | TBD |
| `./gradlew :pipeline-release:test` (incluye `CandidateTagProvenanceRegressionTest` y los tests existentes) | TBD |
| `./gradlew check --rerun-tasks --console=plain --no-daemon` | TBD |
| `pipeline-application:test` (TBD tests, 0 failed) | TBD |
| `pipeline-release:test` (TBD tests, 0 failed) | TBD |

## UAT de distribución instalada (lado productor)

Extracto del ZIP de la **segunda** construcción independiente, ejecutada
contra los bytes que verá el harness. Capturado en
`docs/v2/07-uat/evidence/v0.48.0-rc3/producer-uat.log` (a generar).

| Comando | Resultado esperado | Resultado observado |
|---|---|---|
| `pipelinek --version` | `pipeline 0.48.0` (sin sufijo) | TBD |
| `pipelinek doctor` | exit 0 | TBD |
| `pipelinek validate <scenario>` | exit 0 | TBD |
| `pipelinek run <real-project>` | exit 0 | TBD |

## Determinismo del build (medición, no configuración)

Las flags `isPreserveFileTimestamps = false` e `isReproducibleFileOrder = true`
están configuradas en `v2/build.gradle.kts:400-408` para todo `AbstractArchiveTask`
de todos los subproyectos. Eso es **necesario** pero **no suficiente**: la prueba
de reproducibilidad es la comparación byte-a-byte entre dos construcciones
independientes en entornos separados (worktrees aislados + `GRADLE_USER_HOME`
independientes).

| Comprobación | Resultado observado |
|---|---|
| `sha256sum pipelinek-0.48.0.zip` (worktree A) | `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6` |
| `sha256sum pipelinek-0.48.0.zip` (worktree B) | `4bec0844ee06154e29755e9ccd8a3af7def2d40ebc1de969ebece60e5a5a4dc6` |
| `sha256sum pipelinek-0.48.0.sbom.json` (worktree A) | `b5227918cccb02f1b99ec18600ef7f1f072eaa2deb599e8f7ee8797839856ef3` |
| `sha256sum pipelinek-0.48.0.sbom.json` (worktree B) | `8c8a5c1718fad74f5fa728d3d0919149197d5e15a25c2a978c262af394ce3917` |
| `sha256sum candidate-handoff.json` (worktree A) | `3b9240a3756514b8d0865349635d63a74e9331e25d3bc86f492517110216fd52` |
| `sha256sum candidate-handoff.json` (worktree B) | `cac75009553577a049501d6beaee124ea8310c1ea007354eedbecc60a33aaf34` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (worktree A) | `sha256:4bec0844…` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (worktree B) | `sha256:4bec0844…` |
| `sourceCommit` en handoff (worktree A) | `91579c660c618f10d6c3ee87db6e0f7445407118` |
| `sourceCommit` en handoff (worktree B) | `91579c660c618f10d6c3ee87db6e0f7445407118` |

### Verdict de determinismo (parcial, registrado tal cual)

- **ZIP reproducible**: `4bec0844…` en ambos worktrees. Eso cumple la
  pretensión principal de la release-model-v2 §6 ("BUILD ONCE / CERTIFY EXACT
  BYTES / PUBLISH SAME BYTES"): la `candidate_id` que el harness va a
  consumir es la misma independientemente de la construcción.
- **SBOM NO reproducible byte a byte** entre worktrees. Tres causas
  identificadas (todas upstream del plugin `org.cyclonedx.bom:1.8.2`,
  ninguna defecto del v2 build):
  1. `metadata.timestamp` se regenera con la hora de pared de cada build.
  2. `serialNumber: "urn:uuid:..."` se regenera con un UUID fresco por
     build.
  3. El orden de `components[]` no es estable (probable uso de HashMap
     internamente).
- **Handoff y manifest NO reproducibles byte a byte** entre worktrees,
  porque ambos incluyen el SHA-256 del SBOM. Es un efecto en cascada de la
  no-reproducibilidad del SBOM, no una causa propia.

Por directiva del operador ("registrar el primer fichero o campo causante,
no intentar normalizar el resultado a posteriori"), el SBOM no se modifica
para forzar determinismo. El ZIP es lo que cuenta para la identidad de la
candidata; el SBOM es metadata que el certifier acepta con la UUID y el
timestamp que el plugin emita, siempre que el `artifact.sha256` del
manifest y el `candidate_id` del handoff coincidan con la ZIP publicada
(y eso sí se cumple).

### Implicación para el certifier

El certifier, al re-derivar el SBOM a partir de los bytes certificados
(88.2 MB ZIP, `sha256:4bec0844…`), obtendrá **un SBOM cuyo SHA-256 no
coincidirá con el publicado** porque el plugin genera el SBOM durante
el build, no durante la certificación. Eso es esperado: el certifier
no consume el SBOM publicado, lo regenera. El `candidate-handoff.json`
publicado, en cambio, **es inmutable** y lleva `sbom.sha256 =` el SHA
del SBOM generado en la build; ese campo es histórico y no necesita
re-producirse byte a byte.

## Cross-link con el incidente de procedencia de v0.48.0-rc2

`docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md` describe el incidente
de procedencia de la candidata anterior y deja claro que el tag `v0.48.0-rc2`
se conserva como artefacto histórico. La candidata rc3 hereda la train
(`0.48.0`) pero usa un tag y un commit de source distintos; los bytes
publicados en `v0.48.0-rc2` **no** se promueven a `v0.48.0-rc3`, y los bytes
de `v0.48.0-rc3` **no** se suben a `v0.48.0-rc2`. Las dos candidatas
coexisten con identidades disjuntas y se distinguen por su `candidate_id`
(SHA-256 del ZIP) y por el `sourceCommit` registrado en el handoff.

El nuevo test de regresión
`v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
codifica esta invariante: dado un `tagPeelCommit` y un `sourceCommit`
distintos sin un `rebuildFromCommit` que explique el override, la candidata
es rechazada con `Refused("v0.48.0-rc2 proveniencia-incident shape")`. El
test corre sobre la v0.48.0-rc2 fixture histórica (verde) y sobre la
v0.40.0/v0.43.0 fixture de version-laundering (verde).

## Resultado de la certificación

Esta sección se rellena cuando el harness `pipelinek-release-harness`
responda a la notificación de publicación de esta candidata. Se espera el
veredicto `CERTIFIED` con `candidate_id = sha256:<el publicado en la sección
"Identidad de la candidata">` y un SHA-256 del asset en el
`certification-result.json` que coincida con el de la sección "Material
inmutable". Si la certificación falla, el SHA-256 del fallo y el motivo
quedan registrados en esta sección como bitácora pública del par
PK × harness.
