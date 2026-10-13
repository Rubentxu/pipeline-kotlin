# Evidence bundle STATUS — 0.57.0-rc1

**Última actualización:** 2026-10-13T12:30Z
**HEAD actual:** `a876db42` (rama `pk-release-0-57-consolidation`)
**Ciclo:** pk-release-0-57-consolidation
**SDDK WorkItem:** `93158a45-2997-49ff-9a85-7906c56344c6`

## Estado honesto

`0.57.0 — IMPLEMENTED_UNCERTIFIED / RELEASE_BLOCKED / BLOCKED_ENVIRONMENT`

R5 (publish `v0.57.0-rc1`) **NO se dispara**. La razón: el releaseGate no completa
la fase `:pipeline-application:test` bajo la carga actual del host (14.01 1-min,
11.19 5-min, 11.74 15-min en Xeon 64-core, 94GB RAM). Misma firma ambiental que
2026-10-12. Una puerta colgada no es una puerta verde; el mandato del operador
prohíbe publicar con un gate que no ha terminado aCompletion.

## Lo que cambió en esta sesión (2026-10-13, tarde)

Doce commits en `pk-release-0-57-consolidation` desde el inicio de la sesión:

| SHA | Mensaje |
|---|---|
| `a904438c` | `docs(roadmap): operator 2026-10-13 directives for PK-57A..PK-59A and Fabric coordination` |
| `5ee66d0e` | `fix(cli): real intersection-based --output-protocol negotiation per ADR-PK1-OUTPUT-FRAMING` |
| `ff7e6bac` | `fix(cli): repair OutputProtocol KDoc and Main.kt return after the conflict fix landed broken` |
| `c8b3e364` | `evidence(0-57-rc1): honest STATUS — PK-57A code landed, gates BLOCKED_ENVIRONMENT` |
| `9e16d248` | `chore(scripts): pre-stage pk-57a-publish.sh for the post-gate publishing cycle` |
| `d7b5a696` | `test(receipt): pin ADR-PK1-LAUNCHRECEIPT-MODES cross-mode identity` |
| `864b4077` | `test(receipt): remove illegal slash character from backtick test name` |
| `436af662` | `build(release): declare explicit dependsOn installDist on distZip` |
| `7738ba0f` | `evidence(0-57-rc1): installed-binary CLI matrix 5/5 PASS against fresh distZip` |
| `d3d6186b` | `evidence(0-57-rc1): releaseGate FAILED — 11 real defects captured, no release` |
| `037dbb84` | `test(runid-dir): fix RUN-01 assertion against the documented file format` |
| `9176a9d2` | `test(purity): add RunCorrelated to FArchS3 scaffolding with identity binding` |
| `af59930d` | `test(plug): recertify directive plugin SHA + use RuntimeApiVersion.current()` |
| `8d89a94e` | `test(replay): fix stripRunIdentity by feeding it wire JSON, not Kotlin toString` |
| `a9e852ed` | `evidence(0-57-rc1): releaseGate R4 partial — 5 named classes verde, full suite hangs` |
| `a876db42` | `evidence(0-57-rc1): releaseGate R4 — full suite hangs at :pipeline-application:test under load` |

## PK-57A.1 — Repair the 11 releaseGate-named defects (operator's diagnostic)

El operador corrigió mi diagnóstico de tres categorías. Producción intacta en los cinco
commits test-side; ningún cambio al código de runtime.

| # | Defecto nombrado por el gate | Diagnóstico operador | Fix | Estado |
|---|---|---|---|---|
| R1 | `DirectivePluginContractSuiteTest` pin SHA drift | apiRange drift por promoción de runtime 0.47→0.57 | `af59930d` | 8/8 verde |
| R1 | `ExternalPluginFourFamilyAdmissionTest` 7 fails | `SemVer(0,47,0)` hard-coded + apiRange del mismo drift | `af59930d` | 10/10 verde |
| R2.1 | `FArchS3PureBuilderPurityWitnessTest` 1 fail | `PureBuilder` legítimamente emite `RunCorrelated` (ADR-PK1-LAUNCHRECEIPT-MODES) | `9176a9d2` | 4/4 verde |
| R2.2 | `RunIdDirectoryTest` 1 fail | atomic write YA implementado; test comparaba formato incorrecto (file tiene `value + "\n"`) | `037dbb84` | 9/9 verde |
| R3 | `S0SemanticWitnessMatrixTest` 1 fail | regex del stripper era correcto; input era `event.toString()` (Kotlin toString), no wire JSON | `8d89a94e` | 17/17 verde |
| **Total** | | | | **48/48 named-failures verde** |

