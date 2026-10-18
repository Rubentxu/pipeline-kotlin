---
type: adr
id: ADR-PK-DF-002
title: "Product First — retirar el framework de QA embebido y limitar pruebas por riesgo"
status: accepted
date: 2026-10-17
ratified: 2026-10-18
deciders: "Rubentxu (product owner): APROBADO 2026-10-18 (gate humano concedido explícitamente)"
related:
  - ADR-0071
  - ADR-0072
  - ADR-0074
  - ADR-0099
  - ADR-0105
  - ADR-PK-QV-003
  - ADR-PK-QV-004
  - ADR-PK-DF-001
---

# ADR-PK-DF-002 — Product First, retirada radical del test-harness interno

**ACEPTADA 2026-10-18.** Sustituye las políticas incompatibles enumeradas abajo, **cada una en su momento y por su propia enmienda**, no de golpe: la decisión 4 sigue condicionada a la enmienda de `AGENTS.md`/`ROADMAP.md`, y la decisión 2 tiene dos cláusulas con estados distintos (ver abajo). El gate humano fue concedido explícitamente por el product owner el 2026-10-18.

> **Alcance de esta ratificación — lo que NO resuelve.** Esta aceptación **no resuelve el conflicto del registro de verdad de estados** descrito en [`CONFLICT-ADR-STATUS-REGISTRY.md`](CONFLICT-ADR-STATUS-REGISTRY.md), que sigue abierto: 21 ADR más permanecen en `proposed`, incluidas `ADR-0071`, `ADR-0072` y `ADR-0074`, que esta misma ADR lista en su `related:` y que `AGENTS.md` invoca como autoridad MANDATORY de la STEP CONSTITUTION. Aquél documento afirma que pedir la ratificación de esta ADR sin más «no es una decisión binaria»; la decisión del product owner es explícita y se aplica, pero su alcance es **esta** ADR y el desbloqueo de H0.1, **no** la coherencia del registro en su conjunto. Resolver eso exige decidir cuál es el registro de verdad y aplicarlo a todo el rango — es un bloque propio, no una línea de este diff.

## Contexto comprobable — y su corrección tras la medición

RC6→HEAD: 437 líneas tocadas de producto frente a 5.788 de tests y 3.742 de fuentes de test-harness. V2 tiene `:test-harness` como módulo Kotlin de 38 fuentes en `src/main` + 51 tests, con catálogos, planes, sidecars, ciclo y motor de retirada.

> **Corrección 2026-10-18.** El texto original afirmaba además que «`pipeline-application` arrastra `:test-harness` en `implementation` y aloja `InProcessProductProbeExecutor` en `src/main`», y de ahí concluía que «la infraestructura QA ha entrado en la distribución ejecutable». **Medido en HEAD, ambas son falsas**: las únicas dos referencias del repositorio son `testImplementation(project(":test-harness"))` (línea 212) y `laneReportClasspath(project(":test-harness"))` (línea 702) de `pipeline-application/build.gradle.kts`, ambas **test-side** [**corregido el 2026-10-18 más tarde ese mismo día**: la segunda era una configuración de tarea que servía a un recibo que nadie leía, y se eliminó; hoy queda una sola referencia]; y `InProcessProductProbeExecutor` vive en `pipeline-application/src/test/kotlin/.../application/harness/`. La fuga se cerró en `5e816d84` y hoy la impide mecánicamente `FArch021QaModulesStayOffRuntimeClasspathTest`, que además afirma que su propio escaneo no es vacuoso.
>
> **Qué sobrevive y qué no.** El argumento económico —deriva de coste y de responsabilidad— sigue en pie y es el que sostiene esta ADR. El de severidad —superficie de fallo en el binario distribuido— **no**: era inexistente al ratificar. Una ADR cuyo contexto se corrige a la baja no se reescribe hacia abajo: se registra la medición y se acepta sobre lo que queda, que es lo suficiente para decidir.

