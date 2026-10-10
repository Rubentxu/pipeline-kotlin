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
