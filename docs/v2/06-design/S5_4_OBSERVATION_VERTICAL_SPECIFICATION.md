# S5.4 — Observation Vertical: especificación

**Ciclo:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine` · fase `Specify`
**Base:** `main` = `569a088c` · informe de exploración `192fbdbc` (artefacto `art-f4699773`)
**Puerta de esta fase:** `requirements-testable` · requisito `specification`

---

## 1. La decisión, y de dónde sale

**El rechazo viaja en el puerto.** `EventPage` gana un brazo de rechazo.

Esto no es una preferencia de diseño: es la única salida compatible con dos cosas que ya están
decididas.

1. **C1 — «el consumidor debe saber que 41 existe».** Si el rechazo no viaja, quien pagina recibe
   una página corta y **no puede distinguir** «no había más filas» de «había una que no pude leer».
   C1 exige que lo sepa, y hay un solo sitio donde puede saberlo sin inventar: el resultado de la
   lectura.
2. **La prohibición de una segunda autoridad.** La alternativa —que el consumidor externo lea
   `EventStore.readRecords` directamente y elija su propia política— cumple C1 pero traslada al
   borde la decisión de cursor, orden, `limit`, `hasMore` y continuación. Eso es exactamente la
   duplicación que **E4c pasó una unidad eliminando**, y el propio `EventStore.kt` la describe:
   *«that is not a hypothetical: it is exactly what `SqliteEventStore.readSlice` did before E4c»*.

---

## 2. Hallazgo que simplifica la implementación

Al escribir esta especificación se comprobó algo que **no estaba en el informe de exploración** y
que reduce el trabajo a un cambio pequeño y bien localizado:

> **El store YA transporta los rechazos.** No hay que inventar el tipo, ni decidir su forma, ni
> tocar la persistencia.

```kotlin
// pipeline-events/.../EventRecordRead.kt
sealed interface EventRecordRead {
    data class Decoded(val event: DomainEvent) : EventRecordRead
    data class Undecodable(
        val kind: String?, val eventId: String?,
        val reason: UndecodableReason, val rawRowPresent: Boolean = true,
    ) : EventRecordRead
}

sealed interface UndecodableReason {
    data class MalformedPayload(val detail: String) : UndecodableReason
    data class UnknownKind(val kind: String) : UndecodableReason
}

