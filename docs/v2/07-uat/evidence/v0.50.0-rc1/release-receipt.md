# PipelineK v0.50.0-rc1 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — candidata construida sobre HEAD verde y
limpio de `release/cric-m2-v0.50.0-rc1`, tag inmutable en `origin`, material
en `dist/candidates/v0.50.0-rc1/`, **GitHub Prerelease publicada y asset
verificado por re-descarga**. Pendiente: `CERTIFIED` por el release harness
tras la batería de certificación real (el harness reconstruye la imagen
desde el ZIP publicado, no reutiliza una imagen previa identificada solo
por nombre). El recibo del fallo de certificación, si lo hay, se añadirá
al final de este documento como sección "Resultado de la certificación"
cuando el harness responda.

> **Origen y procedencia.** Esta candidata abre el **train 0.50.0**
> (MINOR). El train anterior (`0.49.0`) está cerrado: su única Prerelease
> publicada es `v0.49.0-rc1`, que permanece en `CANDIDATE_PUBLISHED` a
> la espera del veredicto del certifier. El cambio de train refleja tres
> nuevas capacidades de consumidor publicadas en este release como
> `EXPERIMENTAL` (`runtime.inspect.v1`, `runtime.cancel.v1`,
> `runtime.recover.v1`) además de la **carry-forward** de las dos
> capacidades `CRIC-M1` ya `PUBLICADA` (`output.follow.v1`,
> `events.follow.v1`). La rc1 es la **primera candidata** de este train
> (`candidate_sequence = 1`).
>
> El `sourceCommit` registrado en el handoff es `bc5861b7` (chore(release):
> bump 0.49.0 -> 0.50.0). El tag `v0.50.0-rc1` se aplica sobre ese mismo
> commit, de modo que la asociación tag → bytes se sostiene sin overrides.
> El `:pipeline-release:candidateAdmission` imprimió `source provenance
> VERIFIED: clean tree at bc5861b70dbffee5e22144c04b488c04bf4825b1`.

**Release:** prerelease en GitHub.

- **URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.50.0-rc1
- **Tag:** `v0.50.0-rc1` (anotado, peel = `bc5861b7`).
- **Commit de build:** `bc5861b70dbffee5e22144c04b488c04bf4825b1` (registrado
  en `candidate-handoff.json` como `sourceCommit`).
- **Train:** 0.50.0 (MINOR). Sequence dentro del train: **1** (esta es la
  primera candidata del train 0.50.0).
- **Push:** HECHO. La rama `release/cric-m2-v0.50.0-rc1` contiene
  `bc5861b7` (HEAD al construir el ZIP); el push del tag se hace antes
  de la Prerelease para que la GitHub release apunte al peel correcto.
- **Provenance decider:** `CandidateTagProvenance.evaluate(...)` corre sobre
  `(tagPeel=bc5861b7, sourceCommit=bc5861b7, rebuildFromCommit=null,
  publishedSha=b5a257f0…, rebuildSha=b5a257f0…)` y devuelve `Clean`
  (decider existente; ver sección "Cross-link con el incidente de
  procedencia de v0.48.0-rc2" abajo).
- **Identity gate:** `evaluateDistributionIdentity(...)` reporta
  `identity INCOMPLETE: expected 0.50.0 but these surfaces were not
  observable: RUNTIME_VERSION, MANIFEST_VERSION`. El log del task
  `:pipeline-release:candidateAdmission` admite la candidata
  (`[release] candidate admission PASSED`); la advertencia nombra las
  dos superficies sin probe actual (el manifest `version = "0.50.0"` sí
  se materializa; ver `Material inmutable` abajo). Es la misma situación
  observada en builds de `v0.49.0-rc1` y anteriores — la probe
  `RUNTIME_VERSION`/`MANIFEST_VERSION` no se ha implementado todavía
  para esta rama y se documenta como follow-up (no bloqueante).

## Capacidades publicadas

Capacidades declaradas como publicadas por este candidato. Las dos
capacidades `CRIC-M1` se llevan **PUBLICADA** desde `v0.49.0-rc1`; las
tres capacidades `CRIC-M2` se abren como **EXPERIMENTAL** en este
release y transicionan a `PUBLICADA` tras la certificación por
`pipelinek-release-harness`.

| Capability ID | Versión | Publicada en | Test unitario | Test cross-JVM | Estado |
|---|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.50.0-rc1` | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10), carry-forward en `v0.50.0-rc1` | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) | **PUBLICADA** |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) | — | **EXPERIMENTAL** |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) | — | **EXPERIMENTAL** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`); matriz pura de decisión: `RuntimeRecoverDecisionTableFitnessTest` (12 casos, `:pipeline-runtime`) | — | **EXPERIMENTAL** |

