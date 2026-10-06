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

### 2.3 La superficie externa es la CLI, y eso obliga a tocarla

**Corrección de alcance, hecha al escribir este plan y no antes.**

La versión anterior de este documento daba `MainEventsCli` por fuera de alcance. **Es falso**, y
por una razón que no es de opinión sino de la arquitectura publicada:

> The package is `events.durable` for every file here … `events` and `events.identity` are
> published, `events.durable` is not, and **a published source that names the third is a build
> error waiting to be written**. — `pipeline-events-store/build.gradle.kts`

`SqliteEventStore` vive en `events.durable` y **no se publica, deliberadamente**, porque

> The read side and the write side are different authorities. A consumer that can name the journal
> can decide what a replay returns; a consumer that can only page history can only observe.

De ahí sale todo lo demás: **un proceso externo no puede abrir el store**, porque el store no está
publicado y porque las fuentes publicadas no pueden nombrarlo. El único punto de entrada durable
externo que existe es la CLI (`Main.kt:121` → `MainEventsCli`), que está en `pipeline-application`
y tampoco se publica.

Consecuencia: para entregar BLOCK C —dos procesos, cursor real entre ellos, rechazo visible— **hay
que arreglar `MainEventsCli`**, porque es por donde un proceso externo puede observar. No es
ampliación: es el camino. Y arreglarlo es lo que convierte el fail-open del §4 en un hecho cerrado.

El cambio en la CLI es el mismo que en el reader, no otro:

```kotlin
reader.readAfter(run, cursor, limit)   // en vez de history(...) + take(limit)
```

y emitir los rechazos de la página de forma que un proceso externo los **vea**, no los adivine.

**Alternativas descartadas por qué no:**

- *Publicar `pipeline-events-store`*: deshace la separación entre lado lectura y lado escritura
  que es la razón de que no esté publicado. Prohibido por diseño.
- *Que el consumidor externo lea el SQLite por su cuenta*: segunda implementación de la lectura,
  es decir exactamente lo que E4c eliminó.
- *Que el consumidor resuelva el store por reflexión o classpath*: puerta de atrás, y el mismo
  repositorio lo llama «side door» al hablar del consumidor de una sola coordenada.

---

## 3. Lo que este diseño NO cambia

- **`readSlice` y `requireFullyDecoded()` siguen lanzando.** `readSlice` es el modo estricto y
  existe; esta vertical es la que elige el modo observabilidad. **R5.**
- **El store y la persistencia.** `SqliteEventStore` ya sabe rehusar y ya nombra la fila por
  columnas. No se toca SQL, ni esquema, ni `readRecord`.
- **`history` sigue existiendo.** Sigue siendo el camino «sólo decodificados» y por eso sigue sin
  poder rehusar. **No se borra**: es un atajo legítimo para quien quiere sólo lo decodificable y
  lo sabe. Lo que se corrige es que `MainEventsCli` deje de usarlo (§2.3).
- **`EventCursor`, `EventSlice`, la secuencia durable y `EventRegistry`.** No hay cursor nuevo, ni
  secuencia nueva, ni replay engine, ni store, ni base de suscripciones.

---

## 4. Conformidad arquitectónica

**Dirección de dependencias:** el puerto cambia en `pipeline-events`; el adaptador
(`pipeline-events-store`) **no se toca**. Un puerto que crece hacia el exterior sin que el
adaptador crezca hacia dentro mantiene la dirección hexagonal: el puerto nombra un resultado que el
adaptador ya sabe producir, no uno nuevo que el adaptador tendría que inventar.

**Un solo camino:** no hay una segunda ruta de lectura. `readAfter` sigue siendo *el* camino del
reader; lo que cambia es si colapsa el rechazo o no. Y `MainEventsCli` **deja de tener un camino
propio**: pasa de filtrar `history` en memoria a usar `readAfter`, con lo que el fail-open del §4
desaparece por construida y no por convención.

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

**D-M5 cubre la CLI**, que entró en alcance al descubrirse §2.3: volver a `history` + `take(limit)`
en memoria, dejando el reader arreglado. Es la tentación de «no tocar la CLI porque es mucho más
código». Si esa mutación no rompe nada, **BLOCK C no está entregado**: el puerto observaría el
rechazo y el único proceso que puede observarlo en la práctica seguiría sin verlo.

---

## 6. Orden de ejecución

1. `EventPage.refusals` + fitness de las dos mitades (que debe **fallar** con `readAfter` sin
   tocar: es la prueba de que la ley discrimina).
2. `readAfter` → `readRecords`.
3. `MainEventsCli` → `readAfter`, con los rechazos visibles en su salida (§2.3).
4. Los siete requisitos R1–R7 como pruebas.
5. **D-M1…D-M4**, cada una atribuida 1:1, restaurada con sha256.
6. `apiCheck` y, si BCV rechaza, la excepción con SHA real.
7. La vertical externa con la prueba de reinicio de R6.
8. `check --rerun-tasks` completo sobre el árbol exacto.

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