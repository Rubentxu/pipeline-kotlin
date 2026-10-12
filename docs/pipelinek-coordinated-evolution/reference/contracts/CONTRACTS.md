# Contratos compartidos — semántica, ADTs, FP, seguridad y compatibilidad

**Normativo para los contratos; Kotlin ilustrativo, no código compilable ni API congelada.** Al implementar, adaptar nombres a los módulos y vocabulario actuales; NO crear `RunId2`, `ConsoleRef2`, `WorkerLease2`, second `ProjectionReducer` ni reabrir el modelo de domínio existente.

## Leyes de diseño inspiradas en Haskell

1. **Functional core / imperative shell:** decidir/validar/reducir en funciones puras; las escrituras, reloj, red, disco, procesos y sleeps viven en intérpretes mediante ports. El core no conoce `Process`, `File`, `Channel`, `ServerCall`, `S3Client` ni `Jenkins`.
2. **Algebraic Data Types:** sum types sellados para alternativas reales, product types para datos comprobados. No booleanos múltiples incoherentes (`isTerminal`, `isComplete`, `isLost`) ni `null` para errores. `when` exhaustivo, sin `else` cuando sea posible.
3. **Totalidad en frontera de dominio:** entradas externas se decodifican a `Either` / `Outcome.Valid/Refused`; excepciones técnicas quedan en adapter, donde se convierten a typed failures. No usar `catch(Exception)` para «success».
4. **Tipos inteligentes:** identidades, offsets, digest, bytes, revisions, epochs y budget no aceptan negativos, valores vacíos o overflow. Constructores de validación puros y `value class` para identidades cuando el coste lo permita.
5. **Idempotencia comprobable:** `apply(apply(s,x),x) == apply(s,x)` para el mismo rango y digest. Mismo key con distinto digest => ConflictingReplay. ACK != latest event observed.
6. **Monotonía:** un cursor comprometido nunca disminuye; un ACK avanzado exige cobertura demostrada sin huecos. Un refusal no avanza cursor. `complete` es afirmación de source, no ausencia de nuevas filas.
7. **Separación epistemológica:** `Unknown`, `Unobservable`, `Lost`, `Unavailable` y `Terminal` no son sinónimos. Un timeout observacional no vuelve fallida la ejecución.
8. **No efectos colaterales en predicados:** `admit`, `score`, `decidePressure`, `compileQuery`, `nextReconcileAction`, `pruneEligibility` son puros; sus resultados se interpretan fuera.
9. **Boundedness por bytes y coste:** todo buffer, página, scan, regex y cola tiene presupuesto. Una `Flow` representa demanda de lectura, no estado durable.
10. **No global order:** EventCursor secuencial por run, OutputCursor por stream, OutputFrameOrdinal por run. Ni ticks del reloj ni mezcla de frames generan causalidad entre planos.

## ADTs base

```kotlin
@JvmInline value class NonNegativeOffset private constructor(val value: Long) {
    companion object { fun parse(raw: Long): Result<NonNegativeOffset> =
        if (raw >= 0) Result.success(NonNegativeOffset(raw))
        else Result.failure(IllegalArgumentException("negative offset")) }
}

data class ByteRange(val start: NonNegativeOffset, val endExclusive: NonNegativeOffset)
// Invariante de constructor validado: endExclusive >= start; no concatenar rangos solapados a ciegas.

sealed interface StreamAvailability {
    data class Open(val committedEnd: NonNegativeOffset) : StreamAvailability
    data class Sealed(val finalEnd: NonNegativeOffset) : StreamAvailability
    data class RetentionGap(val earliest: NonNegativeOffset) : StreamAvailability
    data class Corrupt(val range: ByteRange, val diagnosticRef: String) : StreamAvailability
    data class Unavailable(val reason: AvailabilityReason) : StreamAvailability
}

sealed interface ExecutionObservation {
    data class Alive(val identity: String, val attempt: Long) : ExecutionObservation
    data class RecoveredTerminal(val outcome: RuntimeTerminal) : ExecutionObservation
    data class Lost(val evidence: String) : ExecutionObservation
    data class Unobservable(val cause: String) : ExecutionObservation
}
sealed interface RuntimeTerminal { data object Succeeded : RuntimeTerminal; data class Failed(val code: Int) : RuntimeTerminal; data object TimedOut : RuntimeTerminal; data object Interrupted : RuntimeTerminal }
```

