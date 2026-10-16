# SPEC-PK-QV-01 — Plan de impacto y evidencia honesta

**Estado:** PROPOSED. Refiere: `PIPELINE_TEST_HARNESS.md`, ADR-0072, `CERTIFICATION_PROTOCOL.md`, `TEST_STRATEGY.md`.
**Implementación candidata:** `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/{catalog,plan}/` y adaptadores existentes. Antes de crear nuevos tipos verificar `ContractCatalogue`, `ValidationPlan`, `ImpactResolver`, `EvidenceReader`, `Dispatch`, `Gate`.

## Requisitos

- **R1 Identidad:** la obligación se identifica por `ContractId`/`CapabilityId` y propietario; el test seleccionado no se identifica solo por nombre simple de clase (`FooTest` puede repetirse en módulos). Identidad de suite = `module + FQCN + caseId` cuando aplique.
- **R2 Inputs:** `ChangedInput` admite `KotlinSource`, `JavaSource`, `BuildLogic`, `ProtoSchema`, `ServiceDescriptor`, `Resource`, `ExecutableScript`, `Fixture`, `UnknownProduction`, `NonProduction`. Clasificación sin side effects en el decider.
- **R3 Fail closed:** `UnknownProduction` no puede convertirse en `Selected(emptySet())`; `NonProduction` puede producir un plan vacío justificado. Un recurso que cambia `ServiceLoader` es producto.
- **R4 Selección:** reglas de globs con propietarios, escalamiento de nivel y suites obligatorias. `ContractCatalogueFitness` comprueba `source→capability` y `capability→test`, incluye recursos y módulos nuevos.
- **R5 Nivel:** ADT cerrada HF0..HF6 y `ObservedBoundary` obtenido por runner, no inferido del nombre. El nivel exigido y el observado se comparan; niveles HF5/HF6 no colapsan en HF4.
- **R6 Evidencia XML:** parser seguro XXE, considera `tests`, `skipped`, `errors`, `failures`; requiere al menos una prueba ejecutada, ausencia de fallos y match de suite FQCN, invocación y commit. Reportes de ejecución anterior no valen.
- **R7 Proveniencia:** `expectedCommit != observedCommit` → `StaleEvidence`; el commit observado procede del **binario** mediante `version --json` cuando certifica distribución, no del Git local. Para tests de decisión sin binario, vincular a SHA del artefacto construido y reportado en esa invocación (no falsificar que hubo binario).
- **R8 Pureza:** planificar y adjudicar es puro; leer Git/XML/filesystem y ejecutar Gradle son adaptadores separados. `Gate` coordina, no decide semántica.
- **R9 Rendimiento:** agrupar filtros por módulo/proyecto Gradle y evitar `--rerun-tasks` global salvo necesidad de evidencia fresca; limpiar únicamente los reportes de invocación afectada.
- **R10 Compatibilidad:** capa adaptadora desde las firmas actuales `ValidationPlan`, `SuitePolicy` y `Gate` sin cambiar API pública de producto; versiones de esquema persistido explícitas.

## Modelo ADT de referencia (no copiar si ya existe equivalente)

```kotlin
@JvmInline value class CapabilityId(val value: String)
@JvmInline value class ContractId(val value: String)

enum class HarnessFidelity { HF0, HF1, HF2, HF3, HF4, HF5, HF6 }

sealed interface ChangedInput {
    data class KotlinSource(val path: String) : ChangedInput
    data class ProtoSchema(val path: String) : ChangedInput
    data class ServiceDescriptor(val path: String) : ChangedInput
    data class BuildLogic(val path: String) : ChangedInput
    data class OtherProduction(val path: String) : ChangedInput
    data class NonProduction(val path: String) : ChangedInput
}

sealed interface ImpactDecision {
    data class Selected(val obligations: Set<ContractId>) : ImpactDecision
    data class Refused(val reasons: NonEmptyList<Refusal>) : ImpactDecision
}

sealed interface ReportDecision {
    data class Valid(val executed: Int, val skipped: Int) : ReportDecision
    data class Refused(val cause: Cause) : ReportDecision
    enum class Cause { EMPTY, ALL_SKIPPED, FAILURES, ERRORS, CORRUPT, WRONG_SUITE, WRONG_INVOCATION }
}
```

`NonEmptyList<T>` solo es pseudotipo contractual aquí: usar un tipo ya presente o implementar en `v2/test-harness` si no existe. No inventar otra librería FP sin criterio de compilación/dependencias.

## Propiedades algebraicas

- **Determinismo:** `resolve(changes,catalogue) == resolve(permutation(changes),catalogue)`.
- **Monotonicidad conservadora:** agregar un cambio de producto jamás elimina una obligación del plan seleccionado.
- **Incertidumbre visible:** si existe algún cambio productivo sin dueño, el resultado es `Refused`, incluso si otras rutas sí tienen dueños.
- **No vacuidad:** `tests=0` o `skipped=tests` → `Refused`.
- **Independencia del nombre:** dos FQCN iguales en nombre simple pero módulos distintos no cuentan como una evidencia.
- **Proveniencia:** mismo XML con SHA binario incorrecto → `Refused(StaleEvidence)`.

## Escenarios efímeros por primera implementación

AAT-IMP-01: cambio `*.proto` y `META-INF/services` selecciona o rehúsa explícitamente.
AAT-IMP-02: `TEST-*.xml` con `tests=0`, `skipped=0` no pasa.
AAT-IMP-03: `tests=12`, `skipped=12`, `failures=errors=0` no pasa.
UAT-IMP-04: operador consulta plan y ve capacidad, dueño, nivel y acción; reportes pertenecen al SHA.
AAT-IMP-05: falsear `buildCommit` binario no deja certificar.

Tras su ejecución se conservan las *leyes* como tests persistentes (`ImpactResolverPropertyTest`, `EvidenceReaderContractTest` o nombres ya existentes) y se borran scripts temporales de ciclo.
