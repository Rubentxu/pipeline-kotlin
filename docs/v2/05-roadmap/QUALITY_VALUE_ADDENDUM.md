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

### Continuación operativa verificada — 2026-10-17 (`main@526fdeac920b8bc52f787290af58669bf9069bed`)

**Ámbito.** Esta sección actualiza solo los TODO pendientes de **BQ1–BQ5** de esta adenda; **no sustituye** `ROADMAP.md` (secuencia RP y bloques B0–B7), las ADR aceptadas ni `AGENTS.md`. La continuación del 16/10 es histórica y no se vuelve a ejecutar completa. Se inspeccionó código de `main`, no se ejecutó el gate sobre este SHA. Antes de actuar, recuperar estado SDDK y comparar `origin/main`; actualizar el diagnóstico si avanzó. No inventar versiones/tag de RC: consultar los publicados al cierre.

**Estado reconciliado 2026-10-18 contra `main@3ad2e5b6`.** Esta seccion se redacto contra `main@526fdeac` y su diagnostico describe ese SHA, no el HEAD actual. Al reconciliarlo: **HITO 1 estaba 4/4 implementado y 0/4 marcado**, con los commits de correccion ya en `origin/main`; el tablero mostraba `0/16` y por tanto no distinguia "no empezado" de "hecho sin cerrar". Debilidades 1, 2, 3, 5 y la mitad FQCN de la 4 estan cerradas y citadas por commit; `--rerun-tasks` global y la igualdad exacta del conjunto remoto siguen vivas. **HITO 1 no se declara DONE**: su criterio exige candidata publicada desde su SHA, y eso es `BLOCKED_EXTERNAL` (el publicador y el certifier no son de este repositorio).

**Avance que NO se debe repetir.** `TaskTerminalDecision` y sus consumidores (`ShExecution`, ProcessDurableTaskRuntime, CapturedRun) ya han evolucionado; existen guards de arquitectura. `ExecutionBoundaryFactory` ya responde `Failure(ENGINE)` sin ejecutor, en lugar de `Success`. El harness incorporó `Fidelity` y muchos ADTs/portadores JSON tipados; el SDK BOM ya tiene cinco constraints. `v0.62.0-rc6` se publicó con los cinco assets de ADR-PK-DF-001, pero es anterior a `main` actual. No reabrir estas labores por nombres de TODO obsoletos.

**Avance posterior a `19d592e5` (no incluido en la versión del paquete).** La serie BQ4 v22..v27 (commits `19d592e5`, `526fdeac` y anteriores) cerró las cadenas `<pending>` y `<refused:*>`, las parejas nullable paralelas (`Proposal` tipada en `ValidationPlan` y `ChangedInput`), los emisores JSON a mano (`InventoryClassifierMain` y `LanePlannerMain` con `@Serializable` + `kotlinx.serialization`), los campos vacíos de los carriers de retirement/cycle/plan y la validación estricta de SHA-1/SHA-256 en `DigestFormat`. Esto reduce la superficie falsificable del veredicto, pero no cierra el P0 de ciclo (los puntos 1–4 de "Debilidades" siguen abiertos).

**Debilidades observadas en codigo HEAD, y estado de cada una a 2026-10-18 contra `main@3ad2e5b6`:**

| # | Debilidad como se redacto | Estado a 2026-10-18 | Cierre |
|---|---|---|---|
| 1 | `finalise()` clasifica como propio todo hijo no presente en `operatorOwnedSnapshot` | **CERRADA** | `9cb8baef` |
| 2 | `:test-harness:cycle` conserva `isIgnoreExitValue=true` | **CERRADA** | `071c522d` |
| 3 | `ChangedInputClassifier` excluye por prefijo `v2/test-harness/` | **CERRADA** | `9183dc62` |
| 4 | `EvidenceReader`/`Gate` indexan por nombre simple; `Gate` conserva `--rerun-tasks` global | **MITAD**: identidad FQCN cerrada (`817f28c1`); `Gate.kt:171` sigue emitiendo `--rerun-tasks` | ver L29 |
| 5 | el lane rapido conserva los tres JAR externos en `testImplementation(files(...))` | **CERRADA**: viven en `pluginContractTest` | ver L28 |
| 6 | existen UAT `@Disabled(migrated-pending)`, incluida `UatLocal005BannedImportsTest` que el plan de migracion ordena conservar | **PARCIAL**: el hueco que se le atribuyo (la prohibicion JGit sin guard) **no existia**; hay fitness interno en `origin/main` desde `fc38dfdd` y corregido por `c602e6b4`. Lo que sigue vivo es la decision de revalidar IMP-001 | ver L57 |

