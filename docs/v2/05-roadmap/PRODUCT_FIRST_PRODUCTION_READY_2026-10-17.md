# PipelineK — hoja de trabajo H0–H5 para Production Ready con **poda radical**

**Estado:** propuesta ejecutora subordinada a `docs/v2/05-roadmap/ROADMAP.md` (única secuencia RP/B0–B7). Base inspeccionada `1c21f303`. **No duplicar TODO autoritativo ni crear roadmap alternativo**: integrar este fichero mediante un único enlace en §13 y actualizar la agenda real/SDDK. Política nueva condicionada a aceptar ADR-PK-DF-002 y las enmiendas de `AGENTS.md`/ADR-PK-QV-003.

## Estado medido (2026-10-18, `main@4dd4f725`)

Este bloque existe porque el checklist de abajo y el recibo
`docs/v2/07-uat/DELIVERY_FIRST_RADICAL_INTEGRATION_2026-10-17.md` se contradecían: el
checklist declaraba H0 entera sin empezar mientras el recibo documentaba H0.3 ejecutada y
verificada sobre el artefacto instalado. Un lector que abría sólo este fichero concluía que
no se había hecho nada. Aplicar la regla de `ROADMAP.md` §13.7 — *registrar `DONE` sólo tras
código eliminado e integración real* — a la evidencia ya comprometida, sin ratificar nada nuevo.

| Hito | Estado | Evidencia / bloqueo |
|---|---|---|
| H0.1 | `DONE` (2026-10-18) | Gate humano concedido por el product owner; `ADR-PK-DF-002` **ratificada** con su contexto corregido a medición de HEAD. Sus enmiendas siguen sin ejecutarse (bloque propio). No resuelve el registro de verdad de estados: ver `CONFLICT-ADR-STATUS-REGISTRY.md`. |
| H0.2 | `DONE` (2026-10-18) — cerrado por medición: **el objetivo no se sostiene** | El blocker («47 de 51 claims de equivalencia no resuelven a un test vivo») era una cita de la **población equivocada**. Medido sobre las **124** filas `@Disabled` de `v2/`: **0** promesas de cobertura sin sucesor vivo. **0 borrados justificados.** Ver debajo. |
| H0.3 | `DONE` | `5e816d84`. `InProcessProductProbeExecutor` `src/main`→`src/test`; `implementation`→`testImplementation`. Verificado sobre el ZIP instalado, no en el árbol. Guardarraíl `FArch021QaModulesStayOffRuntimeClasspathTest` mutation-tested en ambos sentidos. **Revalidado en HEAD y otra vez el 2026-10-18**: queda **un** uso de `:test-harness` en `pipeline-application`, `testImplementation` (`:212`), que es test-side por definición. La segunda arista, `laneReportClasspath` (`:702`), era la configuración de una tarea que nadie leía y se eliminó con H0.4. |
| H0.4 | `PARCIAL` — `lanes` cerrado (2026-10-18) | **El síntoma ROJO que se citaba era falso**: `FArchM3` está 7/7 verde y sus 4 sitios declarados son los reales, todos en `harness/plan/`. **La precondición también era falsa y por eso no bloqueaba nada**: la selección de lane es `-Plane=` en Gradle, no `:test-harness`. Ejecutado: borrados `laneReport` + el paquete `harness.lanes` completo (3 `src/main` + 4 `src/test`), owners verificados en cero. Quedan `cycle`, `retirement`, `plan`, `catalog`, `util` (**35** ficheros en `src/main`). |
| H0.5 | cerrado (2026-10-18) | La política de la fila `unit legacy` está decidida y **aplicada en la puerta**: la permanencia es una familia de §3 y se comprueba en `DeletionGuard`. `3c3d1cf9`. |
| H0.6 | cerrado (2026-10-18) | compile + contrato real + 2 negativos verados + ZIP real construido y ejecutado. La integración como preparación de H1 sigue sin ocurrir. |

**Lo que este bloque NO hace.** Ratificar la ADR **no autoriza ninguna poda** y no convierte su
aceptación en progreso de H0.4: las cuatro enmiendas siguen sin ejecutarse. Marcar H0.3 `DONE` es
aplicar una regla aceptada a un commit ya integrado. El 2026-10-18 este bloque ganó H0.1; ese mismo
día, por medición independiente, se cerró además `lanes` dentro de H0.4 — que no era una prerrogativa
de la ratificación, sino un corte con owners verificados en cero que la ADR ya autorizaba. **Lo que
queda de H0.4 (`cycle`, `retirement`, `plan`, `catalog`, `util`) no está autorizado por corte
automático:** son dueños de comportamiento que un gate consume, y su regla de sustitución es la
precondición que la decisión 2b conserva para ellos.

## Norte y criterio de prioridad

**Primero capacidad útil, luego solo pruebas que detecten defectos reales.** Desde RC6 se tocaron 9.530 líneas en QA frente a 437 de producto; invertir tendencia. Una unidad de trabajo sin consumidor real queda subordinada. Por defecto ninguna feature puede crear subsistemas nuevos de harness, sidecars, manifests, routers, catálogos o múltiples E2E. La excepción P0 exige defecto reproducido.

## H0 — CORTAR EL MONSTRUO: retirar QA embebida sin desmontar el producto

**Valor entregado:** ZIP/SDK más pequeño, build/contribución sin framework de auto-certificación y bucle rápido sin dependencias de QA. **No esperar a reparar la FSM de `CycleRunner`, la ontología de `ImpactResolver` y el `Gate`: el objetivo es desinstalarlos.**

