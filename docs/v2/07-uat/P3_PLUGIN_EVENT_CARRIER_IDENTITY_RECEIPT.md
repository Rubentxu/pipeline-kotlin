# P3 — El carrier de evento de plugin tiene identidad y sobrevive al releer

**Item:** `0553d7ef-adec-417c-9f8f-effc754425d0` (Runtime Observation Contract Closure) · Slice P3
**SHA certificado:** `804eb6676da93a401be15375e02b6476440ddb43` (rama `p1-orphan-core-sh`, árbol limpio)
**Gate:** `BUILD SUCCESSFUL in 30m 27s` · `329/329` tareas · `GATE_EXIT=0` · `check --rerun-tasks`
**Log:** `/var/home/rubentxu/.local/state/pipelinek-gates/gate-804eb667.log` (sha256 `4597b396b9addf068fd1082f5bac9a53150a110400aef8e717f334e22f160051`)
**Volumen:** 740 XML · **4925 tests / 0F / 0E / 140S** · 27 módulos
**Fecha:** 2026-10-05

---

## 1. Qué establece este slice

Un evento que un **plugin** contribuye ya no es un texto que el motor hops. Es un carrier cerrado
con identidad propia, que el store le sella su `sequence`, que el log durable serializa, y que —
esto es lo que faltaba — **vuelve a leerse**.

P3 cierra la cadena completa del payload foráneo:

```text
contribuyente declara  →  registro abierto (fail-closed)
                      →  emisión con sequence de la autoridad del store
                      →  serialización durable
                      →  relectura con la MISMA identidad
                      →  re-tipado por el codec del contribuyente
```

Commits: `99c2b8ab` (registro abierto) · `e427adac` (carrier cerrado) · `e6f28427` (consumidor
externo) · `22f23afa` (ABI) · `43a2aec2` (identity en el store) · `804eb667` (supervivencia al releer).

## 2. Evidencia sobre `804eb667`

### 2.1 Gate completo

`check --rerun-tasks` sobre árbol limpio: **329/329 tareas**, `GATE_EXIT=0`, 30m27s.
740 XML · 4925 tests · 0F · 0E · 140S · 27 módulos con resultados.

Delta contra el gate de P2 (4906 tests, `5ea4137d`): **+19 tests**, atribuidos exactamente y sin
resto — 13 en `EventRegistryTest` (slice 1), 4 en `RegistryEventEmitterTest` (slice 2), 2 en
`JsonEventLogRoundTripTest` (este commit). Los 140 skips no se mueven.

### 2.2 Fila de dinero

Round-trip del carrier (`JsonEventLogRoundTripTest`): sobrevive el write→read con
`registryKind="acme.validated"`, `schemaVersion=2`, `payload` intacto, `emittedBy` intacto y
`sequence=4` **sin reasignar**. Un carrier sin `registryKind` decodifica a `null` y la lista
queda vacía.

Cada aserción tiene su mutación que la mata: quitar la rama de `decodeEvent` deja `decoded` vacío
y `assertEquals(1, decoded.size)` falla; defaultar `registryKind` a `""` hace fallar el caso
negativo. La rama de decodeEvent **no** puede degradar a un carrier indistinguible.

## 3. Lo que el gate encontró: dos reds, y sólo uno era mío

Este slice llegó al STEP-CERT por un camino que no era el previsto, y el desvío es la parte
informativa del recibo.

### 3.1 Gate sobre `43a2aec2` — NO verde (1 fallo)

`BUILD FAILED in 27m 56s` · 318 tareas · `:pipeline-application:test` 2350 tests / 1 failed /
121 skipped · `ADV-007 large changelog completes within timeout` con `TimeoutException`.

**Diagnóstico: el producto estaba bien y el test medía la máquina.** El fixture de ADV-007 son 500
spawns secuenciales de `git`, que en una caja descargada y sin contención cuestan **53.3s**
(medido; XML sha256 `13555c7163a95cf90d54502777cd03087da0c6a838245c07ed249ca316a59dc4`), y el
`@Timeout(60)` era **de clase**: el presupuesto entero se lo gastaba el fixture antes de llamar al
producto. Bajo la carga paralela del gate la línea murió sin haber ejercitado el executor (XML
sha256 `5c579e5b942c0e98092ea6fc10abeb7191c278b3b5b3e9caa7626e6834d5cfa9`). Encima el test
afirmaba `elapsedMs < 60_000`, una cifra de rendimiento que el executor nunca prometió y que
duplicaba las dos aserciones discretas de debajo.

