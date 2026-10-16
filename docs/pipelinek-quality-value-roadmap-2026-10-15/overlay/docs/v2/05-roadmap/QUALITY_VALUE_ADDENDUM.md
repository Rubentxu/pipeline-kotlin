# Adenda QUALITY VALUE — bloques BQ1..BQ5 (propuesta; integrar en ROADMAP.md)

**Fecha de elaboración:** 2026-10-15. **Baseline de análisis:** `main@29981033`; volver a medir HEAD al arrancar cada hito.
**Autoridad:** subordinada a `ROADMAP.md` §0 y §13 y `AGENTS.md`; no sustituye RP-0..RP-9, B0..B7 ni el backlog. Los hitos BQ se ejecutan como **mejoras transversales de entrega/validación** entre bloques autorizados, no como una segunda cola funcional.

## Principios vinculantes

1. **Valor visible primero.** Cada bloque deja un comando/DSL/función de producto mejor y una release candidate publicada, no solo un documento o una matriz de tests.
2. **FP Haskell-like en Kotlin.** Núcleo funcional, datos inmutables, ADTs `sealed`, transiciones expresadas como funciones puras, intérpretes I/O en bordes, dependencias explícitas. Preferir composición, folds y algebraic data a árboles `if`/strings. Ningún `runBlocking`, `File`, `ProcessBuilder` ni lectura de Git dentro de decisiones.
3. **Ontología única.** `Capability` (lo prometido), `Contract` (la ley), `Scenario` (el testigo), `Evidence` (observación), `Verdict` (decisión), `ReleaseCandidate` (artefacto) son conceptos diferentes. No tratar un nombre de clase test como un contrato ni un test XML como certificación.
4. **Testing proporcional.** Desarrollo = afectados HF0/HF1; integración = frontera relevante + ABI/fitness + negativo crítico; RC = matriz completa exigida y certifier externo según ADRs. Selección fail-closed para producción desconocida; docs-only sin coste de distribución.
5. **Test de ciclo caduca.** Cada UAT/E2E/AAT recién creada por el agente es temporal; al cerrar hito se conservan expectativas como contrato/ejemplo y se borra el script temporal y sus fixtures exclusivas tras pruebas verdes frescas. Las UAT heredadas no se borran antes de equivalencia externa comprobada.
6. **Release veraz.** `CANDIDATE_PUBLISHED` únicamente tras integración `main`, tag RC alcanzable, ZIP reproducible + checksums/SBOM/manifest y publicación en remoto; no afirmar CERTIFIED/STABLE sin `pipelinek-release-harness`.

## Mapeo a trabajo ya existente

| Bloque | Riesgo/roadmap superior | Referencias normativas exactas | Cambios principales |
|---|---|---|---|
| BQ1 | RP-0/RP-1, B0/B1: verdad del gate | `PIPELINE_TEST_HARNESS.md`, `TEST_STRATEGY.md`, `ADR-0072`, `CERTIFICATION_PROTOCOL.md` | Plan de impacto total, evidencia no vacía y fidelidad honesta |
| BQ2 | RP-2/RP-4, B3: feedback efectivo | `PIPELINE_TEST_HARNESS.md`, `EXECUTABLE_SCENARIO_CORPUS.md`, `ADR-0071`, `ROADMAP.md` §13.6 | Build rápido por fronteras, aislar `installDist` y procesos |
| BQ3 | RP-3/RP-6, B1/B2/B3: simplificación de semántica | `STEP_CONSTITUTION.md`, `DSL_SPEC.md`, `ADR-0070`, `ADR-0103` | Una vertical del runtime/DSL con ADTs y decisiones puras, sin reescritura masiva |
| BQ4 | RP-1/RP-5, B3/B4: aceptación y artefactos | `PRODUCTION_READY_UAT_MATRIX.md`, `CERTIFICATION_PROTOCOL.md`, `ADR-0074`, `ADR-0099` | Test temporal orquestado, evidencia verificable, cierre y RC |
| BQ5 | RP-4/RP-5, B4/B7: consolidación y deuda | `RESPONSIBILITY_MIGRATION_ROADMAP.md`, `DISTRIBUTION_RELEASE_SPEC.md`, `ADR-0099`, `ADR-0105` | Equivalencia externa y retirada de suites antiguas, reducción medible de tiempo |

