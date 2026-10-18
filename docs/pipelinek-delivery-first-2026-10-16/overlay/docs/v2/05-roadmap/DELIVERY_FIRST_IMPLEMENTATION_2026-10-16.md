# Ejecución de BQ1–BQ5 por entregas grandes (overlay, 2026-10-16)

> **Subordinación obligatoria:** este documento detalla `ROADMAP.md` §0, §13 y su `QUALITY_VALUE_ADDENDUM.md` (incluida su «Continuación operativa auditada — 2026-10-16»). No crea otro roadmap ni cambia RP-0…RP-9. Reutiliza `DELIVERY_FIRST_IMPLEMENTATION_DELTA.md`, `DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md` y ADR-PK-QV-001..004 **aceptados**. `ADR-PK-DF-001` queda **PROPOSED** hasta autorización; su contenido no puede contradecir §8 vigente de `AGENTS.md`.

**Objetivo cuantificable:** menos tiempo hasta tener una característica/paquete instalable, sin pérdida de detección de defectos críticos. Los números de rendimiento son hipótesis: medir p50/p95 de cinco iteraciones y no inventar benchmarks. Entregas **grandes por valor**, tareas de desarrollo pequeñas dentro de cada hito, commits atómicos Conventional Commits y cierre con RC inmutable e integración remota. No hacer un RC por test ni por documentación.

**Desbloqueo transversal de publicación:** como `main@5de40c45` ya contiene un publicador con lista distinta a `AGENTS.md` §8, el primer hito H0 DEBE incorporar el arreglo mínimo de publicación conforme al contrato vigente (Opción A de ADR-PK-DF-001) **antes de intentar su primera RC**; no esperar a H2 para reparar un defecto P0 que invalida cualquier cierre. H2 se reserva para la evolución SDK/Maven y la transición de política **si autorizada**. Si gates de certificación obligatorios continúan rojos, H0 permanece BLOCKED y se puede adelantar investigación H1/H2 sin atribuirle release falsa.

**Preflight obligatorio por hito:** `git fetch --all --tags`, `git status --short`, `git rev-parse HEAD`, inspección PR #140/#142 y tags/releases, `sddk project resolve`/`sddk status` si están realmente disponibles, `./gradlew -p v2 tasks` según entorno, identificar siguiente WU autorizado por roadmap, fuentes de verdad y consumidor. Comprobar tests/gates ya implementados: **si hay ley demostrada no recrearla**. Si las decisiones aceptadas exigen más fidelidad, este plan nunca la reduce.

## Secuencia y valor

| Hito | Correspondencia existente | Capacidad observable al salir | Trabajo crítico | Coordinación |
|---|---|---|---|---|
| **H0 — Ciclo seguro, sin falso verde** | P0 adelantado de BQ4; orden excepcional ya fijado en `QUALITY_VALUE_ADDENDUM` | `:test-harness:cycle` devuelve error real cuando falta evidencia, no borra ficheros ajenos | Corregir solo gaps que persistan en HEAD | Equipo H0 test/decider + seguridad, merge serial |
| **H1 — Loop rápido pero veraz** | BQ1 + BQ2, antes de ampliar Steps | `validation-gate.py` selecciona/corre lo debido; edición de dominio evita build instalado y plugins no impactados | Clasificar entradas y cerrar tags/Gradle DAG | Paralelizables clasificación y Gradle, integrar sin carreras |
| **H2 — Producto real distribuible** | BQ5 distribución/P2 Maven, PR #142; nunca bloquea RP funcional independiente | CLI ZIP instalable con asset-set exacto; SDK resoluble por consumidor remoto **si autorizado** | Única política release; BOM de cinco coordenadas y SDK JAR+POM/module; no fuentes | Maven y publicación separados, reconciliación final exclusiva |
| **H3 — Mejora de consola observable** | BQ3; `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` OBS-3/OBS-4 | Operador puede leer frames realmente ordenados con `pipelinek console --frames` sin cronología inventada, dentro de presupuesto | Corregir fallo medido del lector paginado o presentación; decisiones puras y una autoridad de frames | Dominio/reader y CLI en áreas separadas, integración por dueño |
| **H4 — Migración/limpieza sin deuda nueva** | Cierre BQ4/BQ5, `RESPONSIBILITY_MIGRATION_ROADMAP.md` | Harness de ciclo funcional y suite interna menor sin perder garantías de recuperación/secretos/ABI | Retiro 1:1 por prueba equivalente del certifier, no por nombre | Inventario paralelo; borrado/publicación serial |