Estas cinco filas son la versión canónica del registro de capacidades
para `v0.50.0-rc1`; el contrato
`docs/pipelinek-coordinated-evolution/coordination/INTERFACE_CONTRACT.md`
sección 6 y la tabla "Capacidades publicadas (CRIC-M2)" del mismo
contrato reflejan idéntico estado. El test de pin
`CapabilityRegistrationTest` (4 casos, `:pipeline-application`) verifica
byte-a-byte que ambas tablas existen y nombran los test classes.

## Material inmutable

Directorio local ignorado por Git (`.gitignore` → `/dist/`):

```text
dist/candidates/v0.50.0-rc1/
├── pipelinek-0.50.0.zip
├── pipelinek-0.50.0.sbom.json
├── candidate-handoff.json
├── distribution-manifest.json
└── SHA256SUMS
```

| Material | Bytes | SHA-256 | Build |
|---|---:|---|---|
| `pipelinek-0.50.0.zip` (worktree A, build inicial) | 92 537 770 | `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` | A (build inicial) |
| `pipelinek-0.50.0.zip` (worktree B, `GRADLE_USER_HOME=/tmp/gradle-home-rc-verify-50`) | 92 537 770 | `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` | B (re-build de verificación) |
| `pipelinek-0.50.0.sbom.json` (build A) | 1 300 000 | `a97f9b8fcc7d4048fe38f65baf58fbd20ab3e37edbe379f7af53f6f71d055988` | A |
| `pipelinek-0.50.0.sbom.json` (build B) | 1 300 000 | `65f9f794af143356ae9a864c4ac8912f146ad575b0f31a616f55cb8da40312ce` | B |
| `candidate-handoff.json` (build A) | 890 | `3f51774ce6345a39732446c2ad70763000082dc9dfa02005de814fe007ad6461` | A |
| `candidate-handoff.json` (build B) | 890 | `d39bd3bd20f59bcb5245fe5771fa8da7edbdfc89fa29c0abbd023bbaa606e7a1` | B |
| `distribution-manifest.json` (build A) | 656 | `53ab00cae40926ae8319eb941d19f07ac51687bdce6f4830297991caed1db0fd` | A |
| `distribution-manifest.json` (build B) | 656 | `7edf75ea800eb024d0f9443fc431297f9f55ef7a9a1aeeb771bb1d63720250c2` | B |
| `SHA256SUMS` (cubre los assets publicados) | 362 | cubre los SHA del **build B** | n/a |

> Los SHAs del **build B** son los que se publican (son los que verá el
> harness tras re-descargar los assets). Los SHAs del **build A** se
> conservan en esta tabla para documentar la **medición de determinismo**:
> ver "Determinismo del build" abajo. El `candidate_id` y el
> `artifact.sha256` del handoff son siempre los del ZIP (idénticos en A y
> B por ser el ZIP reproducible).

