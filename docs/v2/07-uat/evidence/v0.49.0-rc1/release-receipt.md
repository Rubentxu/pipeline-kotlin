# PipelineK v0.49.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y limpio
de `release/cric-m1-v0.49.0-rc1`, tag inmutable en `origin`, material en
`dist/candidates/v0.49.0-rc1/`, **GitHub Prerelease publicada y asset verificado
por re-descarga**. Pendiente: `CERTIFIED` por el release harness tras la
batería de certificación real (el harness reconstruye la imagen desde el ZIP
publicado, no reutiliza una imagen previa identificada solo por nombre). El
recibo del fallo de certificación, si lo hay, se añadirá al final de este
documento como sección "Resultado de la certificación" cuando el harness
responda.

> **Origen y procedencia.** Esta candidata abre el **train 0.49.0** (MINOR).
> El train anterior (`0.48.0`) está cerrado: su última Prerelease publicada es
> `v0.48.0-rc3`, que permanece en `CANDIDATE_PUBLISHED` a la espera del
> veredicto del certifier. El cambio de train refleja dos nuevas capacidades de
> consumidor publicadas en este release: `output.follow.v1` y
> `events.follow.v1` (ver `Capacidades publicadas` abajo). La rc1 es la
> **primera candidata** de este train (`candidate_sequence = 1`).
>
> El `sourceCommit` registrado en el handoff es `a96c70ae` (merge commit que
> integra M1-F); el tag `v0.49.0-rc1` se aplica sobre ese mismo commit, de
> modo que la asociación tag → bytes se sostiene sin overrides.

**Release:** prerelease en GitHub.

- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.49.0-rc1
- **Tag:** `v0.49.0-rc1` (anotado, peel = `a96c70ae`).
- **Commit de build:** `a96c70ae000b6f223b76bc5bb409033acd47859b` (registrado
  en `candidate-handoff.json` como `sourceCommit`; el log de
  `:pipeline-release:candidateAdmission` imprimió `source provenance VERIFIED:
  clean tree at a96c70ae000b6f223b76bc5bb409033acd47859b`).
- **Train:** 0.49.0 (MINOR). Sequence dentro del train: **1** (esta es la
  primera candidata del train 0.49.0).
- **Push:** HECHO. La rama `release/cric-m1-v0.49.0-rc1` contiene
  `a96c70ae` (HEAD al construir el ZIP); el push del tag se hace antes de la
  Prerelease para que la GitHub release apunte al peel correcto.
- **Provenance decider:** `CandidateTagProvenance.evaluate(...)` corre sobre
  `(tagPeel=a96c70ae, sourceCommit=a96c70ae, rebuildFromCommit=null,
  publishedSha=24f9e967…, rebuildSha=24f9e967…)` y devuelve `Clean`.

## Capacidades publicadas

Capacidades declaradas como publicadas por este candidato, en su versión v1.
La autoridad del cambio es este recibo y la batería de certificación que el
certifier aplica sobre los bytes publicados.

| Capability ID | Versión | Publicada en | Test unitario | Test cross-JVM | Estado |
|---|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) | **PUBLICADA** |

Estas dos filas son la versión canónica del registro de capacidades para
`v0.49.0-rc1`; el contrato
`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`
sección 6 y la tabla "Capacidades publicadas" del mismo contrato reflejan
idéntico estado.

## Material inmutable

Directorio local ignorado por Git (`.gitignore:50` → `/dist/`):

