# WU-RP-030 — Fitness de connascence evento/codecs/sequence

**Fecha:** 2026-09-22 · **Base:** main @ `949e6b2e` (RP-2 CLOSED)
**Test:** `v2/pipeline-architecture-tests/.../Rp030EventCodecsConnascenceFitnessTest.kt` (4 checks)
**Cambio de producción:** UNO, mínimo, justificado por el fitness.

## Análisis de connascence (modelo-evento ↔ codecs ↔ sequence)

El family `DomainEvent` (51 variantes selladas) está acoplado a 5 sitios:

| Sitio | Despacho | Protección |
|---|---|---|
| `SequenceAssigner.withSequence` | `when(clase)` | compilador (exhaustivo) |
| `SqliteEventStore.appendAssigned` | `when(clase)` | compilador |
| `InMemoryEventStore.appendAssigned` | `when(clase)` | compilador |
| `EnvelopeProjector.subjectOf`/`provenanceOf` | `when(clase)` | compilador |
| `JsonEventLog.decodeEvent` | `when(kind: String)` | **NINGUNA** ← hueco |

El hueco: añadir una variante nueva compila sin error, se **codifica**, pero
`decodeEvent` devuelve `null` (`else -> null`) y el evento se pierde
silenciosamente en cualquier superficie que relee historial persistido.

## Defecto real encontrado (P2 — pérdida silenciosa en replay)

Verificado por el fitness F2: `TimestampsEntered`, `TimestampsExited` y
`StepAdmissionObserved` NO tenían rama de decode en `JsonEventLog`. Cualquier
lectura del log JSON persistido (replay, observación externa) perdía esos tres
kinds. Fix mínimo: 3 ramas de decode añadidas (incluido `executorCalls` de
StepAdmissionObserved). Sin cambio de contrato de wire (los campos ya se
codificaban).

## Fitness añadido (test-side, mecánico)

- F1: kind literal de cada variante == su nombre; únicos; set == sealedSubclasses.
- F2: `decodeEvent` tiene rama para CADA kind del family cerrado.
- F3: los sitios exhaustivos por clase no esconden `else ->`.
- F4: `EnvelopeProjector.subjectOf` exhaustivo sin `else`.

## Verificación

- L1: Rp030 4/4 GREEN (RED inicial válido: solo F2 falló tras corregir 2 bugs
  del propio oráculo — F1a regex de clase, F4 ventana de bloque).
- L4 módulo events: 188/188 GREEN (codec tocado).
- L4 módulo architecture-tests: 313/313 GREEN.
- L3 consumidores (B11ContextBlocks 7/7, ExecutionPaths 8/8) GREEN.

## Cierre (checklist del proyecto)

```text
Reference implementation consulted: none applicable (fitness interno; patrón sealed-hierarchy exhaustivity de ADR-0046 §D2)
Behaviour adopted: decode total del family cerrado + fitness mecánico de connascence
Intentional deviations: none
Security implications reviewed: sin exposición nueva; fix recupera eventos de auditoría (StepAdmissionObserved) en superficies de observación
Tests demonstrating the contract: Rp030EventCodecsConnascenceFitnessTest (4), JsonEventLogRoundTripTest (regresión), FArchL7DomainEventExhaustivityTest
```