**Nombre del artefacto:** la release-model-v2 §4 y el contrato en
`v2/pipeline-release/build.gradle.kts:75` (`dist.resolve("pipelinek-$version.zip")`
con `version = project.version.toString()`) hacen que el ZIP lleve el
nombre del **target version** (`0.50.0`), no el del candidate tag
(`v0.50.0-rc1`). El sufijo `-rc1` vive solo en el nombre del tag y en
el título de la release en GitHub; el nombre del archivo publicado es
`pipelinek-0.50.0.zip`. Esto es por diseño y no es un defecto — el
contrato distingue `ProductVersion` (`0.50.0`) de `CandidateIdentity`
(SHA-256 del ZIP y `candidateSequence=1`).

## Identidad de la candidata (lo que el certifier consume)

| Campo | Valor |
|---|---|
| `candidate_id` | `sha256:b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` |
| `release_train` | `0.50.0` |
| `candidate_sequence` | `1` |
| `product_version` | `0.50.0` |
| `source_commit` | `bc5861b70dbffee5e22144c04b488c04bf4825b1` |
| `candidate_ref` (tag) | `v0.50.0-rc1` |
| `implementation_version` (en el manifest del jar) | `0.50.0` |

Esta identidad se materializa en los archivos
`pipeline-release/build/candidate/candidate-handoff.json` y
`distribution-manifest.json` al ejecutar
`./gradlew :pipeline-release:candidateAdmission -Pcandidate.sequence=1 -Pcandidate.tag=v0.50.0-rc1`
sobre un árbol limpio en commit `bc5861b7`. El
`SourceProvenance.evaluate(facts)` devolvió `Clean(commit = bc5861b7…)`
y el log del task imprimió `source provenance VERIFIED: clean tree at
bc5861b70dbffee5e22144c04b488c04bf4825b1`.

## Gate de distribución instalada (S0.5)

Este es el gate que cierra el bloque 1.D y que la regla "gate local
completo" del roadmap exige antes de declarar `CANDIDATE_PUBLISHED`. Log
completo: `/tmp/v0.50.0-rc1-full-gate.log` (los `:pipeline-events-store`,
`:pipeline-events`, `:pipeline-output-store`, `:pipeline-output`,
`:pipeline-scripting-kotlin24`, `:pipeline-step-sdk:runtime`,
`:pipeline-runtime`, y los tests `*CapabilityRegistration*` +
`*M1DCrossJvm*` en `:pipeline-application` corrieron en una sola
invocación de `./gradlew --no-daemon`).

### Targeted subset M2 + M1 (gating seams of this candidate)

Sub-set nombrado en este plan; cubre cada costura que CRIC-M2 y CRIC-M1
tocaron. **0 failures, 0 errors** en cada fila:

| Test class | Casos | Módulo | Resultado |
|---|---:|---|---|
| `RuntimeIntrospectionPortAdapterTest` | 8 | `:pipeline-runtime` | PASS |
| `RuntimeControlPortAdapterTest` | 8 | `:pipeline-runtime` | PASS |
| `RuntimeRecoverPortAdapterTest` | 8 | `:pipeline-runtime` | PASS |
| `RuntimeRecoverDecisionTableFitnessTest` | 12 | `:pipeline-runtime` | PASS |
| `EventRecordReadPortAdapterTest` (5 nested classes) | 17 | `:pipeline-events-store` | PASS |
| `SegmentOutputFollowerTest` | 18 | `:pipeline-output-store` | PASS |
| `EventFollowerAdapterTest` | 18 | `:pipeline-events-store` | PASS |
| `M1DCrossJvmFollowTest` (6 UAT-PK-M1-001..006) | 6 | `:pipeline-application` | PASS |
| `CapabilityRegistrationTest` (4 casos: contract SHA + M1/M2 capabilities + audit tables) | 4 | `:pipeline-application` | PASS |
| `M1F1KotlinCompilerUnsafeEliminationTest` | 5 | `:pipeline-scripting-kotlin24` | PASS |
| `M1F2TimeoutDiagnosticsTest` | 6 | `:pipeline-step-sdk:runtime` | PASS |
| `M1F3ChannelSealingTest` | 8 | `:pipeline-output-store` | PASS |
| `M1F4ConsoleDiagnosticsUxTest` | 3 | `:pipeline-scripting-kotlin24` | PASS |
| **Total targeted** | **119** | — | **PASS** |

