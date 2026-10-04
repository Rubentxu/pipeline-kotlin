# B2 — Output Plane: retención, liberación y un diagnóstico que hubo que corregir

**WorkItem:** `f24f3ac0-b889-407c-9e46-f5e524120818`
**Base:** `051fb610` (B2b)
**Rama:** `s4-a1b-scripted-shell-spine`
**Bloque:** B2 del mandate del owner — «retención/pruning con ownership explícito + crash/halt con MUERTE REAL»

---

## 0. El hallazgo que reencuadró el bloque

Este bloque empezó con un diagnóstico mío que **era falso**, y la medición lo desmontó. Se
conserva aquí entero porque el valor de un recibo no es sólo lo que queda en pie, sino que un
lector pueda ver qué se creyó, con qué evidencia se tambaleó, y qué la refutó.

### Lo que afirmé

> «I1, I2 e I6 están escritos y sin certificar. Los 9 tests de `OutputPlaneConformanceTest` hacen
> `recover()` dentro del mismo JVM, que no es un crash. El hueco es el writer con bytes en su
> buffer de usuario al morir, que un lector no debe ver.»

### Por qué era falso

`SegmentOutputStore` escribe con:

```kotlin
Files.write(layout.segmentFile, bytes, CREATE, WRITE, APPEND)
```

`Files.write` **abre, escribe y cierra** el canal en cada llamada. No hay `FileOutputStream`, ni
`BufferedWriter`, ni ningún buffer de usuario que sobreviva entre llamadas. Los bytes no
reconocidos no están en un buffer: están **en `cur.seg` por encima del committed offset**, que es
justo lo que `reconcile()` trunca:

```kotlin
val readableEnd = currentBase + onDisk
if (readableEnd > committed) {
    truncateTo(layout.segmentFile, onDisk - (readableEnd - committed))
    return readableEnd - committed
}
```

I2 e I4 estaban cubiertos, y la decisión de M1 de no forkear —documentada en su propio KDoc como *«recovery
reads durable state, so the state is what has to be reproduced»*— es **sólida**. No se toca.

### Lo que sí quedaba, y era real

Leyendo `OutputAdoption.I6` con el witness literal —*«kill between reserve and write»*— frente al
helper `crashAfterWriting` de M1, que muere entre *write* y *commit*: eran dos estados durables
distintos, y sólo uno estaba cubierto. **Una reserva declarada que muere antes de escribir un solo
byte** no tenía cobertura.

Y al perseguirlo apareció un **defecto de producción** (§4).

---

## 1. B2a — el contrato del Output Plane para un paso sin salida

Medido sobre la distribución instalada, no supuesto:

| qué hizo el hijo | qué guarda el plano | qué responde el lector |
|---|---|---|
| escribió salida | los bytes | la página |
| no escribió nada (`sh("true")`, capture-mode con stderr mudo) | **no hay stream** | `console-refused: unknown-stream`, exit 1 |
| el reader apunta a otro stream | no hay stream | el mismo refusal |

**El plano contiene un stream si y sólo si el proceso escribió bytes de transcript.** El motivo
está en el código: `consoleSource` resuelve a `null` cuando no hay nada que transmitir, e
`ingestTranscriptIntoOutputPlane` retorna en vez de inventar un stream.

Eso convierte un refusal en una respuesta de **tres** salidas, y la regla del helper era de dos.
`transcript()` lanza ante cualquier refusal; `transcriptOrAbsent()` responde `null` sólo ante
`UnknownStream`, y sólo para el caller que tiene el evento que separa «el paso no escribió» de
«el reader está mal apuntado». Ninguno devuelve `""` para decir *no lo sé*.

Consecuencia sobre una fila que estaba verde por la razón equivocada: `C4-empty-stderr` afirmaba
que un capture-mode sin stderr no produce evento de consola, lo cual es cierto porque ese canal ya
no lleva nada. Ahora afirma algo falsable —**que el transcript no se materializa a partir del valor
tipado**— y la mutación que inyecta el valor tipado en el plano voltea exactamente las dos filas
C4 y ninguna otra.

## 2. B2b — quién puede borrar

El store podía borrar un directorio de stream y nada decía quién tenía derecho a pedirlo. Dos
respuestas, ninguna incorrecta: guardarlo todo para siempre (una fuga) o dejar que cualquiera con
un stream id lo borre (una autoridad de borrado sin motivo, que no se revisa, no se reproduce y no
se distingue de un fallo).

Tres vocabularios cerrados en vez de un flag:

- `RunLifecycle` — qué pasó con el run. Lo responde el runtime, que lo ejecutó.
- `RetainUntil` — cuándo puede irse su output. Se declara una vez.
- `OutputPruneIntent` — si **este** borrado procede y por qué.