### H0 — Ciclo seguro y demostrablemente fallido ante un fallo

**Código:** `v2/test-harness/src/main/kotlin/.../cycle/{Cycle,ProbeExecutor,CycleRunner,CycleCli}.kt`, `v2/test-harness/build.gradle.kts`. Primero ejecutar las leyes existentes: `Cycle.decide` **ya distingue** `ProbeOutcome.BlockedExternal/UnsupportedFidelity` y el runner ya contiene medidas de confinamiento; no reimplementar lo corregido. Verificar los huecos: `JavaExec.isIgnoreExitValue=true`, promociones con referencia pero sin verificación del sustituto, propiedad de todos los paths incluso symlinks/race y consistencia de provenance.

**Entregas TODO:**
- [ ] Implementar una sola proyección `ExitVerdict` desde el ADT de ciclo al código de salida de Gradle/CLI; `Verified` válido = 0, `Refused/Blocked/Unsupported/OtherCommit/NoEvidence` = no cero; no confiar en `exitCode` de un probe aislado.
- [ ] `PromotionEvidence` verificable por contrato/recibo concreto: existencia, identidad del candidato/commit, HF requerido, digest y *oráculo* ejecutado; string con prefijo no basta. Evitar segunda autoridad de certificación: consumir lector del harness/contrato existente.
- [ ] Borrado tras `Closed` exclusivamente por manifiesto de propiedad; archivos ajenos, softlinks y mutaciones de ruta tras `prepare` se rechazan sin efecto. Retener logs de error fuera del directorio eliminado; no borrar evidencia hasta su traslado/manifest.
- [ ] 1 UAT de CLI real + 1 AAT de negativos compuestos fuera de Git; una propiedad de estado y una de contención que permanecen. Cerrar conforme `DELIVERY_FIRST_MINIMAL_ACCEPTANCE.md`.

**STOP:** un error del comando pasa por verde, una prueba no ejecutada se promueve, se borra path no poseído, o el mismo SHA no explica el resultado. **Refs:** `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `ADR-PK-QV-003`, `ADR-PK-QV-004`, `BQ_ACCEPTANCE_MATRIX.md`.

### H1 — Selección honesta y tres lanes ejecutables

**Código:** `ImpactResolver`, `ContractCatalogue`, `SuitePolicy`, `GateCli`/`EvidenceReader`; `v2/pipeline-application/build.gradle.kts`; `v2/test-harness/build.gradle.kts`; `scripts/validation-gate.py`.

**Entregas TODO:**
- [ ] Introducir/reutilizar `ChangedInput` cerrado y decidir `Production/NonProduction/UnknownProduction` por *efecto semántico*, no substring conveniente: incluir `scripts/release*`, `scripts/install*`, wrappers, manifests, SPI/resources, test-harness propio, `build.gradle.kts`, otros scripts ejecutables ligados a garantías. `UnknownProduction` se rechaza con dueño/capability propuestos; evitar tabla por fichero.
- [ ] Ley de monotonicidad de obligaciones: añadir cambios productivos nunca elimina tests; una edición en el decider/gate obliga como mínimo a fitness adversarial del propio selector. `EvidenceReader` exige ejecución real, `tests>skips`, `failures/errors=0`, FQCN+Gradle path+invocación, SHA/artefacto y HF observado. Datos incompletos → `Refused`, no `passed`.
- [ ] Medir `--dry-run` desde checkout **fresco** y quitar las tres dependencias `buildExample*Plugin` de `tasks.test` solo tras mover tests reales a lane instalado/plugin y declarar correctamente `testClassesDirs`/`classpath`/tag o filtro. `installedContractTest` debe ejecutar >=1 test realmente marcado y necesario; no contar clases con `@Tag` inexistente. La suite rápida no ejecuta ni instala el ZIP ni compila plugins externos por accidente.
- [ ] La task/planificador debe gobernar tareas ejecutadas, no solo emitir JSON; no iniciar Gradle anidado bajo lock de `v2/settings.gradle.kts`. `fast` (HF0/HF1), `integration` (fronteras afectadas) y `candidate` (instalación/publicación) se calculan mediante el mismo catálogo.
- [ ] Medir mismo host/caché/commit representativo cinco veces (p50/p95), objetivo mejora >=40% del caso de cambio de dominio como **objetivo, no promesa**. Guardar costes y detección de mutantes antes/después.

**STOP:** `Selected(empty)` ante cambio productivo no clasificado, XML 0/all skipped aprobado, lane instalado con 0 tests, plugin/distribución obligatorio en un HF0 sin impacto, evidencia de otro commit aceptada. **Refs:** `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `TEST_EXECUTION_LANES_SPEC.md`, `PIPELINE_TEST_HARNESS.md`, `ADR-PK-QV-002`, `BQ_ACCEPTANCE_MATRIX.md`.