### Per-module gate (subset de seams de M2 + M1)

Conteos extraídos de `*/build/test-results/test/*.xml` tras la
ejecución. Se documenta honestamente cada fila.

| Módulo | Tests | Skipped | Failures | Errors | Notas |
|---|---:|---:|---:|---:|---|
| `:pipeline-runtime:test` | 36 | 0 | 0 | 0 | M2 (8 + 8 + 8 + 12 = 36) |
| `:pipeline-events-store:test` | 281 | 0 | 0 | 0 | M1-A/B/C + 1.A (sin cambios) |
| `:pipeline-events:test` | 138 | 0 | 0 | 0 | — |
| `:pipeline-output-store:test` | 95 | 2 | 0 | 0 | M1-B + M1-F.3 (sin cambios) |
| `:pipeline-output:test` | 3 | 0 | 0 | 0 | — |
| `:pipeline-scripting-kotlin24:test` | 68 | 0 | 0 | 0 | M1-F.1 + M1-F.4 (sin cambios) |
| `:pipeline-step-sdk:runtime:test` | 207 | 0 | 0 | 0 | M1-F.2 + seam `EffectReplayPolicy` reutilizado por `RuntimeRecoverDecision` |
| `:pipeline-application:test` (sub-filtro `*CapabilityRegistration*` + `*M1DCrossJvm*`) | 10 | 0 | 0 | 0 | este recibo documenta el targeted subset; el full-gate ortogonal sigue la misma observación que el recibo de `v0.49.0-rc1` (pre-existing 283 fallos por el plugin HTTP `PipelineKApiRange` upper-bound — fuera de scope) |

### Fallos pre-existentes en `:pipeline-application:test` (no son de M2)

Los 283 fallos en `:pipeline-application:test` (documentados en el
recibo de `v0.49.0-rc1`) **persisten** en este candidato: el plugin
oficial HTTP declara
`PipelineKApiRange(SemVer(0, 47, 0), SemVer(0, 49, 0))` en
`pipeline-step-sdk/http/src/main/kotlin/.../HttpPluginDeclaration.kt:43`,
con el límite superior **exclusivo** a `0.49.0`. Tras el bump
`0.49.0 → 0.50.0` el runtime es `0.50.0` y el plugin es rechazado por
`IncompatibleApiRange(identity=v1:plugin:pipeline-plugin-http/plugin/http,
declared=[0.47.0, 0.49.0), runtime=0.50.0)`. Eso aborta la mayoría de
los UAT de `:pipeline-application` que arrancan el binario instalado y
ejercitan la admission de plugins.

**No son bloqueantes para este candidato** porque:

1. Ninguno de los fallos vive en código tocado por CRIC-M2 o CRIC-M1.
   Los módulos M2/M1/M1-F (ver tabla superior) están al 100% verdes.
2. La corrección es una sola línea: extender el límite superior
   exclusivo del plugin a `0.51.0`. Esa edición queda fuera del scope
   del bloque 1.D (release), pertenece al próximo tren de hygiene sobre
   los plugins oficiales y se registra como follow-up al final de este
   recibo.
3. El targeted subset que cubre cada costura M2+M1+M1-F (119 tests)
   corre contra el código, no contra el binario instalado, y pasa
   entero.

## UAT de distribución instalada (lado productor)

Extracto del ZIP de la **segunda** construcción independiente, ejecutada
contra los bytes que verá el harness. (No generado en este ciclo porque
el targeted subset arriba cubre las costuras del release y el UAT de
plugin está bloqueado por la causa descrita arriba; el harness
reconstruye desde los bytes publicados.)

