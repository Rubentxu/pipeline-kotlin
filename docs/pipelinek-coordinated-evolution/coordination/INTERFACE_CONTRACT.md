# CROSS-REPO INTERFACE CONTRACT — CRIC-1

**Estado:** propuesta vinculante al adoptarse en ambos repos. **Versión normativa:** `CRIC-1`; cambio breaking => nuevo contrato, no editar retrospectivamente el anterior.

- Productor: [Rubentxu/pipeline-kotlin](https://github.com/Rubentxu/pipeline-kotlin).
- Consumidor y orquestador: [Rubentxu/pipelinek-fabric](https://github.com/Rubentxu/pipelinek-fabric).
- Documento idéntico en AMBOS paquetes: `coordination/INTERFACE_CONTRACT.md`. El SHA-256 de este documento es una llave de admisión.
- Autoridades de producto: ROADMAP/AGENTS/ADR respectivos, que NO quedan sustituidos por un ZIP. La autoridad de la promoción del PAR de releases es un único recibo inmutable `PAIR_RECEIPT.json`, no un estado narrativo divergente.

## Contrato de semántica, no de nombres Kotlin hipotéticos

1. **Identidades:** `runId`, `operationId`, `attemptId`, canal y stream conservan el mismo referente a través de los dos productos. `Stage`/`Step` son relaciones por identidad, no solo por texto de nombre. No inferir una ejecución nueva cuando un lector se reconecta.
2. **Plano de salida:** bytes redactados antes de persistir, secuencia de offsets por stream, orden observado de frames sin fingir reloj global con eventos; `Open`, `Sealed`, `Unavailable`, `RetentionGap`, `Corrupt` se distinguen; un run terminal no implica consola completa.
3. **Plano de eventos:** lectura incremental con secuencia estable, idempotencia y rechazo de huecos; los DomainEvents no son un sistema de transporte de stdout, ni representan el `OperationJournal` crudo.
4. **Valores tipados:** `sh(returnStdout=true)` y otros `TypedStepValue` nunca se convierten automáticamente en logs públicos ni eventos de red.
5. **Runtime inspeccionable:** `inspect/recover/cancel/follow` mediante interfaces segregadas, reutilizando los puertos públicos REALES de PipelineK. Una lectura no ejecuta recuperación destructiva; no relanza efectos externos automáticamente.
6. **Compatibilidad y negociación:** contrato de capacidades explícitas y rango compatible; ausencia de `output.follow.v1` o `events.follow.v1` requiere fallback anunciado o refusal tipado, nunca un falso LIVE. ABI publicada y consumidor compilado contra ella, no contra el árbol de fuentes ni `mavenLocal` en el gate final.

    > **CRIC-M1 (v0.49.0-rc1, 2026-10-10):** `output.follow.v1` y `events.follow.v1` quedan **publicadas** en el release de PipelineK `v0.49.0-rc1`. La ausencia deja de ser el caso por defecto para consumidores que adopten ese release; los releases anteriores siguen bajo el contrato "ausencia => fallback o refusal" sin cambios. Certifying tests: `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) y `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`); cross-JVM e2e: `M1DCrossJvmFollowTest` (6 casos UAT-PK-M1-001..006, `:pipeline-application`). La autoridad del cambio de contrato exige la actualización de `CONTRACT_SHA256.txt` y el recibo de release inmutable.
    >
    > **CRIC-M2 (v0.50.0-rc1, 2026-10-10):** `runtime.inspect.v1`, `runtime.cancel.v1` y `runtime.recover.v1` quedan **publicadas como EXPERIMENTAL** en el release de PipelineK `v0.50.0-rc1`. Certifying tests: `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`), `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) y `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`); matriz pura de decisión: `RuntimeRecoverDecisionTableFitnessTest` (12 casos, `:pipeline-runtime`). La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.50.0-rc1` deben tratar las tres capacidades como negociables pero todavía no estables, y los releases anteriores siguen bajo el contrato "ausencia => fallback o refusal" sin cambios. Las capacidades M1 (`output.follow.v1`, `events.follow.v1`) se mantienen **PUBLICADA** en este release. La autoridad del cambio de contrato exige la actualización de `CONTRACT_SHA256.txt` y el recibo de release inmutable.
    >
    > **CRIC-M3 (v0.51.0-rc1, 2026-10-11):** `output.read.digested.v1`, `output.pin.v1` y `output.refusal.retention.v1` quedan **publicadas como EXPERIMENTAL** en el release de PipelineK `v0.51.0-rc1`. Certifying tests: `OutputReadDigestedAdapterTest` (5 casos, `:pipeline-output-store`), `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`), `PruneAuthorisationAdapterTest` (5 casos, `:pipeline-output-store`), `OutputRefusalClosedTest` (4 casos, `:pipeline-output-store`) y `RecoverRefusalPinExtensionTest` (3 casos, `:pipeline-runtime`); además `CapabilityRegistrationTest` (5+ casos, `:pipeline-application`) pin byte-a-byte la presencia de las cinco capacidades M1+M2+M3 y la tabla CRIC-M3. La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.51.0-rc1` deben tratar las tres capacidades como negociables pero todavía no estables, y los releases anteriores siguen bajo el contrato "ausencia => fallback o refusal" sin cambios. Las capacidades M1 (`output.follow.v1`, `events.follow.v1`) se mantienen **PUBLICADA**; las capacidades M2 (`runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1`) permanecen **EXPERIMENTAL** en este release. La autoridad del cambio de contrato exige la actualización de `CONTRACT_SHA256.txt` y el recibo de release inmutable.
    >
    > **CRIC-M6 (v0.53.0-rc1, 2026-10-11):** `runtime.structure.v1` y `runtime.operation.v1` quedan **publicadas como EXPERIMENTAL** en el release de PipelineK `v0.53.0-rc1`. Certifying tests: `RuntimeIntrospectionPortStructureAdapterTest` (8 casos, `:pipeline-runtime`), `RuntimeIntrospectionPortOperationAdapterTest` (6 casos, `:pipeline-runtime`) y `RuntimeIntrospectionPortStructurePaginationTest` (5 casos, `:pipeline-runtime`); `CapabilityRegistrationTest` (8 casos tras M6) pin byte-a-byte la presencia de las diez capacidades M1+M2+M3+M6 y la tabla CRIC-M6. La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.53.0-rc1` deben tratar las dos capacidades como negociables pero todavía no estables, y los releases anteriores siguen bajo el contrato "ausencia => fallback o refusal" sin cambios. Las capacidades M1 (`output.follow.v1`, `events.follow.v1`) se mantienen **PUBLICADA**; las capacidades M2 (`runtime.inspect.v1`, `runtime.cancel.v1`, `runtime.recover.v1`) y M3 (`output.read.digested.v1`, `output.pin.v1`, `output.refusal.retention.v1`) permanecen **EXPERIMENTAL** en este release. La autoridad del cambio de contrato exige la actualización de `CONTRACT_SHA256.txt` y el recibo de release inmutable. **PARTIAL closure note (audit I.6):** la implementación M6 first-cut usa agrupación sintética single-stage / single-step (el envelope de `PipelineEventEnvelope` descarta los campos específicos de tipo de los eventos tipados, así que la agrupación dirigida por eventos requiere un M-block de seguimiento que exponga un puerto de lectura con forma de evento tipado). El test contractual pinea explícitamente la agrupación sintética; un M-block futuro que añada agrupación dirigida por eventos actualizará los tests.
    >
    > **CRIC-M7 (v0.54.0-rc1, 2026-10-12):** `runtime.eventshape.v1` queda **publicada como EXPERIMENTAL** en el release de PipelineK `v0.54.0-rc1`. Esta capacidad cierra el M6 I.6 PARTIAL: el `EventShapeReadPort` (en `:pipeline-events`, puerto fun-interface SAM) devuelve los eventos tipados (`StageStarted` / `StageFinished` / `StageSkipped` / `StepStarted` / `StepFinished` — 5 casos, sealed ADT) que el `RuntimeIntrospectionPort.runStructure` compone para reemplazar la agrupación sintética M6 con agrupación dirigida por eventos. Certifying tests: `EventShapeReadPortAdapterTest` (10 casos, `:pipeline-events`), `RuntimeIntrospectionPortStructureAdapterTest` (3 casos M7 nuevos, total 11), `RuntimeIntrospectionPortOperationAdapterTest` (6 casos, dos actualizados a la nueva `IntrospectionRefusal.UnknownOperation`), `IntrospectionUnknownOperationRefusalTest` (3 casos, hardening B.2); `CapabilityRegistrationTest` (8 casos tras M7) pin byte-a-byte la presencia de las once capacidades M1+M2+M3+M6+M7 y la tabla CRIC-M7. La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.54.0-rc1` deben tratar la capacidad como negociable pero todavía no estable. Hardening items del audit M7 cerrados: B.1 (`DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L` en `RuntimeIntrospectionPort` companion), B.2 (caso `IntrospectionRefusal.UnknownOperation(operationId, attempt)` aditivo), B.3 (5 casos del sealed `TypedEvent` pineados por `EventShapeReadPortAdapterTest` test 9).