**Sutileza:** Kotlin `Result` es solo ejemplo en smart constructor; a nivel de puertos públicos preferir ADTs `Valid/Invalid` propios o los ya existentes, para no escapar excepciones sin taxonomía. `ByteRange` necesita validación de orden y overflow, y en producción no exponer constructor que viole invariantes.

## Puerto Runtime/Worker — lo mínimo necesario

**Propietario:** interfaces en published PipelineK SDK cuando el runtime pueda ofrecer la operación; el adaptador Fabric traduce a `WorkerSession` existente. PipelineK no importa `Fabric`.

```kotlin
interface RuntimeInspectionPort {
    suspend fun inspect(run: RuntimeRunRef): InspectResult
    suspend fun recover(run: RuntimeRunRef, authority: RecoveryAuthority): RecoverResult
}
interface RuntimeLifecyclePort {
    suspend fun start(request: PreparedExecution): StartResult
    suspend fun cancel(run: RuntimeRunRef, command: CancellationId): CancelResult
}
interface RuntimeEventReadPort { suspend fun read(request: EventReadRequest): EventReadResult }
interface RuntimeOutputReadPort { suspend fun read(request: OutputReadRequest): OutputReadResult }
interface RuntimeWakeupPort { fun observeCommitSignals(run: RuntimeRunRef): kotlinx.coroutines.flow.Flow<CommitSignal> }
```

**Segregación:** lifecycle NO expone consola, output NO expone cancelación, reader NO puede llamar `recover` que modifique escritor vivo. `RuntimeWakeupPort` opcional; fallback lector durable. Las interfaces se componen en el adapter (dependency inversion). Reusar `OutputReadPort`, `EventStore`, `OutputTailPort` existentes, creando adaptadores antes que reemplazarlos.

`RecoverResult` nunca debe significar «relanzado» implícitamente. Debe modelar `Reused`, `RecoveredTerminal`, `StillAlive`, `FailClosed`, `Diverged` y `Unobservable` de acuerdo con las autoridades de recovery existentes.

## Replicación: discriminantes y rangos

```kotlin
sealed interface ReplicatedItem {
    data class EventPage(val run: RunKey, val range: SequenceRange, val payloadDigest: Digest, val schemaVersion: Int) : ReplicatedItem
    data class OutputRange(val run: RunKey, val stream: StreamKey, val range: ByteRange, val digest: Digest, val channel: OutputChannel) : ReplicatedItem
}
sealed interface ApplyVerdict {
    data class Committed(val key: DurableKey, val committedThrough: Long) : ApplyVerdict
    data class AlreadyCommitted(val key: DurableKey) : ApplyVerdict
    data class Gap(val expected: Long, val received: Long) : ApplyVerdict
    data class Conflict(val key: DurableKey) : ApplyVerdict
    data class StaleOwner(val epoch: Long) : ApplyVerdict
    data class Retryable(val problem: String) : ApplyVerdict
    data class UnsupportedVersion(val min: Int, val max: Int) : ApplyVerdict
}
```

Clave de idempotencia = `(tenant, run, stream/event-plane, start, endExclusive, digest, owner epoch)` con contrato explícito para reenvíos de antigua generación: la autoridad de ejecución decide si es legítimo entregar historia de una generación anterior; una nueva lease no concede permiso automático de insertar datos de un writer zombie. `digest` evita confundir reenvío idéntico con conflicto; identidad del executor por sí sola no prueba dedupe.

Leyes:
- Para un rango aceptado `[a,b)` y reenvío idéntico: `AlreadyCommitted`, ningún nuevo append.
- Mismo rango con digest distinto: `Conflict`, nunca ACK válido ni sobrescritura.
- Si falta `[a,b)`, llegada de `[b,c)` => `Gap`, se conserva evidencia de b y no se infiere a.
- ACK durable se emite solo tras bytes y manifest/metadata durables, no tras `send()`, `flush()` de socket o actualización del cache.
- Un rechazo de fencing no autoriza borrar la única copia de hechos no reconciliados.

## Archivo: capacidades segregadas