**Refs vigentes:** `TEST_EXECUTION_LANES_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, ADR-0072, ADR-PK-QV-003, ADR-0105, ADR-PK-DF-002 (**ratificada 2026-10-18**), `RESPONSIBILITY_MIGRATION_ROADMAP.md`.

- [ ] **H0.1 Aceptar o bloquear explícitamente política:** revisar ADR-PK-DF-002, proponer enmiendas mínimas a `AGENTS.md`, roadmap y specs. Si no se autoriza cambiar la obligación `check` y borrado legacy, continuar solo R0 y separar dependencia; etiquetar `BLOCKED_POLICY`, nunca dar por aprobada una relajación. **→ `DONE` (2026-10-18): gate humano concedido por el product owner; `ADR-PK-DF-002` ratificada. Sus enmiendas NO se ejecutaron con la ratificación** — siguen cada una con su propio diff, y la decisión 4 (el `check` deja de ser condición por merge) queda sin aplicar porque la regla de `AGENTS.md` que la contradice sigue vigente.
- [x] **H0.2 Borrado inmediato de código muerto:** podar métodos `@Disabled` históricos (p.ej. en `CoreSleepRegistryPrimaryFitnessTest`), guardas de conteo de líneas (candidato `CoordinatorGrowthGuardrailTest`), restos sin consumidor, reglas duplicadas de inspección textual de detalles privados. **No** sustituir por tests equivalentes del framework. Guardar un solo test funcional si era una ley real. **→ `DONE` (2026-10-18): cerrado por medición. El objetivo no se sostiene sobre la población medida y no se borró nada.**

  El blocker de la versión anterior («**47 de 51** claims de equivalencia no resuelven a un test vivo», atribuido a `tools/disabled_equivalence_audit.py`) **medía otra población**. Ese script audita las filas que `test_portfolio.py` etiqueta `R0_PRUNE_DISABLED_METHODS`, que hoy son **1**. Su docstring además lo dice: distingue *archivo de trazabilidad* de *promesa de cobertura*, y su propia población es casi vacía. La cifra de 51 venía de una versión anterior del clasificador.

  Re-medido sobre **las 124** filas `@Disabled` de `v2/` con `tools/disabled_reason_census.py`, clasificando la **forma de la razón** y no buscando la palabra `superseded`:

  | Forma | Filas | Qué promete |
  | --- | --- | --- |
  | `ArchivalSnapshot` | 55 | nada: narra un censo de burn-down histórico («el conteo 4-id quedó superado por el 3-id») |
  | `AnchoredToLocal` | 32 | una ADR o un fichero local que **existe y resuelve** |
  | `FreeText` | 31 | por qué no corre; no reclama sustitución |
  | `SupersededByTest` | 6 | un test sucesor — **los 6 existen y corren** |

  **Resultado: 0 promesas de cobertura que no se puedan cobrar.** La sexta fila (`CoreSleepCoordinatorCharacterizationTest`) dice «superseded by `core.sleep` LEGACY_REMOVED», que nombra un Step **retirado**, no un test sustituto; la herramienta la clasifica como retirada y no como promesa, que es la lectura correcta.

  El número que faltaba para poder cortar: de las 6 filas que nombran sucesor, **0** autorizan un borrado. De las 55 de archivo, ninguna es borrable — son el archivo de trazabilidad que `H0.2` ya había protegido una vez. **El corte queda sin objeto, y esa es la conclusión honesta: no es `NOT_RUN` por falta de permiso, es que la población que el hito nombraba no contiene código muerto.**

  La herramienta es nueva y su «0» tiene dientes verificados: mutar `superseded by CoreSleepRegistryPrimaryFitnessTest` por un nombre inexistente la pone roja en esa fila exacta, y el fichero se restauró a hash limpio después.
- [x] **H0.3 Fuera del runtime:** eliminar o reclasificar `InProcessProductProbeExecutor` (`application/src/main/harness`), su ServiceLoader y dependencia `implementation(project(":test-harness"))`; conservar HF1 por tests de `PipelineRule`/Step. Demostrar que distribución no contiene ese QA. **No** retirar `pipeline-event-harness`, consumidor de `events verify`.
- [~] **H0.4 Eliminar los subsistemas completos:** `v2/test-harness/{cycle,retirement,lanes,plan,catalog,util}` y sus 51 tests después de verificar owners; borrar `laneReport`, `validationPlan`, `scripts/validation-gate.py`, entrada de settings y referencias runtime. Sustituir por tasks Gradle que YA existen, y RC externa. **No crear otro Kotlin harness más pequeño**. **→ `PARCIAL`; `lanes` CERRADO (2026-10-18).** Dos afirmaciones que bloqueaban este hito eran falsas, y las dos se corrigieron por medición antes de tocar nada:

1. **El síntoma ROJO.** `FArchM3CanonicalTaskRuntimeTest` está **7/7 verde**; el único `ProcessBuilder(` no declarado es el literal de cadena que `retirement/TestClassifier.kt` usa para *nombrar* la API que busca, y `KotlinSourceSanitizer` enmascara los literales. Los 4 sitios reales que la allowlist declara son los de `harness/plan/`.
2. **La precondición.** Decía que `:test-harness` era «el dueño del clasificador de *lanes*, razón de que el bucle interior sea rápido». Falso: la selección que corre la suite es `-Plane=fast|installed|release` en `pipeline-application/build.gradle.kts`, precede a `lanes/` y no depende de él; ninguna receta de `justfile` pasa `-Plane`. El sustituto que la precondición exigía **ya existía**.

**Corte ejecutado, con owners verificados en cero:** `TestLane`, `LaneSpec` y `LaneReport` no tienen **ninguna** referencia en todo `v2/`; `LanePlanner` sólo aparecía en la tarea `laneReport`, que escribía `build/reports/lane-planner.json` sin que nada lo leyera (ni test, ni gate, ni `justfile`) y sin que nada dependiera de ella. Sus dos fitness.make `error()` si el paquete no existe: su sujeto se va con él, y el hermano que cubre la misma clase de defecto sobre `catalog.ContractCatalogue.Fidelity` (`CycleFidelityStringDiscriminatorFitnessTest`) **permanece**. Se borraron 7 ficheros: la tarea y su configuración en el build, más 3 de `src/main` y 4 de `src/test`.

**Lo que NO se tocó:** `cycle`, `retirement`, `plan`, `catalog`, `util`. La regla de la decisión 2b —sustituto medido antes del corte— sigue vigente **para ellos**, y sigue siendo la razón por la que el módulo no se borra en bloque. **Y la orden de esta misma viñeta que decía «borrar `validationPlan`, `scripts/validation-gate.py`» queda CORREGIDA por medición:** `plan/` alimenta la allowlist **viva** de `FArchM3` (sus 4 sitios de `ProcessBuilder` declarados son exactamente los de `harness/plan/`), así que cortarlo obligaría a editar una guarda hoy verde — el corte debilita el invariante en vez de cumplirlo. Y `validation-gate.py` no es un recibo que nadie comprueba: construye el classpath, lanza el JVM y **rechaza** evidencia vacía u obsoleta. Lo que le falta es un **llamador** — `.github/workflows/` vacío, ninguna receta del `justfile`, ningún otro script lo invoca—, que es *dormente*, un defecto distinto. Su lanzador en Python está justificado y medido: `settings.gradle.kts` toma un candado exclusivo sobre el checkout y una tarea Gradle que arrancase más builds se encontraría con el candado que ella misma tiene. Lo que procede es **medir su coste**, no borrarlo.
- [x] **H0.5 Poda del portafolio real:** revisar candidatas `migrated-pending`, UAT de binario y fitness de estructura. Retirar en tandas por garantías y evidencia, conservar críticos hasta equivalencia. Eliminar métodos @Disabled por historia de commits. No confundir `migrated-pending` con certifier verificado. **→ CERRADO (2026-10-18, `3c3d1cf9`).** La política de la fila `unit legacy` está decidida y, más importante, **aplicada donde importa**.

  Las **79 clases** de `v2/` con al menos un `@Disabled`, agrupadas por la regla que `AGENTS.md` §3 usa para decidir su destino:

  | Familia | Clases | Filas | Destino según §3 |
  | --- | --- | --- | --- |
  | UAT (`Uat*`, `Lpr*`, `PublishHtml*`, `StashOps*`, `Compat*`) | 54 | 54 | **migran** al harness; 32 ya ancladas a `ADR-PK-QV-003` o a `disabled_uat_review_2026-10-17.md`, **22 sin ancla** |
  | fitness / contrato (`*FitnessTest`, `*ContractSuite*`) | 10 | 44 | **se quedan** (42 son snapshots de trazabilidad) |
  | **unit legacy / otro** | **15** | **28** | **nadie ha decidido** — no son UAT y §3 no las nombra |

  La fila que bloqueaba la poda era la tercera. **15 clases no son UAT (no migran al harness) y tampoco están en la lista de «tests que se quedan siempre» de §3**, así que su destino dependía de qué nombre tuviera el fichero. Eso es una frontera expresada como lista de prefijos, que es exactamente la forma frágil que ya se pagó dos veces en este repo (`CoordinatorGrowthGuardrailTest` era un nombre; `lane-planner.json` era un nombre).

  **La decisión que este hito necesitaba (2026-10-18): la permanencia es una propiedad de las FAMILIAS de §3, no de una lista de prefijos, y se comprueba en la puerta — no en el clasificador.**

  El paquete `retirement` ya tenía el sitio donde escribirla y no la tenía escrita: `PERMANENT_FITNESS_PATHS` era un set de **un** path — `UatLocal005BannedImportsTest`, la única fila que §2 marca **SE QUEDA**, o sea la única cuyo *nombre* no la delata como contrato permanente. Las otras familias de §3 caían todas en la misma rama del clasificador, `observedProcessApis`: cinco de ellas observan el binario instalado, así que `classify()` les devolvía `MigrateExternalPending` — el estado que §4.1 puede promover a `RedundantWithProof`, la única forma que `DeletionGuard` acepta.

  Dos de esas cinco son `InstalledContractTagMembershipFitnessTest` e `InstalledDistributionHarnessFitnessTest`: los fitnesses que vigilan el contrato de distribución instalada. Un guard que clasifica su propio guard como migratable es la forma `green-row-that-certifies-the-defect`, y aquí no era hipotético — el único ingrediente de esta tabla que sobrevive a la promoción es la comprobación en la puerta.

  Tres consecuencias medibles:

  1. **La frontera son las familias, no los prefijos.** Prueba de que el nombre falla: `UatLocal005EnvSpecialCharsTest` (proceso) y `UatLocal005BannedImportsTest` (código) comparten prefijo y tienen sujeto distinto.
  2. **La comprobación vive en `DeletionGuard.check`, no delegada al clasificador.** §4.1 *es* el camino que promociona la decisión del clasificador; si la permanencia viviera sólo allí, una promoción que lo saltara borraría un contrato permanente sin que ninguna otra fila del paquete lo notara. Un contrato permanente no se retira por **ninguna** prueba.
  3. **La familia fitness tiene dos grafías y ambas casan.** `*FitnessTest` (**92** ficheros) y `FArch*Test` (**57** gates de arquitectura numerados por ADR). El primer intento protegía 57 ficheros de menos: un patrón que nombra una convención en vez del concepto protege la que el autor recordó. Cobertura medida tras el corte: **166** ficheros `.kt` de `v2/`.

  **Lo que esta decisión NO abre:** no autoriza retirar ninguna fila. La poda sigue exigiendo su prueba por garantías (equivalencia demostrada, recibo del harness). Lo que cierra es el limbo: hoy `unit legacy` tiene destino — si su fichero pertenece a una familia de §3, es intocable; si no, es migrable y necesita prueba como cualquier otra. Y la tabla de 79 clases sigue siendo un **censo**, no un veredicto: 0 de las 124 filas `@Disabled` promete un sucesor que no exista (H0.2), así que podar «por historia de commits» no elimina ninguna promesa rota, porque no hay ninguna.

  Evidencia: `:test-harness:test` **357 tests / 61 clases / 0 fallos**, y **dos** mutaciones 1:1 atributo a atributo, cada una verificada **aplicada** antes de leer su resultado (borrar el check del guard; borrar el regex `FArch`) y restauradas a hash limpio. La segunda existe porque la primera creí que cubría una familia que no cubría.

  Recordatorio de lo que H0.2 acaba de medir, porque es la trampa de este hito: **0** de las 124 filas promete un sucesor que no exista. Podar «por historia de commits» no elimina ninguna promesa rota, porque no hay ninguna.
- [x] **H0.6 Comprobación mínima:** 1 test compilación + 1 contrato real afectado + 1 negativo veraz del gate cambiado; build ZIP y smoke existente sin runtime QA; verificación integrada según política autorizada. Datos antes/después: archivos, clases, tiempo, classpath, tamaño de ZIP, falsos rojos. Integrar como preparación de **la siguiente entrega funcional H1**, sin RC solo de limpieza. **→ CERRADO (2026-10-18, sobre `3c3d1cf9`).**

  | Comprobación pedida | Ejecutada | Resultado |
  | --- | --- | --- |
  | 1 test compilación | `:test-harness:test --rerun-tasks` | exit 0, **357 tests / 61 clases / 0 fallos / 0 skips** |
  | 1 contrato real afectado | `PermanentContractArchitecturTest` (6 tests, verdes) | invariante de permanencia verificado sobre las 6 familias de §3 |
  | 1 negativo veraz del gate cambiado | **2** mutaciones, cada una verificada aplicada antes de leer su resultado | M1 borrar el check del guard → RED en la fila del guard; M2 borrar el regex `FArch` → RED en la fila de reconocimiento. Ficheros restaurados a hash limpio |
  | build ZIP | `:pipeline-application:distZip --rerun-tasks` | exit 0, 89 tareas ejecutadas, 1m 6s |
  | smoke sin runtime QA | binario del ZIP extraído en `/tmp` | `version` → `pipeline 0.62.0` exit 0; opción inválida → exit 1 con uso tipado |

  **Datos antes/después del ZIP:**

  | | antes (`2026-10-17 00:58`) | después (`2026-10-18 10:50`) | Δ |
  | --- | --- | --- | --- |
  | bytes comprimido | 93,463,309 | 93,059,240 | **−404,069 (−0,43 %)** |
  | bytes descomprimido | 101,897,833 | 101,456,304 | −441,529 |
  | ficheros | 52 | 51 | **−1** |
  | sha-256 (prefijo) | `dd450802…` | `7bc465ef…` | — |

  **El Δ NO es de este corte, y eso es demostrable sin reconstruir nada:** la distribución **no contiene `test-harness`** — 0 ocurrencias de `test-harness` o `InProcessProductProbeExecutor` en el ZIP, y ningún jar de ese módulo en el classpath del launcher. `3c3d1cf9` sólo toca `v2/test-harness/src/{main,test}/…/retirement/`, así que no puede mover un byte del ZIP por construcción. El Δ viene de los commits de documentación y de QA entre ambas construcciones. **Se registra como delta heredado, no como efecto medido del corte**, porque un número sin esa atribución es la forma que este hito ya pagó tres veces (ver H0.2).

  **Identidad EXACTA, las cinco superficies** (política de release del repo): raíz `pipelinek-0.62.0/`, runtime `pipeline 0.62.0`, 20 jars de producto en `0.62.0` de 46 (los otros 26 son dependencias de terceros: kotlin, BouncyCastle, Spring, sqlite-jdbc), sin sufijo de candidata. Los 4 plugins de ejemplo llevan versión propia `0.1.0` porque no son el producto.

  **Un falso rojo instructive:** el primer intento de este hito lanzó `:pipeline-release:distZip` — una ruta que suena a la del módulo `pipeline-release` y **no existe**. El `exit=0` que devolvió el shell era del `nohup … &`, no del build; el log decía `BUILD FAILED in 5s`. La segunda forma de leerlo habría sido Measure: 51 ficheros en un ZIP de ayer no es el ZIP nuevo. La tarea real se encontró **midiendo** `:pipeline-application:tasks --all`, no adivinando. Es la clase `gradle-success-on-a-skip-is-not-evidence` con un shell de por medio.

**Agent teams H0:** R-1 dependencias runtime/harness y build (toca `src/main` y `settings`), R-2 pruebas históricas/métodos Disabled (`src/test`), R-3 inventario/scope y comprobación de ZIP **solo lectura**. Integrador: política + compilación + commit de corte. No usar dos agentes editando `build.gradle.kts` simultáneamente.

**STOP:** si la poda obliga a construir `Harness2`, más ADTs o otra matriz de fidelidad, revertir al contrato simple Gradle/JUnit. Si se ve riesgo crítico sin contrato, NO borrarlo; aislar una sola prueba antes de seguir.

## H1 — Observación/consola realmente utilizable

**Valor:** `pipelinek console --follow` consume bytes exactos desde proceso activo, mantiene stdout/stderr, cursor y secretos protegidos. Incluye deuda real de `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-2 / OBS-3 aplicables), RP-2/RP-3/B3.

