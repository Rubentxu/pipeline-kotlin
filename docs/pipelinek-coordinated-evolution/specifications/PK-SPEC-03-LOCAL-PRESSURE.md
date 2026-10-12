# PK-SPEC-03 — Recursos y presión local (sin replantear runtime)

**Owner** PipelineK. Fabric es solo consumidor de capacidades. Implementación mínima: métricas y límites locales; no construir otro scheduler.

## Capacidades 

`MemoryPressure`, `OutputStoragePressure`, `ProcessBudget`, `OpenFileBudget` y `DiskCommitLatency` son observaciones tipadas, no resultados terminales de la pipeline. Una política local `ExecutionResourceBudget` puede rechazar antes de lanzar un subprocess con datos suficientes, o degradar solo telemetría auxiliar. No descartar DomainEvents ni output que se prometió almacenar.

- Las bombas stdout/stderr drenan concurrentemente, buffers finitos y redactor antes de store; si el store no admite bytes, la política debe ser `BackpressureBounded` o `FailExecutionExplicitly`, no `DropSilently`.
- Publicar solo los metadatos del runtime que necesita un Worker Agent; no trasladar a PK el governor de múltiples tenants/cliente/stream.
- Medir si batching o wakeup evita llamadas al store sin aumentar latencia del primer frame parcial. No convertir la optimización en un umbral de 1KiB para emitir 20 bytes.
- Retención `RetainUntil` actual debe respetar pins o documentar modo `ReliableBuffered` no disponible mientras falte un pin seguro.

## Oráculos

Output no cambia por observador lento, RSS acotado, falta de disco con refusal explícito, no fugas de secretos aunque el backend caiga, dos lectores independientes mismo run y resultado de step intacto.
