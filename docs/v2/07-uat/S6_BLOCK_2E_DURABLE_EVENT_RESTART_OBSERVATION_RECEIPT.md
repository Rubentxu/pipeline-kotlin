# S6 BLOCK 2e — Evento durable, muerte del escritor y observación externa

Rama `s6-plugin-sdk`. Base: `5dc2e487` (`feat(sdk): la admision en dos pasada, y lo que solo se ve al cerrarla`).

BLOCK 2 pidió una prueba de producto sobre la distribución instalada, y `5dc2e487` entregó la
primera mitad de la cadena:

```text
manifest
→ pre-load admission
→ contributor composition
→ frozen registry
→ execute
```

Este documento cierra la segunda mitad:

```text
execute
→ durable plugin event
→ restart
→ external observation
```

**No se promete Reactor.** La observación externa es un proceso distinto leyendo el almacén durable
a través del subcomando `events`; no hay bus de eventos, ni suscriptor remoto, ni entrega exactly-once.

---

## 1. El hueco que existía: dos verdes que nunca se tocaron

Antes de este bloque la cadena estaba repartida en dos UAT que no se cruzaban:

| UAT | Probaba | En qué proceso |
|---|---|---|
| `PluginAdmissionInstalledDistributionUatTest` | admisión rechaza artefactos sin manifest y con manifest que miente | proceso instalado |
| `P3DPluginEventInstalledDistributionUatTest` | un plugin externo emite un evento que sobrevive al proceso | proceso instalado |

Las dos verdes. Y, sin embargo, **la cadena completa no estaba probada**, porque ninguna de las
dos afirmaba la unión:

> Nada demostraba que el evento durable procediera de una composición **admitida**.

Una implementación puede emitir perfectamente y haber esquivado la puerta. El evento es la prueba
de que un plugin funcionó; no es la prueba de que se le admitiera antes de ejecutar su código. Son dos
propiedades distintas, y sólo la segunda es la que S6 existe para garantizar.

### La aserción que cierra el hueco

El informe de composición (`PreResolvedComposition.reportTo`) es la única pieza del producto que
conecta las dos mitades, y lo hace por construcción, no por convención:

```kotlin
// MainRuntimeSupport.admitThenComposeOrExit
if (outcome.admitted.isNotEmpty()) {
    composition.reportTo { line -> System.err.println(line) }
}
```

Tres propiedades encadenadas, todas verificables leyendo el código de producción:

1. **Sólo se escribe si hubo artefacto admitido** — el guard es `admitted.isNotEmpty()`.
2. **Los kinds que imprime salen del `EventRegistry` congelado**, que la pasada 2 construyó
   cargando `ServiceLoader` **exclusivamente sobre artefactos admitidos**.
3. **Ocurre en el mismo proceso y el mismo intento que emite**, porque
   `admitThenComposeOrExit` es la única función de composición y ambos puntos de `Main.kt`
   (`Main.kt:351` rama en memoria, `Main.kt:757` rama durable) pasan por ella.

Por tanto, que el kind **de este plugin** aparezca en ese informe es la admisión decidiéndose, en la
corrida que emitió. Fila `1b` de la UAT:

```kotlin
val eventLine = eventCompositionLine(run1.stderr)
assertTrue(
    eventLine != null && eventLine.contains(PLUGIN_EVENT_KIND),
    "the run's own composition report must name $PLUGIN_EVENT_KIND: that report is emitted " +
        "only after pass 1 admitted an artifact and pass 2 froze a registry from admitted " +
        "artifacts, so it is the observable proof that this event came out of an ADMITTED " +
        "composition rather than an ungated one. Report line was: $eventLine",
)
```

El prefijo que se busca (`"Discovered external event definitions:"`) está **copiado** del informe
de producción, no inventado: un renombrado aguas arriba rompe esta fila en lugar de volverla una
aserción que ya no casa con nada.

---

## 2. HALLAZGO: el último harness de la cadena seguía con el patrón de deadlock

`P3DPluginEventInstalledDistributionUatTest` era el **único** fichero de toda la cadena del evento
de plugin que aún lanzaba la distribución instalada por su cuenta:

```kotlin
val proc = ProcessBuilder(binary.absolutePath, *args).start()
val finished = proc.waitFor(5, TimeUnit.MINUTES)
return CliResult(
    proc.exitValue(),
    proc.inputStream.bufferedReader().readText(),   // <- después de esperar
    proc.errorStream.bufferedReader().readText(),
)
```