data class EventRecordSlice(
    val records: List<EventRecordRead>,
    val nextCursor: EventCursor, val hasMore: Boolean,
    val decoded: List<DomainEvent>,
    val refusals: List<EventRecordRead.Undecodable>,   // <-- YA EXISTE
)
```

El hueco está **una sola capa más arriba**: `EventPage` transporta `envelopes`, `nextCursor` y
`hasMore`, y al proyectar **descarta los rechazos**.

**Corolario que evita la alternativa por completo:** `readAfter` no necesita reimplementar nada.
Cambia `sink.readSlice(...)` por `sink.readRecords(...)` — **la misma autoridad**, que ya corta por
filas y ya decide cursor, orden, `limit` y `hasMore` — y reparte el resultado: proyecta los
`Decoded` a envelopes y lleva los `Undecodable` al brazo nuevo. No es una segunda política; es la
misma política sin colapsar el resultado.

---

## 3. Requisitos

Cada requisito es **falsificable** y dice qué observación lo tumba. Una prueba que no puede fallar
no es un requisito.

### R1 — Un registro durable no desaparece nunca

Una fila que no decodifica aparece en el resultado como rechazo tipado, con su `sequence`.

> **Se tumba:** una página sobre las filas 40/41/42 donde la 41 no aparece en ningún sitio, o
> aparece solo como hueco sin `sequence`.

### R2 — El consumidor distingue malformado de schema desconocido

El rechazo conserva la clasificación que el store ya hace. Un consumidor externo puede decir *por
qué* no pudo leer, no sólo *que* no pudo leer.

> **Se tumba:** un rechazo que sólo expone «ilegible», o que colapsa `MalformedPayload` y
> `UnknownKind` en el mismo caso.

### R3 — El cursor avanza por filas, no por proyecciones

Con un rechazo en medio de la página, `nextCursor` sitúa al consumidor **después** de la fila
rechazada, y ninguna fila se cuenta dos veces ni se salta.

> **Se tumba:** el cursor de la página 40/41/42 apunta a 40 (se pierde la 42) o a 42 y volver a
> pedir desde él devuelve la 42 otra vez.

### R4 — El rechazo no fabrica un evento

Un rechazo **no** se proyecta a envelope. `envelopes` sólo contiene eventos decodificados.

> **Se tumba:** un `envelope` cuyo `kind` corresponde a la fila rechazada, o un
> `UnknownDomainEvent` admissible emitido en su lugar.

### R5 — El modo estricto sigue siendo estricto

`EventStore.readSlice` y `requireFullyDecoded()` **no cambian**: siguen lanzando
`UndecodableEventRecordException`. Un consumidor que quiera todo-o-nada lo sigue teniendo.

> **Se tumba:** `readSlice` dejando de lanzar, o `requireFullyDecoded()` dejando de propagar.

### R6 — Reinicio real entre dos procesos

Proceso A abre historia, consume una página, persiste el cursor y **termina**. Proceso B arranca
limpio, recupera ese cursor y continúa. Sin pérdida, sin duplicado, orden estable.

> **Se tumba:** B repitiendo una fila que A ya consumió, o saltándosela; o el cursor de B sin
> relación con el que escribió A.

### R7 — Built-in y plugin por igual

Un evento tipado built-in y uno de plugin llegan proyectados; una definición de plugin
desconocida y un payload malformado llegan **rechazados y clasificados**, nunca convertidos en un
evento genérico inventado.

> **Se tumba:** un payload malformado apareciendo como evento genérico, o un kind de plugin
> desconocido disappearing en silencio.

---

## 4. Alcance

**Dentro:** el brazo de rechazo en `EventPage`, `readAfter` leyendo `readRecords`, y la vertical
externa que lo consume de punta a punta (store → read acotado → resolución tipada → observación o
rechazo → cursor → proceso que termina → proceso que arranca y continúa).

**Fuera, explícitamente:**

- **Un cursor, una secuencia, un replay engine, un event store o una base de suscripciones
  paralelo.** Todos existen; duplicarlos está prohibido.
- **Tocar `SqliteEventStore` o la persistencia.** El store ya entrega `refusals`.
- **`MainEventsCli`** es un fail-open vivo (`history` en vez de `readAfter`, filtro y `take` en
  memoria). Está en el seam, pero arreglarlo es trabajo de S5.4 **distinto** de la vertical, y por
  eso la vertical **no puede apoyarse en él como prueba de nada**. No se arregla aquí.
- **S5.5 (reactors)**: sigue condicionado a que exista un consumidor productivo real
  (`evento + estado de reactor → decisión pura → admisión`). Sin él, `DEFERRED` por ADR.

---

## 5. Cómo se sabe que esta especificación es buena

Tres controles que se ejecutan **antes** de escribir implementación, y que la pueden tumbar:

| control | qué revela |
|---|---|
| **Mutación R1**: borrar el brazo de rechazos de la página | si R1 realmente discrimina o si el store ya lo tapaba |
| **Mutación R3**: hacer que el cursor avance por `decoded` en vez de por filas | si R3 distingue el cursor real de uno que parece correcto |
| **Mutación R4**: proyectar un rechazo como envelope genérico | si R4 detecta la fabricación o si el tipo lo impide |

Si alguna no se puede tumbar, el requisito es decorativo y se reescribe o se elimina. **Un requisito
que no puede fallar no es un requisito.**

---

## 6. Referencias

- `docs/v2/06-design/S5_REACTIVE_EVENT_SPINE_EXPLORATION.md` — el hueco y sus dos salidas (§3.1)
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventRecordRead.kt` —
  `EventRecordRead`, `UndecodableReason`, `EventRecordSlice.refusals`
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt`
  — `EventPage` (el brazo que falta)
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryReader.kt`
  — `readAfter`, el punto de corte
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventStore.kt` — la autoridad
  de paginación y por qué `readSlice` se define sobre `readRecords`
- `docs/v2/07-uat/P3E_E6_SEMANTIC_STRING_MIGRATION_RECEIPT.md` — E4c, la unidad que construyó esto