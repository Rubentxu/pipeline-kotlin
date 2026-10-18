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
| H0.1 | `BLOCKED_POLICY` | `ADR-PK-DF-002` sigue `status: proposed`. Enmiendas enumeradas pero **no ratificadas**. Requiere decisión del operador. |
| H0.2 | `NOT_RUN` — bloqueado por **evidencia**, no por permiso | Medido con `tools/disabled_equivalence_audit.py`: **47 de 51** claims de equivalencia no resuelven a un test vivo. `test_portfolio.py` reclasificó 16 candidatos a `R0_ARCHIVED_TRACEABILITY`; población `R0_PRUNE_DISABLED_METHODS` **17 → 1**. **0 borrados autorizados.** |
| H0.3 | `DONE` | `5e816d84`. `InProcessProductProbeExecutor` `src/main`→`src/test`; `implementation`→`testImplementation`. Verificado sobre el ZIP instalado, no en el árbol. Guardarraíl `FArch021QaModulesStayOffRuntimeClasspathTest` mutation-tested en ambos sentidos. **Revalidado en HEAD**: los dos únicos usos restantes de `:test-harness` en `pipeline-application` son `testImplementation` (`:212`) y `laneReportClasspath` (`:702`). |
| H0.4 | `BLOCKED` por H0.1 | El módulo sigue vivo: `settings.gradle.kts:102`, 89 ficheros Kotlin. Su consecuencia observable es **`FArchM3` en ROJO honesto**: `ProcessBuilder` en 4 ficheros de `test-harness/src/main`. La única cura es el borrado; no se silencia ni se debilita el fitness. |
| H0.5 | `NOT started` | — |
| H0.6 | parcial | Las comprobaciones de H0.3/R1 se ejecutaron (compile, `installDist`, smoke `events verify`). La integración como preparación de H1 no ocurrió. |

**Lo que este bloque NO hace.** No ratifica la ADR, no autoriza ninguna poda, y no convierte
`BLOCKED_POLICY` en progreso. Marcar H0.3 `DONE` es aplicar una regla aceptada a un commit ya
integrado; las cuatro amendments de `ADR-PK-DF-002` siguen requiriendo firma del operador.

## Norte y criterio de prioridad

**Primero capacidad útil, luego solo pruebas que detecten defectos reales.** Desde RC6 se tocaron 9.530 líneas en QA frente a 437 de producto; invertir tendencia. Una unidad de trabajo sin consumidor real queda subordinada. Por defecto ninguna feature puede crear subsistemas nuevos de harness, sidecars, manifests, routers, catálogos o múltiples E2E. La excepción P0 exige defecto reproducido.

## H0 — CORTAR EL MONSTRUO: retirar QA embebida sin desmontar el producto

**Valor entregado:** ZIP/SDK más pequeño, build/contribución sin framework de auto-certificación y bucle rápido sin dependencias de QA. **No esperar a reparar la FSM de `CycleRunner`, la ontología de `ImpactResolver` y el `Gate`: el objetivo es desinstalarlos.**

