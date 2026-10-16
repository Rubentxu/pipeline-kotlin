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

## Continuación operativa auditada — 2026-10-16 (main a66fe88c)

**Alcance:** actualización del TODO **existente** BQ1..BQ5, subordinada al orden RP de `ROADMAP.md` `0/`13 y a `AGENTS.md`. No crea otra autoridad ni reinicia trabajo ya probado. Hechos observados directamente en `main@a66fe88c5f19aecf019e1452e520aa7307cef006`, sin ejecución independiente de Gradle. `v0.62.0-rc5` es la última RC publicada verificada (ZIP JVM, SBOM, SHA256SUMS, git-head y lane-planner; tag sobre el mismo SHA). **No reservar versiones/tags de RC futuras: descubrir el siguiente disponible en el momento de publicación.**

### Estado de implementación (revisar de nuevo antes de cada bloque)

| Bloque | Ya existe en código | Pendiente para DONE |
|---|---|---|
| BQ1 | ImpactResolver clasifica wrapper/Gradle/SPI, Gate lee XML después de ejecutar, catálogo de ocho contratos | Entradas relevantes aún no cubiertas, nivel HF **observado**, prueba de invocación actual |
| BQ2 | `:test` deja de depender directamente de `installDist`; LanePlannerMain usa catálogo real; `-Plane` declarado | `installedContractTest` no tiene `testClassesDirs/classpath` explícitos ni se localizan clases `@Tag("installed-contract")`; checkout fresco aún necesita plugin JAR preexistente, faltan mediciones |
| BQ3 | Propiedades de `ConsoleOperationSelection` y deciders preexistentes | Una mejora funcional visible del producto con ADT, negativo discriminante y autoridad única |
| BQ4 | Ciclo HF0/HF1 y adapter del producto, validación de bytes SHA y cierre pasando por Promoted | `CycleRunner` transforma bloqueos/unsupported a `exitCode=0`, promoción por referencia no verificada, rutas ID de probe y borrado amplio, `cycle` ignora exit de JavaExec; UAT de ZIP pendiente |
| BQ5 | Reader/guard de veredictos y ADR-PK-QV-003 aceptado | Equivalencias externas verificadas por candidato y retiro autorizado de pruebas heredadas; PR #140 abierta |
| SDK/distribución | CLI JVM ZIP publicado; cinco contratos con Maven local, BOM; PR #142 abierta | SDK sin destino remoto; BOM constriñe cuatro de cinco contratos (falta runtime-api), `sdkBomModules` enumera runtime-api como si fuera documento BOM; assets internos extra deben desaparecer |

