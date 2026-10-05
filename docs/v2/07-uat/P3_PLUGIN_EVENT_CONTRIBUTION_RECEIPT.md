# P3-A..P3-D — Un plugin externo declara y emite sus propios eventos

**STEP-CERT sobre el SHA exacto `b96c647e`.**
Work item SDDK `0553d7ef-adec-417c-9f8f-effc754425d0` — Runtime Observation Contract Closure.

Este recibo certifica el tramo P3-A..P3-D. Los slices 1 y 2 (identidad del carrier y
secuencia asignada por el store) están certificados por
[`P3_PLUGIN_EVENT_CARRIER_IDENTITY_RECEIPT.md`](P3_PLUGIN_EVENT_CARRIER_IDENTITY_RECEIPT.md)
sobre `804eb667`; este recibo **no** reescribe esa evidencia ni la hereda.

---

## 1. Qué se certifica

Que un artefacto externo al repositorio puede, **sin una sola línea de cambio en core**,
declarar un kind de evento propio y emitirlo durante una ejecución real, y que el evento
sobrevive a la relectura del store durable con su identidad intacta.

La cadena completa, verificada:

```text
example-uppercase-plugin-0.1.0.jar          (artefacto externo, compilación aparte)
   │
   ├─ META-INF/services/
   │     …registry.EventDefinitionContributor
   │     → UppercaseEventContributor         declara UppercaseApplied + codec
   │
   └─ UppercaseObservedStepDefinition       declara PLUGIN_EVENT_EMISSION_CAPABILITY
                  │
                  ▼
ExternalEventDefinitionDiscovery          ServiceLoader en runtime/application,
   │                                      fail-closed ante cualquier anomalía
   ▼
EventRegistry  ──►  admitted como capability en el límite de preparación
   │
   ▼
PluginEventEmissionAdapter  ──►  RegistryEventEmitter  ──►  EventSink
   │
   ▼
SqliteEventStore            la secuencia la asigna el store, no el productor
   │
   ▼
readSlice(runId, after = null)              reabierto en un proceso NUEVO
   │
   ▼
PluginEventEmitted { registryKind = "example.uppercase.applied",
                     schemaVersion = 1, payload = <json> }
```

Cero ramas por `StepKey`, cero ediciones de compilador, cero ediciones de aplicación, cero
acceso del plugin al store.

---

## 2. Evidencia del gate

Gate ejecutado sobre `b96c647e` con árbol limpio, árboles de trabajo sin modificar:

```text
cd v2 && ./gradlew check --rerun-tasks
```

| Dato | Valor |
|---|---|
| Resultado | `BUILD SUCCESSFUL in 32m 58s` |
| `GATE_EXIT` | `0` |
| Tareas ejecutadas | 562 |
| XML de resultado | 742 |
| Tests | **4935** |
| Fallos | **0** |
| Errores | **0** |
| Skips | 140 |
| Log | `~/.local/state/pipelinek-gates/gate-b96c647efe02a79451e383664a7f8a363dcfadc3.log` |
| sha256 del log | `b7df7067d4c29605b551f7d953407611773b9bb73a1768f780e2c9c51ee60a87` |

Recuento **por XML con recorrido recursivo**, nunca por glob de un nivel: el glob omite
`pipeline-step-sdk/*`, que está dos niveles abajo, y subestima el total.

### 2.1 Verificación focal previa, por clase

| Suite | Tests | F | E |
|---|---|---|---|
| `CoordinatorGrowthGuardrailTest` | 2 | 0 | 0 |
| `P3DPluginEventInstalledDistributionUatTest` (HF2) | 2 | 0 | 0 |
| `ExternalEventDefinitionDiscoveryTest` | 5 | 0 | 0 |

HF2 = distribución instalada real, plugin externo en el classpath, store reabierto en
proceso nuevo.

---

## 3. El bloque por commit

| SHA | Qué establece |
|---|---|
| `ff1a6df0` | P3-A: SPI `EventDefinitionContributor` en contrato publicado. P3-B: `ExternalEventDefinitionDiscovery` con `ServiceLoader` y `compose()` fail-closed. P3-C: `PluginEventEmission` + capability + adaptador, cableado en contexto, engine y composition root |
| `a9fcba8f` | P3-D: la costura se muda de `pipeline-application` (no publicado) a `pipeline-events` (publicado); el plugin aporta descriptor y contribución; HF2 sobre distribución instalada |
| `2f4ae83c` | Dump ABI de `pipeline-events` — delta **aditivo**, 13 líneas, 0 eliminaciones |
| `d90d2c8b` | Reparación de regresión: el Step de referencia vuelve a `requiredCapabilities = emptySet()` |
| `b96c647e` | La costura de emisión se compone en el dispatch engine; el coordinador baja a 579 líneas |

---

## 4. Dos defectos reales encontrados por el camino