**Refs vigentes:** `TEST_EXECUTION_LANES_SPEC.md`, `TEST_IMPACT_AND_EVIDENCE_SPEC.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, ADR-0072, ADR-PK-QV-003, ADR-0105, ADR-PK-DF-002 (pendiente), `RESPONSIBILITY_MIGRATION_ROADMAP.md`.

- [ ] **H0.1 Aceptar o bloquear explícitamente política:** revisar ADR-PK-DF-002, proponer enmiendas mínimas a `AGENTS.md`, roadmap y specs. Si no se autoriza cambiar la obligación `check` y borrado legacy, continuar solo R0 y separar dependencia; etiquetar `BLOCKED_POLICY`, nunca dar por aprobada una relajación. **→ `BLOCKED_POLICY` (2026-10-18): amendments propuestas, ninguna ratificada.**
- [ ] **H0.2 Borrado inmediato de código muerto:** podar métodos `@Disabled` históricos (p.ej. en `CoreSleepRegistryPrimaryFitnessTest`), guardas de conteo de líneas (candidato `CoordinatorGrowthGuardrailTest`), restos sin consumidor, reglas duplicadas de inspección textual de detalles privados. **No** sustituir por tests equivalentes del framework. Guardar un solo test funcional si era una ley real. **→ `NOT_RUN` (2026-10-18): medido antes de cortar; la etiqueta autorizaba 54 borrados de archivo de trazabilidad. Población 17 → 1, 0 autorizados.**
- [x] **H0.3 Fuera del runtime:** eliminar o reclasificar `InProcessProductProbeExecutor` (`application/src/main/harness`), su ServiceLoader y dependencia `implementation(project(":test-harness"))`; conservar HF1 por tests de `PipelineRule`/Step. Demostrar que distribución no contiene ese QA. **No** retirar `pipeline-event-harness`, consumidor de `events verify`.
- [ ] **H0.4 Eliminar los subsistemas completos:** `v2/test-harness/{cycle,retirement,lanes,plan,catalog,util}` y sus 51 tests después de verificar owners; borrar `laneReport`, `validationPlan`, `scripts/validation-gate.py`, entrada de settings y referencias runtime. Sustituir por tasks Gradle que YA existen, y RC externa. **No crear otro Kotlin harness más pequeño**. **→ `BLOCKED` por H0.1 (2026-10-18). Su síntoma observable es `FArchM3` ROJO, y es correcto: `ProcessBuilder` en 4 ficheros de `test-harness/src/main`.**
- [ ] **H0.5 Poda del portafolio real:** revisar candidatas `migrated-pending`, UAT de binario y fitness de estructura. Retirar en tandas por garantías y evidencia, conservar críticos hasta equivalencia. Eliminar métodos @Disabled por historia de commits. No confundir `migrated-pending` con certifier verificado.
- [ ] **H0.6 Comprobación mínima:** 1 test compilación + 1 contrato real afectado + 1 negativo veraz del gate cambiado; build ZIP y smoke existente sin runtime QA; verificación integrada según política autorizada. Datos antes/después: archivos, clases, tiempo, classpath, tamaño de ZIP, falsos rojos. Integrar como preparación de **la siguiente entrega funcional H1**, sin RC solo de limpieza.

**Agent teams H0:** R-1 dependencias runtime/harness y build (toca `src/main` y `settings`), R-2 pruebas históricas/métodos Disabled (`src/test`), R-3 inventario/scope y comprobación de ZIP **solo lectura**. Integrador: política + compilación + commit de corte. No usar dos agentes editando `build.gradle.kts` simultáneamente.

**STOP:** si la poda obliga a construir `Harness2`, más ADTs o otra matriz de fidelidad, revertir al contrato simple Gradle/JUnit. Si se ve riesgo crítico sin contrato, NO borrarlo; aislar una sola prueba antes de seguir.

## H1 — Observación/consola realmente utilizable

**Valor:** `pipelinek console --follow` consume bytes exactos desde proceso activo, mantiene stdout/stderr, cursor y secretos protegidos. Incluye deuda real de `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-2 / OBS-3 aplicables), RP-2/RP-3/B3.

- [ ] **H1.1** Verificar OBS falsificadores aún válidos en HEAD (`ObsAOutputStreamingCharacterisationTest`, `ObsPc208LevelBUatTest`) sin tratar los `@Disabled` como pruebas verdes. Reproducir **un** defecto visible real de entrada incremental/canal/cursor, o elegir siguiente defecto OBS vivo.
- [ ] **H1.2** Corregir productor→redactor→Output Plane→CLI sin segundo writer/event plane de bytes. Propiedad redacción antes de persistencia; no duplicar responsabilidades de recovery.
- [ ] **H1.3** Solo test del seam de salida afectado + **1 UAT temporal** de CLI con stdout/stderr/cursor y negativo secreto si riesgo afectado. Reusar smoke instalado del RC. Borrar pruebas temporales y fixtures tras aceptación.
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