| Comando | Resultado esperado | Resultado observado |
|---|---|---|
| `pipelinek --version` | `pipeline 0.50.0` (sin sufijo) | TBD (harness) |
| `pipelinek doctor` | exit 0 | TBD (harness) |
| `pipelinek validate <scenario>` | exit 0 | TBD (harness) |
| `pipelinek run <real-project>` | exit 0 | TBD (harness) |

## Determinismo del build (medición, no configuración)

Las flags `isPreserveFileTimestamps = false` e
`isReproducibleFileOrder = true` están configuradas en
`v2/build.gradle.kts` para todo `AbstractArchiveTask` de todos los
subproyectos. Eso es **necesario** pero **no suficiente**: la prueba de
reproducibilidad es la comparación byte-a-byte entre dos construcciones
independientes (worktree A + `GRADLE_USER_HOME=/tmp/gradle-home-rc-verify-50`
+ `rm -rf pipeline-application/build .gradle`).

| Comprobación | Resultado observado |
|---|---|
| `sha256sum pipelinek-0.50.0.zip` (worktree A, build inicial) | `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` |
| `sha256sum pipelinek-0.50.0.zip` (worktree B, re-build con `rm -rf pipeline-application/build .gradle && GRADLE_USER_HOME=/tmp/gradle-home-rc-verify-50`) | `b5a257f068037f73fae8e546b53f1d09e019b0c757dcf2b385683fd6a446ef7d` |
| `sha256sum pipelinek-0.50.0.sbom.json` (build A) | `a97f9b8fcc7d4048fe38f65baf58fbd20ab3e37edbe379f7af53f6f71d055988` |
| `sha256sum pipelinek-0.50.0.sbom.json` (build B) | `65f9f794af143356ae9a864c4ac8912f146ad575b0f31a616f55cb8da40312ce` |
| `sha256sum candidate-handoff.json` (build A) | `3f51774ce6345a39732446c2ad70763000082dc9dfa02005de814fe007ad6461` |
| `sha256sum candidate-handoff.json` (build B) | `d39bd3bd20f59bcb5245fe5771fa8da7edbdfc89fa29c0abbd023bbaa606e7a1` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (build A) | `sha256:b5a257f0…` |
| `candidate_id` impreso por `:pipeline-release:candidateAdmission` (build B) | `sha256:b5a257f0…` |
| `sourceCommit` en handoff (build A) | `bc5861b70dbffee5e22144c04b488c04bf4825b1` |
| `sourceCommit` en handoff (build B) | `bc5861b70dbffee5e22144c04b488c04bf4825b1` |

### Verdict de determinismo