> **Precedencias:** BQ1 → BQ2 → BQ3 → BQ4 → BQ5. Se permiten pruebas exploratorias paralelas pero no crear una segunda autoridad ejecutora. No cambiar la secuencia RP del roadmap padre. Si ya existe una implementación completa con evidencias en HEAD, marcar BQ correspondiente `ALREADY_DONE` con prueba y no recrearla.

## BQ1 — Decir la verdad sobre lo que se ha probado

**Valor para desarrollador:** `validationPlan` produce decisión rápida, determinista y explicable para cualquier modificación relevante; `validation-gate.py` rechaza evidencia vacía, obsoleta o infiel. Reducir investigación de verdes falsos.

**TODO de entrega:**

- [ ] Auditar `ImpactResolver.isProductionPath()` contra TODOS los archivos con efecto: `src/main` Kotlin/Java, recursos `META-INF/services`, `*.proto`, Gradle `settings`, scripts de build/distribución, cambios de ABI/manifest y fixtures cuyo contenido rige un contrato. ADT `ChangedInput` clasificado; cambios reconocidos pero sin contrato → `Refused(UnclassifiedInput)`; cambios de tests/harness → fitness del harness, no omisión automática.
- [ ] Reconducir las 8 filas de `ContractCatalogue` hacia un catálogo extensible de **capacidad→fuentes→nivel mínimo→suites**, manteniendo las leyes bidireccionales (`ContractCatalogueFitnessTest`) y evitando catálogo de una fila por cada fichero. No resolver path con `it.endsWith(path)` si produce colisiones entre propietarios.
- [ ] Extraer `EvidenceReader` hacia `ReportObservation` puro + `XmlReportReader` impuro. Validar `tests>0`, `tests-skipped>0`, `errors=failures=0`, y clase/paquete, archivo por invocación. Rechazar no-execución, XML corrupto y reportes de otra invocación.
- [ ] `requiredFidelity` debe ser compatible HF0..HF6 de ADR-0072. Un HF3 no puede salir PASS por el mero hecho de ejecutar una clase HF2. Emitir resultado tipado `ObservedBoundary`, no `Set<String>` sin procedencia.
- [ ] No desactivar los guards existentes ni renombrar tests para hacerlos pasar; demostrar mutaciones que detecten reporte 0 tests, all skipped, `buildCommit` erróneo, recurso de servicio no clasificado.

**Pruebas de ciclo efímeras (AAT/UAT):** AAT-BQ1-01 muta XML a cero tests; AAT-BQ1-02 coloca 100% skipped; AAT-BQ1-03 altera `META-INF/services`; UAT-BQ1-01 recorre cambio de producción nuevo y comprueba negativa razonada; AAT-BQ1-04 invalida SHA. Ejecutarlas contra el guard real. **Promover** solo tests puros del decider y fitness del mapa. Borrar scripts de mutación/fixtures temporales antes del SHA de prerelease.

**STOP:** ningún cambio de producción desconocido puede reportarse como `VALIDATION DISCHARGED`; ninguna evidencia de HF inferior satisface la requerida.
**Salida:** planner/CLI usable, no falso verde de las 4 mutaciones, suites funcionales rápidas; `main`+`v<next>-rcN` reales y recibo con SHA. No cerrar si el gate no se puede ejecutar.

## BQ2 — Bajar el tiempo de feedback sin bajar garantías

**Valor visible:** desarrollo normal compila/probar sus contratos sin instalar CLI ni compilar plugins externos; el desarrollador puede pedir `fast`, `integration` o `release` sin adivinar tareas.

**TODO de entrega:**