Es exactamente la forma que S6-PRE investigó y eliminó, y por eso el fichero vivía en
`KNOWN_DEBT`. No era hipotético aquí: `Main.kt:431` imprime **todo** el log de eventos de la
corrida en un solo `println`, así que cualquier pipeline no trivial puede rebasar los 64 KiB del
pipe; un harness que espera antes de drenar se bloquea para siempre, y cuando `@Timeout` cortaba
el test el JVM `pipelinek` hijo quedaba vivo degradando todas las medidas posteriores.

Migrado a `OwnedSubprocess`, igual que los otros siete puntos de llamada en su momento.

### El token prohibido en el propio comentario

El primer KDoc que escribí para explicar la migración contenía el token literal del lanzamiento
(`ProcessBuilder(`). La ley de fitness **escanea los fuentes como texto crudo**, de modo que
documentar la forma antigua con su ortografía literal mantenía a este fichero en la lista de
exenciones y hacía la migración inverificable desde la propia ley. La misma trampa que ya había
mordido antes con un KDoc de producción. El comentario ahora explica el cambio sin deletrear el
token, y dice por qué.

---

## 3. No-vacuidad por mutación

La fila nueva tiene que poder ponerse roja; si no, es una aserción decorativa.

**Mutante:** invertir el guard del informe de composición en `MainRuntimeSupport.kt`:

```kotlin
-                if (outcome.admitted.isNotEmpty()) {
+                if (outcome.admitted.isEmpty()) {
                     composition.reportTo { line -> System.err.println(line) }
                 }
```

Un solo token, sin tocar la firma ni el tipo, y con semántica clara: el informe pasa a imprimirse
sólo cuando **nada** fue admitido, es decir, nunca en esta corrida.

### Resultado

| | control | mutante |
|---|---|---|
| `BUILD` | SUCCESSFUL | **FAILED** |
| tests | 2 | 2 |
| failures | 0 | **1** |
| fila muerta | — | `an external plugin emits its own event and it is readable after the process is gone` |
| fila intacta | — | `an unknown kind is refused by name and writes nothing to the real store` |

Mensaje de fallo, íntegro:

```
the run's own composition report must name example.uppercase.applied: that report is emitted
only after pass 1 admitted an artifact and pass 2 froze a registry from admitted artifacts,
so it is the observable proof that this event came out of an ADMITTED composition rather than
an ungated one. Report line was: null ==> expected: <true> but was: <false>
```

`Report line was: null` es el dato importante: el mutante no produjo un informe distinto, sino
**ningún** informe, que es exactamente el mundo que la aserción afirma que no puede ocurrir.

Atribución 1:1 — una fila muere, la hermana negativa sobrevive. Nada más se movió.

### Restauración verificada

```
antes: 60d5acad00915bbeb61bdd45897cb05feab07a392885c8718560c21991b44204
ahora: 60d5acad00915bbeb61bdd45897cb05feab07a392885c8718560c21991b44204
```

Hash idéntico. El árbol tras la mutación sólo difería en los dos ficheros de test; **código de
producción sin tocar**.

### Una corrección de método

La primera atribución del fallo la hice con un regex sobre el XML de JUnit y **asignó el fallo a la
fila equivocada**. Rehecha con un parser XML (`xml.etree.ElementTree`), la atribución correcta es la
de la tabla: muere la fila que lleva la aserción nueva. Se deja constancia porque un recuento o una
atribución sacada de un regex flojo sobre XML es exactamente la clase de error que produce una
evidencia falsa sin que nadie lo note.

---

## 4. La deuda del fitness baja de 28 a 27

`InstalledDistributionHarnessFitnessTest` mantiene una lista explícita de harnesses que aún lanzan
la distribución instalada por su cuenta, y exige dos cosas: que el conjunto de infractores **nuevos**
sea vacío, y que las entradas de la lista que ya no correspondan a ningún infractor se **borren**
(`stale.isEmpty()`). La segunda mitad es la que hace la deuda decreciente: migrar un fichero sin
quitarlo de la lista deja el test rojo.

```
KNOWN_DEBT: 28 -> 27   (P3DPluginEventInstalledDistributionUatTest migrado en este bloque)
```

Los tres contadores en prosa del propio fichero (KDoc de clase, la serie `32 -> 29 -> 28` y el
comentario de `KNOWN_DEBT`) se actualizaron a 27 en el mismo bloque: un contador que miente en su
propio comentario es deuda documental, y este repositorio ya ha pagado por las que se dejaron escrito
lo que el código dejó de hacer.

---

## 5. Lo que este bloque NO demuestra

Se deja escrito para que nadie lo lea como más de lo que es.