7. **Control distribuido:** Fabric posee lease, fencing, asignación deseada y worker reconciler; PipelineK posee recuperación efectiva de sus operaciones. Un silencio del proceso o un socket desconectado no equivale a un resultado.
8. **Replicación:** ACK únicamente tras persistir exactamente el rango y digest aceptados. Falta `[a,b)` + recepción `[b,c)` => Gap, no ACK acumulado; `(a,b,digestA)` repetido => AlreadyCommitted; digest distinto => Conflict.
9. **Ownership:** PipelineK NO depende de Fabric, Jenkins, S3, Elasticsearch ni gRPC para su core; Fabric NO reinterpreta terminalidad por logs ni escribe directamente el journal privado de PipelineK.
10. **Límites:** memoria, buffers, páginas, cuotas, CPU de consultas y disco son finitos. Observadores lentos no ejercen backpressure directamente sobre la ejecución; agotamiento del almacenamiento obligatorio produce política declarada de backpressure/fallo, no drop silencioso.
11. **Seguridad:** autenticación antes de consulta, autorización por run/tenant, secretos redactados antes de cualquier almacén público, control separado de observación, payload y compresión acotados, cursors ligados a ACL/query.
12. **Retención:** no podar rangos hasta probar que su garantía activa se transfirió a una copia durable, y que no existe pin. Un indexador (ELK/Loki) no es autoridad de bytes.