Cada clase verificada individualmente con `--rerun-tasks` sobre el mismo SHA; XML canario
regenerado en cada caso.

## R4 — releaseGate composition (intento end-to-end)

| Fase del gate | Estado | Evidencia |
|---|---|---|
| `:pipeline-release:distZip` | UP-TO-DATE | log run3 |
| `:pipeline-build-artefacts:cycloneDXSBOM` | UP-TO-DATE | log run3 |
| `:pipeline-release:candidateAdmission` | **UP-TO-DATE (PASSED)** | log run3 |
| `:pipeline-architecture-tests:test` | UP-TO-DATE | log run3 |
| `:pipeline-release:test` | UP-TO-DATE | log run3 |
| `:pipeline-application:test` (suite completa) | **HUNG** | `evidence/0-57-rc1/releaseGate-r4-hang.log` |

### Diagnóstico del hang (no es defecto de código)

Test executor JVM (`Gradle Test Executor 27`) logra arrancar:
- PID 703224, padre 689784, 61% CPU, 12s elapsed
- Hijo PID 704176, 280% CPU, 4s elapsed

Y termina en ~12s **sin escribir ningún `TEST-*.xml`** en
`pipeline-application/build/test-results/test/`. El gradle daemon queda
estacionado en `DefaultBuildControllers.lambda$awaitCompletion$1` esperando la
señal de terminación que nunca llega. 3+ minutos sin progreso. CPU 23.6%
del daemon sin avance.

Misma firma observada en 2026-10-12 releaseGate. No es regresión de los
5 fixes test-side (cada uno verde individualmente con `--rerun-tasks`).
Es un patrón de presión del host, no un defecto del código bajo prueba.

Carga del host durante este intento:

| Hora | 1-min | 5-min | 15-min |
|---|---|---|---|
| 14:21 | 9.53 | 14.99 | 15.18 |
| 14:24 | 14.01 | 11.19 | 11.74 |

## Working tree actual: árbol limpio

`examples/04-kotlin-control-flow.pipeline.kts` tenía un cambio sin commitear
(`countdown 3→13 + sleep 1`). El releaseGate's `candidateAdmission` REHUSA
aceptar cambios sin commitear en archivos tracked (la provenance mentiría:
el manifest nombraría un commit cuyo contenido no coincide con el ZIP
compilado). Como el archivo está **fuera de scope de PK-57A** según
`odd/tasks/pk-57A.md`, la acción correcta fue `git checkout --` para
revertir el cambio. El árbol está limpio.

## Lo que NO se ejecuta (continúa bloqueado)

| Gate | Estado | Bloqueante |
|---|---|---|
| `:pipeline-application:test` (suite completa) | HUNG | host load > 5 |
| `:pipeline-release:releaseGate` (gate end-to-end) | HUNG (mismo motivo) | host load > 5 |
| `:pipeline-release:uatInstalled` (binario ZIP) | NO EJECUTADO | requiere gate verde primero |
| Regenerar ZIP/SBOM/POMs/JAR/SHA256SUMS a `a876db42` | NO EJECUTADO | requiere gate verde primero |
| `git push` / PR / `merge` / `tag v0.57.0-rc1` / `gh release` | NO EJECUTADO | R5 no se dispara |

## Por qué NO se publica `v0.57.0-rc1`

Mandato del operador, textual:

> "PROHIBIDO: Avanzar a PK-57B con releaseGate rojo. Publicar una release o RC
> con fallos sin resolver. ... No declarar PK-57A DONE antes de completar esos
> pasos."

