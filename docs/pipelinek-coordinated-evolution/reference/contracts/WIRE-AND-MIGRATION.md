# Diseño de wire, versionado y migración incremental

## Regla: evolucionar, no sustituir contratos adoptados

Los tipos siguientes son **borradores no congelados**. Mantener `WorkerNotice`, `WorkerHello`, `WorkerLease`, `ConsoleSource`, `ProjectionSource`, `StreamFraming` y `fabric_control.proto` donde ya existen. Añadir nuevas capacidades mediante ramas versionadas/ADTs y mappers. No copiar estos mensajes a nuevos módulos si el vocabulario existente ya representa el campo.

## Envelope de replicación (borrador lógico)

```text
Frame {
  protocol_version: uint32
  worker_session_id: id
  worker_id: id
  run_id: id
  lease_fencing_token: token
  message_id: id
  lane: CONTROL | EVENTS | OUTPUT | TELEMETRY
  content: ControlCommand | EventPage | OutputRangeRef | PayloadChunk | Ack | Refusal | Heartbeat
}

OutputRangeRef {
  operation_id, stream_id, attempt_id, channel
  from_offset, end_exclusive
  capture_frame_ordinal_start, capture_frame_ordinal_end
  content_digest, byte_size, payload_codec
  storage_ref?           // optional, only under verified pinning
}

Ack {
  message_id, lane, idempotency_key
  durable_accepted_through? // never inferred from arrival of a later page
  storage_receipt_digest
  receiver_epoch
}
```

**No combinar Events y Output:** la misma `message_id` no implica un mismo reloj. En Output, frame ordinal solo representa orden observado de captura del run, y no implica causalidad con un DomainEvent. El `lease_fencing_token` se valida antes de aplicar datos. Para datos históricos de un attempt anterior tras reasignación se requiere protocolo de recovery/claim específico; un nuevo dueño no debe poder reescribirlos silenciosamente.

## Compatibilidad

| Productor | Consumidor | Resultado |
|---|---|---|
| PK legacy con solo página/console | Fabric nuevo | Fallback lector paginado con SLA explícito, sin reclamar LIVE si no lo cumple |
| PK `output.follow.v1` + `events.follow.v1` | Fabric M1+ | Seguimiento durable por cursor |
| Worker sin rango durable ACK | Fabric M3+ | Refusal capability / no permitir perfil ReliableBuffered remoto |
| Fabric M3+ | Jenkins legacy | Unary ObserveRun/ReadConsole con paginación |
| Fabric M4+ | Jenkins nuevo | Snapshot+Watch, Replay+Follow |
| Fabric M5+ archive local | Jenkins nuevo | Transparente a backend |
| Fabric M5+ S3 | Jenkins nuevo | Transparente a backend |
| Fabric M5+ backend caído | Jenkins nuevo | `BEHIND/UNAVAILABLE`, no `COMPLETE` falsamente |

La negociación entre Worker y Fabric usa `WorkerHello` y `ProtocolRange` existentes. PK publica en SDK un manifiesto de capacidades y la versión resoluble. Introducir `capabilityHash` solo si aporta protección concreta; evitar duplicar las listas de features.

## Evolución del disco

1. Nuevo metadato de stream/cursor se escribe en formato versionado y se lee en dual-version durante migración.
2. Archivos antiguos siguen visibles mediante reader original o adapter, sin convertir todos los logs a la vez.
3. El writer nunca asume que archivo viejo soporta pin/ack nuevo; negotiate capabilities antes.
4. Segment manifest se confirma después de object PUT y checksum; no borrar la versión vieja antes de evidenciar la nueva.
5. Rollback documentado: si writer nuevo hace formato incompatible, desactivar feature y mantener reader de nueva versión para completar recovery o bloquear rollback con explicación.

## Operaciones y dominios de error

- **Transport error:** timeout, peer unreachable, broken stream, deadline; no reinterpretar como `CommandRejected` ni `RunFailed`.
- **Domain refusal:** Unauthorized, InvalidCursor, UnsupportedCapability, GoneByRetention, Gap, Conflict, StaleOwner, InsufficientCapacity, NotDurable, Corrupt; devolver tipos sin `null` de escape.
- **Observation incomplete:** run activo sin output, output abierto sin eventos nuevos, RunConcluded sin output sealed, fuente no observable, indexador retrasado. Un estado sin datos no es prueba de complete.

## Seguridad del wire

Payload máxima longitud y frame size; checksums por segmento, no expansión de compresión descontrolada; evitar caracteres de terminal no sanitizados en logs operativos de errores. No transportar credenciales ni scripts completos en notices de observación; start assignments solo en ruta autorizada. Tokens de cursor firmados y ligados al query+tenant.

## Contratos de prueba desde wire

`sameRangeSameDigestIdempotent`, `sameRangeDifferentDigestConflict`, `gapNeverAcknowledged`, `futureVersionRefused`, `sessionCapabilityIntersection`, `controlProgressUnderOutputFlood`, `readerDoesNotCauseRecovery`, `typedRefusalNotTransportStatus`, `snapshotWatchNoGap`, `legacyUnaryInterop`.
