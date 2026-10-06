# S5.4 — Observation Vertical: plan de implementación

**Ciclo:** `p-733fb505b5a6bd2d/rp7-sem-s5-event-spine` · fase `Plan`
**Diseño:** `ea92ac89` (con la corrección de alcance) · artefacto `art-72c90f5f`
**Especificación:** `084c6ae9` · `art-8c1caf0f`
**Base:** `main` = `569a088c`
**Puerta de esta fase:** `plan-executable` · requisito `implementation-plan`

---

## 1. Ficheros que se tocan, y los que no

| # | fichero | módulo | qué |
|---|---|---|---|
| 1 | `v2/pipeline-events/.../identity/EventHistoryPorts.kt` | **publicado** | `EventPage.refusals` |
| 2 | `v2/pipeline-events/.../identity/EventHistoryReader.kt` | **publicado** | `readAfter` → `readRecords` |
| 3 | `v2/pipeline-application/.../MainEventsCli.kt` | no publicado | usar `readAfter`, emitir rechazos |
| 4 | `v2/pipeline-architecture-tests/.../FArchS54RefusalReachesThePageFitnessTest.kt` | test | **nuevo**, ley de las dos mitades |
| 5 | `v2/pipeline-events/src/test/.../EventHistoryReaderTest.kt` | test | expectativas que cambian de verdad |
| 6 | `v2/pipeline-events-store/src/test/.../EventHistoryContractTest.kt` | test | caso 40/41/42 sobre `SqliteEventStore` |
| 7 | `examples/observation-vertical/` | **nuevo proyecto** | dos procesos + cursor entre ellos |

**No se tocan:** `SqliteEventStore`, `InMemoryEventStore`, `JsonEventLog`, `EventRecordRead`,
`EventStore`, `EventCursor`, `EventRegistry`, `DurableReadTruthFitnessTest`, y **ninguna fuente
publicada que nombre `events.durable`** — esa es una regla mecánica, no una preferencia.

---

## 2. Orden, y por qué en este orden

### Paso 1 — La ley primero, y tiene que **fallar**

Escribir `FArchS54RefusalReachesThePageFitnessTest` antes de tocar el código, y comprobar que
**falla**. La ley ata **las dos mitades**:

1. `EventPage` declara un campo cuyo tipo es el del store;
2. `readAfter` **propaga** ese campo desde `EventRecordSlice`.

Comprobar sólo la primera es exactamente el fallo D-M4: un contrato que miente sobre sus
capacidades. La ley lee el tipo, no cuenta literales.

> **Puerta del paso:** el fitness debe fallar, y el mensaje debe nombrar `readAfter`. Si pasa en
> verde antes de tocar nada, la ley no discrimina y hay que reescribirla.

### Paso 2 — `EventPage.refusals`

Campo con default `emptyList()`, tipo `List<EventRecordRead.Undecodable>`. Sin vocabulario nuevo:
el store ya produce ese tipo y ya clasifica `MalformedPayload` / `UnknownKind`.

### Paso 3 — `readAfter` → `readRecords`

Reparto del resultado: `decoded` se proyecta a envelopes, `refusals` viaja al brazo nuevo,
`nextCursor` y `hasMore` se copian tal cual. **No se recalcula nada** — `readRecords` ya decidió
todo eso.

### Paso 4 — `MainEventsCli`

`readAfter` en vez de `history` + filtro + `take` en memoria, y los rechazos **visibles en la
salida** para que un proceso externo los vea en vez de adivinarlos.

### Paso 5 — R1–R7 como pruebas

Siete requisitos, siete observaciones discretas. El caso obligatorio 40/41/42 va contra
`SqliteEventStore` real, porque un store en memoria nunca rehusa.

### Paso 6 — D-M1…D-M5, una por una

| | qué muta | qué debe morir |
|---|---|---|
| **D-M1** | borrar `refusals = slice.refusals` | R1 y R2 |
| **D-M2** | proyectar los `Undecodable` como envelopes | R4 |
| **D-M3** | `nextCursor` avanzando por `decoded` | R3 |
| **D-M4** | añadir el campo y **no** tocar `readAfter` | la ley de las dos mitades |
| **D-M5** | devolver la CLI a `history` + `take` en memoria | la prueba de superficie externa |