- [ ] Inventariar DAG Gradle real con `--dry-run` y medición wall-clock/JVM/GC de casos representativos: edición dominio, compilador, SDK, CLI y documentación. Baseline medido, no estimación.
- [ ] Separar tests con fuente de verdad: `:pipeline-application:test` para contratos in-process rápidos; tarea/source set específico para ejecutores de binario instalado (p.ej. `installedContractTest`) con dependencias `installDist`; tareas `restartContractTest` y `sandboxContractTest` solo si aportan frontera distinta. Nombres propuestos: adaptar a plugin y Gradle actuales; no imponer sin compilar.
- [ ] Quitar dependencia global de `:pipeline-application:test` de `installDist` y de 3 builds externos SOLO cuando todas las clases que las necesitan se hayan reasignado; las suites externas siguen siendo obligatorias en su gate de release.
- [ ] Desacoplar `forkEvery=1` y `maxParallelForks=1` del conjunto rápido; conservar serialización/aislamiento solo donde `FileBacked...`, subprocesses o bloqueo real lo exigen. Medir 2 ejecuciones de tareas rápidas en cambios sucesivos sin `--rerun-tasks` global.
- [ ] Resolver fixture de caché `UatLocal010SmokeE2ESandboxOfflineTest` sin dependencia implícita del orden entre clases.
- [ ] Agregar un planificador que agrupe por módulo y frontera y no ejecute varios Gradle sobre el mismo checkout con lock concurrente. No crear un scheduler paralelo a Gradle.

**Pruebas efímeras:** UAT-BQ2-01 cambio de dominio ejecuta solo subconjunto correcto; AAT-BQ2-01 simula fallo `installDist` y verifica que no afecta HF0; AAT-BQ2-02 fuerza runner fallido, plan rechaza; UAT-BQ2-02 una única distribución instalada comprueba 3 pasos y un error de CLI. **Promover** tests puros del plan/dispatch y un canario mantenido por la frontera externa; borrar scripts UAT/AAT de ciclo tras equivalencia.

**Presupuesto orientativo a **medir** (no promesa):** comparación p50/p95 de tiempo en 5 repeticiones, con suite invariable y mismo host; objetivo reducción ≥40% en feedback de cambio de dominio, cero pérdida de mutaciones relevantes. Si el coste supera el presupuesto, identificar tareas de DAG culpables.
**Salida:** comandos rápidos y de release documentados, gate completo no debilitado, prerelease nueva integrada en `main`.

## BQ3 — Una vertical FP real: semántica pura y efectos periféricos

**Valor visible:** una ruta concreta de DSL/runtime (priorizar la que tenga incidencia activa del roadmap) se puede razonar, simular y depurar sin poner en marcha el subprocesso. Disminuye errores de replay/resultado y complejidad del coordinador.

**TODO de entrega:**

- [ ] Seleccionar **UNA** familia de defecto productivo medida, no una refactorización por LOC: p.ej. `ConsoleOperationSelection`, `ShExecution` o la transición `retry/waitUntil` que tenga issue activa. Identificar dueño real y por qué los tests existentes no demuestran propiedad.
- [ ] Formular vocabulario cerrado `Input`, `State`, `Decision`, `EffectIntent`, `Failure` reutilizando tipos de `pipeline-domain` / `pipeline-runtime-api`, sin strings de outcome mágicos y sin segunda autoridad de replay (ADR-0103).
- [ ] Implementar `decide(state,input): Decision` puro; interpreter recibe `Clock`, almacenamiento o proceso explícitos; separar `observe` de `decide` de `apply`. Escoger fold total y transiciones exhaustivas; en estado durable desconocido rechazar (fail closed).
- [ ] Reducir copias equivalentes de `if`/validación entre CLI, coordinator y Step handler **solo para la vertical seleccionada**. Eliminar rutas duplicadas y llamadas inalcanzables demostradas por fitness/mutación, no por intuición.
- [ ] Verificar mismos eventos/outcome/bytes de antes, más caso negativo que antes fallaba. Documentar ABI/semver si cambia el contrato; si requiere breaking change → autorización explícita.

**Temporales:** UAT-BQ3-01 ejecutar script real de usuario; AAT-BQ3-01 mutar una transición `when` para crear outcome erróneo; AAT-BQ3-02 duplicar efecto en replay; AAT-BQ3-03 cambiar un enum wire desconocido. **Promover:** leyes de reducer, evento y no-replay en tests puros; pruebas de distribución al harness externo. Borrar UAT/AAT transitorias tras cierre.

