# S5 — Reactive Event Spine: informe de exploración

**Ciclo:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine`
**Fase:** `explore` → propuesta `Specify`
**Base:** `main` = `569a088c` (P3-E cerrado, puerta verde)
**Fuente canónica:** `docs/pipelinek-semantic-evolution/04-events-reactivity.md`

---

## 1. La conclusión de la exploración, primero

**El eje de S5.4 no hay que construirlo: hay que consumedlo.** La lectura durable honesta —la que
devuelve evento tipado **o** rechazo explícito, y nunca hace desaparecer una fila— ya existe, ya
está certificada por **P3-E E4c** y ya tiene un puerto público (`EventStore.readRecords`) y un
consumidor (`EventHistoryReader.readAfter`).

Lo que **no** existe, y es la decisión de producto que bloquea la fase `Specify`, es **dónde viaja
el rechazo**. `EventPage` transporta `envelopes`, `nextCursor` y `hasMore`, y **ningún brazo de
rechazo**: un consumidor que pagina por `readAfter` ve una página corta y no puede distinguir "no
había más filas" de "había una fila que no pude leer". La C1 de BLOCK C —«el consumidor debe saber
que 41 existe»— **no la satisface el puerto actual** y exige una decisión explícita.

Y hay un **fail-open vivo** en el único consumidor de eventos que tiene el producto, en el mismo
seam que S5.4 va a tocar (§4).

---

## 2. Lo que ya está, y es reutilizable sin duplicarlo

| autoridad | ubicación | papel |
|---|---|---|
| `EventCursor` | `pipeline-events/.../identity/EventHistoryPorts.kt` | posición durable; `evt-cursor-v1` |
| `EventSlice` | `pipeline-events/.../EventRecordRead.kt` | página + `nextCursor` + `hasMore` |
| `EventStore.readRecords` | `pipeline-events/.../EventStore.kt` | **decodificado-o-rehusado**, corte por FILAS |
| `EventStore.readSlice` | ídem | `readRecords(...).requireFullyDecoded()` |
| `EventHistoryReader.readAfter` | `.../identity/EventHistoryReader.kt` | proyecta la página que ya cortó el store |
| `EventRegistry` | usado por `StepDispatchEngine`, `PluginEventEmissionAdapter` | registro abierto de eventos |

**Prohibición que este informe hereda:** no crear otro cursor, otro sequence, otro replay engine,
otro event store ni una base de datos de suscripciones paralela. Todo lo anterior está en
`pipeline-events` y `pipeline-events-store` y es la única autoridad.

### 2.1 Por qué E4c importa aquí

`EventStore.kt` documenta por qué `readSlice` se define **en términos de** `readRecords` y no al
lado:

> A store that decoded first and then counted would let an unreadable row shrink the page below
> `limit`, corrupt `hasMore`, and move the continuation cursor by an amount that has nothing to do
> with how far the reader got.

Es exactamente la corrupción que S5.4 daría por hecha si construyera su propia paginación. No hay
que repetir ese error: hay que llamar al store.

---

## 3. El hueco: el rechazo no tiene dónde viajar

`EventHistoryReader.readAfter` es el camino correcto y su propio KDoc dice qué hace y qué no:

> `readSlice` refuses rather than shortening when a durable row will not decode … `EventPage`
> carries envelopes, which are IDENTITY, and projecting an unreadable row into an envelope would be
> inventing a record that the store could not interpret.
>
> **A consumer that must page past refusals reads `EventStore.readRecords` directly and chooses its
> own policy.**

Ese último párrafo es la línea que S5.4 tiene que decidir. Hoy hay dos caminos y ninguno cumple
C1:

- **`readAfter`**: se niega a inventar un envelope (correcto), pero entrega una página corta **sin
  decir por qué**. El consumidor no puede distinguir el final real de un hueco. `EventPage` no
  tiene brazo de rechazo.
- **`readRecords` directo**: sí transporta el rechazo, pero obliga al consumidor a **reimplementar**
  la política de cursor, orden, `hasMore` y continuación — que es la segunda autoridad que E4c pasó
  media vida eliminando.

### 3.1 Las decisiones que esto abre para `Specify`

1. **¿Añade `EventPage` un brazo de rechazo, o el consumidor externo pagina por `readRecords`?**
   Lo primero mantiene una sola autoridad de paginación y hace el rechazo observable; lo segundo no
   toca el puerto pero reintroduce la política duplicada en el borde. **La primera es la que encaja
   con E4c y con la ley de Semantic Constitution §8.**
2. **Un rechazo, ¿detiene la página o la atraviesa?** Detener es el default actual y es lo honesto
   para un envelope, pero un lector de observabilidad necesita **saber** que la fila existe aunque
   no pueda proyectarla.
3. **¿Qué es una fila malformada frente a un schema desconocido?** §3 de la fuente canónica exige
   fall-closed para lo desconocido; `H3` de BLOCK H exige lo mismo para el futuro. La decisión
   tiene que ser una, no dos.

---

## 4. Hallazgo: `MainEventsCli` es un fail-open vivo

`MainEventsCli` (`pipeline-application/.../MainEventsCli.kt:72-80`) es hoy el consumidor de eventos
del producto, y usa **el otro camino**:

```kotlin
val envelopes = reader.history(run, query)      // Sequence, sólo decodificados
    .filter { cursor == null || it.sequence > cursor.lastSequence }
    .take(limit)
    .toList()