Cada una atribuida 1:1, restaurada con sha256 verificado.

### Paso 7 — ABI

`apiDump` + `apiCheck`. Si BCV rechaza, excepción en `published-contract-exceptions.json` con el
**SHA real**, en commit posterior a la implementación. **Nunca antes, nunca preventiva.**

### Paso 8 — La vertical externa

`examples/observation-vertical/`, proyecto Gradle propio:

```
proceso A: CLI → lee página → escribe cursor a disco → sale
proceso B: CLI → arranca limpio → lee el cursor → continúa
```

**R6**: si el cursor fuese interno al proceso, B no podría continuar donde A paró. Sin pérdida,
sin duplicado, orden estable.

---

## 3. Cómo se sabe que el plan es ejecutable

Este plan se declara ejecutable por sus **precondiciones verificadas**, no por su claridad:

| precondición | verificada por | estado |
|---|---|---|
| `EventSink` da acceso a `readRecords` | `EventStore.kt:180` | `interface EventSink : EventStore` |
| el rechazo se produce de verdad | `SqliteEventStore.kt:717`, `InMemoryEventStore.kt:226` | ambos sobreescriben |
| el store ya clasifica el motivo | `EventRecordRead.kt` | `MalformedPayload`, `UnknownKind` |
| la CLI es la entrada durable externa | `Main.kt:121`, `publishedContractModules` | `events.durable` no se publica |
| el hueco existe | `EventHistoryPorts.kt` | `EventPage` sin brazo |

Las seis se comprueban con `/tmp/verify-s5-design.sh` (19 afirmaciones, exit 0). **Un plan cuyo
gap no se ha verificado en el código es una intención.**

---

## 4. Puertas y límites

- **Durante el desarrollo:** tests quirúrgicos. Nada de `check` completo por microcambio.
- **Antes de tocar código:** la huella del árbol, y otra vez al terminar, igual que en P3-E.
- **Al cerrar la unidad:** `check --rerun-tasks` sobre el árbol exacto, evidencia por SHA, commit
  atómico, y el recibo **después** de la evidencia.
- **Prohibido en esta unidad:** stubs, placeholders, hardcodes, autoridades duplicadas, fallbacks
  `String` semánticos, leyes de source-scan vacías, y `apiDump` como permiso.

## 5. Lo que este plan NO cubre

- **S5.5 (reactors).** Condicionado a que exista un consumidor productivo real
  (`evento + estado de reactor → decisión pura → admisión normal`). Sin él: `DEFERRED` por ADR, y
  no se construye una API ornamental. Nunca efectos directos en el reactor.
- **S6, S7, S8** y el resto de la secuencia operativa.
- **`pipeline-events-store`_publicación.** No se publica, y publicarlo deshace la separación
  lectura/escritura que lo justifica.
- **El ciclo `rp7-sem-s4-scripted-runtime-v2`**, que sigue `OPEN` aunque su trabajo está integrado
  y cuyo cierre pasa por aprobación humana.

## 6. Referencias

- `docs/v2/06-design/S5_4_OBSERVATION_VERTICAL_SPECIFICATION.md` — R1–R7
- `docs/v2/06-design/S5_4_OBSERVATION_VERTICAL_DESIGN.md` — el diseño y la corrección de alcance
- `docs/v2/06-design/S5_REACTIVE_EVENT_SPINE_EXPLORATION.md` — el fail-open de `MainEventsCli`
- `v2/pipeline-events-store/build.gradle.kts` — por qué `events.durable` no se publica
- `v2/pipeline-events/.../EventStore.kt`, `.../EventRecordRead.kt` — la autoridad y el tipo
- `v2/pipeline-events/.../identity/EventHistoryPorts.kt`, `...Reader.kt` — puerto y reader
- `v2/pipeline-application/.../MainEventsCli.kt`, `Main.kt:121` — la superficie externa