**Salida:** característica demostrable (no solo módulos movidos), funciones puras legibles, paths duplicados reducidos en ámbito elegido; RC publicada tras integración `main`.

## BQ4 — Ciclos AAT/UAT efímeros con garantías de cierre

**Valor visible:** al abrir una feature, el agente obtiene un manifiesto de obligaciones y un runner de AAT/UAT temporal; al cerrar, puede demostrar qué se probó y *por qué no queda el código temporal*.

**TODO de entrega:**

- [ ] Construir en `v2/test-harness` un **modelo de ciclo**: `Draft→Running→EvidenceCaptured→Promoted→Closed` y resultados `Verified/Refused/Blocked` tipados. Shell separado en tooling/CLI; nada de estados persistentes en repositorio, salvo ADR/spec/contrato estable.
- [ ] Generar `CycleManifest` desde plan de impacto/capability (no inventar IDs de contrato): lista explícita de escenarios, frontera HF, mutación y futuro dueño de la garantía. No generar tests cuando ya hay un oráculo permanente válido.
- [ ] Runner sandbox temporal por ciclo, directorios únicos, timeout y limpieza de procesos descendientes, `no network` por defecto, no secretos, colector de evidencia inmutable con SHA de fuente y binario.
- [ ] `close` rechaza ciclos sin evidencia, con skipped, sin sustituto permanente o sin veredicto externo donde corresponde; ejecuta borrado acotado **solo** de ficheros temporales que figuren en el manifiesto (no glob sobre `src/test`).
- [ ] Retención: repositorio guarda referencias ligeras a contract IDs y a un digest/veredicto almacenado en artefacto externo; no commits enormes de logs ni estado de sesión.

**Temporales:** UAT-BQ4-01 proceso real contra ZIP, AAT-BQ4-01 intervención adversarial del runner, AAT-BQ4-02 borrado parcial interrumpido, AAT-BQ4-03 canario de fuga de credenciales. **Promover:** leyes de lifecycle/guard, test de integración del CLI y contract tests de lecturas de evidencia; borrar las pruebas de sesión antes de tag.

**Salida:** comando local documentado que prepara/ejecuta/cierra una UAT de feature y muestra eliminaciones auditables, RC integrada en `main`.

## BQ5 — Retirada heredada por equivalencia externa y limpieza real

**Valor visible:** proyecto mantiene menos test code/coste por cada cambio; certificación de distribución/restart/sandbox ya no bloquea el loop rápido del repositorio productor y continúa protegiendo releases en el harness externo.

**TODO de entrega:**

- [ ] Consumir el inventario de PR #140 sin asumir que esté mergeado; regenerar inventario **de todo `src/test`** en HEAD y fusionar ambos usando rutas/IDs. Clasificar nombres + inspección de `ProcessBuilder`, `AppBinSupport`, runners y scripts. Nunca asimilar 169 entradas a 169 pruebas redundantes.
- [ ] Clasificar cada test: `KEEP_CORE_CONTRACT`, `TEMPORARY_CYCLE`, `MIGRATE_EXTERNAL_PENDING`, `REDUNDANT_WITH_PROOF`, `TEST_FIXTURE`, `LEGACY_NON_V2`, `BLOCKED`. Exigir escenario, oráculo, contrato, fault fingerprint y cost/time medidos.
- [ ] Por oleadas: (a) duplicados claros de smoke (conservando mutación), (b) UAT de CLI/distribución, (c) kill/restart/sandbox/secrets (más crítica), (d) scripts y legacy V1, solo tras equivalencia. Validar mismo defecto con binario instalado y CandidateId; obtener receipt del harness externo.
- [ ] Cuando el protocolo histórico lo exija, **marcar `@Disabled("migrated...")` y no borrar** hasta aprobar la excepción/sucesión de política propuesta en ADR-PK-QV-003. Si se aprueba, eliminar fuentes/fixtures huérfanas en un commit atómico. No borrar originales en ausencia de sustituto verde.
- [ ] Comparar tiempos de PR, integración, RC y tasa de defectos y detección de mutantes con la base de BQ2. Retirar metadata redundante, no checks críticos. Proponer al propietario de roadmap B7 retirada V1 por equivalencia; no borrar V1 sin demostrar consumidores.