envelopes.forEach { println(EnvelopeCodec.encode(it)) }
val last = envelopes.lastOrNull()?.sequence ?: cursor?.lastSequence ?: 0L
System.err.println("evt-cursor-v1:$runId:$last")
```

Tres consecuencias, todas del mismo defecto:

1. `history` se apoya en `sink.eventsFor(runId)`, que «cannot produce a refusal (it yields only
   decoded events)». Una fila malformada o de schema desconocido **desaparece sin más**.
2. El filtro de cursor y el `take(limit)` se aplican **en memoria, sobre envelopes decodificados**:
   el límite cuenta hechos projections, no filas. Es la corrupción de `hasMore` que E4c arregló en
   el store, todavía viva en el consumidor.
3. El cursor que emite sale del **último envelope decodificado**, así que ni siquiera el consumidor
   puede inferir que había algo entre medias.

Es la misma clase de defecto que E4b/E4c cerraron en `JsonEventLog` y en `CanonicalInvocation`: un
valor no fiable convertido en silencio en un hecho. **No se arregla en este commit de
exploración**; queda como trabajo de S5.4, y es exactamente por lo que la vertical externa no puede
apoyarse en este CLI como prueba de nada.

---

## 5. Alcance de S5.4, en una frase

Entregar **una** vertical externa completa —`durable event store → read acotado → resolución tipada
→ observación o rechazo → cursor → proceso que termina → proceso que arranca y continúa`— apoyada
en las autoridades de §2, con el rechazo **visible** en el resultado de lectura (decisión de §3.1),
sin scraping de logs, y sin crear un segundo cursor, secuencia, replay engine ni store.

Los escenarios de salida ya están definidos por el usuario y no se rediseñan aquí: C1 (registro
honesto con el caso 40/41/42), C2 (built-in, plugin, schema desconocido, payload malformado),
C3 (prueba de reinicio entre dos procesos: sin pérdida, sin duplicado, orden estable, cursor real).

---

## 6. Qué NO se decide aquí

- **S5.5 (reactors).** BLOCK D lo condiciona a que exista un consumidor productivo real que necesite
  `evento + estado de reactor → decisión pura → admisión normal`. Si no aparece, S5.5 se registra
  `DEFERRED` por ADR y no se construye una API ornamental. **No es parte de este informe.**
- **S6, S7, S8** y el resto de la secuencia: fuera de P3-E y fuera de este ciclo.
- El estado del ciclo `rp7-sem-s4-scripted-runtime-v2`, que sigue `OPEN` aunque su trabajo está
  integrado en `main`. Su cierre pasa por `cycle.supersede`, que está detrás de aprobación humana
  (`surface.cycle_state#cycle_supersede`). **No se altera aquí.**

---

## 7. Referencias

- `docs/pipelinek-semantic-evolution/04-events-reactivity.md` — envelope, registro, autoridad de
  emisión, consumidores, semántica de entrega, replay
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventStore.kt` —
  `readRecords` / `readSlice` / `requireFullyDecoded`
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryReader.kt`
  — `history` frente a `readAfter`
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryPorts.kt`
  — `EventPage`, `EventCursor`
- `v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/MainEventsCli.kt`
  — el fail-open de §4
- `docs/v2/07-uat/P3E_E6_SEMANTIC_STRING_MIGRATION_RECEIPT.md` — P3-E cerrado, base de este ciclo