### H2 — Release consumible y SDK real, sin assets internos

**Código:** `scripts/release-pipelinek.sh`, `v2/pipeline-release/`, `SdkZipBuilder`, `v2/build.gradle.kts`, `v2/pipeline-sdk-bom/build.gradle.kts`, configuración `maven-publish`, instalador, PR #142.

**Entregas TODO:**
- [ ] **Decisión previa:** conciliar PR #142 y `AGENTS.md` §8 vía `ADR-PK-DF-001`. **Hasta aceptación rige el asset-set exacto de cinco:** `pipelinek-<v>.zip`, `.sbom.json`, `.sdk.zip`, `pipelinek-bom-<v>.json`, `SHA256SUMS`. No usar tres assets mientras el contrato prohíba cinco. Nunca subir `HANDOFF.md`, `git-head.txt`, corpus, test/fixtures o source ZIP voluntario como asset. Los Source code automáticos de GitHub no son productos.
- [ ] Publicador debe obtener el asset set del **mismo** `CandidateAdmission`/manifiesto versionado que genera la build (sin duplicar lista manuscrita). Comparar multiset exacto del remoto (faltantes y sobrantes), checksums de bytes publicados, `version --json` del ejecutable descargado e instalación limpia. El staging y publisher no deciden aceptación semántica independientemente.
- [ ] `publishedContractModules` contiene cinco contratos; `pipeline-sdk-bom` impone `api` constraints a los cinco (añadir `pipeline-runtime-api`). `sdkBomModules` contiene solo `pipeline-sdk-bom`; no publicar store-side. Exigir JAR+POM+`.module` donde corresponda, metadata de versiones y transitividad. Consumidor externo debe compilar plugin SIN `includeBuild`, ni ruta a `sdk-repo` local.
- [ ] Si el destino Maven/credenciales no se han autorizado, registrar `SDK_PUBLISHED=BLOCKED_EXTERNAL` sin mentir. La RC CLI puede publicarse solo si cumple la política vigente y gates; no afirmar que el SDK remoto existe por producir ZIP local.
- [ ] Un E2E efímero del **ZIP descargado** (instala, `version --json`, ejecución mínima), un UAT efímero de consumidor Maven remoto condicionado a destino, un AAT compuesto de asset faltante/sobrante. Permanentes: contrato de conjunto exacto y prueba barata de BOM/publicación; pruebas externas completas pertenecen al certifier.