El publicador verifica presencia de los assets listados por el manifiesto, pero no igualdad exacta del conjunto remoto. Nada de esto equivale a prueba dinamica de fallo: caracterizar RED antes del fix. Al reconciliar no se ejecuto el gate sobre este SHA; lo unico afirmable aqui es que los commits citados existen en `origin/main` y que el codigo citado se leyo en este HEAD.

#### HITO 1 — El ciclo nunca borra trabajo ajeno y el gate dice la verdad (BQ4 P0 + BQ1)

**Valor:** el agente y el desarrollador pueden confiar en el resultado de `cycle`/`validation-gate` y ejecutar pruebas temporales sin perder ficheros.

- [x] **Propiedad explícita de ficheros.** Sustituir `currentEntries - operatorOwnedSnapshot` por registro de rutas creadas por el propio runner/ejecutor y borrado limitado a esas rutas. Proteger symlinks, cambios concurrentes, fallos a mitad de limpieza y ficheros operator-owned. Mantener `Cycle.decide` puro y el filesystem en el intérprete existente. → `9cb8baef`: registro explicito `createdFiles` + `RegisteringExecutorAdapter`; se elimina el diff de snapshot. Sustento: `CycleRunnerSnapshotDiffFitnessTest`, `CycleRunnerTest`.
- [x] **Errores veraces.** Propagar el exit code del CLI en `:test-harness:cycle`; no usar el SHA de 40 ceros ni un directorio por defecto que el propio CLI rechace. Distinguir `Refused`, `BlockedExternal`, `UnsupportedFidelity`, `NotRun` y `Verified` sin falsos PASS. → `071c522d`: `CycleCli` rechaza `--source-sha`/`--cycle-dir` ausentes y `:test-harness:cycle` tiene `isIgnoreExitValue = false`. Sustento: `CycleCliRequiredArgsFitnessTest`.
- [x] **Selección y evidencia vinculadas.** Dejar de ignorar cambios ejecutables del propio harness. Identificar suites mediante módulo + FQCN + invocación real; vincular manifiesto, XML, `sourceCommit`, artefacto SHA y nivel HF observado a la misma ejecución; rechazar duplicados, falta de pruebas, todo `SKIP` y sidecar no verificable. Reutilizar ADTs ya implementados; no inventar un segundo catálogo. → `9183dc62` (se elimina la self-exclusion por prefijo) + `817f28c1` (`TestSuiteId` gana `fqcn`; la evidencia se indexa por FQCN). Sustento: `HarnessSelfExclusionFitnessTest`, `FqcnIdentityFitnessTest`.
- [x] **Pruebas efímeras mínimas:** AAT de archivo ajeno creado durante el ciclo (debe sobrevivir) y AAT de suites homónimas/sidecar falso (debe rechazarse). Un UAT del CLI real solo si las AAT no prueban el comportamiento invocado. Conservar únicamente tests puros/fitness de esas dos garantías; borrar temporales y fixtures exclusivos antes del gate final. → las dos garantias tienen fitness permanente (los cuatro de arriba); ningun commit de correccion dejo scripts ni fixtures efimeros. NO se afirma aqui que el gate se ejecutara sobre el codigo final: no se ejecuto en esta reconciliacion.

**Agent Teams:** A1 `cycle/` y prueba filesystem; A2 `plan/` + `Gate` y evidencia; A3 negativos en directorio temporal. Propietarios separados; un integrador reúne y pasa los gates.