- **No hay Reactor, ni bus, ni suscriptor remoto.** La "observación externa" es un proceso distinto
  (`pipelinek events`) leyendo el mismo almacén SQLite. Es observación desde otro proceso, no
  distribución de eventos.
- **No se afirma nada sobre re-ejecución ni reanudación.** La prueba es: el escritor muere, el
  estado durable sobrevive, otro proceso lo lee sin volver a ejecutar. Si `restart` se entendiera
  como "reanudar la ejecución de un run interrumpido a mitad", eso es otra capacidad y no es lo que
  esta UAT mide.
- **La congelación del registro se prueba por construcción, no por aserción CLI.** Que el registro
  sea inmutable tras `build()` es una propiedad de la construcción de los registries (S6/F) y
  queda cubierta por las pruebas de registry. No se ha inventado una aserción de "congelado" sobre
  la CLI porque no es observable desde fuera sin un artificio.
- **El proceso observador es más tonto de lo que la cadena necesita, y eso es una propiedad a
  favor.** `pipelinek events` se despacha en `Main.kt:122`, **antes** de `CliParser.parse`
  (`Main.kt:153`), de modo que retorna con `System.exit` antes de que exista composición alguna.
  `MainEventsCli` abre `SqliteEventStore` y un `EventHistoryReader` y nada más: no admite, no
  compone, no carga bytecode del plugin.

  Es decir, la observación externa no la hace un lector que admita el plugin y lea su propio
  evento — la hace un proceso que **nunca cargo el plugin** y aun así reconstruye su evento desde el
  estado durable. Esa es la forma fuerte de la afirmación: el contrato del evento sobrevive sin
  necesidad del artefacto que lo produjo.

  (Medido leyendo el orden de despacho y los imports del CLI, no inferido.)
- **`PRODUCT-GATE` sigue `BLOCKED_EXTERNAL`.** Sin CI remota desde `754ddda0`, este bloque es
  `STEP-CERT`: el SHA exacto queda demostrado por la escalera local, no por un pipeline remoto.

---

## 6. Ficheros

| Fichero | Cambio |
|---|---|
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/P3DPluginEventInstalledDistributionUatTest.kt` | migración a `OwnedSubprocess`; fila `1b` de admisión→composición→evento; `CliResult` eliminado |
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/InstalledDistributionHarnessFitnessTest.kt` | `KNOWN_DEBT` 28 → 27; contadores en prosa actualizados |

Ningún fichero de producción modificado.

---

## 7. Evidencia ejecutada

Gate prescrito, sobre el árbol de este bloque:

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

BUILD SUCCESSFUL in 31m 38s
320 actionable tasks: 320 executed
```

`320 executed` y no una sola `up-to-date`: es una corrida completa, no un resumen de cachés.

Recuento desde los XML de `test-results`, acotados por la marca de arranque de esta corrida
(`mtime`), no desde totales acumulados:

```text
779 clases | 5156 tests | 0 fallos | 0 errores | 140 skips
```

Idéntico al gate de `5dc2e487` (5156). Correcto y esperado: este bloque **no añade ni quita
tests**, migra y amplía dos existentes, luego el delta debe ser cero. Un delta distinto habría
significado que se coló o se perdió algo.

Cero procesos `pipelinek` supervivientes tras el gate. Los que aparecen en `pgrep` pertenecen a
`PipelinekFabricBackStage`, un proyecto ajeno; no son de esta distribución.

### Re-ejecución de la ley de fitness sobre el árbol final

Durante la corrida del gate se corrigieron los tres contadores en prosa del fichero de fitness
(28 -> 27), y esa corrección se hizo **mientras `compileTestKotlin` ya se había ejecutado**. Un
cambio de comentario no cambia el bytecode, pero decirlo es una aserción mía, no una medición, así
que se comprobó en vez de argumentarse:

```text
./gradlew :pipeline-application:test --tests '*InstalledDistributionHarnessFitnessTest'

> Task :pipeline-application:testClasses UP-TO-DATE
> Task :pipeline-application:test
BUILD SUCCESSFUL in 25s

suite: S6-PRE — todo harness de la distribucion instalada pasa por OwnedSubprocess
tests=1 failures=0 errors=0 skipped=0
```

`testClasses UP-TO-DATE` es la prueba: que `compileTestKotlin` no se haya vuelto a lanzar en esta
re-ejecución significa que el gate anterior **sí** compiló la versión final de ese fichero.
Con `KNOWN_DEBT` en 27, las dos mitades de la ley quedan verdes a la vez: `unexpected` vacío
(ningún infractor nuevo) y `stale` vacío (ninguna entrada obsoleta).