- [x] **H1.1** Verificar OBS falsificadores aún válidos en HEAD (`ObsAOutputStreamingCharacterisationTest`, `ObsPc208LevelBUatTest`) sin tratar los `@Disabled` como pruebas verdes. Reproducir **un** defecto visible real de entrada incremental/canal/cursor, o elegir siguiente defecto OBS vivo.

  **Cerrado 2026-10-18 en `main@28851ee5`.** Cuatro `@Disabled` cuya razón era *"the defect is real"* re-habilitados, con la transición medida y con dientes demostradas. Pero **el defecto real no era del producto, y eso es lo que H1.1 encontró**:

  ```text
  clase                       antes                  despues
  ObsA...Characterisation     tests=2 skipped=2     tests=2 skipped=0 f=0 e=0
  ObsPc208$Falsifiers         tests=10 skipped=2    tests=10 skipped=0 f=0 e=0
  ```

  La mitad de esa tabla es la trampa de la que H1.1 se guardaba: `skipped=2` bajo `BUILD SUCCESSFUL` no mide nada. Re-habilitadas, `f01` y `f04` pasaron 10 de 10 — y después `f04` leyó **5 de sus 10 líneas** y murió en *"line 6 must be present"*, el síntoma exacto que su propia razón `@Disabled` citaba.

  **La causa era la espera, no el Output Plane.** `awaitIncrement` devuelve en cuanto hay *más bytes que la línea base*, y una muestra a mitad de drenaje la satisface; `done` lo toca el productor al terminar su script, antes de que el agente haya drenado. `f09` ya llevaba esta lección medida en su propio KDoc y ya esperaba el sello —a estas filas nunca se les aplicó—. El arreglo es `awaitFullDrain`, compartido por `f01`/`f02`/`f04`/`f07`, que es exactamente su contrato común. **No** se aplicó a `f03`/`HappyPath` (un kill impide el sello) ni a `f10` (afirma sobre lecturas *durante* el drenaje): cada fila espera el hecho que su contrato necesita.

  Mutaciones, **re-verificadas después** del arreglo (una medida contra otro harness mide ese harness). Ambas aplicadas-verificadas antes de leer, y ambos targets restaurados a `git diff` vacío:

  ```text
  M-2  RedactingOutputIngress nunca hace commit   -> RED 7 de 10, f01 y f04 entre ellas
  M-3  OperationOutputStreams: stderr -> STDOUT  -> RED 2 de 10, f04 por la comprobacion nueva
  fiabilidad tras el arreglo                      -> 4 de 4 verdes
  ```

  Dos hallazgos que quedan abiertos y **no** se declaran cerrados aquí:

  - `f02` sobrevive a M-2 porque su aserción es una ausencia y un plano vacío la satisface. Verde sin haber medido. → **cerrado en H1.3** (marcador benigno presente + rojo bajo M-2).
  - `OperationOutputStreams.init` valida `runId`/`operationId` y **no** que los canales sean distintos: un `stderr` con canal `STDOUT` lo satisface. La suposición que la clase expresa no tiene invariante que la sostenga. → **cerrado en H1.3** (dos `require` de identidad de canal + `OperationOutputStreamsChannelInvariantTest`). **Medido como latente, no vivo**: la única construcción del repo era correcta. Y existía un **gemelo** en `runtime-api` con el mismo hole → cerrado también en H1.3.