**Refs existentes:** `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `PIPELINE_TEST_HARNESS.md`, `ADR-PK-QV-002`, `ADR-PK-QV-003`, `BQ_ACCEPTANCE_MATRIX.md`.

**DONE del hito:** rechazo discriminante de ambos errores y prueba final sobre código limpio; integración en `origin/main`, nueva candidata publicada desde su SHA y validación del ZIP/asset SHA. No atribuir `CERTIFIED` a la RC.

#### HITO 2 — Checkout limpio, tests rápidos y distribución comprobable (BQ2 + BQ1 residual)

**Valor:** poder compilar y probar cambios de dominio sin precalentar JARs ni reinstalar la aplicación, y detectar problemas de empaquetado antes de publicar.

- [ ] Separar los tests que usan JARs externos del source set rápido. `:pipeline-application:test` debe funcionar tras clone limpio; `installedContractTest` debe declarar inputs/source set apropiados, seleccionar realmente las clases tagged, comprobar `executed>0` y construir los plugins cuando sea necesario. Medir el DAG real de Gradle.
- [ ] Eliminar `--rerun-tasks` universal del inner loop; asegurar frescura por identidad/invocación para las suites afectadas y reutilizar caché válida. Medir p50/p95 comparable sobre cinco ejecuciones antes/después, sin imponer una reducción ficticia.

  **Estado reconciliado 2026-10-18 contra `main@bb4191ed`. La casilla NO se cierra, y el motivo cambia su objeto.** Tres mediciones, no lecturas:

  **(a) La prohibicion ya es normativa y el codigo la incumple.** `TEST_IMPACT_AND_EVIDENCE_SPEC.md:16` (R9) ordena "evitar `--rerun-tasks` global salvo necesidad de evidencia fresca", y `AGENTS.md:1336-1347` reserva `--rerun-tasks` a escalado. `Gate.kt:171` lo emite incondicionalmente. No es una preferencia de esta hoja: es una desviacion de una spec vigente.

  **(b) La frescura por identidad YA EXISTE y es mas fuerte que un `executed>0`.** No hay que construirla: `ImpactResolver.kt:278-282` indexa la evidencia por `suite.fqcn` y emite `SelectionRefusal.SelectedButNotRun` ante ausencia; `GateCli.kt:260` la renderiza como rechazo ("suite X was selected but produced no clean evidence"). Cinco ficheros de test la ejercitan. Por tanto quitar `--rerun-tasks` no abriria un verde falso: `clearStaleReports` ya borra los XML antes, asi que un `UP-TO-DATE` dejaria cero evidencia -> rechazo para todas las suites. Rojo honesto.

  **(c) El coste de quitarlo no es "mas lento": es "indiagnosticable".** Ese mismo rojo rechazaria todas las suites a la vez, indistinguible de una regresion real. Y hay un defecto real y adyacente ya medido: `ImpactResolver.kt:281` construye el rechazo con `suite.filter` (el patron JUnit simple) en vez de `suite.fqcn` -- exactamente el nombre ambiguo que `817f28c1` elimino como clave de busqueda. La correccion sobrevive en el camino del mensaje: el diagnostico que el operador lee no distingue dos suites homonimas. Correccion de una linea, pero **no se aplica aqui**:Gradle esta ocupado y tocaria codigo bajo verificacion.

  **Por que la p50/p95 que esta casilla pide es prematura.** Medido en `ADR-PK-DF-002` (accepted, ratificada 2026-10-18), decision 2b: `plan/` **se conserva**, y su razon medida es que alimenta la allowlist viva de `FArchM3CanonicalTaskRuntimeTest`. Decision 7 nombra `ImpactResolver` como mecanismo de auto-certificacion a retirar, y su precondicion es literalmente "las tareas seleccionadas realmente ejecutaron tests >0". O sea: **la medicion que L29 pide para optimizar un flag es la misma que la ADR pide como precondicion para borrar el mecanismo que emite el veredicto.** Optimizar `GradleSuiteRunner` antes de probar `executed>0` es trabajo sobre codigo que una ADR aceptada programa para retirada, y ordenada por la propia prueba que L29 quiere hacer. El orden correcto es el de la decision 7: primero `executed>0`, y `--rerun-tasks` se resuelve como consecuencia de que `ImpactResolver` desaparezca, no como una optimizacion independiente. Hasta entonces la casilla queda abierta con (a), (b) y (c) como estado, no como trabajo pendiente difuso.

  **Correccion de un documento-caducado, en el sentido contrario al que se leia.** `docs/v2/07-uat/DELIVERY_FIRST_RADICAL_INTEGRATION_2026-10-17.md:300-312` afirma que FArchM3 sigue ROJO y que el remedio es borrar el modulo. **Medido: `FArchM3CanonicalTaskRuntimeTest` esta 7/7 verde** (`failures="0" errors="0"`, XML `2026-10-18T11:22:52Z`) y la propia `ADR-PK-DF-002` dice que ese sintoma "no debe volver a citarse como bloqueo". Un informe que cita como bloqueo vivo un sintoma que la ADR aceptada ya retiro es el segundo tablero divergente que esta reconciliacion existe para evitar.
- [ ] En el `releaseGate`, exigir exactamente el perfil vigente de cinco assets: runtime ZIP, SBOM, SDK ZIP, BOM JSON y SHA256SUMS; comprobar **completitud e integridad**, sin extras, y probar los mismos bytes como consumidor. Mantener ADR-PK-DF-001 Opción A mientras no se autorice expresamente la B de PR #142. Publicación Maven remota = `BLOCKED_EXTERNAL` si faltan destino/credenciales.
- [ ] **Pruebas efímeras mínimas:** UAT de checkout limpio que ejecute el test afectado; E2E único que instale el ZIP y ejecute un pipeline pequeño; AAT de asset faltante/extra. Promover fitness de lanes y contrato exacto de assets; borrar los scripts/fixtures efímeros después de resultados observados.

**Agent Teams:** B1 Gradle/source sets; B2 selección, frescura y rendimiento del gate; B3 release/SDK y consumidor. B2 no toca lógica Gradle de B1; B3 no modifica el publicador simultáneamente con otro agente.

**Refs existentes:** `TEST_EXECUTION_LANES_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `DISTRIBUTION_RELEASE_SPEC.md`, `P2_MAVEN_PUBLICATION.md`, `ADR-PK-DF-001`, `ADR-PK-QV-004`.

