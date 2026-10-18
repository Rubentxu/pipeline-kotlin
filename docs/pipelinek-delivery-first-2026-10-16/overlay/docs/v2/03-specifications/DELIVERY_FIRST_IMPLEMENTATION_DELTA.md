# Especificación de implementación — deltas de entrega-first (PROPOSED)

**Tipo:** especificación complementaria, NO sustitución de `TEST_IMPACT_AND_EVIDENCE_SPEC.md` (`SPEC-PK-QV-01`), `TEST_EXECUTION_LANES_SPEC.md` (`SPEC-PK-QV-02`), `FUNCTIONAL_CORE_ONTOLOGY_SPEC.md` (`SPEC-PK-QV-03`), `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md` (`SPEC-PK-QV-04`), `DISTRIBUTION_RELEASE_SPEC.md`, `STEP_CONSTITUTION.md`, `CLI_OBSERVABILITY_SPEC.md` o de las decisiones ADR-0070..0074 / ADR-PK-QV-001..004. **Semántica de esos documentos prima en caso de conflicto.** Las formas Kotlin son diseño ilustrativo; el agente debe usar tipos y firmas existentes cuando expresen la misma semántica.

## D1 — Ontología y núcleo funcional reutilizable

Forma aspiracional:

```kotlin
// PSEUDOCÓDIGO DE CONTRATO: no pegar tal cual si existen ADTs equivalentes.
sealed interface ChangedInput {
    val path: RepoRelativePath
    data class KotlinSource(override val path: RepoRelativePath) : ChangedInput
    data class ExecutableBuildOrReleaseScript(override val path: RepoRelativePath) : ChangedInput
    data class ServiceDescriptor(override val path: RepoRelativePath) : ChangedInput
    data class GeneratedOrConsumedResource(override val path: RepoRelativePath) : ChangedInput
    data class HarnessDecisionSource(override val path: RepoRelativePath) : ChangedInput
    data class NonProduct(override val path: RepoRelativePath) : ChangedInput
    data class UnknownProduction(override val path: RepoRelativePath, val why: String) : ChangedInput
}
sealed interface ImpactDecision {
    data class Selected(val guarantees: Set<ContractId>, val fidelity: RequiredFidelity) : ImpactDecision
    data class Refused(val reasons: List<Refusal>) : ImpactDecision // invariante reasons no vacío
}
// classify/resolve: datos -> decisión, sin leer git ni Gradle.
fun classify(path: RepoRelativePath, ownership: OwnershipRules): ChangedInput
fun decideImpact(inputs: List<ChangedInput>, catalogue: ContractCatalogue): ImpactDecision
```

**Invariantes:** no usar `String` magic para `Outcome`, `RunId`, estado de workflow, `HF`; distinguir `Unknown` de `NotApplicable`; `fail-closed` ante riesgo productivo sin propietario; no meter lógica de `when(stepKey)` en coordinador. Kotlin `sealed interface`, `data class` inmutables, `Result` tipado, `when` exhaustivo, `map/fold` sin estado mutable compartido para decisiones. Evitar `runBlocking`, `File`, `ProcessBuilder`, reloj, red, `System.exit` en el dominio; ubicar su traducción en intérpretes. No introducir Arrow ni otra dependencia FP salvo necesidad real. No abstraer un único caso solo por estilo.

La separación de tres dimensiones es obligatoria:

| Dimensión | Dueño | Decisión pura | Adaptador con efectos |
|---|---|---|---|
| `Input → impact → test obligations` | `test-harness` catálogo/plan | `ImpactResolver` + `Gate.adjudicate` | Git reader + Gradle runner + JUnit XML reader |
| `ProbeOutcome → cycle state / deletion intent` | ciclo, **no** CLI/Gradle | `Cycle.decide` | `ProbeExecutor`/`CycleRunner` + sandbox temp filesystem |
| `Output frame continuation → render intent` | `OutputFrameIndex`/contrato de consola | `ConsoleOperationSelection` y reducer actual | OutputReadPort + CLI printer/follow |
| `CandidateManifest → expected assets` | `pipeline-release` | set esperado/digest/compatibilidad | Gradle productor + publicador/gh + consumidor |

### Leyes mínimas algebraicas