**STOP:** artefacto inconsumible, publicación con lista incompleta/sobrante, checksum inválido, SDK anunciado remoto sin un consumidor desde remoto, rechazo de plugin oculto. **Refs:** `DISTRIBUTION_RELEASE_SPEC.md`, `ARTIFACTS_SUPPLY_CHAIN.md`, `STEP_PLUGIN_SDK.md`, `P2_MAVEN_PUBLICATION.md`, `ADR-0089`, `ADR-PK-QV-004`, `ADR-0105`.

### H3 — Consola real más útil y semántica FP pura

**Problema de consumidor:** la API histórica `ConsoleReadService.read()` concatena canales por orden fijo; `--frames` y `OutputFrameIndex` ya existen y son la ruta para orden por frame. **No** cambiar semántica antigua, inventar nuevo protocolo o reimplementar el índice: elegir **un defecto OBS-3/4 realmente reproducible** (paginación/orden/continuación/follow, por prioridad y coste) y corregirlo con uso de la ruta actual. Un experimento rojo en CLI debe demostrar el dolor antes de programar. Si ya cumple la propiedad, escoger el siguiente fallo medido del mismo roadmap, no construir una feature fantasma.

**Entregas TODO:**
- [ ] Diseñar *solo en la vertical seleccionada* `ObservationRequest` → `ObservationDecision` / `ReadIntent` / `Refusal` usando ADTs existentes (`ConsoleOperationSelection`, `OutputFrameIndex`, `OutputCursor` y outcomes). `decide(state, input)` total/pura; intérpretes implementan lectura, escritura y reloj inyectados. Nunca mezclar cursor de bytes por stream y ordinal de frames global.
- [ ] Preservar separación Events Plane vs Output Plane y cronología real sólo cuando `--frames` ofrece ordinal sustentado; si no hay orden, informar proyección, no inventarlo. Resolver continuación paginada en lectura índice con budget; exigir fidelidad de utf-8/canales/bloqueos según contratos OBS aplicables.
- [ ] Probar ley de determinismo/continuación/cursor ajeno/refusal/streaming con pocos casos parametrizados; un UAT real `.pipeline.kts` con stdout/stderr intercalados + un AAT de corrupción de ordinal/cursor (si el fallo observado lo justifica). Reutilizar canarios existentes; no crear segunda suite OBS.
- [ ] Reportar ahorro/coste y UX observable (salida de `pipelinek console --frames ...` antes/después), contrato semántico, ABI si cambió; cambio público prohibido sin ADR/autorización.

**STOP:** falsa equivalencia interleaving vs concatenación, pérdida o duplicación de bytes, cursor que salta frames, regresión de compatibilidad histórica. **Refs:** `CLI_OBSERVABILITY_SPEC.md`, `EVENT_MODEL.md`, `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`, `ADR-0106-frame-index-ownership.md`, `ADR-0103-one-replay-authority.md`, `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md`, `ADR-PK-QV-001`.

### H4 — Retirar pruebas heredadas por equivalencia, no por cansancio

**Entregas TODO:**
- [ ] Tomar PR #140 como inventario **provisional**. Cruzar todas las clases `src/test/`, scripts de releases y harness externo por capacidad, oráculo, fallos, costo, HF y *fingerprint* causal. En principio mantener unit, contract, fitness, propiedades y un canario instalado; no clasificar únicamente por prefijos `Uat`/`E2E`/`AAT`.
- [ ] Migrar por oleadas **dentro de este bloque**: (1) CLI/smoke redundante, (2) procesos/restart, (3) secretos/sandbox. Elegir primero lo que más tiempo ahorra y cuyo reemplazo ya es demostrable. Para riesgo crítico exigir mismo `CandidateId`, `inputDigest`, `failureKind`, `symptom`, `causalBoundary` y veredicto del `pipelinek-release-harness`, bajo `ADR-PK-QV-003`; sin esos cinco ejes, `MIGRATION_PENDING`, no borrado.
- [ ] Si una UAT antigua no es equivalente a ninguna en harness: mantener/quarantinar explícitamente solo según política, sin `@Disabled` engañoso ni inventar PASS. Eliminar **solo** las heredadas autorizadas mediante commit atómico y prueba de ausencia de garantía perdida.
- [ ] Mantener la herramienta de ciclo útil para HF0/HF1 y delegar HF2+ al certifier, sin añadir nuevo scheduler. Retirar duplicidad entre el runner interno y certificación externa; métricas reales p50/p95, LOC de mantenimiento y defectos escapados.