`SegmentOutputStore` **puede borrar y no puede decidir**: nunca se entera de si un run sigue vivo, y
en cuanto lo supusiera sería una segunda autoridad sobre el ciclo de vida de un run. Por eso un
borrado nombra su motivo en el tipo: `OutputPruneIntent` no tiene caso para un run en curso, ni
para «limpia la salida antigua». **Borrar la salida de un run vivo no es un error que esta API
pueda expresar**, con o sin `force = true`.

El journal no recibe nada de esto. Puede referenciar output —es como el lector de consola resuelve
un stream a partir de un run y una operación— pero no tiene ningún método que borre. Un componente
que sólo puede citar output no puede podarlo.

### La decisión que costó encontrar

El listado de streams de un run **no se expone**, porque expondría ids y no hay forma honesta de
reconstruirlos. `safe()` pliega `/` sobre `_`:

```kotlin
fun safe(name: String): String = name.replace(Regex("[^A-Za-z0-9._-]"), "_")
```

Invertir ese mapeo para volver a `OutputStreamId` no es una inversión: un id con `_` legítimo se
perdería, y el reader acabaría leyendo otro stream creyendo que es el suyo. Deshacer la colisión
exigía cambiar el layout en disco, que es un cambio de formato durable y por tanto **STOP (1)** —y
no de este bloque. Así que el prefijo de run es un **filtro, nunca un parseo**, y quien necesite
ids los construye, que ya conoce el run y las operaciones. Lo que sí se expone es
`hasOutputFor`, que responde una pregunta real con sí y no reales.

La cuenta de bytes del informe de poda sale del **committed offset del store**, no de `Files.size`:
un segmento puede estar sellado, y el tamaño de un fichero no es lo que se prometió a un lector.

## 3. B2c — la mitad de I6 que nadie cubría, y un defecto

`I6_NO_AMBIGUOUS_SLOT` dice *«kill between reserve and write»*. El helper de M1
`crashAfterWriting` muere entre *write* y *commit*. Son estados durables distintos y sólo uno
estaba cubierto. Tres filas nuevas cubren la mitad que faltaba:

1. una reserva abandonada antes de escribir un solo byte deja el stream intacto y lo ya comprometido
   sigue legible;
2. liberar una reserva vacía devuelve el base comprometido, y reutilizar ese rango deja el stream
   **denso** — sin hole y sin solape;
3. abandonar una reserva sobre un stream que nunca escribió no es un error, y el stream sigue
   usándose después.

### El defecto

`truncateTo` era:

```kotlin
FileChannel.open(file, StandardOpenOption.WRITE).use { it.truncate(size) }
```

Dos fallos, ambos alcanzables desde `OutputReservation.abandon` en un camino ordinario:

1. **`NoSuchFileException`.** `FileChannel.open(..., WRITE)` sin `CREATE` lanza si el fichero no
   existe, y un stream sin nada comprometido no tiene `cur.seg`. Un *release* que lanza es el peor
   fallo posible en ese método: el llamador **no puede liberar el rango que intenta liberar**, así
   que la reserva queda pendiente hasta que un recovery tiene que rescatarla.
2. **Extensión con ceros.** `FileChannel.truncate` *extiende* un fichero, rellenándolo de NUL. Un
   release que creciera el segmento dejaría un hole de ceros dentro del stream — exactamente el
   hueco permanente que `I3_ORDER_IS_DENSE` prohíbe, y que ningún reader podría distinguir de
   output real.

Arreglo: truncar es ahora total en las dos direcciones. Si el fichero no existe, no hay nada que
truncar. Si el tamaño pedido no es menor que el actual, no se toca. Y una aserción sobre el
comportamiento discovered algo más, que quedó escrito: una reserva-then-release deja un stream
**conocido y vacío** (extent 0), no un stream desconocido — y esa diferencia es la que permite a una
consola decir «este paso no produjo salida» en vez de no saber nada.

### Dos de mis aserciones estaban mal antes que el store

La primera versión de la fila de reutilización afirmaba que tras liberar y reescribir el stream
seguía teniendo un solo bloque. Falló, y al leer la traza estaba claro que **el test era el
equivocado**: reutilizar una reserva reserva 24 bytes y escribe un segundo bloque, así que
«primero + primero» era la respuesta correcta. La ley es densidad, no «se parece a como estaba»,
y la aserción se reescribió sobre el extent y sobre las dos mitades. La segunda afirmaba que un
stream sólo-reservado daría `committedExtent == null` cuando lo correcto es `0`, porque `reserve`
ya creó su directorio. Dos correcciones propias antes de que el store fuera el sospechoso, y
quedan en el historial del bloque porque son el tipo de error que un recibo debe hacer visible.

## 4. Veredicto del gate

```text
cd v2 && ./gradlew :pipeline-output:test :pipeline-application:test \
  :pipeline-architecture-tests:test :pipeline-domain:apiCheck detekt \
  -PexcludeSlowTests=true --rerun-tasks
```

| | |
|---|---|
| `BUILD SUCCESSFUL in 19m 28s` | 114 tareas ejecutadas, **EXIT=0** |
| errores de compilación `^e:` | **0** |
| `FAILED` | **0** |