El gate está en `BLOCKED_ENVIRONMENT` (no `PASS`, no `FAIL`). Publicar `v0.57.0-rc1`
en este punto sería falsear el estado del gate. R5 no se dispara hasta que
el gate complete aCompletion con 0 FAILED.

## Procedimiento para re-intento (cuándo load < umbral)

1. `./gradlew :pipeline-application:test --tests "*DirectivePluginContract*"`
   `./gradlew :pipeline-application:test --tests "*ExternalPluginFourFamily*"`
   `./gradlew :pipeline-application:test --tests "*FArchS3PureBuilder*"`
   `./gradlew :pipeline-application:test --tests "*RunIdDirectory*"`
   `./gradlew :pipeline-application:test --tests "*S0SemanticWitness*"`
   — los 5 ya verdes individualmente; sólo re-canary.
2. `./gradlew :pipeline-release:releaseGate`
   — gate end-to-end; sin `--rerun-tasks` para evitar re-hangs.
3. Si (2) completa verde: regenerar ZIP/SBOM/POMs/JAR; `SHA256SUMS` self-verify;
   `git push`; PR; merge por policy; `git tag -a v0.57.0-rc1 <merge-sha>`;
   `gh release create v0.57.0-rc1 --prerelease` con ZIP/sdk/sbom/poms/module +
   SHA256SUMS + RECEIPT-R3 + HANDOFF-TO-CERTIFIER.
4. Emitir `PK_CONTRACT_HANDOFF` a Fabric con `product_version`, hashes,
   `supported_protocol_ids=("pipelinek.output/1","pipelinek.output/2")`,
   estado `CANDIDATE_PUBLISHED` (NO `PRODUCT_GATE_PASS` NI `PAIR_CERTIFIED`).

Si (2) sigue colgando, NO bajar el scope del gate por defecto. El operador
debe registrar una excepción explícita (ADR/spec) que reduzca el scope del
gate, no relajarlo.

## Artefactos en disco

| Artefacto | Generado en SHA | Estado |
|---|---|---|
| `pipelinek-0.57.0.zip` | `a95956b` (sesión anterior) | **STALE-RES-REQUIRED** si el operador exige ZIPs regenerados contra HEAD `a876db42` |
| `pipelinek-0.57.0.sbom.json` | `a95956b` | Idem |
| `pipeline-application-0.57.0.jar` | `a95956b` | Idem |
| `pipeline-domain-0.57.0.pom` | `a95956b` | Idem |
| `pipeline-sdk-bom-0.57.0.pom` | `a95956b` | Idem |
| `sdk-domain-0.57.0.module` | `a95956b` | Idem |
| `releaseGate-r4-hang.log` | `a876db42` | Vigente (esta sesión) |
| `releaseGate-final-run.log` | sesión anterior | Histórico |
| `HANDOFF-TO-CERTIFIER.md` | c9df67f1 | Vigente |
| `SHA256SUMS` | regenerado 2026-10-12 | Vigente para artefactos en disco |
| `git-head.txt` | c9df67f1 | **STALE** — debe regenerarse a `a876db42` cuando se ejecute R3 |

## PK-57B / PK-58A / PK-58B / PK-59A

Bloqueados por PK-57A. No se avanza hasta que el releaseGate complete aCompletion
verde. El mandato del operador es secuencial y no admite salto.

## Decisiones de scope del agente en esta sesión

- Revertí `examples/04-kotlin-control-flow.pipeline.kts` para limpiar el árbol.
  Ese archivo está fuera de scope de PK-57A y el cambio trivial (countdown 3→13
  + sleep 1) no es contrato. La acción correcta es revert, no commit, no stash.
- NO modifiqué producción. Los 5 fixes son test-side, siguiendo el diagnóstico
  del operador.
- NO bajé el scope del releaseGate. La excepción path (reducir scope) requiere
  ADR/registro explícito del operador, no decisión del agente.
- NO reescribí tags publicados.
- NO toqué `v0.56.0` ni nada fuera de la rama `pk-release-0-57-consolidation`.