1. **Conservación de selección:** `obligations(diff1) ⊆ obligations(diff1 + diff2)` si no hay rechazo; si una ruta productiva nueva no clasifica, el resultado se endurece a `Refused`, no se vacía.
2. **No falso PASS:** `isApproved` es verdadero solo ante ejecución positiva, identidad correcta y nivel HF realmente observado; `Skipped/NotRun/Blocked/Unsupported/Stale/OtherCommit` nunca lo producen.
3. **Decomposición sin duplicación:** dos APIs distintas que exponen misma regla llaman al **mismo decider**; sus intérpretes pueden variar. Evitar duplicar mapeo a exit codes en `CycleCli` y `build.gradle.kts`.
4. **Efectos trazables:** toda orden destructiva o publicación debe estar precedida por una decisión sobre un inventario tipado; tests de ley sobre el decider, un canario del intérprete en el nivel mínimo fiel.
5. **Determinismo:** mismos Input+State+configuración versión → igual Decision; no mezclar `OutputCursor` por stream con `FrameOrdinal` entre streams.
6. **Seguridad de replay:** exactamente una autoridad semántica de replay, según ADR-0103; normalización de observaciones no convierte estado desconocido en éxito.

## D2 — Cambio de código del selector y gate (H1)

- Conservar `ContractCatalogue` y sus bindings; ampliar clasificación de `ChangedInput` y patrones sin nuevo catálogo paralelo. Ejemplos que DEBEN seleccionar garantía de release/fit o `Refused`: `scripts/release-pipelinek.sh`, `scripts/validation-gate.py`, `v2/test-harness/.../ImpactResolver.kt`, `.gitattributes`, `v2/pipeline-release/src/main`, `v2/gradle/wrapper`, `v2/pipeline-sdk-bom/build.gradle.kts`, manifest público y service descriptor. Un README histórico aislado puede ser `NonProduct` justificadamente.
- `v2/test-harness/` es propietario del **decider de validez**: no queda exento sin un guard propio que impida falsos verdes. Evitar ciclo de auto-invocación; basta ejecutar leyes puras/fitness de H0/H1 por selección de camino conocido.
- `Gate.run` contrasta suites realmente ejecutadas, métricas de XML con `tests > 0`, `tests > skipped`, failures/errors=0 y FQCN+modulo, ejecutor, SHA/artefacto. Prohibido aceptar el resultado por que existe un XML viejo o por haber invocado una tarea.
- Los fallos conocidos de la aplicación se separan en `PRODUCT_DEFECT | TEST_COUPLING | INFRASTRUCTURE | INTENTIONAL_CHARACTERIZATION | STALE_EVIDENCE`, manteniendo el conjunto completo de filas y su conteo. Ninguna caracterización deliberadamente roja participa como PASS del gate normativo.

## D3 — Tres lanes físicas Gradle (H1)

| Lane | Entrada | Solo debe incluir | Mínimo de honestidad |
|---|---|---|---|
| `fast` | `:pipeline-application:test`/tests por módulo, `-Plane=fast` | HF0/HF1 + compilación/fitness afectados, sin `installDist` ni builders externos no impactados | Conteo ejecutado y `--dry-run` verificable en checkout fresco |
| `integration` | gates existentes (`validation-gate.py` / Gradle) | fronteras reales afectadas, ABI, ServiceLoader, negativos relevantes | Invocación ejecutada coincide con el planner |
| `candidate` | `:pipeline-release:releaseGate` + lane instalada + certifier cuando proceda | build reproducible, ZIP/SDK/SBOM, instalación, incompatibilidad, SAST exigido | Identidad SHA/CandidateId real y asset-set completo |

1. En `tasks.test` quitar `dependsOn(":buildExamplePlugin", ...)` **solo cuando** `testImplementation(files(...))` deje de cargar archivos generados sin dependencias correctas; mover clases que necesitan esos JAR al test task correcto o source set específico; nunca reemplazar con dependencia oculta en caché.
2. En `installedContractTest`, declarar `testClassesDirs`/`classpath` de tests, crear anotaciones `@Tag("installed-contract")` en clases REALES o seleccionar FQCN probado, luego exigir `executed > 0`. La fast lane debe excluir las mismas clases y jamás compartir credenciales/estado entre forks.
3. `-Plane=release` **no** significa que `:test` por sí solo corra toda la certificación; `releaseGate` conserva dependencia explícita de todas las obligaciones exigidas. Verificar composición/lock de `v2/settings.gradle.kts`.
4. No introducir `--rerun-tasks` global en el inner loop; full gate únicamente al integrar/RC cuando es normativo. Benchmarks p50/p95 registrados en mismo host con cache fría y caliente separadas.

## D4 — Lifecycle seguro de aceptación efímera (H0/H4)

Forma aspiracional, reutilizando `Cycle`, `CycleOutcome`, `ProbeOutcome` reales:

```kotlin
sealed interface ProbeVerdict {
    data class Verified(val evidence: EvidenceId, val observedHF: Fidelity, val sourceCommit: CommitId) : ProbeVerdict
    data class Refused(val reason: Failure) : ProbeVerdict
    data class Blocked(val dependency: Dependency) : ProbeVerdict
}
sealed interface CleanupDecision {
    data class DeleteOwned(val files: Set<OwnedProbePath>, val receipt: ReceiptRef) : CleanupDecision
    data class Keep(val reasons: List<Refusal>) : CleanupDecision
}
fun decideClosure(state: CycleState, evidence: List<ProbeVerdict>, replacement: ReplacementProof): CleanupDecision
```

