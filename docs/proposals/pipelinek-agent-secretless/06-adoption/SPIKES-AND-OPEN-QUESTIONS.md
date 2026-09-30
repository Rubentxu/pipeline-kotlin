# Spikes y preguntas abiertas

## SPIKE-ASX-01 — Naming CLI

Comparar `command`, `invoke`, `exec`. Criterio: no sugerir semántica shell-free cuando V1 baja a `core.sh`.

## SPIKE-ASX-02 — POSIX argv encoder

Property tests contra shell real con espacios, comillas, Unicode, empty args, newlines y metacaracteres. Determinar qué inputs se rechazan en vez de intentar soportarlos.

## SPIKE-ASX-03 — Linux Secret Service

Probar contra KeePassXC y al menos otra implementación disponible. Documentar unlock/session behavior y errores.

## SPIKE-ASX-04 — ASV protocol seam

Definir el mínimo protocol adapter que PipelineK necesita: describe/prepare-use/session/revoke. Confirmar que ninguna operación retorna secret bytes.

## SPIKE-ASX-05 — Gradle config/cache overlay

Encontrar el patrón que permite config privada por run sin invalidar/copyar toda la cache.

## SPIKE-ASX-06 — Credential requirement declaration

Comparar:

- metadata estática;
- resolver tipado dependiente del input;
- capability invocation desde handler.

Preferir la forma que mantenga codec/handler puros y admission verificable.

## SPIKE-ASX-07 — Profile composition

Formalizar merge algebra y comprobar asociatividad/idempotencia donde aplique.

## SPIKE-ASX-08 — Sensitive provider protocol

UDS + framing/versioning + peer identity. No implementar networking remoto dentro de RP-7.

## SPIKE-ASX-09 — Ephemeral flow IR

Determinar si puede ensamblar directamente el mismo `CompiledPipeline` sin pasar por Kotlin scripting y sin crear un compiler paralelo.

## SPIKE-PXR-01 — Process runtime extraction

**Deferred.** Sólo después de ASX: mapear responsabilidades de `DurableShellExecutor`; decidir si una extracción común reduce complejidad sin romper `sh`.