- [x] **H1.2** Corregir productor→redactor→Output Plane→CLI sin segundo writer/event plane de bytes. Propiedad redacción antes de persistencia; no duplicar responsabilidades de recovery.

  **La propiedad de redacción NO tenía implementación. Cerrado en `3bf9f6a2`.** ADR-0049 §D8 afirma que el contenido de una credencial no aparece en superficies de evento. El contrato estaba aceptado y el código no hacía nada al respecto: `Main.kt` carries una comentario que dice que los secretos del store «se añaden independientemente», y no los añade ningún camino — lo verifiqué enumerando las escrituras de `registeredSecrets` antes de tocar nada, no leyéndolo.

  **La clase de defecto nueva: un falsificador con DOS causas de vacuidad.** Las tres filas eran incapaces de fallar por dos razones independientes, y **arreglar sólo la primera las dejaba verdes**:
  1. ejecutaban `sh("echo done")` — nada imprimible que poder filtrar;
  2. afirmaban sólo sobre `stdout`. La salida de `sh` no vive en el log de eventos sino en la transcripción durable, alcanzable por `shTranscript`.

  Cambiar `echo done` por `printenv`/`cat` **no** las vuelve rojas: siguen afirmando sobre la superficie donde la salida no puede llegar. Lo comprobé — la primera ronda de falsificadores salió 3/3 verde y la leí como «la redacción funciona». Era la segunda causa. Ambas tenían que cerrarse, y por eso el valor del falsificador está en haber medido el mecanismo en vez de accepting el verde.

  **La costura es el binding, y no el store.** Es el último punto donde el valor existe y el primero donde un proceso hijo puede leerlo. Ni antes —el store reclamaría lookahead para credenciales que la ejecución nunca enlaza, y cada byte reclamado es latencia de salida pagada en cada step— ni después.

  **Dos formas de secreto, dos registros distintos.** Los handles de env llevan el valor y se registran enteros. Los ficheros materializados se enlazan por su **ruta**, que va enmascarada a propósito (`JAVA_HOME`, `PATH`): redactar la ruta rompería el binding, y el propio executor ya afirma que una ruta enlazada **debe** aparecer. El contenido es otro secreto, y se registra desde la materialización retenida — `ProjectionResult.retainedMaterializations`, no olfateando strings.

  **Registro por registro, y lo medí antes de escribirlo.** `maxLiteralByteLength()` es el **máximo** de todo lo registrado y es el lookahead del `StreamingRedactor`: una sola clave SSH de 3 KB registrada entera obliga a retener 6 KB antes de emitir un byte, en cada step. Por registro la ventana es 2 × el registro más largo, y OpenSSH/PEM envuelven base64 a 70 columnas — ~140 bytes en vez de ~6 KB. El hueco que queda, escrito en el KDoc de producción y no escondido: un secreto *reconstruido* a través de registros, que necesita un redactor que re-divisione, no un literal mayor.

  ```text
  UatLocal008CredentialsTest clase completa     tests=27 failures=0 errors=0 skipped=1 (CR-BD-034, preexistente)
  MUTACION  anular el registro                  -> ROJAS 029, 030, 031 y 022, cada una con el mensaje de su contrato
  CR-BD-032 bajo la misma mutacion              -> sigue VERDE (control: la fila de ámbito no está acoplada a la costura)
  :pipeline-credentials-api / executor / domain tests=838 failures=0 errors=0 skipped=0
  ```

  **Un footgun latente encontrado al implementar.** `addSecret(handle)` llamaba `use`, que **borra el buffer al salir**: registrar una credencial enlazada la destruía antes de que `sh` corriera. Invisible porque todo call site actual registra un handle que acaba de construir y tira. De ahí `SecretHandle.peek` — «estoy mirando» y «he terminado con esto» son hechos distintos sobre la vida, y colapsarlos en un flag es exactamente cómo un wipe-on-exit se convierte en wipe-on-first-peek.

  **Dos filas afirmaban la fuga, y una de ellas era la única testigo de su propio contrato.**
  - CR-BD-022 afirmaba `U_P=admin:secret123` en la transcripción: **certificaba la fuga** que §D8 prohíbe. Invertida a dos afirmaciones — la clave presente (la vinculación llegó) y el valor ausente.
  - CR-BD-032 afirmaba valores para distinguir outer/inner/restored. Con redacción activa los tres renglones son `****` e idénticos, así que el contrato de ámbito que la fila existe para probar queda **inobservable**. Sus valores pasan a 7 bytes, por debajo de `MIN_MASKABLE_LENGTH` y por diseño no enmascarables. El comentario explica por qué **no** deben «mejorarse» a longitudes realistas: eso borraría en silencio la única cobertura de ámbito que existe.

  **Verifiqué el mecanismo antes de reescribir los tests**, porque «expected true but was false» admite dos explicaciones con arreglos opuestos: redacción (correcto, hay que arreglar el test) o truncamiento por ventana (defecto de producto, hay que arreglar el redactor). Metí el transcript en el mensaje de aserción y medí: `outer=**** / inner=**** / restored=****` — la salida **completa** sobrevive y sólo los valores se enmascaran. Era redacción.

  **Latente, NO corregido aquí.** `MaterializedCredential.close()` (credentials-api/spi) hace `handle?.let { /* handle cleanup if needed */ }` — un no-op disfrazado de trabajo, la clase «comentario que afirma una propiedad que el cableado no tiene». Sus únicos constructores `fromHandle` están en tests, así que no lo ejecuta producción: enumeré las construcciones antes de calificarlo. Fix de una línea, sin trazabilidad de milestone propia, y meterlo en un commit de seguridad sería peor que dejarlo escrito.