**DONE del hito:** checkout limpio verde, test rápido medido, test instalado positivo real y conjunto de assets exacto; integración y nueva RC (no versión predeterminada). El SDK remoto puede seguir bloqueado sin fingir publicación.

#### HITO 3 — Corregir una vertical visible de observabilidad (BQ3; OBS-2 bajo su propio gate)

**Valor:** `pipelinek console` entrega bytes reales de un Step activo y conserva redacción y atribución por canal. Este bloque es **una corrección de producto**, no otra ronda de refactors de tipos.

**Estado reconciliado 2026-10-18 contra `main@9e6f233b`.** El primer checkbox pide reproducir un defecto que **ya no se reproduce**. Medido con evidencia vigente, no con un recollection: `ObsAOutputStreamingCharacterisationTest` (2 tests), `ObsPc208LevelBUatTest$Falsifiers` (10) y `$HappyPath` (6) dan **18 verdes, 0 fallos, 0 skips**, con XML de `2026-10-18T11:44Z`; el ultimo commit que toca produccion del Output Plane es `aeecb92a` a las `07:13Z`, cuatro horas antes, y ningun fichero de produccion bajo `pipeline-application/src/main` ha cambiado desde entonces. La causa la documenta `fbbb8edf` ("cuatro `@Disabled` con la razon caducada, y el defecto no era del producto"): la razon de Those deshabilitaciones era una hypothesis del harness, no un defecto. **Este hito ya no es "corregir una vertical": es "certificar que la correccion esta cerrada"**, y por tanto no se cierra con un test verde en el arbol: falta el kill real de `ADR-OBS-003` y la CLI instalada.

- [x] Reproducir en el HEAD actual los falsificadores `ObsAOutputStreamingCharacterisationTest` y `ObsPc208LevelBUatTest` (primera escritura sin salto de línea; stdout activo y stderr inactivo). Elegir el defecto reproducible prioritario; no transformar una prueba roja en `@Disabled` para cerrar el hito. → **cerrado por ausencia de defecto, no por cierre de hito**: los 18 tests verdes con evidencia vigente (arriba) significan que no hay defecto reproducible que elegir. Ningun `@Disabled` se toco para llegar ahi; `fbbb8edf` los reactivó porque su razon habia caducado.
- [ ] Corregir el seam productor → redactor → Output Plane → lectura CLI, manteniendo una sola autoridad de bytes y la semántica de cursores. Distinguir `Open/Sealed/Refused` mediante tipos existentes, sin doble replay ni spool de secretos crudos. Para caída de JVM y continuidad del hijo, aplicar la decisión vigente de `ADR-OBS-003` y no declarar Nivel B verde sin una prueba de kill real.
- [ ] **Pruebas efímeras mínimas:** UAT con salida incremental observable antes de terminar el Step y AAT con secreto fragmentado entre escrituras. E2E kill/restart **solo** si se cambia ese contrato; en tal caso debe ejecutarlo el certifier con la candidata exacta. Promover una regresión mínima de Output Plane más la propiedad de redacción; borrar scripts temporales.