Ninguno era hipotético. Los dos se habrían declarado "terminado" sin este bloque.

### 4.1 `JsonEventLog.decodeEvent` no tenía rama para el carrier nuevo

El evento se **escribía y se perdía en silencio al releer**: `decodeEvent` no conocía
`PluginEventEmitted`, así que un store reabierto no devolvía lo que el store contenía. El
sistema entero parecía funcionar porque el productor y el consumidor nunca se cruzaban en
la misma ejecución.

La rama añadida **no** defaulta `registryKind` ni `schemaVersion`: devuelven `null`. Un
valor sustituto re-tiparía el registro como evento de otro plugin, que es peor que
perderlo. El fitness F1 de `Rp030` se amplió del fichero `DomainEvent.kt` a todo el
paquete de eventos con `Files.walk`, porque escaneaba un solo fichero y por eso dejó
cubrir una variante en silencio. La jerarquía sellada pasó de 68 a **71** variantes y el
pin `FArchL7` se movió.

### 4.2 El Step de referencia había adquirido una dependencia en silencio

`UppercaseStepContractSuiteTest` falló al pedir `PLUGIN_EVENT_EMISSION_CAPABILITY` para
un Step que no lo usa. La tentación era actualizar la aserción. Se hizo lo contrario:
`uppercase` volvió a declarar **cero** capabilities y se creó `uppercaseObserved`
(`example.uppercase.observed`) como Step observador real.

La razón es que una referencia que ha adquirido una dependencia deja de ser la
referencia. El plugin demuestra las dos formas —pedir nada / pedir una costura— y
`uppercase` sigue siendo la que prueba la primera.

---

## 5. Lo que este recibo NO dice

Cinco límites, escritos para que nadie los lea como cobertura que no existe.

1. **No hay CI remota.** Desde `754ddda0` (2026-09-30) `.github/workflows/` está vacío y
   `gh run list` sólo devuelve jobs de Dependabot. Este STEP-CERT **no** exige CI remota
   por definición (`CERTIFICATION_PROTOCOL.md` §4 separa STEP-CERT de PRODUCT-GATE), pero
   en consecuencia **el `PRODUCT-GATE` sigue `BLOCKED_EXTERNAL`** y este commit no lo
   mueve ni un milímetro.

2. **La recuperación del carrier no está probada.** La prueba HF2 reabre el store en un
   proceso nuevo, lo que prueba lectura, **no** resistencia a un kill. El kill alrededor
   del COMMIT de SQLite pertenece a **S7 / 0.49** por decisión de diseño: la unidad durable
   es el propio COMMIT (WAL), y la ley es `pre-COMMIT ⇒ evento ausente`,
   `post-COMMIT ⇒ completo`, `nunca ⇒ carrier parcial`. **No se creó journal específica**
   para `PluginEventEmitted` y no se creará.

3. **La superficie contractual no tiene clasificación de madurez todavía.** `EventRegistry`
   es pública y BCV la congela, pero no existe autoridad que diga si es `STABLE`,
   `PARTIAL` o `EXPERIMENTAL`. Eso es E5 de P3-E, y es lo que gobierna si el próximo
   cambio puede ser breaking.

4. **El schema semántico del Event Plane sigue parcialmente expresado en `String`.** Este
   bloque certifica la *identidad* y el *carrier*, no la *semántica*. El inventario
   completo está en
   [`P3E_EVENT_SEMANTIC_STRING_INVENTORY.md`](../06-design/P3E_EVENT_SEMANTIC_STRING_INVENTORY.md)
   y por eso Observer (S5.4) **no** se abre todavía.

5. **El gate cubre este SHA, no los documentos posteriores.** `abb26530` (el inventario
   E1) es posterior a `b96c647e` y no toca producción; la evidencia de este recibo es del
   árbol del primero, y por ley un recibo no hereda a otro SHA.

---

## 6. Criterio de salida

```text
[ X ] un JAR externo declara un kind sin cambios en core
[ X ] la emisión ocurre durante una ejecución real sobre la distribución instalada
[ X ] la secuencia la asigna el store, no el productor
[ X ] el carrier sobrevive a reabrir el store en otro proceso
[ X ] registryKind / schemaVersion sin defaults en el decoder
[ X ] fallo cerrado ante contributor roto, colisión o declaración inválida
[ X ] re-declarar un kind con otro schemaVersion se rechaza, no se actualiza
[ X ] BCV aditivo: 13 líneas, 0 eliminaciones
[ X ] detekt limpio
[ X ] check --rerun-tasks verde sobre b96c647e con árbol limpio
[ ] CI remota del SHA                              → BLOCKED_EXTERNAL, no aplica a STEP-CERT
[ ] kill alrededor del COMMIT                      → S7 / 0.49 por decisión de diseño
[ ] madurez del contrato publicado                 → P3-E E5
```