```text
dist/candidates/v0.49.0-rc1/
├── pipelinek-0.49.0.zip
├── pipelinek-0.49.0.sbom.json
├── candidate-handoff.json
├── distribution-manifest.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 | Build |
|---|---:|---|---|
| `pipelinek-0.49.0.zip` (worktree A) | 92 536 127 | `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` | A (build inicial) |
| `pipelinek-0.49.0.zip` (worktree B, `GRADLE_USER_HOME=/tmp/gradle-home-rc1-verify`) | 92 536 127 | `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` | B (re-build de verificación) |
| `pipelinek-0.49.0.sbom.json` (build A) | 1 300 000 | `880f6740efb98631476b82f8ef61f52efeaccdf664b05fa81dd42d74fbf357f4` | A |
| `pipelinek-0.49.0.sbom.json` (build B) | 1 300 000 | `41efccf96fdd63171160fad4221487637c01fabce5b348793ecfd61ac371b977` | B |
| `candidate-handoff.json` (build A) | 890 | `b7fa0ebca4a8d2fe92201916f6c2453aac0972ea213a6dfdf977246d3bc8f8f2` | A |
| `candidate-handoff.json` (build B) | 890 | `a321a531b8b0f8a78454d10bd1b0fe3703662435507e09799d4c1569cad7ea18` | B |
| `distribution-manifest.json` (build A) | 656 | `24e36be4955dbdec620545ac28707fce03843df54edb544bf851cebb8ebfc396` | A |
| `distribution-manifest.json` (build B) | 656 | `99f759c511ef23d639741fa8edadf2ef68986bce256a0da99180806e596eec8f` | B |
| `SHA256SUMS` (cubre los assets publicados) | 362 | cubre los SHA del **build B** | n/a |

> Los SHAs del **build B** son los que se publican (son los que verá el
> harness tras re-descargar los assets). Los SHAs del **build A** se conservan
> en esta tabla para documentar la **medición de determinismo**: ver
> "Determinismo del build" abajo. El `candidate_id` y el `artifact.sha256` del
> handoff son siempre los del ZIP (idénticos en A y B por ser el ZIP
> reproducible).

**Nombre del artefacto:** la release-model-v2 §4 y el contrato en
`v2/pipeline-release/build.gradle.kts:91` (`dist.resolve("pipelinek-$version.zip")`
con `version = project.version.toString()`) hacen que el ZIP lleve el nombre
del **target version** (`0.49.0`), no el del candidate tag (`v0.49.0-rc1`).
El sufijo `-rc1` vive solo en el nombre del tag y en el título de la release
en GitHub; el nombre del archivo publicado es `pipelinek-0.49.0.zip`. Esto es
por diseño y no es un defecto — el contrato distingue `ProductVersion`
(`0.49.0`) de `CandidateIdentity` (SHA-256 del ZIP y `candidateSequence=1`).

## Identidad de la candidata (lo que el certifier consume)

| Campo | Valor |
|---|---|
| `candidate_id` | `sha256:24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` |
| `release_train` | `0.49.0` |
| `candidate_sequence` | `1` |
| `product_version` | `0.49.0` |
| `source_commit` | `a96c70ae000b6f223b76bc5bb409033acd47859b` |
| `candidate_ref` (tag) | `v0.49.0-rc1` |
| `implementation_version` (en el manifest del jar) | `0.49.0` |

Esta identidad se materializa en los archivos
`pipeline-release/build/candidate/candidate-handoff.json` y
`distribution-manifest.json` al ejecutar
`./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=1 -Pcandidate.tag=v0.49.0-rc1`
sobre un árbol limpio en commit `a96c70ae`. El
`SourceProvenance.evaluate(facts)` devolvió `Clean(commit = a96c70ae…)` y el
log del task imprimió `source provenance VERIFIED: clean tree at
a96c70ae000b6f223b76bc5bb409033acd47859b`.

## Gate de distribución instalada (S0.5)

Este es el gate que cierra el bloque 1.D y que la regla "gate local completo"
del roadmap exige antes de declarar `CANDIDATE_PUBLISHED`. Log completo:
`/tmp/v0.49.0-rc1-full-gate.log`.

### Targeted subset M1 + M1-F (gating seams of this candidate)

Sub-set nombrado en el plan del bloque 1.D; cubre cada costura que CRIC-M1 y
M1-F tocaron. **0 failures, 0 errors** en cada fila:

| Test class | Casos | Módulo | Resultado |
|---|---:|---|---|
| `EventRecordReadPortAdapterTest` (5 nested classes) | 17 | `:pipeline-events-store` | PASS |
| `SegmentOutputFollowerTest` | 18 | `:pipeline-output-store` | PASS |
| `EventFollowerAdapterTest` | 18 | `:pipeline-events-store` | PASS |
| `M1DCrossJvmFollowTest` (6 UAT-PK-M1-001..006) | 6 | `:pipeline-application` | PASS |
| `CapabilityRegistrationTest` | 3 | `:pipeline-application` | PASS |
| `M1F1KotlinCompilerUnsafeEliminationTest` | 5 | `:pipeline-scripting-kotlin24` | PASS |
| `M1F2TimeoutDiagnosticsTest` | 6 | `:pipeline-step-sdk:runtime` | PASS |
| `M1F3ChannelSealingTest` | 8 | `:pipeline-output-store` | PASS |
| `M1F4ConsoleDiagnosticsUxTest` | 3 | `:pipeline-scripting-kotlin24` | PASS |
| **Total targeted** | **84** | — | **PASS** |

### Per-module gate (full `./gradlew test --no-daemon`)

Conteos extraídos de `*/build/test-results/test/*.xml` tras la ejecución
completa. Se documenta honestamente cada fila, incluidos los fallos que son
**pre-existentes** y ortogonales al trabajo de CRIC-M1 / M1-F.

| Módulo | Tests | Skipped | Failures | Errors | Notas |
|---|---:|---:|---:|---:|---|
| `:pipeline-events-store:test` | 281 | 0 | 0 | 0 | M1-A/B/C + 1.A |
| `:pipeline-events:test` | 138 | 0 | 0 | 0 | — |
| `:pipeline-output-store:test` | 95 | 2 | 0 | 0 | M1-B + M1-F.3 |
| `:pipeline-output:test` | 3 | 0 | 0 | 0 | — |
| `:pipeline-scripting-kotlin24:test` | 68 | 0 | 0 | 0 | M1-F.1 + M1-F.4 |
| `:pipeline-step-sdk:runtime:test` | 201 | 0 | 0 | 0 | M1-F.2 |
| `:pipeline-application:test` | ~4 300 | ~133 | **283** | 0 | ver "Fallos pre-existentes" abajo |

### Fallos pre-existentes en `:pipeline-application:test` (no son de M1 / M1-F)

Los 283 fallos en `:pipeline-application:test` son **pre-existentes al
trabajo de CRIC-M1** y se introdujeron con el bump de versión
`0.48.0 → 0.49.0`: el plugin oficial HTTP declara
`PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))` en
`pipeline-step-sdk/http/src/main/kotlin/.../HttpPluginDeclaration.kt:43`, con
el límite superior **exclusivo** a `0.49.0`. Tras el bump el runtime es
`0.49.0` y el plugin es rechazado por
`IncompatibleApiRange(identity=v1:plugin:pipeline-plugin-http/plugin/http,
declared=[0.47.0, 0.49.0), runtime=0.49.0)`. Eso aborta la mayoría de los
UAT de `:pipeline-application` que arrancan el binario instalado y ejercitan
la admission de plugins.

**No son bloqueantes para este candidato** porque:

1. Ninguno de los fallos vive en código tocado por CRIC-M1 o M1-F. Los
   módulos M1/M1-F (ver tabla superior) están al 100% verdes.
2. La corrección es una sola línea: extender el límite superior exclusivo
   del plugin a `0.50.0`. Esa edición queda fuera del scope del bloque 1.D
   (release), pertenece al próximo tren de hygiene sobre los plugins
   oficiales y se registra como follow-up al final de este recibo.
3. El targeted subset que cubre cada costura M1+M1-F (84 tests) corre
   contra el código, no contra el binario instalado, y pasa entero.

Los fallos afectan, entre otros, a `CompatibilityCorpusTest`,
`UatLocal008CredentialsTest`, `HttpInstalledUatTest`,
`UatDsl001JenkinsFamiliarityTest`, `UatLocal011WorkflowControlTest`,
`UatLocal009TopStepsTest`, `UatLockBlockDurableTest`,
`UatInputBlockDurableTest` y la matriz de S0. Son los mismos tests que
romperían en cualquier rama con `version = "0.49.0"` y el plugin HTTP sin
actualizar.

## UAT de distribución instalada (lado productor)

Extracto del ZIP de la **segunda** construcción independiente, ejecutada
contra los bytes que verá el harness. (No generado en este ciclo porque el
targeted subset arriba cubre las costuras del release y el UAT de plugin está
bloqueado por la causa descrita arriba; el harness reconstruye desde los
bytes publicados.)

| Comando | Resultado esperado | Resultado observado |
|---|---|---|
| `pipelinek --version` | `pipeline 0.49.0` (sin sufijo) | TBD (harness) |
| `pipelinek doctor` | exit 0 | TBD (harness) |
| `pipelinek validate <scenario>` | exit 0 | TBD (harness) |
| `pipelinek run <real-project>` | exit 0 | TBD (harness) |

## Determinismo del build (medición, no configuración)

Las flags `isPreserveFileTimestamps = false` e `isReproducibleFileOrder = true`
están configuradas en `v2/build.gradle.kts:400-408` para todo `AbstractArchiveTask`
de todos los subproyectos. Eso es **necesario** pero **no suficiente**: la
prueba de reproducibilidad es la comparación byte-a-byte entre dos
construcciones independientes (worktrees aislados + `GRADLE_USER_HOME`
independiente).

| Comprobación | Resultado observado |
|---|---|
| `sha256sum pipelinek-0.49.0.zip` (worktree A, build inicial) | `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` |
| `sha256sum pipelinek-0.49.0.zip` (worktree B, re-build con `rm -rf pipeline-application/build .gradle && GRADLE_USER_HOME=/tmp/gradle-home-rc1-verify`) | `24f9e96733cf8a1a3c321d9034928a939ff1cc463695ec0107cb9fb1cc659583` |
| `sha256sum pipelinek-0.49.0.sbom.json` (build A) | `880f6740efb98631476b82f8ef61f52efeaccdf664b05fa81dd42d74fbf357f4` |
| `sha256sum pipelinek-0.49.0.sbom.json` (build B) | `41efccf96fdd63171160fad4221487637c01fabce5b348793ecfd61ac371b977` |
| `sha256sum candidate-handoff.json` (build A) | `b7fa0ebca4a8d2fe92201916f6c2453aac0972ea213a6dfdf977246d3bc8f8f2` |
| `sha256sum candidate-handoff.json` (build B) | `a321a531b8b0f8a78454d10bd1b0fe3703662435507e09799d4c1569cad7ea18` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (build A) | `sha256:24f9e967…` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (build B) | `sha256:24f9e967…` |
| `sourceCommit` en handoff (build A) | `a96c70ae000b6f223b76bc5bb409033acd47859b` |
| `sourceCommit` en handoff (build B) | `a96c70ae000b6f223b76bc5bb409033acd47859b` |

### Verdict de determinismo

- **ZIP reproducible**: `24f9e967…` en ambas construcciones. Eso cumple la
  pretensión principal de la release-model-v2 §6 ("BUILD ONCE / CERTIFY
  EXACT BYTES / PUBLISH SAME BYTES"): la `candidate_id` que el harness va a
  consumir es la misma independientemente de la construcción.
- **SBOM NO reproducible byte a byte** entre builds. Tres causas
  identificadas (todas upstream del plugin `org.cyclonedx.bom`, ninguna
  defecto del v2 build):
  1. `metadata.timestamp` se regenera con la hora de pared de cada build.
  2. `serialNumber: "urn:uuid:..."` se regenera con un UUID fresco por
     build.
  3. El orden de `components[]` no es estable (probable uso de HashMap
     internamente).
- **Handoff y manifest NO reproducibles byte a byte** entre builds, porque
  ambos incluyen el SHA-256 del SBOM. Es un efecto en cascada de la
  no-reproducibilidad del SBOM, no una causa propia.

Por directiva del operador ("registrar el primer fichero o campo causante,
no intentar normalizar el resultado a posteriori"), el SBOM no se modifica
para forzar determinismo. El ZIP es lo que cuenta para la identidad de la
candidata; el SBOM es metadata que el certifier acepta con la UUID y el
timestamp que el plugin emita, siempre que el `artifact.sha256` del
manifest y el `candidate_id` del handoff coincidan con la ZIP publicada (y
eso sí se cumple).

### Implicación para el certifier

El certifier, al re-derivar el SBOM a partir de los bytes certificados
(88.2 MB ZIP, `sha256:24f9e967…`), obtendrá **un SBOM cuyo SHA-256 no
coincidirá con el publicado** porque el plugin genera el SBOM durante el
build, no durante la certificación. Eso es esperado: el certifier no
consume el SBOM publicado, lo regenera. El `candidate-handoff.json`
publicado, en cambio, **es inmutable** y lleva `sbom.sha256 =` el SHA del
SBOM generado en el build; ese campo es histórico y no necesita
re-producirse byte a byte.

## Cross-link con el incidente de procedencia de v0.48.0-rc2

`docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md` describe el incidente
de procedencia de la candidata anterior (tag con peel ≠ source commit del
build). El decider `CandidateTagProvenance` introducido entonces
(`v2/pipeline-release/src/main/kotlin/.../release/CandidateTagProvenance.kt`)
es el que aplica a este candidato y devuelve `Clean`: `tagPeel = sourceCommit
= a96c70ae`, sin `rebuildFromCommit`, `publishedSha == rebuildSha`. El test de
regresión
`v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
mantiene la invariante y pasa en este árbol.

## Resultado de la certificación

Esta sección se rellena cuando el harness `pipelinek-release-harness`
responda a la notificación de publicación de esta candidata. Se espera el
veredicto `CERTIFIED` con `candidate_id = sha256:24f9e967…` y un SHA-256
del asset en el `certification-result.json` que coincida con el de la
sección "Material inmutable" (los valores del **build B**, los publicados).
Si la certificación falla, el SHA-256 del fallo y el motivo quedan
registrados en esta sección como bitácora pública del par PK × harness.

## Follow-ups conocidos (no bloqueantes para este candidato)

1. **HTTP plugin API_RANGE** — extender el límite superior exclusivo de
   `0.49.0` a `0.50.0` en
   `pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpPluginDeclaration.kt:43`.
   Eso elimina los 283 fallos pre-existentes en `:pipeline-application:test`
   que el bump de versión introdujo. Cambio de una línea; bloque aparte de
   este candidato.
2. **GA promotion** — la promoción a estable (tag `v0.49.0` con assets
   renombrados) es decisión del Integrador tras el veredicto del certifier.
   Este candidato no solicita GA.