**Agent Teams:** C1 captura/Output Plane; C2 redacción/canales; C3 CLI y aceptación como consumidor, sin editar el mismo fichero que C1. Integrar primero C1+C2, después C3.

**Refs existentes:** `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md`, `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`, `ADR-OBS-003-child-output-descriptor-ownership.md`, `ADR-0103-one-replay-authority.md`, `ADR-PK-QV-001`, `CERTIFICATION_PROTOCOL.md`.

**DONE del hito:** defecto RED→GREEN real, CLI instalada observable y sin pérdida de bytes/secretos; merge/push, test del artefacto final y RC nueva si el gate admite publicación. No declarar `OBS-2 CERTIFIED` sin autoridad externa.

#### HITO 4 — Menos UAT heredadas, mismo contrato de consumidor (BQ5 + distribución SDK pendiente)

**Valor:** menos coste de mantenimiento y una release consumible con garantías comprobadas por el propietario correcto.

- [ ] Reactivar o sustituir por fitness interno la prohibición JGit de `UatLocal005BannedImportsTest`: `RESPONSIBILITY_MIGRATION_ROADMAP.md` la clasifica explícitamente como **KEEP**. No migrar una regla del código fuente al certifier de ZIP. **Medido 2026-10-18, por el denominador y no por el nombre:** el requisito "escribir el fitness" **ya esta cumplido** y el sustituto es mas fuerte que la UAT. (a) La clase no tiene un unico `@Test`: tiene **dos**; IMP-001 esta `@Disabled("migrated-pending ...")` a nivel de **metodo** y **IMP-002** ("no hardcoded git binary paths") esta **vivo**. (b) Existia un fitness previo, `FArchL5NoJgitTest` (4 tests, vivo), que escanea **1** directorio y cuyo propio KDoc admite que "passes on an empty directory". (c) El sucesor completo es `BannedImportsFitnessTest` (`fc38dfdd`), ya en `origin/main` y **corregido** por `c602e6b4` ("the JGit ban covered 7 of 30 production modules"): descubre los `src/main` desde el filesystem en vez de una lista a mano, y una segunda fila cross-checkea contra una derivacion independiente para que el descubrimiento no pueda reducirse en silencio. **Estado: `NOT_RUN` en este SHA** — no hay XML para `BannedImportsFitnessTest`, y un guard sin evidencia de ejecucion no es verde. Queda por decidir si IMP-001 se reactiva como UAT o se queda `@Disabled` con el fitness como sustituto permanente, que es lo que la frontera de responsabilidad ya sugiere. **Aviso de metodo:** buscar el sucesor por el nombre de fichero que se sospechaba fallo dos veces en esta reconciliacion; medir el denominador (`grep -rln` sobre el token) lo encontro a la primera.
- [ ] Reconciliar PR #140 y todos los `@Disabled(migrated-pending)` del HEAD con sus garantías, huella de defecto, coste y frontera HF; inspeccionar `OBS-A`, `OBS-PC-208`, replay, sandbox y secretos. Diferenciar `Keep`, `MigrateExternalPending`, `DeleteReady(proof)` y `Blocked` usando la ontología existente.
- [ ] Antes de borrar UNA UAT heredada, exigir recibo del `pipelinek-release-harness` del mismo `CandidateId`, mismo defecto y nivel HF suficiente. Sin recibo, no declarar migración ni retirar prueba crítica: registrar `BLOCKED_EXTERNAL` y continuar trabajo autónomo.
- [ ] Verificar desde un consumidor limpio el SDK ya empaquetado (cinco coordenadas Maven + BOM, plugin compilado), sin afirmar que `build/sdk-repo` es publicación remota. PR #142 continúa pendiente de decisión de política; no fusionarla por conveniencia de tests.
- [ ] **Pruebas efímeras mínimas:** UAT de equivalencia productor–harness para una sola migración real, AAT de recibo `CandidateId` incorrecto y E2E de consumidor SDK si no existe cobertura equivalente. Promover el guard de retirada que discrimina equivalencias falsas; borrar probes nuevos. Borrar tests heredados **solo** con `DeleteReady(proof)` y autorización.

