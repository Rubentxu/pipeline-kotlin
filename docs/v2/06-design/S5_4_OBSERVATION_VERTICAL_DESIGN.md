# S5.4 — Observation Vertical: diseño

**Ciclo:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine` · fase `Design`
**Especificación:** `084c6ae9` · artefacto `art-8c1caf0f` · 7 requisitos R1–R7
**Informe:** `192fbdbc` · artefacto `art-f4699773`
**Puerta de esta fase:** `architecture-consistent` · requisito `design`

---

## 1. Comprobado antes de diseñar

Cuatro hechos leídos del código, no asumidos. Los tres primeros **habilitan** el diseño y el
cuarto lo delimita.

| # | hecho | dónde | por qué decide |
|---|---|---|---|
| F1 | `interface EventSink : EventStore` | `EventStore.kt:180` | `sink` **ya tiene** `readRecords`. No hace falta widening ni puerto nuevo. |
| F2 | `InMemoryEventStore:226` y `SqliteEventStore:717` **sobreescriben** `readRecords` | ambos | El rechazo se produce de verdad. Sin esto el brazo nuevo sería **siempre vacío** y R1 decorativo. |
| F3 | El default de `readRecords` se construye sobre `eventsFor` y **no puede rehusar** | `EventStore.kt:146-160` | Por eso F2 es obligatoria: la regla ya está escrita y `DurableReadTruthFitnessTest:226` la vigila. |
| F4 | `DurableReadTruthFitnessTest` fija el **store**, no el reader | fitness test | El cambio no toca lo que ese test ata. |

**Y un alineamiento que no es mío:** el KDoc de `SqliteEventStore.readRecords` dice ya

> Returning a sealed result rather than throwing is deliberate: the caller here is a PAGE, and
> **a page has to be able to carry the refusal.**

El store anticipa este diseño. La pieza que falta es exactamente la que su propio KDoc describe.

---

## 2. Superficie de cambio

Tres puntos, todos en el módulo de contrato salvo la vertical, que es externa.

### 2.1 `EventPage` gana el brazo de rechazo

```kotlin
// pipeline-events/.../identity/EventHistoryPorts.kt
data class EventPage(
    val envelopes: List<PipelineEventEnvelope>,
    val nextCursor: EventCursor?,
    val hasMore: Boolean,
    val refusals: List<EventRecordRead.Undecodable> = emptyList(),   // <-- nuevo
)
```

**El tipo ya existe.** `EventRecordRead.Undecodable` lleva `sequence`, `eventId`, `kind`,
`reason` (`MalformedPayload` | `UnknownKind`) y `rawRowPresent`. No se inventa vocabulario, no se
crea un ADT nuevo, no se duplica la clasificación que el store ya hace.

**Sobre el ABI, decidido por adelantado y no en el momento.** `pipeline-events` está `EXPERIMENTAL`
en `published-contract-maturity.json`, así que una ruptura es admisible **registrándola**. Pero
añadir un campo con default no debería romper a nadie: los llamadores Kotlin siguen compilando, y
para el enlazador binario el punto a verificar es si sobrevive el constructor de tres argumentos.
La secuencia es: `apiDump` + `apiCheck` primero; si BCV acepta, no hay nada que registrar; si
rechaza, se registra la excepción en `published-contract-exceptions.json` con el SHA real, **igual
que se hizo en P3-E** y nunca antes. No se registra una excepción preventiva para algo que BCV ya
aceptó.

### 2.2 `readAfter` deja de colapsar el resultado

```kotlin
// pipeline-events/.../identity/EventHistoryReader.kt
override fun readAfter(run: ResourceRef, cursor: EventCursor?, limit: Int): EventPage {
    val runId = run.segments.last()
    val slice = sink.readRecords(runId, cursor, limit)     // la MISMA autoridad
    return EventPage(
        envelopes = slice.decoded.map { EnvelopeProjector.project(it, providerLookup) },
        nextCursor = slice.nextCursor,
        hasMore = slice.hasMore,
        refusals = slice.refusals,                        // <-- ya no se pierde
    )
}
```

**Esto no es una segunda política de paginación.** `readRecords` es la autoridad y ya decide
corte por filas, orden, `limit`, `hasMore` y `nextCursor`. Lo único que cambia es que su resultado
deja de colapsarse: antes se llamaba a `readSlice`, que es `readRecords(...).requireFullyDecoded()`
y por tanto **tira la página entera** cuando hay un rechazo. Ahora se llama a la autoridad directa
y se reparte. La diferencia es de qué se hace con el rechazo, no de cómo se corta la página.

**R1, R2 y R4 quedan estructuralmente garantizados:** un rechazo no puede desaparecer (viaja), distingue
malformado de desconocido (viaja `reason`) y no se proyecta a envelope (sólo `decoded` se proyecta).

### 2.3 La vertical externa

Fuera de `v2`, en `examples/`, con su propio `settings.gradle.kts` y **sin `project(...)`**: es la
misma forma que los consumidores que ya certifican el contrato publicado.

```
proceso A: abre historia → lee página → escribe cursor → sale
proceso B: arranca limpio → recupera cursor → continúa
```

**R6** es lo que prueba que el cursor es real y no una paginación local: si el cursor fuese interno
al proceso, B no podría continuar donde A paró.

---

## 3. Lo que este diseño NO cambia

- **`readSlice` y `requireFullyDecoded()` siguen lanzando.** `readSlice` es el modo estricto y
  existe; esta vertical es la que elige el modo observabilidad. **R5.**
- **El store y la persistencia.** `SqliteEventStore` ya sabe rehusar y ya nombra la fila por
  columnas. No se toca SQL, ni esquema, ni `readRecord`.
- **`history` sigue igual.** Sigue siendo el camino «sólo decodificados» y por eso sigue sin poder
  rehusar. No se corrige aquí; es la base del fail-open de `MainEventsCli`, que es trabajo
  distinto.
- **`EventCursor`, `EventSlice`, la secuencia durable y `EventRegistry`.** No hay cursor nuevo, ni
  secuencia nueva, ni replay engine, ni store, ni base de suscripciones.

---

## 4. Conformidad arquitectónica

**Dirección de dependencias:** el puerto cambia en `pipeline-events`; el adaptador
(`pipeline-events-store`) **no se toca**. Un puerto que crece hacia el exterior sin que el
adaptador crezca hacia dentro mantiene la dirección hexagonal: el puerto nombra un resultado que el
adaptador ya sabe producir, no uno nuevo que el adaptador tendría que inventar.

**Un solo camino:** no hay una segunda ruta de lectura. `readAfter` sigue siendo *el* camino del
reader; lo que cambia es si colapsa o no. `MainEventsCli` sigue usando `history` y por eso sigue
siendo un fail-open — **que es justo por lo que no puede ser la prueba de esta vertical**.

**Fitness que vigila el resultado:**

| fitness | qué impide |
|---|---|
| `DurableReadTruthFitnessTest` | que un store con payload de texto deje de sobreescribir `readRecords` |
| `EventHistoryReaderTest`, `EventHistoryContractTest` | que `readAfter` cambie de contrato (se actualizan sólo si su expectativa cambia de verdad) |
| fitness nuevo de esta unidad | que `refusals` vuelva a desaparecer entre la autoridad y la página |

**New fitness, y por qué:** sin una ley que ate `EventPage.refusals` a `EventRecordSlice.refusals`,
el brazo se puede volver a borrar sin que nada se entere. La ley debe **leer el tipo**, no contar
literales: que el campo exista, que su tipo sea el del store y que `readAfter` lo propague.

---

## 5. Falsadores del diseño

Escrito antes de implementar, con la misma regla que la especificación: **si un control no puede
tumbar nada, no es un control.**

| control | qué tumba si falla |
|---|---|
| **D-M1** borrar `refusals = slice.refusals` en `readAfter` | R1 y R2 dejan de cumplirse: el rechazo desaparece |
| **D-M2** proyectar los `Undecodable` como envelopes | R4: se fabrica un evento |
| **D-M3** hacer que `nextCursor` avance por `decoded` en vez de por filas | R3: el cursor pierde o repite la fila 42 |
| **D-M4** añadir el brazo a `EventPage` pero dejar `readAfter` en `readSlice` | R1: el brazo existe y siempre está vacío — el fallo más caro, porque **parece** implementado |

**D-M4 es el riesgo real de este diseño.** El cambio más fácil de equivocar es añadir el campo y
olvidar el segundo punto, dejando un contrato que miente sobre sus capacidades. Por eso el fitness
nuevo tiene que comprobar **las dos mitades**, no que el campo exista.

---

## 6. Orden de ejecución

1. `EventPage.refusals` + fitness de las dos mitades (que debe **fallar** con `readAfter` sin
   tocar: es la prueba de que la ley discrimina).
2. `readAfter` → `readRecords`.
3. Los siete requisitos R1–R7 como pruebas.
4. **D-M1…D-M4**, cada una atribuida 1:1, restaurada con sha256.
5. `apiCheck` y, si BCV rechaza, la excepción con SHA real.
6. La vertical externa con la prueba de reinicio de R6.
7. `check --rerun-tasks` completo sobre el árbol exacto.

---

## 7. Referencias

- `docs/v2/06-design/S5_4_OBSERVATION_VERTICAL_SPECIFICATION.md` — R1–R7
- `docs/v2/06-design/S5_REACTIVE_EVENT_SPINE_EXPLORATION.md` — el hueco y por qué la alternativa
  se descartó
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventStore.kt` — F1, F3 y el
  `readSlice` estricto de R5
- `v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventStore.kt`
  — F2 y el KDoc que anticipa la página con rechazo
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/EventRecordRead.kt` — el tipo
  del rechazo y `UndecodableReason`
- `v2/pipeline-events/src/main/kotlin/dev/rubentxu/pipeline/v2/events/identity/EventHistoryReader.kt`
  — el punto de corte
- `v2/pipeline-architecture-tests/.../DurableReadTruthFitnessTest.kt` — la ley que no se toca
- `docs/v2/07-uat/P3E_E6_SEMANTIC_STRING_MIGRATION_RECEIPT.md` — E4c, y el precedente de la
  excepción de ABI