| módulo | clases | tests | F | E | skipped |
|---|---|---|---|---|---|
| `pipeline-application` | 301 | 2240 | 0 | 0 | 120 |
| `pipeline-architecture-tests` | 90 | 442 | 0 | 0 | 10 |
| `pipeline-output` | 3 | 46 | 0 | 0 | 0 |
| **total** | **394** | **2728** | **0** | **0** | **130** |

Freshness comprobada: los XML más recientes de los tres módulos son de 17:50, 17:34 y 17:31, todas
de esta pasada. `pipeline-output` sube de 34 a 46 tests: las 9 de `OutputRetentionTest` y las 3 de
la mitad de I6.

Una nota sobre el tiempo: esta pasada tardó lo mismo que la de B1 (19 min) pero durante ella un
worker de Gradle de **otro repositorio** (`PipelinekFabric`) competía por CPU. No costó resultado
—`FAILED=0` en todo momento— y queda escrito porque un gate que se alarga sin fallar se parece
mucho a un gate colgado, y la diferencia es saber dónde mirar antes de parar nada.

### Detekt pagó por primera vez

La primera pasada de este bloque falló con `^e: 1`:

```text
SegmentOutputStoreTest.kt:33:17 firstBlock is returning a constant.
Prefer declaring a constant instead. [FunctionOnlyReturningConstant]
```

Y en la corrección, `VariableNaming` pidió `lowerCamelCase` para el `val` privado. Son dos reglas
que el cableado de `check` hecho en B1 составляет por fin. Ninguna era un defecto de producto;
ambas eran mías, y ninguna habría llegado al gate si detekt hubiera seguido siendo el comentario
de un workflow borrado.


## 5. Mutaciones de B2

| # | mutación | RED atribuidos |
|---|---|---|
| M3 | inyectar el valor tipado de `sh` en el Output Plane | 2 de 17 — exactamente las dos filas C4, y ninguna otra |
| M4 | `prune` sin ámbito de run (limpia todo el plano) | 1 de 9 — exactamente el aislamiento entre runs |
| M5 | `RetainUntil.Forever` autorizando como la política por defecto | 1 de 9 — exactamente la fila que exige que nunca autorice |
| M6 | `truncateTo` sin guard de existencia ni de tamaño | 1 de 46 — exactamente la fila del stream que nunca escribió |

Todas restauradas con `sha256sum -c` y verificadas en verde después.

## 6. B2d — auditoría de las cuatro clases sospechadas

Resultado **negativo**, que es un resultado: ninguna era una trampa.

| clase | uso de `EchoOutputCaptured` | veredicto |
|---|---|---|
| `UatLocal008CredentialsTest` | uno, sobre `echo("LINE1 normal")` / `echo("LINE2 also-normal")` | legítima — `core.echo` es semántico y su texto sí es un hecho del run. Los 24 `sh(` del fichero están en otros tests que no leen eventos |
| `UatLocal005RegressionGateTest` | `RG-003` exige presencia, sobre `echo("RG-003-OK")` | legítima por el mismo motivo |
| `WULpr010CliCharacterizationTest` | cuenta 1 `EchoOutputCaptured` en un resume | legítima — es el `echo` journalizado, y la fila mide que un resume **no** re-ejecuta al hijo |
| `UatLocal010SmokeE2ESandboxTest` | **sólo el import** | import muerto, eliminado |

Ese import muerto era deuda de verdad y no cosmética: sugiere que la clase observa output de
proceso por el canal de eventos, cuando no lo hace, y un lector futuro podría añadir ahí un test
creyendo que el canal funciona. Detekt no lo señala —`UnusedImports` no está en la configuración
activa— así que queda como deuda que el gate no ve.

## 7. Lo que B2 deja abierto

- **El fsync sigue sin hacerse**, y eso es una limitación declarada, no un hueco:
  `OutputNotEstablished.POWER_LOSS_DURABILITY` dice que la propiedad probada es *«un proceso que
  muere no pierde nada que reconoció»*, y no la que la mayoría entendería por durable. B2 no la
  amplía y ningún artefacto puede reclamarla sin una disciplina de fsync y un modelo de fallo que
  corte la luz en vez de matar un proceso.
- **Un stream reservado y liberado queda en disco con extent 0.** Es inocuo y legible como
  «vacío» —y esa legibilidad es justo lo que la nueva fila certifica—, pero un barrido que rebase
  su corte en el extent podría llevarlo o no según la política. B2 no lo borra porque no es basura
  todavía, y decidirlo sin un modelo de retención maduro sería una segunda autoridad.
- **La política `RetainUntil` no está cableada a ningún runtime.** B2b deja la operación y la
  política escritas y probadas; quién las invoca al terminar un run es trabajo de B3, donde el
  cierre de stage ya tiene un punto único donde colgarlo.