**Agent Teams:** D1 productor/inventario y fitness JGit; D2 consumidor SDK; D3 `pipelinek-release-harness`/equivalencias (repositorio distinto). D3 opera con autoridad externa y no altera directamente los veredictos locales.

**Refs existentes:** `RESPONSIBILITY_MIGRATION_ROADMAP.md`, `DISTRIBUTION_RELEASE_SPEC.md`, `P2_MAVEN_PUBLICATION.md`, `BQ_ACCEPTANCE_MATRIX.md`, `ADR-PK-QV-003`, `ADR-0105-admission-check-publication-authority.md`.

**DONE del hito:** reducción medible de tests/coste **con equivalencia**, SDK utilizable y código final comprobado. Merge/push + RC si el gate y la mejora real lo justifican; `BLOCKED_EXTERNAL` nunca cuenta como `DONE` ni se resuelve publicando una RC vacía.

#### Protocolo transversal de cada hito (aplicar `delivery-first-testing`)

1. Recuperar `sddk status` + `origin/main`, diff y consumidor; comparar con tareas ya ejecutadas. Consultar `skills/delivery-first-testing/SKILL.md`, `references/navigation.md` y solo lentes `ready`. Para feature: `change-impact → ephemeral-uat → regression-promotion`; para fallos: `failure-triage`; para integración y candidata: `merge-assurance → integration-assurance → release-assurance`; para costes: `harness-economics`.
2. Planificar cambios como funciones puras + ADTs reutilizados + intérpretes de efectos. Contratos primero, negativa RED discriminante, parche mínimo, tests afectados. No duplicar registros, deciders, validadores de identidad ni autoridad de replay/publicación.
3. Implementar en worktrees de Agent Teams con límites de fichero, commits atómicos y un integrador único. No concurrent Gradle sobre el mismo checkout; pruebas completas solo en integración/RC según gates vigentes.
4. Antes de cerrar, por cada UAT/E2E/AAT **creada en la sesión** decidir `PROMOTE/REPLACE/RETIRE/DEFER` y conservar (`capability`, contrato, escenario, SHA fuente/candidato, HF real, veredicto, mutación y prueba sustituta). **Borrar las temporales y fixtures exclusivos tras asegurar su cobertura**; no borrar heredadas ni material probatorio ajeno. `git status` y `git ls-files` deben mostrar cero temporales añadidas.
5. Ejecutar gates sobre el **código FINAL posterior al borrado**, integrar en `origin/main` con permisos adecuados, descubrir etiqueta RC libre y publicar un solo artefacto candidato sobre el SHA correspondiente. Verificar inventario, SHA256, arranque e instalación real. No auto-mergear ni publicar si no existe autorización. Un hito bloqueado conserva estado `BLOCKED/NOT_RUN` y no se proclama publicado/certificado.
6. Registrar entrega concisa: `valor al consumidor`, `SHA`, `artefacto/candidateId`, `tests ejecutados/fallidos/skipped`, `UAT temporales borradas`, `equivalencias heredadas`, `p50/p95` si procede y `siguiente hito`. El certifier externo es el único que afirma `CERTIFIED`; este repositorio publica únicamente RC conforme ADR-0099/0105.

**Glosario añadido al de esta adenda:** `Owner` = módulo/agente que puede cambiar un contrato; `EffectIntent` = descripción tipada de un efecto aún no ejecutado; `Probe` = prueba de aceptación efímera y de un solo ciclo; `Fault fingerprint` = entrada, síntoma, tipo y causa del defecto que debe distinguir el sustituto; `CandidateId` = digest de los bytes exactos del ZIP; `Guard` = prueba permanente pequeña que rechaza una regresión; `STOP` = bloqueo verificable, nunca PASS.

---

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