- [x] **H1.3** Solo test del seam de salida afectado + **1 UAT temporal** de CLI con stdout/stderr/cursor y negativo secreto si riesgo afectado. Reusar smoke instalado del RC. Borrar pruebas temporales y fixtures tras aceptación.

  **Cerrado el alcance que H1.1 dejó medido.** No hizo falta UAT nueva: los dos ítems eran locales y uno de ellos era de producto. Sin ampuляр el gate.

  **a) `f02` era verde por vacuidad. Cerrado.** Sus dos aserciones eran de AUSENCIA, y un Output Plane vacío las satisface — por eso sobrevivía a M-2. Ahora el productor escribe un marcador benigno **antes** de los fragmentos y la fila lo exige presente. Con M-2 (`RedactingOutputIngress` nunca hace commit) `f02` pasa de verde a **ROJA** por su propia aserción nueva. La fila ya no puede go green sobre un plano sin señal.

  **b) `OperationOutputStreams` expresaba una suposición de dos canales sin invariante que la sostuviera. Cerrado.** Su `init` comparaba `runId`/`operationId` y nada más, así que `stderr` sobre el canal `STDOUT` era un valor **legal y silenciosamente aceptado**: `select(setOf(STDERR))` devolvía lista vacía sobre una transcripción viva, y `all` rendía el mismo stream dos veces. Añadidos dos `require` de identidad de canal — identidad y no `!=`, porque los campos se **llaman** `stdout` y `stderr` y `!=` habría admitido el par invertido igual.

  ```text
  OperationOutputStreamsChannelInvariantTest   tests=4 skipped=0 failures=0 errors=0
  :pipeline-output completo                    tests=14 skipped=0 failures=0 errors=0
  M-4  se elimina el require de stderr         -> RED 1 de 4, la fila que apunta
  M-3  stderr sobre STDOUT, con el invariante  -> sigue COMPILANDO; RED 2 de 4
  f02 bajo M-2 (antes sobrevivia)             -> ROJA por la asercion nueva
  ```

  **Corrección de alcance: era LATENTE, no un defecto vivo.** Antes de dar esto por cerrado enumeré **todas** las construcciones del tipo en el repo, en vez de suponerlo. `of()` es la única, y construye el par correcto: ninguna ejecución produjo un par cruzado. Lo que faltaba era el invariante que lo mantendría cierto cuando aparezca el próximo call site. El mensaje de `aeecb92a` lo describe en términos de consecuencia vivida y eso no es exacto; el KDoc de producción ya dice LATENTE con la medición que lo sostiene.

  **c) El gemelo de `runtime-api`, con el mismo hole y la misma causa. Cerrado.** Existe un **segundo** `OperationOutputStreams` —este con `stdoutStream`/`stderrStream` anulables— en `pipeline-runtime-api`. Cerrar solo `:pipeline-output` habría dejado la forma rota en dos sitios. Su única construcción es `RuntimeIntrospectionPortStoreAdapter.streamsForOperation` (leído, no supuesto: `firstOrNull { endsWith("::$op::stdout") }` frente a `::stderr`, sufijos disjuntos), luego también latente.

  Aquí la primera versión del guard estaba **equivocada** y el test lo cazó en la primera ejecución: escribí `require(stdoutStream != stderrStream)`, que **rechaza `null != null`**, es decir `false`, es decir rechaza el estado *ninguno* — un valor legítimo, el de una operación que no declara streams. Un guard que se lee correcto y rechaza un estado real es peor que no tenerlo, porque el tipo pasa a prometer algo que no puede cumplir. La forma correcta es la disyunción de tres términos. Filas: las dos de rechazo dicen que el guard tiene dientes; la de los tres estados dice que no tiene de más. Un guard que sólo prueba que puede rechazar algo lo cumple uno que lo rechaza todo.

  ```text
  :pipeline-runtime-api completo              tests=16 skipped=0 failures=0 errors=0
  M-1  require(true) (guard sin dientes)       -> RED 2 de 4, exactamente las de rechazo
  :pipeline-runtime:compileKotlin --no-build-cache -> exit=0, recompilación real
  ```

  **Una corrección que la medición obligó.** Escribí que el invariante hacía el cruce «irrepresentable». **Es falso, y lo medí antes de dejar el KDoc**: `require` es un rechazo en runtime, no una restricción de tipos — ambos campos son `OutputStreamAddress` y una dirección sobre el canal STDOUT es una dirección válida. Lo que M-3 demuestra es *dónde* aflora el defecto: antes el par se construía en silencio y todo consumidor lo heredaba; ahora no existe valor que heredar. El KDoc de producción y el del test llevan la corrección escrita, porque una afirmación más fuerte que la comprobada es exactamente el defecto que este test existe para cazar.

  El defecto propio fue el **arnés**: escribí las dos filas de rechazo con la construcción **fuera** del `assertThrows`, y como `init` rechaza en construcción, el rechazo correcto se escapaba de la aserción y la fila reportaba un defecto de producto inexistente. Dos filas rojas con el mensaje exacto del invariante que acababa de funcionar. La construcción va dentro del lambda, y el porqué está en el KDoc de la fila.
  **d) La clase de validación de digest, y un test que **certificaba** el defecto. Cerrado.** Auditando por qué el predicado de formato vivía disperso encontré la respuesta: el dueño del formato, `Sha256`, declara "lowercase, 64 characters" en su KDoc pero no dejaba que nadie lo *comprobara*. Cada consumidor que recibía un digest de fuera tenía que decidir el formato solo, y medido 2026-10-18 varios lo decidían como `length == 64` a secas: 64 caracteres de cualquier tipo pasaban y llegaban como un digest que nunca podría coincidir con uno real. Un `require` cuyo mensaje promete "64 hex characters" mientras prueba sólo la longitud es la forma exacta del defecto.

  ```text
  :pipeline-domain:test (Sha256 + ArtifactHandle)   tests=14 skipped=0 failures=0 errors=0
  :pipeline-runtime-api:test                        tests=5  skipped=0 failures=0 errors=0
  :pipeline-step-sdk:files:test                     tests=22 skipped=0 failures=0 errors=0
  M-A  se quita takeIf(...) en DeleteDirExecutor    -> RED 1 de 22, la fila que apunta
  M-B  se quita takeIf(...) en CleanWsExecutor      -> RED 1 de 22, la FILA GEMELA; la de
                                                           deleteDir sobrevive -> las dos son
                                                           independientes, no un guard común
  ```

  El hallazgo importante no era el hole sino **el test que lo sostenía**: `WCL-S-002 deleteDir idempotent with pre-existing marker` escribía `knownSha = "abc123def456"` — 12 caracteres, no hex — y afirmaba que ese texto llegaba como `sha256`. La fila **certificaba el defecto** y por eso llevaba años verde. Corregido el fixture (un digest real) y añadida la fila que fija el comportamiento nuevo.

  **El guard refusaba era la forma elegante y la equivocada.** Ante un marker corrupto, un `require` atasca el workspace para siempre: una vez escrito un marker malo, ninguna ejecución posterior podría limpiar. La respuesta convergente —"esa cosa no es un marker", se limpia de nuevo— es segura *porque* borrar contenido ya borrado es un no-op. No es un silencio: hace el trabajo, y lo que se prohíbe es que el texto llegue como digest.

  La unificación es lo que deja el arreglo pequeño: `Sha256.isSha256Hex` es la respuesta del que acuña el formato, y los tres carriers (`ArchivedFileEntry`, `OperationInspection`, `OperationSummary`) más los dos ejecutores la usan. No es el helper genérico que había rechazado antes —`SemVer` acepta `A-F` a propósito y simplemente no la llama—, sino el predicado del dueño de un formato concreto.

