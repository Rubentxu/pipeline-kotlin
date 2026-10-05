# ADR-0100 — La página de eventos la decide la autoridad de secuencia, no un lector

**Estado:** ACCEPTED (2026-10-05, BLOCK 3 de la evolution del Event Plane).

**Autor:** pipeline-kotlin (Rubentxu).
**Aplica a:** `:pipeline-events` (contrato publicado) y `:pipeline-events-store`.
**Sustituye:** la paginación que `EventHistoryReader.readAfter` decidía por su cuenta.

## Contexto

El read-side del Event Plane tenía cuatro entradas y ninguna era la autoridad:

| read-side | devuelve | cursor | límite | consumidores de producción |
|---|---|---|---|---|
| `EventStore.eventsFor` | `DomainEvent` (semántica) | no | no | muchos — es el `EventSink` que recorre el producto |
| `EventHistory.history` | envelope (identidad) | no | no | **ninguno** |
| `EventTail.readAfter` | envelope (identidad) | sí | sí | **ninguno** |
| `EventViewProjection` | líneas de texto | no | no | **ninguno** (su propio KDoc lo confiesa) |

La única lectura paginada era `EventTail.readAfter`, y decidía el corte **en un lector**: leía
`eventsFor` completo, proyectaba **todos** los eventos a envelope, descartaba los que pasaban del
límite y calculaba `hasMore` mirando uno de más. Es decir, seis reglas —cursor, `sequence > after`,
orden, límite, cursor de continuación y `hasMore`— estaban escritas en un componente que no es la
autoridad de secuencia y al que no se puede preguntar "¿cuál es el orden?".

Mientras tanto, la lectura que sí trae semántica (`eventsFor`) no pagina. Un consumidor externo
que quisiera "recorrer los eventos de un run incrementalmente, en orden store-assigned, con cursor
y límite, y ver qué ocurrió" tenía que elegir entre releer el run entero —con historiales de GiB— o
tragarse identidad sin semántica. La tercera vía era re-cutear a mano, y esa es la que se
caracterizó por escrito en `examples/fabric-contract-consumer` antes de que existiera la
capacidad: cuatro reglas que sólo vivían en un KDoc y que todo consumidor iba a reescribir, y
reescribir un poco peor.

## Decisión

**La página la decide quien posee el orden.** `EventStore` —que ya es la autoridad de secuencia
por EVT-0 §2— expone la lectura paginada que le faltaba:

```kotlin
data class EventSlice(
    val events: List<DomainEvent>,
    val nextCursor: EventCursor,
    val hasMore: Boolean,
)

fun EventStore.readSlice(runId: String, after: EventCursor?, limit: Int): EventSlice
```

Tres propiedades que hacen que esto no sea un `readAfter` con otro nombre:

1. **Es aditivo.** No cambia `EventPage`, no cambia `PipelineEventEnvelope` y no cambia ninguna
   firma publicada. `EventSlice` es a `EventPage` lo que `DomainEvent` es a su envelope: la cosa y
   una proyección suya. El envelope sigue siendo estrictamente identity-only.
2. **El default ES la regla.** La implementación por defecto de `readSlice` decide las seis reglas
   una sola vez, sobre `eventsFor`. Un store que puede cortar dentro de su almacenamiento la
   sobrescribe por optimización, y `EventSliceParityLawsTest` es lo que convierte "equivalente"
   en algo con significado: mismo run, mismo cursor y mismo límite producen las mismas sequences,
   el mismo `nextCursor` y el mismo `hasMore`, sea quien sea que respondió.
3. **El lector proyecta, no corta.** `EventHistoryReader.readAfter` dejó de re-decidir el corte y
   mapea el `EventSlice` que le da el store. Eso elimina la segunda implementación de la regla y,
   de paso, el hecho de que construir una página de tres construyera envelopes para el run entero.

`nextCursor` es **no-null**, y es una decisión y no una comodidad: un cursor `null` ya significa
"empezar por el principio", así que una continuación nullable no distinguiría el final de un run de
su comienzo. `hasMore` es el campo que dice si hay más. El `EventPage.nextCursor` sigue nullable
por compatibilidad con lo ya publicado, y su KDoc —que decía "null only when the page reached the
end of history", algo que el código nunca hizo— queda corregido para dejar de prometer lo que no
ocurre.

## Alternativas descartadas

- **Dar elemento tipado a `EventPage`.** Habría convertido el read-side de observación en el
  read-side de dominio, ampliaría el contrato publicado con la jerarquía entera de `DomainEvent` y
  dejado `PipelineEventEnvelope` con un solo consumidor, a un paso de ser un segundo modelo de
  eventos. Rechazado por la ley de *una autoridad, una representación*.
- **Un puerto nuevo para la lectura semántica.** Habría sido añadir una superficie sin cambiar la
  que ya tenía la culpa. `EventStore` ya era la autoridad; lo que le faltaba era el método, no la
  interfaz.
- **No cambiar nada.** `EventStore.readSlice` habría sido un simple `ORDER BY sequence` detrás de
  la interfaz de `eventsFor`, y la autoridad de lectura habría seguido estando en el lector.

## Consecuencias

- Un consumidor externo puede recorrer un run en páginas tipadas desde las coordenadas
  publicadas, sin reimplementar el corte. Está probado desde fuera en
  `examples/fabric-contract-consumer`, y el characterisation-test que primer demonstró el hueco se
  conserva en forma de comparación entre las dos resoluciones.
- `InMemoryEventStore` y `SqliteEventStore` cortan dentro de su almacenamiento. El de SQL usa
  `WHERE sequence > ? ORDER BY sequence LIMIT ? + 1`, que además es el orden que `ux_events_run_sequence`
  cubre, así que la base de datos sirve el orden desde el índice en vez de ordenar un b-tree
  temporal sobre cada fila que casa.
- Sobreescribir `readSlice` es una optimización, nunca un cambio de significado. Lo que mantiene
  esa frase verdadera es `EventSliceParityLawsTest`, no el parecido del código.

## Lo que este ADR NO cierra

`PipelineEventEnvelope.causation` y `.correlation` los rellena `EnvelopeProjector` con `null`
siempre. No es un defecto de serialización —campos, codec y tests existen desde BLOCK 2— sino un
hueco de origen: **`DomainEvent` no lleva contexto causal**, así que nadie puede rellenar esos dos.
La identidad cross-system que el envelope promete está a medias por el lado del evento, no del
sobre. Eso pertenece a S5 (Event Spine) y no se mezcló aquí: hacerlo would've hecho este cambio
mayor de lo que la evidencia sostiene.