Por tanto H0.4 **no** es una fuga que cerrar: es una pregunta de existencia del módulo. `PRODUCT_FIRST_PRODUCTION_READY_2026-10-17.md` declaraba su «síntoma observable es FArchM3 ROJO»; medido el 2026-10-18, `FArchM3CanonicalTaskRuntimeTest` está **7/7 verde**, porque `KotlinSourceSanitizer` enmascara los literales de cadena y el único `ProcessBuilder(` no declarado de `:test-harness` es precisamente el literal que `retirement/TestClassifier.kt` usa para *nombrar* la API que busca. Ese síntoma no existe y no debe volver a citarse como bloqueo.

## Decisiones (sustituyen **solo tras aceptación** políticas incompatibles, y solo cuando su enmienda está hecha)

1. **La garantía, no el test, es la unidad de conservación.** Por cada comportamiento crítico hay una prueba mínima discriminante y en su propietario; pruebas redundantes, `@Disabled` obsoletas, métricas de forma y categorías migradas se eliminan por lotes, con un sustituto único si hace falta.
2. **Desmontar `:test-harness`.** Esta decisión tiene **dos cláusulas con estados distintos**, y confundirlas es lo que produjo el bloqueo falso de H0.4:

   2a. **CERRADA Y PROBADA — la cláusula binaria.** *Nunca dejar `implementation(project(":test-harness"))` en el binario.* Hoy no existe tal arista, y `FArch021QaModulesStayOffRuntimeClasspathTest` la impide mecánicamente por conjunto nombrado de módulos QA (no por patrón de nombre), stripping comentarios, fallo cerrado ante build-file vacío, y con una fila propia que falla si el escaneo dejara de encontrar `:test-harness` — es decir, la guarda no puede volverse vacua al desaparecer el sujeto. **Nada que hacer aquí.**

   2b. **ABIERTA — el desmontaje.** `cycle` (13 ficheros), `plan` (10), `retirement` (10), `catalog` (1) y `util` (1): **35** ficheros —`lanes` (3) ya no está, cerrado el 2026-10-18—. Cifras de referencia: 38 ficheros / 8.250 líneas en `src/main` antes del corte., que **no se transfieren 1:1**. Se borran cuando su garantía esté ya cubierta por Gradle/JUnit, pruebas de dominio y el certifier externo. Prohibido crear un segundo sistema de gating para reemplazar al primero.

   **Precondición nombrada para 2b — CORREGIDA 2026-10-18, y la corrección la cierra.** La versión original de esta precondición afirmaba que «`:test-harness` es hoy el dueño del clasificador de *lanes*, que es la razón de que el bucle interior (`just t`) sea rápido en un módulo que si no cuesta ~1.500 s», y exigía un sustituto medido antes de cortar. **Medido, es falso en sus tres partes**: (a) la selección de lane que corre la suite es `-Plane=fast|installed|release`, implementado en `pipeline-application/build.gradle.kts`, y precede a `lanes/` sin depender de él; (b) `lanes/` no tenía **ningún** importador fuera de sí mismo — `TestLane`, `LaneSpec` y `LaneReport` tienen cero referencias en todo `v2/`, y `LanePlanner` sólo aparecía en la tarea `laneReport`; (c) `lane-planner.json` no lo leía nadie: ni un test, ni un gate, ni una receta de `justfile` — la tarea corría sólo si un humano escribía su nombre a mano.

   El sustituto que la precondición pedía **ya existía**. Por tanto el orden correcto —«sustituto medido → corte → borrado»— se cumple entero en la primera cláusula y se ejecutó: primero se borró `laneReport` (2026-10-18), después el paquete `harness.lanes` completo (3 ficheros de `src/main` + 4 de `src/test`, el mismo día).

   **Por qué no se pierde cobertura al borrar los 4 tests del paquete.** Los dos fitness (`LaneFidelityOrdinalDiscriminatorFitnessTest`, `HandRolledJsonWriterFitnessTest`) no vigilan nada fuera de él: `error()` si `src/main/.../harness/lanes` no existe, y su sujeto son `LaneSpec.minimumFidelity`, `LanePlanner.laneFor` y `LanePlannerMain.realSpecsFromCatalogue` — que se van con el paquete. `LaneFidelity…` era hermano de `CycleFidelityStringDiscriminatorFitnessTest` (misma clase de defecto, campo distinto: `PromotionEvidence.fidelity`), que **permanece** y sigue cubriendo `catalog.ContractCatalogue.Fidelity`. `LanePlannerTest`/`LanePlannerMainTest` caracterizaban `LanePlanner.exampleSpecs()`, un catálogo de ejemplo, no el catálogo real. Cobertura perdida: **cero**.

   **Lo que la precondición acertaba por otra razón, y se conserva.** El riesgo que temía —«quitar el módulo antes de eso cambia un gate lento por un gate que nadie sabe correr»— es real para los subsistemas que sí gobiernan la ejecución. `plan/`, `cycle/` y `retirement/` no se tocan aquí: siguen siendo dueños de comportamiento que un gate usa. La precondición era una regla sensata aplicada al sujeto equivocado; corregir el sujeto no la deroga, la restringe.
   **Por qué `plan/` NO es el siguiente corte, medido 2026-10-18.** La regla de la precondición se aplicó a `lanes/` porque allí era falsa; aquí es **cierta**, y por eso el corte se detiene:

   - `plan/` alimenta la **allowlist viva** de `FArchM3CanonicalTaskRuntimeTest`, que declara sus cuatro sitios de lanzamiento de proceso: `PlanCli`, `GateCli`, `Gate`, `InstalledDistribution`. Borrarlos obliga a editar una guarda hoy verde — el corte **debilitaría** el invariante en vez de cumplirlo.
   - `scripts/validation-gate.py` es un gate **real, no auto-certificado**: construye el classpath, lanza el JVM y **rechaza** evidencia vacía u obsoleta. No tiene *llamador* (`.github/workflows/` está vacío, ninguna receta del `justfile`, ningún otro script lo invoca), lo que lo hace **dormente**, no no-verificado — defecto distinto del de `laneReport`, que además era un recibo que nadie comprobaba.
   - Su forma de lanzador **no** es descuido: `settings.gradle.kts` toma un candado exclusivo sobre el checkout, y una tarea Gradle que arrancase más builds se encontraría con el candado que ella misma tiene. Está medido y documentado en `test-harness/build.gradle.kts`.

   Lo que sí procede es **medir su coste**, no borrarlo: si `validationPlan` no ahorra trabajo real frente a leer `git diff --name-only`, su sustitución es un `check` de Gradle, y eso es un corte con sustituto, no un corte de owners.