- [ ] **H1.4** Integrar código de producto, cambios de poda aprobados, `main`, release candidate de valor real y handoff. `CERTIFIED` solo externo.

**Agent teams:** consola/lectura, escritor/redactor (seams distintos), integración del CLI; archivo compartido se asigna a uno. R-1 del H0 ya terminó antes.

**Refs:** `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md`, `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md`, ADR-M1, ADR-OBS-001..004, ADR-0088.

## H2 — Runtime durable: sin falsos éxitos ni efectos repetidos

**Valor:** kill/restart/retry de un pipeline real y recuperación sin duplicar un efecto confirmado ni hacer desaparecer bytes. RP-3/B3/B5; ADR-0103, ADR-0107, ADR-0075, ADR-S4-R1, `RECOVERY_DURABILITY.md`.

- [ ] Seleccionar un fallo vivo de retry/waitUntil/shell/recovery que se reproduzca en HEAD; no comenzar otro refactor ADT sin daño de producto.
- [ ] Corregir decisión pura y puerto de ejecución existente; evitar segundas autoridades. El cast `divergenceDetector as StrictFingerprintDivergenceDetector` es un seam que se puede hacer polimórfico si bloquea un adaptador real.
- [ ] Pruebas de dominio afectadas + **1 HF3 temporal kill/restart**, AAT de corrupción solo si el decoder cambió; sustituir por la mínima garantía permanente (no conservar ambos testkit e E2E).
- [ ] Limpieza, integración y RC de mejora real, sin ampliar harness.