- `ProbeExecutor` observa. `Cycle.decide` decide. `CycleRunner.finalise` aplica borrado en el único root del ciclo, solo manifest ownership, sin follow symlink a destinos ajenos, ni `../`, ni borrar de manera incidental un fichero de operador. El runner no da `exit=0` para blocked/falta de evidencia. `test-harness/build.gradle.kts` propaga exit de `JavaExec`.
- La promoción verifica existencia de la garantía sustituta o certificado externo sobre el **mismo** CandidateId/defecto/fidelidad, no solo formato `replacement:foo`. La ubicación y la gestión de pruebas efímeras son **externas al repositorio**; prohibido test temporal huérfano o committed.
- Si cleanup se interrumpe, repetir es seguro, no borra archivos de otro ciclo ni altera la evidencia; un `DEFER` crítico bloquea cierre.

## D5 — Construcción y publicación consumible (H2)

**Fuente única:** `:pipeline-release:candidateAdmission` emite `DistributionManifest` con `CandidateId` + el inventario público permitido por la política vigente. `release-pipelinek.sh` CONSUME ese modelo, nunca mantiene otra allowlist divergente. No poner en el repositorio nuevos .jar/.zip generados.

**Normativa actual hasta ADR-PK-DF-001 aceptada:** `AGENTS.md` §8 exige **5** assets exactos (ZIP runtime, SBOM, SDK ZIP, bom JSON, SHA256SUMS). PR #142 sugiere **3** de aplicación y SDK en Maven: NO aplicar unilateralmente, ni saltar el SDK si el contrato obliga a adjuntarlo. `SHA256SUMS` prueba integridad, no completitud; comparar también nombres/multiplicidad remotos. `git-head.txt` y `HANDOFF.md` permanecen solo como evidencia/notas, nunca assets extras.

**SDK:** `publishedContractModules` incluye los cinco `pipeline-{domain,scripting-api,events,output,runtime-api}`; `pipeline-sdk-bom` (java-platform) impone las cinco constraints. `sdkBomModules` no debe contener un contrato ABI: solo `pipeline-sdk-bom`; publicar `runtime-api` exactamente una vez con la lista principal. `SdkZipBuilder` recolecta rutas Maven relativas, sin symlink escape; prueba de extracción `.m2` existente se conserva. Repositorio Maven remoto independiente requiere URL/credenciales autorizadas y test desde consumidor limpio; no simular publicación usando `sdk-repo`.

## D6 — Valor runtime/CLI (H3)

En la vertical de salida seleccionada, separar `ObservationRequest/Decision/ReadIntent/Refusal` de la ejecución. Reusar `ConsoleOperationSelection`, `OutputFrameIndex`, `OutputReadPort`, `MainConsoleCli.readFrames*`, `ObservationOutputReader` y `EventField`; no duplicar lógica de composición. `--frames` es la vía preexistente que intenta preservar ordinal/intercalado; `ConsoleReadService.read()` histórico concatena canales por orden fijo. El plan no autoriza cambiar la semántica del modo antiguo ni añadir banderas públicas sin ADR/compatibilidad. Medir uso real y escoger un defecto OBS-3/4 verificable antes de implementar. Un corpus pequeño con stdout/stderr, paginación, cursor ajeno y fallo de decoding debe discriminar el bug, sin 20 harnesses especiales.

## D7 — Vocabulario del flujo de entrega

| Estado | Lo declara | Condición necesaria | No equivale a |
|---|---|---|---|
| `MERGE_READY` | gate merge local/CI existente | SHA limpio, impacted+fitness PASS | release certificado |
| `CANDIDATE_PUBLISHED` | repositorio GitHub + publisher | SHA integrado, tag, ZIP instalado, asset-set completo, digest | SDK publicado en remoto |
| `SDK_PUBLISHED` | registrador Maven + consumer | coordenadas Maven alcanzables y compiladas desde remoto | `publishToMavenLocal` |
| `CERTIFIED` | pipelinek-release-harness | veredicto firmado/enlazado al CandidateId y SHA | PR merged |
| `STABLE_PROMOTED` | dueño de promoción | mismos bytes RC certificados y gate autorizado | nueva compilación local |

**Contradicción normativa:** si una propuesta cambia la política pública (`ADR-PK-DF-001`), registrar autorización/ADR aceptada y actualizar **todas** las rutas productoras/consumidoras en un solo bloque; no inventar workaround silencioso.