3. **Excepción por producto:** `pipeline-event-harness` se conserva porque el consumidor real `pipeline events verify` lo utiliza. El nombre "harness" no es criterio de baja.
4. **Ciclo habitual reducido:** desarrollo ejecuta test afectado, merge ejecuta área + contratos críticos, integración centralizada/RC asume matriz completa correspondiente, instalable y certifier externo. El `check` global deja de ser condición de **cada** merge **solo tras aprobar la enmienda** a `AGENTS.md` y `ROADMAP.md`; no se elimina como mecanismo de RC/integración.
5. **UAT/AAT/E2E efímeras**: una ejecución externa por comportamiento nuevo cuando un test unitario no puede observarlo; 0 E2E en cambios ordinarios. No guardar cada UAT como regresión. Reusar smoke instalado por release. El test temporal se borra tras verificar aceptación y promover garantía esencial.
6. **La poda histórica se hace con criterio de equivalencia:** ADR-PK-QV-003 ya permite eliminar pruebas creadas en sesión; **no** autoriza eliminar legacy marcadas `migrated-pending` sin equivalencia. Para una retirada masiva se requiere consentimiento explícito de enmienda a `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4 / AGENTS y evidencia por **garantía**, no 5 recibos por fichero. No ocultar pérdidas de secretos/replay/aislamiento bajo "duplicada".
7. **Eliminar mecanismos de auto-certificación ad hoc** (sidecar por suite, `ImpactResolver`, `CycleRunner` y `DeletionGuard`) solo al probar dos hechos: las tareas seleccionadas realmente ejecutaron tests >0 y el artefacto de RC exacto está verificado por la autoridad externa. Ni XML suelto ni SHA inventado son veredicto.
8. **Presupuesto del QA**: un bloque de producto no puede acabar solo con más carriers, fitness/infraestructura QA o documentación de cómo probarlos. Las excepciones son P0 real y medido (secretos, pérdida de datos, falso verde grave, release corrupta). Medir latencia y falsos rojos, no introducir métricas de porcentajes como gates nuevos.

## Enmiendas necesarias tras aceptar la ADR

**Estado 2026-10-18:** ninguna está hecha todavía. La aceptación de la ADR **no las ejecuta por sí sola** — es lo que mantiene la decisión 4 sin contradecir `AGENTS.md`, cuya regla de `check` por ronda sigue vigente hasta que su propio diff se aplique. Cada enmienda es un cambio normativo con su propio diff revisable; agruparlas todas en el commit de ratificación las volvería invisibles.

- `AGENTS.md`: reemplazar regla de `check` global por merge selectivo + integración/RC centralizada; mantener reglas de identidad, evidencia y escalado crítico. Eliminar comandos/normas de test-harness local que se retire, sin afectar SDDK ni publicación.
- `docs/v2/05-roadmap/ROADMAP.md`: conservar como **única autoridad** RP/B0–B7, integrar la poda como WorkUnit de desbloqueo de producto (no roadmap paralelo), registrar DONE solo tras código eliminado e integración real.
- `docs/v2/05-roadmap/RESPONSIBILITY_MIGRATION_ROADMAP.md`: proponer agrupar pruebas por *garantía/huella de fallo*, no por fichero, y permitir retirar `migrated-pending` con equivalencia externa agrupada y aprobación; no eliminar el requisito de demostrar nivel HF suficiente.
- `docs/v2/03-specifications/TEST_IMPACT_AND_EVIDENCE_SPEC.md` / `TEST_EXECUTION_LANES_SPEC.md` / `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`: registrar que el ejecutor custom se retira; **norma semántica** (evidencia veraz, aislamiento, Fidelidad HF) permanece en Gradle / certifier / skill. Si la SPEC exige `CycleManifest/DeletionGuard`, enmendarla o dejar bloqueo, no simular que existe.
- `ADR-0105` y `ADR-PK-QV-004`: no tocar propiedad de certificación externa ni RC tras limpieza; solo producto publica candidata, certifier publica check.

## Sustitutos y prohibiciones

- Gradle `Test` con resultados reales y task identity, `:pipeline-release:releaseGate`, APIs/product contracts existentes, consumidor externo en RC y `delivery-first-testing` ya instalada. No nuevo catalog/router/SaaS de QA/servicio/daemon ni serialización adicional.
- No borrar contratos reales por ser lentos: **moverlos** a la etapa correcta o sustituirlos por equivalentes más baratos y discriminantes. `SKIP/NOT_RUN/BLOCKED` no son `PASS`.
- No usar porcentajes de líneas para decidir seguridad; únicamente economía e impacto.

## Alternativas descartadas

A. **Seguir corrigiendo la FSM, sidecar y fidelity matrix del `:test-harness`:** conserva coste y fragilidad sin comportamiento nuevo de PipelineK. Solo aceptable como parche P0 temporal si el módulo no puede retirarse en el ciclo.

B. **Borrar todas las UAT sin equivalencia:** rápido pero rompe garantías y contradice ADR aceptada. No autorizado.

C. **Cambiar todo a GitHub Actions**: no forma parte de la infraestructura CI local vigente, genera nueva dependencia.

## Observabilidad y criterios de éxito

- Ninguna dependencia transitiva de `:test-harness` llega a ZIP/SDK; `pipeline events verify` continúa funcionando.
- Menos clases Kotlin de QA, menor coste medido de feedback y de cambio test-only.
- Gate selectivo sabe fallar al romper su contrato, RC instala ZIP real y certifier decide; no falso verde y no doble certificación.
- Al menos una feature/defecto real solucionado y entregado por ciclo. La poda es una fase de la entrega, nunca producto autónomo.