**Agent teams:** decider dominio, runtime/application, revisor UAT HF3 en entorno separado. H2 puede avanzar en paralelo con H3 con módulos distintos.

## H3 — SDK/HTTP externo usable

**Valor:** consumidor fuera del repo compila SDK, carga plugin HTTP y obtiene respuesta/error con semántica correcta. RP-6-C/B2/B4, `STEP_PLUGIN_SDK.md`, `P2_MAVEN_PUBLICATION.md`, ADR-0070, ADR-0089, ADR-PK-DF-001.

- [ ] Medir API y HTTP real contra distribución/SDK actuales; completar **una** capacidad incompleta (streaming, timeout, status o cuerpo), sin duplicar modelo HTTP en `pipeline-domain` y SDK.
- [ ] 1 test barato de handler/codec y **1 E2E temporal consumidor independiente** desde ZIP/Maven; rechazo incompatible solo si se cambió contrato de admisión. No 14 escenarios equivalentes por plugin.
- [ ] Empaquetar JAR/SDK/BOM definidos, publicar RC producto y actualizar refs; si repositorio Maven externo no configurado, declarar `BLOCKED_EXTERNAL` solo esa fase.

**Agent teams:** dominio/contrato HTTP, implementación plugin, consumidor externo. H2/H3 no comparten ejecución durable simultáneamente sin contract freeze.

