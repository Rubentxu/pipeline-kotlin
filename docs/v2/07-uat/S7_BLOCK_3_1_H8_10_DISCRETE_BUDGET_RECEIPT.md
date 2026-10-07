# S7 BLOCK 3.1 — H8-10 deja de ser una aserción de tamaño y pasa a ser un presupuesto

Rama `s6-plugin-sdk`. Base: `b01b8d37` (S6 BLOCK 2e).

Decisión del usuario, registrada antes de tocar código:

> Reformularla como observación discreta, sin umbral. El umbral no se vuelve a subir.

La fila era `ratio(crecimiento de peak RSS / crecimiento del cuerpo) < 0.5`. Es una aserción de
**tamaño**, prohibida por Harness Fidelity §3, y fallaba bajo carga a 0.65 habiendo pasado a 0.19:
medía la máquina, no el suscriptor. Subir el umbral no habría hecho otra cosa que subir la banda de
ruido de la caja, así que el ratio se **retira** en lugar de reajustarse.

---

## 1. La sustituta: presupuesto declarado + observación discreta

```text
presupuesto = 256 MiB de heap para el proceso ENTERO, impuesto por el launcher
cuerpo      = 512 MiB, que no cabe ni una vez en ese presupuesto

observaciones:
  exit code == 0
  server.countOf("/large") == 1
```

La afirmación deja de ser "la memoria creció despacio" y pasa a ser **"un cuerpo dos veces su propio
presupuesto de heap completa la corrida"**. Un suscriptor que materializara el cuerpo tendría que
guardar 512 MiB en un heap de 256 MiB; el suscriptor acotado (`BoundedBodySubscriber`) conserva un
prefijo de 1 MiB y digiere el resto, así que no puede y no lo hace.

Ambas observaciones son discretas. Ninguna lee duración, ratio ni magnitud, de modo que la fila ya
no puede ser movida por la carga de la máquina. El peak RSS se sigue recogiendo e imprimiendo,
porque interesa, pero es **caracterización** y deja de ser lo que la fila sostiene.

### El presupuesto se eligió medido, no supuesto

Con el vector de entorno correcto, el binario instalado compila y corre un pipeline trivial con
`-Xmx128m`; 256 MiB deja margen para el cuerpo de la fila sin ahogar al compilador de scripts, que
comparte JVM con el suscriptor.

---

## 2. HALLAZGO: el presupuesto que nadie aplicaba

La primera versión de la fila fijaba el techo con **`DEFAULT_JVM_OPTS`**. No hacía nada.

El start script generado por Gradle hace, en su propio cuerpo:

```sh
DEFAULT_JVM_OPTS=""
```

Una asignación planos en el script **pisa** el valor heredado del entorno antes de que nadie lo lea.
La variable existe, se documenta en el propio launcher como heredable, y no sirve para nada desde
fuera. Medido:

| variable | `-Xmx8m` sobre un pipeline trivial |
|---|---|
| `DEFAULT_JVM_OPTS` | **exit 0** — el tope no se puso |
| `PIPELINEK_OPTS` | **exit 1** — `OutOfMemoryError: Java heap space` |
| `JAVA_OPTS` | **exit 1** — `OutOfMemoryError: Java heap space` |

Un pipeline trivial **no puede** compilar en 8 MiB, así que el `exit 0` de la primera fila no es "sobró
memoria": es que el tope no existía.

Esto es la misma clase de fallo que `PIPELINEK_SPIKE_HOME` — configuración decorativa que parece
evidencia porque se puede escribir — y habría dejado la fila entera certificando un presupuesto que
el proceso nunca tuvo. Se usa `PIPELINEK_OPTS`, que es la variable propiedad del producto y la que el
launcher anexa de verdad.

### La fila comprueba su propio presupuesto

Un presupuesto que nadie aplica hace vacuas todas las aserciones que dependen de él. La fila lo
verifica sobre la línea de órdenes real del hijo:

```kotlin
assertTrue(
    observedCmdline.contains(budget),
    "the child must actually run under the budget this row claims; $budget did not appear " +
        "in any command line of its process tree. A budget that is set but not applied " +
        "makes every assertion below vacuous. Observed: $observedCmdline",
)
```

Y esa comprobación encontró un segundo error en el mismo día: la primera versión leía
`/proc/<pid>/cmdline` del pid que devuelve `ProcessBuilder`, que es el **`/bin/sh` del launcher, no
la JVM**. El start script lanza `java` como hijo en vez de hacer `exec`, así que el flag vive en el
descendiente. La fila ahora recorre el árbol de procesos hasta encontrar el flag. Sin ese recorrido
la autocomprobación habría dado verde sobre un presupuesto que sigue sin verse.

---

## 3. No-vacuidad por mutación

**Mutante:** `BoundedBodySubscriber.appendBoundedPrefix` y `ensureCapacity` convertidos en la forma
pre-H4 — retener el cuerpo **completo** y truncar a la salida, que es lo que hace
`BodySubscribers.ofByteArray` y lo que su propio KDoc nombra como el defecto.

### Resultado

| | control | mutante |
|---|---|---|
| `BUILD` | SUCCESSFUL | **FAILED** |
| tests | 16 | 16 |
| failures | 0 | **1** |
| fila muerta | — | `H8-10 a response twice the heap budget completes…` |

Las otras 15 filas sobreviven. Mutante restaurado con sha256 `6db0a583…` idéntico antes y después.