**Temporales:** UAT-BQ5-01 doble ejecución productor/harness con *mismo* ZIP, AAT-BQ5-01 mutar versión/manifest, AAT-BQ5-02 matar proceso durante replay, AAT-BQ5-03 semilla secreta y comprobación de no exposición. Guardar digest/veredicto del harness; borrar solo estos probes de ciclo. Los originales de producto quedan según clasificación y autorización.

**Salida:** inventario íntegro y estados resueltos, número real de tests movidos/eliminados y de garantías conservadas, ahorro medido y nueva release candidate integrada en `main`. Una migración no demostrada queda `BLOCKED_EXTERNAL` y no suma como cierre.

## Protocolo de cierre común, obligatorio en CADA BQ

1. `git fetch origin && git switch -c feat/bqN-... origin/main` y trabajo en rama con commits Conventional Commits pequeños. Si el repositorio exige otra convención, prevalece `AGENTS.md`.
2. Grabar lista de contratos protegidos y matrix `capability→scenario→oracle→boundary→test→replacement` antes de borrar probes; pruebas negativas del mismo defecto.
3. Ejecutar ciclo de UAT/AAT temporal en carpeta aislada del workspace y obtener `cycle-id`, SHA, hash de artefacto, exit code, tests no omitidos; cualquier `BLOCKED` se conserva como tal.
4. Convertir invariantes descubiertas en contratos/performance fitness y ejecutar mutantes de referencia. **El borrado del código de pruebas temporales ocurre en este punto**, antes de construir la RC: `retirement_guard.py` debe devolver PASS, carpetas temporales desaparecen y ningún código temporario llega a `main`.
5. Rebase/merge contra HEAD remoto actualizado sin reescribir historia publicada. Ejecutar integración completa exigida por `AGENTS.md`/roadmap y, cuando aplique, cierre con test de candidato (canario real de fronteras que cambiaron + matriz obligatoria). No confundir un `check` local con G10.
6. Merge en `main` respetando ADR-0099 (sin squash de historia de release), push. Registrar SHA remoto real, no SHA antes de merge. Construir candidato **una vez** para ese SHA, verificar versión, manifest, SBOM, SHA256SUMS y ZIP; publicar tag prerelease inmutable y enlaces reales.
7. Informar `CANDIDATE_PUBLISHED`, `BLOCKED_EXTERNAL` o STOP según corresponda. Solo el harness externo autoriza `CERTIFIED`, nunca el agente de este repo por interpretación de pruebas locales.

## Métricas mínimas comparables

| Métrica | Regla |
|---|---|
| `feedback_p50`, `feedback_p95` | ≥5 muestras mismas clases de cambio y host comparable; cero claims sin medición |
| `gate_wall_time` | desde primer intento hasta veredicto, no sumar tiempos de tareas solapadas |
| `test_maintenance_ratio` | LOC tocadas para arreglar **tests/harness** ÷ LOC totales de cambios del bloque; publicar como observación, no como KPI para jugar |
| `runtime_execs` | subprocessos/distribuciones arrancadas en el ciclo, antes/después |
| `mutation_survival` | mutantes que sobreviven por garantía; no porcentaje de cobertura de líneas como única ley |
| `deletions_supported` | nº fuentes eliminadas con ID de sustituto y evidencia; 0 si no hubo equivalencia |
| `regressions_escaped` | defectos de producto encontrados después de RC por garantía; registrar sin esconder |

## Resultado esperado

Cinco incrementos ejecutables y cinco releases candidates **si sus gates y publicación se realizan realmente**. El diseño promueve tests que explican y protegen el comportamiento; descarta UAT/E2E/AAT del ciclo sin fabricar un falso verde y externaliza progresivamente la aceptación de distribución de acuerdo con la autoridad existente.