**Secuencia:** corregir inmediatamente el P0 de BQ4 por riesgo de borrado (prioridad de integridad de `ROADMAP.md` `0), sin declarar BQ4 terminado. A continuación mantener BQ1→BQ2→BQ3→BQ4→BQ5; la distribución SDK es un incremento independiente. Si falta certifier o Maven remoto, avanzar bloques autónomos sin falsear `DONE`.

### BLOQUE 1 — P0 de ciclo seguro (corrección BQ4, RC propia)

- [ ] Conservar `ProbeOutcome`/`ObservedBoundary` tipados hasta `Cycle.decide`: solo `Verified` comprobado cuenta; `Skipped`/`NotRun`/`BlockedExternal`/`UnsupportedFidelity`/`OtherCommit` rechazan sin generar eventos éxito. Regression RED antes del fix.
- [ ] Validar source SHA real, hash de evidencia, IDs y rutas de probes, sustituto permanente o recibo externo del mismo candidato. Eliminar referencia fabricada `replacement:<id>` y el SHA por defecto de 40 ceros.
- [ ] Aislar las escrituras y el borrado por propiedad del directorio y manifiesto, no recorrer/borrar cualquier contenido del cycleDir; rechazar symlink/escape/carreras; propagar fallo de `:test-harness:cycle` al build.
- [ ] AAT de sesión: probe bloqueado con exit 0, `../` en ID, sustituto falso, SHA diferente, fichero ajeno y borrado parcial. **Promover** leyes permanentes y **BORRAR UAT/E2E/AAT temporales** más fixtures exclusivas tras recibo correcto; `git ls-files` debe mostrar cero temporales.
- [ ] **Cierre:** merge/push conservando historia, gate fresco sobre código FINAL sin temporales, RC nueva con ZIP real y SHA256SUMS/SBOM, tag y assets remotos verificados. No afirmar BQ4 DONE todavía.

**Refs existentes:** `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `BQ_ACCEPTANCE_MATRIX.md`, `ADR-PK-QV-003` y `ADR-0099`.

### BLOQUE 2 — Selección honesta + vías rápidas (BQ1/BQ2, RC propia)

- [ ] BQ1: catalogar cambios de recursos, scripts de distribución y build que alteran ejecución; casos relevantes no clasificados = negativa. Evidencia de `tests>0`, `skipped<tests`, mismo SHA, misma invocación, mismo nivel HF ejecutado.
- [ ] BQ2: asignar tests que lanzan el ZIP realmente instalado a una tarea Gradle con `testClassesDirs`, `classpath`, JUnit tags reales, `installDist` y conteo positivo. La lane rápida no debe necesitar plugin JAR ya construido cuando parte de checkout limpio.
- [ ] Enlazar `LanePlanner` con selección/ejecución reales, no limitarlo al JSON. Medir DAG y p50/p95 de al menos 5 corridas comparables, sin `--rerun-tasks` universal en el inner loop; objetivo ≥40 % de reducción del caso de dominio, sin perder mutantes.
- [ ] Ejecutar negativas temporales: XML cero/all skipped, recurso desconocido, fallo instalado, proyecto limpio sin cachés, tres pasos CLI y error real. Mantener fitness/propiedades mínimas; **BORRAR UAT/E2E/AAT de sesión** tras reemplazo verificable.
- [ ] **Cierre:** fast/integration/candidate usables, prueba real positiva de installed lane, nueva RC tras merge/push, SHA y métricas verificadas. Marcar BQ1/BQ2 DONE únicamente si cumplen todos sus STOP originales.

**Refs existentes:** `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `TEST_EXECUTION_LANES_SPEC.md`, `PIPELINE_TEST_HARNESS.md`, `BQ_ACCEPTANCE_MATRIX.md`, `ADR-PK-QV-002`.

### BLOQUE 3 — SDK compilado + distribución limpia (P2 Maven/DIST, RC propia)

- [ ] Reconciliar PR #142 y aplicar política `distribution-first-release`: solo ZIP **runtime instalable** de la CLI, SBOM y SHA256SUMS normativos como assets. `HANDOFF.md` son notas, no archivo adjunto; prohibir source archives añadidos manualmente, ZIP duplicados y binarios en Git. Los `Source code` automáticos de GitHub NO se pueden eliminar.
- [ ] Arreglar autoridad Maven: cinco módulos de `publishedContractModules` y un `pipeline-sdk-bom` con las cinco constraints; `sdkBomModules` solo documentos BOM, no `pipeline-runtime-api` duplicado. JAR + POM + `.module`; no publicar stores privados ni asumir que el JAR de app solo reemplaza ZIP ejecutable.
- [ ] Mantener `build/sdk-repo` local como fixture. Configurar publicación remota solo con destino/credenciales autorizados (Nexus o GitHub Packages), probar resolución y compilación de plugin en consumidor limpio, con versiones/transitividad/ABI y SHA exactos. Si no hay acceso, `SDK_PUBLISHED=BLOCKED_EXTERNAL`, aunque la RC CLI pueda salir.
- [ ] UAT/AAT de consumidor Maven y ausencia de contrato en BOM, fuente inesperada en release y checksum incorrecto. Promover test ABI/completitud, **BORRAR temporales** tras sustitución.
- [ ] **Cierre:** RC del producto con asset instalable, lista remota exacta y tags verificables; SDK se llama publicado SOLO si se resolvió de un repositorio remoto. Merge/push/RC también con bloqueo remoto explícito, sin crear release vacía.

**Refs existentes:** `P2_MAVEN_PUBLICATION.md`, `DISTRIBUTION_ROADMAP.md`, `DISTRIBUTION_RELEASE_SPEC.md`, `STEP_PLUGIN_SDK.md`, `ADR-0089`, `AGENTS.md`.

### BLOQUE 4 — Valor de runtime y cierre real del ciclo (BQ3/BQ4, RC propia)

- [ ] Escoger UNA vertical de `ConsoleOperationSelection`, `ShExecution` o `retry/waitUntil` con fallo observado: mejorar comportamiento real, reutilizar vocabulario `State/Input/Decision/EffectIntent/Failure`, `decide` puro, intérprete de E/S en el borde y sin segunda autoridad de replay.
- [ ] Demostrar escenario CLI/DSL real antes/después, fallo nuevo corregido, eventos/outcome compatibles, mutante discriminante y API/semver explicados si cambian.
- [ ] Ejecutar HF1 desde CLI de producto con adapter realmente descubierto (no solo test que instancia clase). Ejecutar UAT-BQ4-01 real sobre ZIP instalado HF2 mediante su propietario/certifier; timeout, no secretos, aislamiento, descendientes, cierre y sustituto verificado. HF2..HF6 jamás devuelven stub `PASS`.
- [ ] UAT real de usuario, E2E de ZIP y AAT de replay/secretos/borrado interrumpido; conservar leyes, escenario y recibo; **BORRAR pruebas creadas solo durante sesión** antes de release.
- [ ] **Cierre:** mejora funcional usable y cierre de ciclo demostrado, merge/push + RC real. El certifier externo sigue siendo la única autoridad de `CERTIFIED`.

**Refs existentes:** `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md`, `DSL_SPEC.md`, `STEP_CONSTITUTION.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `BQ_ACCEPTANCE_MATRIX.md`, `ADR-PK-QV-001` y `ADR-0103`.

### BLOQUE 5 — Retirar UAT heredadas con equivalencia (BQ5, RC si gate permite)

- [ ] Contrastar PR #140 con HEAD y `RESPONSIBILITY_MIGRATION_ROADMAP.md`; clasificar test heredado por capability, escenario, bug fingerprint, HF y coste. No equivaler `Uat*` a «duplicado».
- [ ] Migrar por oleadas (smoke/CLI → restart → sandbox/secretos → V1) usando **el mismo ZIP/CandidateId**, veredicto real del certifier y `DeletionGuard` autorizado. Mantener tests protegidos hasta evidencia de **mismo defecto**. No convertir skipped en verde.
- [ ] Medir tiempo y mantenimiento antes/después, número de pruebas realmente retiradas y garantías conservadas. **BORRAR UAT/E2E/AAT creadas en este bloque** tras promoción; originales solo con equivalencia y aprobación.
- [ ] **Cierre:** retirada real autorizada, tests del estado final, merge/push y RC nueva. Si certifier falta, reportar `BLOCKED_EXTERNAL`, **no marcar DONE ni publicar una RC sin valor solo para tachar el hito**; continuar con otro trabajo ejecutable.

**Refs existentes:** `RESPONSIBILITY_MIGRATION_ROADMAP.md`, `BQ_ACCEPTANCE_MATRIX.md`, `DISTRIBUTION_RELEASE_SPEC.md`, `ADR-PK-QV-003` y `ADR-0105`.

### Protocolo de cierre y borrado — se ejecuta al final de CADA bloque

Aplicar el **protocolo común ya existente** arriba en esta adenda y `ROADMAP.md` `13.6. Para cada prueba temporal guardar `cycleId/sourceSHA/CandidateId`, HF ejecutada, resultado, negativa, contrato sustituto y evidencia. Fallo, omitted, skipped o blocked **no autorizan** borrado. **Borrar solo los ficheros UAT/E2E/AAT de sesión y sus fixtures exclusivas**, comprobar `git status`/`git ls-files` y correr el gate de integración/release sobre código final **después** de la limpieza. Nunca borrar unitarios/fitness/contratos ni UAT heredadas por nombre; exigir veredicto y autorización para ellas.

Cada bloque terminado exige branch integrada en `origin/main` sin squash de historia publicada, tag RC nuevo sobre SHA de producto, binario instalable de esos bytes, checksum/SBOM normativos, enlace y digest observados en GitHub. `CANDIDATE_PUBLISHED` ≠ `SDK_PUBLISHED` ≠ `CERTIFIED` ≠ `STABLE_PROMOTED`; no falsear estados. Los bloqueos externos no paralizan work units independientes.

### Anexo — glosario (lenguaje ubicuo)

| Término | Significado |
|---|---|
| Capability / Contract | Comportamiento prometido / ley que lo protege, no nombre de clase JUnit. |
| Scenario / Oracle | Entrada reproducible / observación que distingue éxito de defecto. |
| Probe efímera | UAT/E2E/AAT del ciclo: se elimina SOLO con garantía permanente conservada. |
| Test heredado | UAT antigua; no se borra con cleanup del ciclo, exige equivalencia externa. |
| Evidence / Verdict | Observación con identidad/digest y frontera / decisión tipada sustentada por ella. |
| HF0…HF6 | Frontera de prueba requerida/real; una HF inferior no demuestra una superior. |
| Decider / Interpreter | Función pura que decide / adaptador que realiza efectos autorizados. |
| Lane | Tareas y tests realmente ejecutados, no informe JSON. |
| Replacement / Equivalence | Garantía sustituta / prueba del mismo defecto al nivel exigido. |
| CandidateId | Hash SHA-256 de los bytes del ZIP concreto, no el texto del tag. |
| SDK BOM / SBOM | POM con constraints Maven / inventario de componentes del producto; NO son lo mismo. |
| Source archive / Release asset | ZIP automático de GitHub generado desde Git / binario adjuntado expresamente. |
| BLOCKED_EXTERNAL | Falta autorización/veredicto ajeno; nunca equivale a DONE. |