```kotlin
interface SegmentArchiveWritePort {
    suspend fun put(segment: VerifiedSegment): ArchiveWriteOutcome
}
interface SegmentArchiveReadPort {
    suspend fun readRange(ref: ArchiveSegmentRef, range: ByteRange, budget: ReadBudget): ArchiveReadOutcome
}
interface SegmentCatalogPort {
    suspend fun register(manifest: SegmentManifest, expected: CatalogRevision): CatalogOutcome
    suspend fun locate(stream: StreamKey, range: ByteRange): CatalogLookup
}
interface RetentionPinPort {
    suspend fun pin(stream: StreamKey, range: ByteRange, purpose: RetentionPurpose): PinResult
    suspend fun release(pin: PinToken, evidence: DurableReceipt): ReleaseResult
}
interface LogSearchPort { suspend fun search(query: ValidatedLogQuery, budget: SearchBudget): SearchOutcome }
```

**No** imponer a S3 transacciones entre objeto+metadata. El protocolo es `upload checksum → verificar lectura/durabilidad declarada → CAS manifest → ACK → release pins`; recuperación de objetos huérfanos y manifiestos divergentes obligatoria. El indexador es un read model reconstruible, no participa en ACK.

## Control y watch

```kotlin
sealed interface RunDesiredState { data object Running : RunDesiredState; data object CancelRequested : RunDesiredState; data object Draining : RunDesiredState }
sealed interface WorkerCondition { data object Ready : WorkerCondition; data class DiskPressure(val evidence: String) : WorkerCondition; data class SpoolPressure(val evidence: String) : WorkerCondition; data class RuntimeUnavailable(val reason: String) : WorkerCondition }
sealed interface WatchRecovery { data class SnapshotRequired(val earliestRevision: Long) : WatchRecovery; data class ReplayFrom(val cursor: String) : WatchRecovery; data class Gap(val earliestOffset: Long) : WatchRecovery }
```

`RunDesiredState` no sustituye `RunState` del dominio ya existente. Evitar una segunda máquina de estados: el adaptador derivará una intención desde órdenes del Registry. La vista `Snapshot+Watch` sirve estados actuales, `Replay+Follow` sirve hechos/bytes; no promover Snapshot a historial perdido.

## Query y paginación

- Compilar `ValidatedQuery` una vez. ACL+presupuestos antes de ejecutar. Selección de metadatos `tenant/project/run/stage/step/operation/channel/eventKind/outcome` primero, contenido después. Búsqueda textual solo sobre bytes que el lector autoriza a proyectar.
- Cursor remoto opaco versionado: `(tenant, principalScopeFingerprint, queryHash, sortMode, indexRevision, eventSequence?, streamOffset?, frameOrdinal?, retentionEpoch, expiration, MAC)`. No construir URL legible manualmente y no poner secretos en token. Cambios de query obligan cursor nuevo.
- Cursor de source avanza por **elementos examinados** incluso si son filtrados, nunca por conteo de resultados mostrados. Abortar consulta con budget agotado devuelve partial + continuation o refusal sin mentir.
- Vista global por frames/VirtualConsoleOffset: mapping monotónico persistente para Jenkins; recomposición de UTF-8 incremental. Orden real entre stdout/stderr es solo orden de captura observado, no causalidad de scheduler.
- Regex no confiable: RE2/J u otra implementación con coste acotable; prohibir expresiones con semántica que no pueda garantizarse y controlar max line/buffer/CPU. Si no hay tal garantía, no habilitar regex remotas arbitrarias.

## Pruebas algebraicas y contract tests obligatorios

Propiedades `idempotentApply`, `monotonicCursor`, `noGapAcknowledged`, `fenceStopsZombie`, `redactionBeforeDurability`, `twoObserversIndependent`, `wakeupLossHarmless`, `retentionPinned`, `finalOutcomeNotFromSilence`, `typedValueNotLog`, `queryPlannerPreservesResults`, `backendParity`. Ejecutarlas sobre adaptadores in-memory y reales, no exclusivamente contra mocks de implementación.

## Versionado

Negociación en `WorkerHello.features` / `ProtocolRange` ya existente en Fabric; añadir features, no un segundo handshake. Capacidades nuevas se activan solo con intersección negociada. Decode de una versión futura => `UnsupportedVersion`, no cast ni dato omitido. Publicación PipelineK SDK → consumer Fabric; fijar digest artifact, versión API, manifest de conformance; probar N contra N y N-1 cuando la matriz de compatibilidad lo permita. Evitar un release upstream sin coordinación para las versiones que exigen simultaneidad de ambos lados.