## Compatibilidad verificable (se exige en cada cambio de superficie)

- Matriz: PipelineK publicado actual × Fabric publicado actual; PK candidato × Fabric consumidor candidato; PK publicado nuevo × Fabric candidato; cliente Fabric anterior × PK nuevo (cuando la evolución promete compatibilidad hacia atrás); ambos publicados juntos tras promoción.
- Versionar las capacidades; cambios incompatibles no pueden pasar por cambios nominales de versión sin test de consumers.
- En Payload/Wire se preservan las restricciones de `WorkerHello`, `WorkerLease`, `StreamFraming`, `ConsoleSource`, `ProjectionSource` y ADR-0022/0024 ya adoptados.
- Antes de implementar un cambio de ABI: ejemplos de rechazo y resultado, fixture de serialización, contrato de compatibilidad N/N-1 y test de interoperabilidad publicado.

## Regla de bloqueo

Una fase puede desarrollarse en ambas ramas, pero **no puede promocionarse el hito M(n), ni arrancarse M(n+1) como trabajo integrado, hasta existir un único `PAIR_RECEIPT` con estado `PAIR_CERTIFIED`, dos refs/tag SHA remotos verificados, checksum del contrato común, evidencia de suites y UAT/AAT cruzadas y artefactos publicados verificados**. `NOT_MEASURED`, `UNKNOWN`, tag ausente, diferencia de contrato o artifact local-only => BLOCKED.

Un release individual puede publicarse previamente cuando su compatibilidad con el consumidor anterior esté demostrada: NO se etiqueta el par como completo hasta verificar ambos releases. Si un repo no cambia en un hito, se conserva explícitamente su versión publicada, pero vuelve a probarse contra la nueva versión del otro.

## Capacidades publicadas (CRIC-M1)