**STOP:** no hay evidencia externa para propiedades críticas; se borra prueba sin equivalente; release empaqueta tests o fuentes; producto cambia solo para acomodar test acoplado. **Refs:** `RESPONSIBILITY_MIGRATION_ROADMAP.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `TEST_SANDBOX_PROFILES.md`, `ADR-PK-QV-003`, `ADR-0105`, PR #140.

## Contrato único de cierre por hito (todos, sin excepción)

1. **Producto:** conducta real visible desde CLI/SDK instalada o mejora medible del flujo de entrega que afecta al consumidor; no cerrar solo con cambios en docs/mocks/tests.
2. **Pruebas en desarrollo:** skill `delivery-first-testing` router → lentes ready (`change-impact`, `ephemeral-uat`, `regression-promotion`, `failure-triage`) según riesgo; focos, no suite completa constante.
3. **Cierre de pruebas efímeras:** promover leyes pequeñas cuando sean necesarias, guardar oráculos/resultados/hashes fuera de repo, borrar SOLO pruebas de este ciclo tras prueba de cobertura/propiedad; `git ls-files` confirma cero efímeras en el **commit final**; bloqueo si `DEFER` crítico.
4. **Integración:** volver a ejecutar checks del commit final *sin tests efímeros*, merge/rebase solo por política Git, PR integrada en `origin/main`, resolver conflictos sin degradar contratos. No lanzar varios Gradle a la vez en un mismo checkout.
5. **Release:** obtener siguiente tag libre sin pre-reservarlo; generar CANDIDATE nueva desde SHA integrado y trazable; ejecutable + manifiesto/SBOM/assets según contrato vigente; gate de instalación/certifier cuando proceda; tag anotado/remote y verificación `gh release view`, integridad **Y** completitud. Nunca mover un tag viejo.
6. **Verdict:** `CANDIDATE_PUBLISHED` describe publicación, `SDK_PUBLISHED` requiere Maven remoto, `CERTIFIED` depende de certifier, `STABLE_PROMOTED` necesita autoridad; un `BLOCKED_EXTERNAL` no se maquilla ni bloquea tareas independientes. Si gate obligatorio está rojo → dejar el hito BLOCKED, sin publicar release ficticia.
7. **Evidence mínima de hito** (referencia externa, no source archive): SHA base/final, user value, comandos + exit + número real de ejecutados/skips, decisiones UAT/promoción, CandidateId/sha ZIP, assets remotos, PR/tag y residuales. No crear un documento por cada test.

## Glosario de lenguaje ubicuo

- **Capability:** función observable del producto; **Contract:** ley inmutable que la protege; **Scenario/Oracle:** entrada y observación discriminante.
- **Probe / UAT / E2E / AAT efímera:** experimento creado solo durante este ciclo; no es artefacto distribuible ni suite perpetua.
- **Functional core:** `Input + State -> Decision` sin E/S ni tiempo ambiente; **Interpreter:** adaptador con efectos explícitos; **ADT:** variantes tipadas cerradas para estados/resultados, sin booleanos de tres significados.
- **Seam:** frontera entre responsabilidades con un único dueño de decisión; **authority:** componente que puede afirmar un hecho, nunca dos escritores/deciders contradictorios.
- **HF (0..6):** nivel real de fidelidad observado; **Evidence:** resultado vinculado a fuente+artefacto; **Verdict:** decisión derivada; **CandidateId:** digest del ZIP exacto.
- **Release:** producto descargable y comprobable; **Maven BOM:** `java-platform` que impone versiones; **SBOM:** inventario de dependencias; **SDK ZIP:** layout Maven empaquetado, no publicación Maven remota.