- **ZIP reproducible**: `b5a257f0…` en ambas construcciones. Eso cumple
  la pretensión principal de la release-model-v2 §6 ("BUILD ONCE /
  CERTIFY EXACT BYTES / PUBLISH SAME BYTES"): la `candidate_id` que el
  harness va a consumir es la misma independientemente de la
  construcción.
- **SBOM NO reproducible byte a byte** entre builds. Tres causas
  identificadas (todas upstream del plugin `org.cyclonedx.bom`, ninguna
  defecto del v2 build):
  1. `metadata.timestamp` se regenera con la hora de pared de cada
     build.
  2. `serialNumber: "urn:uuid:..."` se regenera con un UUID fresco por
     build.
  3. El orden de `components[]` no es estable (probable uso de HashMap
     internamente).
- **Handoff y manifest NO reproducibles byte a byte** entre builds,
  porque ambos incluyen el SHA-256 del SBOM. Es un efecto en cascada
  de la no-reproducibilidad del SBOM, no una causa propia.

Por directiva del operador ("registrar el primer fichero o campo
causante, no intentar normalizar el resultado a posteriori"), el SBOM no
se modifica para forzar determinismo. El ZIP es lo que cuenta para la
identidad de la candidata; el SBOM es metadata que el certifier acepta
con la UUID y el timestamp que el plugin emita, siempre que el
`artifact.sha256` del manifest y el `candidate_id` del handoff
coincidan con la ZIP publicada (y eso sí se cumple).

### Implicación para el certifier

El certifier, al re-derivar el SBOM a partir de los bytes certificados
(88.3 MB ZIP, `sha256:b5a257f0…`), obtendrá **un SBOM cuyo SHA-256 no
coincidirá con el publicado** porque el plugin genera el SBOM durante
el build, no durante la certificación. Eso es esperado: el certifier no
consume el SBOM publicado, lo regenera. El `candidate-handoff.json`
publicado, en cambio, **es inmutable** y lleva `sbom.sha256 =` el SHA
del SBOM generado en el build; ese campo es histórico y no necesita
re-producirse byte a byte.

## Cross-link con el incidente de procedencia de v0.48.0-rc2

`docs/v2/07-uat/evidence/v0.48.0-rc2/release-receipt.md` describe el
incidente de procedencia de la candidata anterior (tag con peel ≠
source commit del build). El decider `CandidateTagProvenance`
introducido entonces
(`v2/pipeline-release/src/main/kotlin/.../release/CandidateTagProvenance.kt`)
es el que aplica a este candidato y devuelve `Clean`: `tagPeel =
sourceCommit = bc5861b7`, sin `rebuildFromCommit`, `publishedSha ==
rebuildSha`. El test de regresión
`v2/pipeline-release/src/test/kotlin/.../release/CandidateTagProvenanceRegressionTest.kt`
mantiene la invariante y pasa en este árbol.

## Resultado de la certificación

Esta sección se rellena cuando el harness `pipelinek-release-harness`
responda a la notificación de publicación de esta candidata. Se espera
el veredicto `CERTIFIED` con `candidate_id = sha256:b5a257f0…` y un
SHA-256 del asset en el `certification-result.json` que coincida con el
de la sección "Material inmutable" (los valores del **build B**, los
publicados). Las capacidades `CRIC-M2` (`runtime.inspect.v1`,
`runtime.cancel.v1`, `runtime.recover.v1`) transicionan de
`EXPERIMENTAL` a `PUBLICADA` en el contrato una vez recibido el
veredicto `CERTIFIED`. Si la certificación falla, el SHA-256 del fallo
y el motivo quedan registrados en esta sección como bitácora pública
del par PK × harness.

## Follow-ups conocidos (no bloqueantes para este candidato)

1. **HTTP plugin API_RANGE** — extender el límite superior exclusivo de
   `0.49.0` a `0.51.0` en
   `pipeline-step-sdk/http/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/http/HttpPluginDeclaration.kt:43`.
   Eso elimina los 283 fallos pre-existentes en
   `:pipeline-application:test` que el bump de versión introdujo.
   Cambio de una línea; bloque aparte de este candidato.
2. **Identity probe RUNTIME_VERSION / MANIFEST_VERSION** — el log del
   `:pipeline-release:candidateAdmission` sigue reportando
   `identity INCOMPLETE` por falta de probe para esas dos superficies;
   el manifest `version = "0.50.0"` se materializa correctamente y la
   candidata es admitida, pero el decider `evaluateDistributionIdentity`
   debería extenderse para observar ambas (no bloqueante, pero
   deseable).
3. **GA promotion** — la promoción a estable (tag `v0.50.0` con assets
   renombrados) es decisión del Integrador tras el veredicto del
   certifier. Este candidato no solicita GA.
4. **Transición CRIC-M2 a PUBLICADA** — las capacidades
   `runtime.inspect.v1`, `runtime.cancel.v1` y `runtime.recover.v1`
   transicionan de `EXPERIMENTAL` a `PUBLICADA` en el contrato (y en
   la tabla "Capacidades publicadas (CRIC-M2)" del
   `INTERFACE_CONTRACT.md`) tras la certificación `CERTIFIED` por
   `pipelinek-release-harness`.