| Capability ID | Versión | Publicada en | Autor | Test unitario | Test cross-JVM | Estado |
|---|---|---|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | PipelineK | `SegmentOutputFollowerTest` (18 casos, `:pipeline-output-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-001/003, `:pipeline-application`) | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` (2026-10-10) | PipelineK | `EventFollowerAdapterTest` (18 casos, `:pipeline-events-store`) | `M1DCrossJvmFollowTest` (UAT-PK-M1-002/004/005/006, `:pipeline-application`) | **PUBLICADA** |

La transición "ausencia => publicada" es breaking para consumidores que asumían la ausencia. Los consumidores deben adaptar su rama de negociación para reconocer ambas capacidades como ofrecidas en `v0.49.0-rc1+`. La autoridad del cambio es el `release-receipt` del candidato correspondiente y la batería de certificación del certifier; este contrato enumera el estado vigente y los tests que lo demuestran.

## Capacidades publicadas (CRIC-M2)

| Capability ID | Versión | Publicada en | Autor | Test unitario | Estado |
|---|---|---|---|---|---|
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | PipelineK | `RuntimeIntrospectionPortAdapterTest` (8 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | PipelineK | `RuntimeControlPortAdapterTest` (8 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` (2026-10-10) | PipelineK | `RuntimeRecoverPortAdapterTest` (8 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |

La matriz pura de decisión del recover (`RuntimeRecoverDecisionTableFitnessTest`,
12 casos, `:pipeline-runtime`) no es cross-JVM; cubre la matriz de
`EffectReplayPolicy.decide` envuelta por el decider público
`RuntimeRecoverDecision.decideRecovery`. La transición
"EXPERIMENTAL => PUBLICADA" ocurre tras la certificación de la candidata
por `pipelinek-release-harness`; hasta entonces, los consumidores que
adopten `v0.50.0-rc1` deben tratar las tres capacidades como
negociables pero todavía no estables. Los releases anteriores siguen
bajo "ausencia => fallback o refusal" sin cambios. La autoridad del
cambio es el `release-receipt` del candidato correspondiente y la
batería de certificación del certifier.

## Capacidades publicadas (CRIC-M3)

| Capability ID | Versión | Publicada en | Autor | Test unitario | Estado |
|---|---|---|---|---|---|
| `output.read.digested.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | PipelineK | `OutputReadDigestedAdapterTest` (5 casos, `:pipeline-output-store`) | **EXPERIMENTAL** |
| `output.pin.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | PipelineK | `OutputPinPortAdapterTest` (10 casos, `:pipeline-output-store`) | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | v1 | `v0.51.0-rc1` (2026-10-11) | PipelineK | `PruneAuthorisationAdapterTest` (5 casos, `:pipeline-output-store`), `OutputRefusalClosedTest` (4 casos, `:pipeline-output-store`), `RecoverRefusalPinExtensionTest` (3 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |

Las tres capacidades M3 introducen superficie nueva sobre los puertos
publicados de `:pipeline-output` (`OutputReadPort.readRangeDigested`,
`OutputPinPort.pin / release / pinsOf / isPinned`,
`OutputRetentionPort.canPrune`) y una cuarta superficie aditiva ya
existente (`OutputRefusal` recibe `RetentionGap`, `Corrupt`,
`Unavailable`, `RangeLostRetention`; `RecoverRefusal` recibe
`PinnedBytesOutsideRecoveredRegion`). Los certifying tests arriba son
los que demuestran el cierre de las invariantes I.4 (recover
no-destructivo bajo pin), I.5 (retención honra pin) e I.6 (pérdida
de retención como refusal tipado, no EOF) que el audit M3
(`dab35001`) había marcado como `PARTIAL` / `UNVERIFIED`. La
transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación
de la candidata `v0.51.0-rc1` por `pipelinek-release-harness`; hasta
entonces, los consumidores que adopten `v0.51.0-rc1` deben tratar las
tres capacidades como negociables pero todavía no estables. Los
releases anteriores siguen bajo "ausencia => fallback o refusal" sin
cambios. La autoridad del cambio es el `release-receipt` del
candidato correspondiente y la batería de certificación del
certifier.

## Capacidades publicadas (CRIC-M6)

| Capability ID | Versión | Publicada en | Autor | Test unitario | Estado |
|---|---|---|---|---|---|
| `runtime.structure.v1` | v1 | `v0.53.0-rc1` (2026-10-11) | PipelineK | `RuntimeIntrospectionPortStructureAdapterTest` (8 casos, `:pipeline-runtime`), `RuntimeIntrospectionPortStructurePaginationTest` (5 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |
| `runtime.operation.v1` | v1 | `v0.53.0-rc1` (2026-10-11) | PipelineK | `RuntimeIntrospectionPortOperationAdapterTest` (6 casos, `:pipeline-runtime`) | **EXPERIMENTAL** |

Las dos capacidades M6 son métodos aditivos sobre `RuntimeIntrospectionPort` (M2) en `:pipeline-runtime`: `runStructure(runId, options)` (enumeración tipada de stages / steps / operations con `StructureRunOptions` / `StructureCursor` para paginación acotada y resume) y `inspectOperation(operationId, attempt)` (consulta tipada de terminalidad por operación). Los certifying tests arriba son los que demuestran el cierre del invariante I.6 (contexto indexable de run / stage / step / operation) que el audit M6 (`83e1c28d`) había marcado como `PARTIAL` (synthetic single-stage / single-step grouping; event-driven grouping requiere un follow-up M-block con un `EventShapeReadPort` tipado). La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación de la candidata `v0.53.0-rc1` por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.53.0-rc1` deben tratar las dos capacidades como negociables pero todavía no estables. Los releases anteriores siguen bajo "ausencia => fallback o refusal" sin cambios. La autoridad del cambio es el `release-receipt` del candidato correspondiente y la batería de certificación del certifier.

## Capacidades publicadas (CRIC-M7)

| Capability ID | Versión | Publicada en | Autor | Test unitario | Estado |
|---|---|---|---|---|---|
| `runtime.eventshape.v1` | v1 | `v0.54.0-rc1` (2026-10-12) | PipelineK | `EventShapeReadPortAdapterTest` (10 casos, `:pipeline-events`), `IntrospectionUnknownOperationRefusalTest` (3 casos, `:pipeline-runtime`), `RuntimeIntrospectionPortStructureAdapterTest` (3 casos M7 nuevos, total 11, `:pipeline-runtime`) | **EXPERIMENTAL** |

La capacidad M7 cierra el M6 I.6 PARTIAL: el `EventShapeReadPort` (en `:pipeline-events`, puerto fun-interface SAM) devuelve los eventos tipados (5 casos del sealed `TypedEvent` ADT) que el `RuntimeIntrospectionPort.runStructure` compone para reemplazar la agrupación sintética M6 con agrupación dirigida por eventos. Los certifying tests arriba son los que demuestran el cierre del invariante I.6 y los tres items de hardening del audit M7: B.1 (`DEFAULT_STRUCTURE_TIMEOUT_MS = 10_000L` en `RuntimeIntrospectionPort` companion), B.2 (caso `IntrospectionRefusal.UnknownOperation(operationId, attempt)` aditivo, pineado por `IntrospectionUnknownOperationRefusalTest`), B.3 (5 casos del sealed `TypedEvent` pineados por `EventShapeReadPortAdapterTest` test 9). La transición "EXPERIMENTAL => PUBLICADA" ocurre tras la certificación de la candidata `v0.54.0-rc1` por `pipelinek-release-harness`; hasta entonces, los consumidores que adopten `v0.54.0-rc1` deben tratar la capacidad como negociable pero todavía no estable. Los releases anteriores siguen bajo "ausencia => fallback o refusal" sin cambios. La autoridad del cambio es el `release-receipt` del candidato correspondiente y la batería de certificación del certifier.

## Capacidades publicadas (CRIC-M6.4 — v0.55.0-rc1, 2026-10-12)

**Release correctiva** que re-emite la candidata M6+M7 con la **superficie M6 reubicada** de `:pipeline-runtime` (host, no consumible) a `:pipeline-runtime-api` (SDK ABI). El contenido binario de las cinco capacidades EXPERIMENTAL vigentes es semánticamente equivalente al de `v0.54.0-rc1`; el cambio es de **placement del módulo publicado**, no de contrato.

### Por qué esta release correctiva

El branch M6 (`pk-cric-m6-impl`) se creó **antes** del M2 module split (`2a97653a`); el M6 surface (`RunStructure`, `OperationInspection`, `StructureRunOptions`, y los métodos `runStructure` + `inspectOperation` sobre `RuntimeIntrospectionPort`) se colocó en `:pipeline-runtime`. Tras el M2 split, el módulo de la superficie consumible es `:pipeline-runtime-api` (sin jdbc / sqlite / event-store). El `release-receipt.md` M6 publicó el `pipeline-runtime-0.53.0.jar` con la superficie adentro, pero ningún consumer podía depender de él sin violar el F.4 negative-scope (jdbc / sqlite / event-store transitivos).

El merge directo de `release/cric-m6-v0.53.0-rc1` a `main` (con `git mv` de los 3 tipos puros) reubica la superficie M6 a `:pipeline-runtime-api` y deja `RuntimeIntrospectionPort` (con los 3 métodos M2+M6) en el módulo correcto. **Esta candidata `v0.55.0-rc1` es la primera release cuyo ZIP puede ser compilado por un consumer externo contra la ABI pública sin arrastrar implementaciones internas.**

### Capacidades (sin cambios sobre `v0.54.0-rc1`)

| Capability ID | Versión | Publicada en | Estado |
|---|---|---|---|
| `output.follow.v1` | v1 | `v0.49.0-rc1` | **PUBLICADA** |
| `events.follow.v1` | v1 | `v0.49.0-rc1` | **PUBLICADA** |
| `runtime.inspect.v1` | v1 | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.cancel.v1` | v1 | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.recover.v1` | v1 | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `output.read.digested.v1` | v1 | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.pin.v1` | v1 | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | v1 | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `runtime.structure.v1` | v1 | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.operation.v1` | v1 | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | v1 | `v0.54.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |

### Material publicado (inmutable)

- **ZIP candidato:** `pipelinek-0.55.0.zip` (88.5 MB aprox.).
- **5 SDK coordinates** a `0.55.0` en `v2/build/sdk-repo/dev/rubentxu/pipeline/v2/`:
  - `pipeline-domain-0.55.0.{jar,pom,module}`
  - `pipeline-events-0.55.0.{jar,pom,module}` (M7 surface aquí)
  - `pipeline-output-0.55.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.55.0.{jar,pom,module}` (M6 surface reubicada aquí)
  - `pipeline-scripting-api-0.55.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.55.0.{pom}`.

### Cambios sobre `v0.54.0-rc1`

1. **M6 surface placement fix:** `RunStructure`, `OperationInspection`, `StructureRunOptions`, y los métodos `runStructure` + `inspectOperation` sobre `RuntimeIntrospectionPort` están en `:pipeline-runtime-api` (no en `:pipeline-runtime`). Un consumer que dependa de `pipeline-runtime-api:0.55.0` ve la superficie M6+M7 completa sin arrastrar jdbc / sqlite / event-store.
2. **`fix(runtime)` AttemptId import:** `RuntimeIntrospectionPortStoreAdapter` re-importa `dev.rubentxu.pipeline.v2.domain.AttemptId` (perdido durante la resolución del conflicto de merge de M6). Sin cambio funcional observable, solo de compilación.
3. **Versión** `0.54.0` → `0.55.0` (train PATCH; el cambio es de placement, no de contrato).
4. **`CONTRACT_SHA256.txt`** refrescado (cambio en INTERFACE_CONTRACT.md por la sección "CRIC-M6.4" arriba).

### Estado de candidatas previas

- `v0.53.0-rc1` (M6 first-cut): sigue publicada en GitHub como Prerelease; **NO es consumible** (la superficie M6 está en `:pipeline-runtime`). Conservada por trazabilidad; el par `PAIR_CERTIFIED` con Fabric debe usar `v0.55.0-rc1`.
- `v0.54.0-rc1` (M7 first-cut): sigue publicada en GitHub como Prerelease; contiene M6 en `:pipeline-runtime` (mismo defecto de placement). Conservada por trazabilidad; el consumer extension usa `v0.55.0-rc1`.
- `v0.55.0-rc1` (M6.4 corrective): candidata canónica para `PAIR_CERTIFIED` con Fabric y para la promoción `EXPERIMENTAL → PUBLICADA` post-`CERTIFIED`.

### Authority

La autoridad de este cambio es la `release-receipt.md` de la candidata `v0.55.0-rc1` y la `PK_CONTRACT_HANDOFF CRIC-M6.4` a Fabric.