## H4 — Candidate Production Ready (riesgos que importan)

**Valor:** usuarios pueden instalar la candidata y ejecutar DSL/Steps/SDK; recuperación, secretos, filesystem y distribución fiables. RP-4/RP-5/RP-7/B4..B6, `DISTRIBUTION_RELEASE_SPEC.md`, ADR-0099/0105/PK-DF-001/PK-QV-004.

- [ ] Reparar únicamente P0/P1 de producto realmente abiertos; inventario de compatibilidad pública, runtime, credenciales, SDK y provenance. No transformar TODO histórico en prerequisito mecánico.
- [ ] `releaseGate` y distribución exacta de 5 assets, sin JAR de QA ni fuentes/fixtures extra innecesarias. Revisar contenido y consumibilidad de SDK; GitHub genera snapshots automáticos source zip pero el producto son binarios/SDK.
- [ ] **1 smoke instalado real** + pruebas críticas de regresión o HF3/HF4 cuando riesgo lo exige; certifier externo G10. Resultados de `check`/integración en etapa centralizada acordada. `0/skip` no vale. V1 solo deprecación documentada si su retiro aún pendiente.
- [ ] Publicar candidata desde SHA integrado en `main`; `CERTIFIED`/`STABLE` solo con autoridad y evidencia real.

## H5 — Retirada final V1 y datos/test antiguos

**Valor:** menos rutas de ejecución, menos upkeep. B7, `RESPONSIBILITY_MIGRATION_ROADMAP.md`, `EXECUTION_MODEL_MIGRATION.md`, `ROADMAP.md`.

- [ ] Caracterizar consumidor V1 restante; moverlo a V2 o eliminar si contrato obsoleto. No mantener dos dispatchers solo por tests viejos.
- [ ] Retirar V1, shims, docs activas engañosas y pruebas históricas redundantes junto a su código; **1** smoke V2 de paridad/compatibilidad, HF requerido para casos críticos.
- [ ] Verificar dependencia inversa y paquete distribuidor, integración final, nueva RC, test temporal retirado.

## Transición de tests durante todos los hitos

**DEV** tests afectados y compile; **MERGE** afectados + contrato crítico; **INTEGRACIÓN** full centralizada según política aceptada; **RC** distribución real, un consumidor externo y certifier; **NUNCA** full suite por cada edición ni E2E persistidos por defecto. Usar `delivery-first-testing` `harness-economics`, `change-impact`, `ephemeral-uat`, `regression-promotion`, `failure-triage`, `merge-assurance`, `integration-assurance`, `release-assurance` solo bajo el router. `UAT/E2E/AAT` temporales con `mktemp -d`, resultados registrados en PR/SDDK, borrado propio antes de SHA release.

## Glosario breve

**Capability**: comportamiento prometido al usuario; **garantía**: propiedad que no puede romperse; **seam**: frontera entre decisiones y efectos; **HF**: fidelidad (in-process/instalado/restart/etc.); **RC**: candidata inmutable, no aprobación estable; **certifier**: entidad externa que ejecuta y publica veredicto G10; **UAT/E2E/AAT efímera**: prueba de ciclo, desaparece tras aceptación y sustitución cuando hace falta; **regresión**: prueba mínima permanente que detecta un defecto crítico; **drift**: ejecución real no corresponde a lo que el test dice cubrir; **BLOCKED_POLICY**: propuesta incompatible con ADR/norma aún no ratificada; **AAT**: caso adversarial de riesgo real, no un rito por bloque.