**Reparación (`19849e80`):** la aserción de duración se **elimina**, no se relaja. Relajarla
habría dejado el claim y la dependencia intactos, que es la forma lavada del mismo defecto. "No
se cuelga" sobrevive como guard de vivacidad de 10 minutos que un deadlock real o un OOM siguen
disparando; "sabe manejar 500 commits" lo demuestran el resultado correcto y el changelog con sus
entradas. HF3: una duración es una propiedad de la máquina, y un rojo correcto que se lee como
defecto del producto es el peor desenlace posible.

**No era una regresión:** `WU_RP_002_2` y `WU_RP_002_3` ya registran este test como FAILED. Flake
crónico. Lo verificable es que la reparación **aguantó la carga real del gate**:
`:pipeline-application:test` pasó completo (2350 tests) en el gate sobre `19849e80`. Eso lo
convierte de suposición en hecho probado bajo concurrencia.

### 3.2 Gate sobre `19849e80` — NO verde (3 fallos), y aquí sí era mío

`BUILD FAILED in 30m 35s` · 325 tareas · `:pipeline-architecture-tests:test` 466 tests / 3 failed /
10 skipped.

| Fitness | Por qué estaba rojo | Qué era |
|---|---|---|
| `FArchL7DomainEventExhaustivityTest > ..._70_variants` | pin de exhaustividad en 70 con 71 variantes | el pin olvidado por quinta vez |
| `Rp030 > every variant declares a unique kind literal` | F1 escaneaba sólo `DomainEvent.kt` | el alcance de la guarda, menor que lo que guarda |
| `Rp030 > decodeEvent has a branch for every variant kind` | `decodeEvent` sin rama para el carrier | **pérdida silenciosa en replay** |

El tercero es el que importa: **`JsonEventLog.decodeEvent` no tenía rama para
`PluginEventEmitted`.** El carrier se escribía y se perdía al releer — el defecto exacto que F2
existe para cazar.

**Por qué apareció ahora.** La sesión anterior certified P3 slice 2 con
`--tests` sobre `:pipeline-events-store:test`. `pipeline-architecture-tests` es un **proyecto
Gradle independiente**, al que ningún filtro por módulo llega. El escalón de fitness no es
opcional en la escalera, y la regla operativa que este slice deja escrita es: **el módulo que
tocas Y el módulo de fitness**, no "el módulo que tocas". Un `UP-TO-DATE` no habría不法 dado
ningún aviso.

**La asimetría deliberada de la rama de decode.** `registryKind` y `schemaVersion` **no**
defaultan, al contrario que `reason` o `path` en las ramas vecinas. Un `StageSkipped` sin
`reason` sigue siendo el `StageSkipped` que ocurrió. Un carrier sin `registryKind` no es uno
degradado: es **inidentificable**, y cualquier valor sustituto re-tiparía ese payload como el
evento de **otro** plugin. Una observación errada es peor que una perdida, así que la línea
malformada decodifica a `null` —el mismo trato que una línea no clasificable— y el registry
vuelve a rechazarla en el lado de lectura. Dos veces fail-closed ganan a una fabricación segura.

**El alcance de F1.** `PluginEventEmitted` vive en su propio fichero, que es la forma mejor
(`DomainEvent.kt` ya es un punto de concentración de ~46 KB). F1 escaneaba un solo fichero, lo
cual encodeaba una suposición que había dejado de ser cierta: que toda variante se declara ahí.
El check dejó de cubrir una variante **en silencio**. Escanear la raíz del paquete de eventos
restaura la intención y es estrictamente más fuerte, porque ahora una literal de `kind` duplicada
en un segundo fichero también se pilla.

## 4. Lo que este recibo NO dice

- **No certifica el PRODUCT-GATE.** Sin CI remota desde `754ddda0`, este es un STEP-CERT del
  productor. La certificación y promoción de la candidata pertenecen al release-harness.
- **El consumidor externo NO está dentro de `check`.** Los 2 tests de
  `examples/fabric-contract-consumer` viven en un build Gradle independiente con su propio
  `settings.gradle.kts` y sin `project(...)`: es la frontera externa por diseño, y por eso sus
  tests **no** forman parte de las 4925 de este gate. Se ejecutan aparte, contra artefactos
  publicados.
- **Un plugin todavía no puede contribuir un evento en una ejecución real.** El registro, el
  carrier, la persistencia, la relectura y el contrato externo existen y están probados, pero
  **no existe `EventDefinitionContributor`, ni descubrimiento `ServiceLoader` para eventos, ni
  cableado de `RegistryEventEmitter` en producción**. `RegistryEventEmitter` no se construye en
  ningún `src/main`. Esto es S6.4 y es el siguiente slice, no una bragging right.
- **Las mutaciones que matan las aserciones están probadas por construcción, no ejecutadas como
  mutación real.** Quitar la rama y defaultar el kind son argumentos, no un run de mutation
  testing.
- **No hay producción de candidatos ni handoff en este slice.** No se materializó
  `v0.47.0-rc3` ni se abrió issue en el harness.