### El modo de fallo real, que NO es el que se suponía

Con el presupuesto realmente aplicado, el mutante **no** muere con `OutOfMemoryError`. Se queda
**colgado por GC thrashing** hasta el deadline de 5 minutos, y el harness lo mata:

```text
java.lang.IllegalStateException: the installed binary hung on [run, --db, …, --allow-network, …]
after 5 min
```

La primera versión del mensaje de aserción afirmaba que moriría con `OutOfMemoryError`. Eso era una
predicción mía sobre un mecanismo, no algo medido, y la medición la refutó. El mensaje reescrito
afirma sólo lo que es cierto —que el suscriptor necesitaría 512 MiB en un heap de 256 MiB— y deja el
mecanismo fuera.

Queda registrado porque es la característica honesta de esta clase de falla: **un presupuesto de
heap ajustado no falla limpio, satura**. El margen medido entre las dos situaciones es de un orden de
magnitud: la fila sana completa la transferencia en decenas de segundos contra un deadline de 300 s,
mientras que el mutante agota ese deadline entero. La discriminación es robusta en la práctica, pero
es una discriminación por completación, no por clasificación, y así se declara.

---

## 4. La guarda de la propia fila falló el primer día

Antes del mutante, la fila ya se puso roja una vez — contra sí misma. Su guarda de honestidad
comparaba `bodyBytes > 2 * 256 MiB`, es decir `536870912 > 536870912`: exactamente igual, no mayor.
La separación que el comentario afirmaba no era la que el código comprobaba.

Corregido declarando el presupuesto **una vez, en bytes**, y derivando el cuerpo de él:

```kotlin
val budgetBytes = 256L * 1024 * 1024
val budget = "-Xmx256m"
val bodyBytes = 2 * budgetBytes
```

Que la guarda atrapara su propio error de autoría es exactamente para lo que está.

---

## 5. Lo que este bloque NO demuestra

- **No es un budget de rendimiento.** El presupuesto de 256 MiB es el instrumento que hace
  falsable la fila, no un objetivo de producto. No se ha medido ni aprobado ningún presupuesto de
  `startup`, `RSS` ni throughput; eso sigue siendo trabajo de caracterización en B3.
- **El peak RSS sigue siendo caracterización, no evidencia.** Medido: **427614208 bytes (~408 MiB)**
  con un tope de heap de 256 MiB y un cuerpo de 512 MiB. El RSS excede al heap porque incluye
  metaspace, code cache y buffers directos — que es precisamente por qué un techo de RSS mediría el
  launcher en vez del suscriptor.
- **`HttpInstalledUatTest` sigue en `KNOWN_DEBT`.** Lanza la distribución instalada con un
  `ProcessBuilder` propio (`run()` en línea 147) y no pasa por `OwnedSubprocess`. Este bloque cambió
  **qué** afirma la fila, no **cómo** lanza el proceso. Su migración sigue siendo deuda visible.
- **No se afirma que el modo de fallo sea estable ante cualquier carga.** La detección es
  "completó dentro del deadline", con un margen medido de un orden de magnitud, no una
  clasificación.

---

## 6. Ficheros

| Fichero | Cambio |
|---|---|
| `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/cli/HttpInstalledUatTest.kt` | fila H8-10 reformulada; `runFresh` acepta `onStart`; helper `cmdlineTree` |

Ningún fichero de producción modificado. El mutante se aplicó a
`pipeline-step-sdk/http/.../BoundedBodySubscriber.kt` sólo para la medición y está restaurado con hash
verificado.

---

## 7. Evidencia ejecutada

Gate prescrito, sobre el árbol de este bloque:

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

BUILD SUCCESSFUL in 33m 59s
320 actionable tasks: 320 executed
```

`320 executed` y ninguna `up-to-date`: corrida completa, no un resumen de cachés.

Recuento desde los XML de `test-results`, acotados por la marca de arranque de esta corrida:

```text
779 clases | 5156 tests | 0 fallos | 0 errores | 140 skips
```

Idéntico al gate de `b01b8d37`. Correcto y esperado: este bloque reescribe una fila y añade dos
líneas de soporte al harness, sin crear ni eliminar ninguna fila de la matriz.

Estado de la fila dentro de ese mismo gate:

```text
HttpInstalledUatTest   tests=16 failures=0 errors=0
H8-10 characterisation (not asserted): budget=-Xmx256m bodyBytes=536870912 peakRssBytes=408866816
```

Cero procesos `pipelinek` supervivientes tras el gate.

> Una nota de lectura del log: `grep '^BUILD'` sobre este log devuelve **tres** líneas `BUILD
> SUCCESSFUL`. Dos son de Gradle que los tests lanzan como subproceso y cuyo stdout queda
> capturado en el padre; la única que certifica este gate es la última, identificada por
> `320 actionable tasks: 320 executed`. Un grep ingenuo daría un verde aquí — el mismo modo de fallo
> que la la de `DEFAULT_JVM_OPTS`, un valor presente pero sin efecto.

### Mutación, corrida completa de la suite affected

```text
control   -> BUILD SUCCESSFUL, 16 tests, 0 failures
mutante   -> BUILD FAILED,      16 tests, 1 failure
             H8-10 ... hung ... after 5 min   (GC thrash, no OutOfMemoryError)
restaurado -> sha256 6db0a583c786c5dd55d9d17085a199e529a7fc6b160a62e8be5236e9e7d240b4 (identico)
```