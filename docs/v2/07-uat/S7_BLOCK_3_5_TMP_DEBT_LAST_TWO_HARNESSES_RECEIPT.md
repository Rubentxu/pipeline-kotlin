# S7 BLOCK 3.5 — los dos ultimos que escribian en /tmp, y por que la ley que iba a hacer no se escribe

Rama `s6-plugin-sdk`. Base: `b6e1ab49` (S7 BLOCK 3.4).

El gate de 3.4 bajo las fugas de 43 a 35, y nombró a los dos autores de las 35 que quedaban. Este
bloque los arregla, y por el camino produce el hallazgo más incómodo del programa de arneses: **la
ley que iba a escribir para cerrar esto, al medirla, resulta no escribible con lo que sé.**

---

## 1. HALLAZGO: una ley basada en la API habria pasado por alto al peor infractor

La idea era cerrar la deuda de `/tmp` con una ley mecánica, como la de S6-PRE. El primer intento
buscó `Files.createTempFile(` / `createTempDirectory(` sin padre. Resultado: **95 ficheros**, de los
que solo **16** eran arneses. Y el peor infractor **no estaba en la lista**:

```text
UatLocal008CredentialsTest   34 ficheros por corrida, 2253 acumulados, 9,0 MB
```

No aparece porque **no usa `createTempFile`**. Escribe con una ruta absoluta escrita a mano:

```kotlin
val errFile = java.nio.file.Paths.get("/tmp/uat008-debug/uat008-stderr-${System.nanoTime()}.log")
val debugFile = java.nio.file.Paths.get("/tmp/uat008-debug/uat008-stdout-${System.nanoTime()}.json")
```

Y tampoco aparecia en un segundo barrido por "harness de la distribucion instalada", porque ese test
**no usa `AppBinSupport`**: lanza `MainKt` en el classpath de pruebas. Mi propia deteccion de harness
era mas estrecha que el defecto.

La leccion es la de `DEFAULT_JVM_OPTS` y la del `apiRange` tecleado, por tercera vez: **el defecto es
"escribe fuera de su sandbox", y tanto la API sin padre como la ruta literal son dosomersiones del
mismo defecto.** Una ley escrita alrededor de la API no mide el defecto, mide una de sus formas, y se
felicita por cubrirla.

### 1.1 Y la ley sigue sin escribirse, por dos clases de falso positivo medidas

Antes de convertir la idea en un fitness,probe las dos formas obvias de implementarla. Las dos fallan:

| intento | resultado |
|---|---|
| escanear `createTemp*` sin padre | 95 ficheros, 16 arneses, **y no incluye al mayor infractor** |
| escanear la cadena `"/tmp/` en el fichero | 93 en codigo, y entre los 16 arneses aparecen rutas que son **entradas**, no escrituras: `WULpr010CliCharacterizationTest:223` pasa `/tmp/lpr010-no-such-...pipeline.kts` a `validate` precisamente porque **no debe existir** |
| escanear todo el texto sin distinguir comentario | condena el KDoc que **documenta** la migracion, que es el unico sitio donde el patron aparece ya corregido |

Las tres distinciones —comentario frente a codigo, escritura frente a entrada, API frente a ruta—
se pueden implementar, pero cada una es logica propia dentro de un test, y este bloque no las ha
resuelto ni medido. Una ley de `/tmp` medio correcta es peor que ninguna: convierte un defecto medido
en un test que parece cubrirlo.

**Se deja sin escribir, con la medicion que la justifica.** Es un bloque entero con su propio gate, no
un apendice de este.

---

## 2. UatEvt002MultiStepReplayTest

Dos filas, cuatro defectos, y uno que no aparece en ninguna otra clase migrada.

**2.1 El directorio de control se creaba sin padre** (`Files.createTempDirectory("uat-evt002-control")`)
y ademas **tenia que sobrevivir a la llamada**, porque `ConsolePlaneProbe` lee el Output Plane desde
el despues de que el hijo sale. Por eso su sitio correcto no es un temporal con padre: es el
`@TempDir` de la fila, y el directorio se crea ahi dentro.

**2.2 `waitFor()` sin plazo** en los dos sitios de lanzamiento.

**2.3 El orden de drenaje invertido.** Las dos filas hacian `waitFor()` y luego leian stdout a EOF, y
stderr solo cuando el codigo de salida no era cero. Leer stdout hasta EOF **antes** de drenar stderr
es el mismo peligro del otro lado: el hijo se bloquea escribiendo stderr que nadie lee, no cierra
stdout, y la lectura del padre tampoco vuelve nunca. Es el riesgo de tuberia de 3.4 con las dos
tuberias cambiadas de sitio.

**2.4 stderr invisible en verde**, igual que en `UatDsl005`.

---

## 3. UatLocal008CredentialsTest

**3.1 El volcado de stdout era INCONDICIONAL**: uno por llamada al helper, por fila, por corrida,
para siempre. El de stderr solo escribia si stderr no venia vacio. Los 34 ficheros del gate son la
suma de los dos.

La decision: **el volcado de stdout desaparece** y **el de stderr pasa a ser una linea de salida de la
prueba**. No es una perdida de diagnostico:

- el stdout ya es el valor de retorno del helper, y el llamante lo aserta y lo mete en su propio
  mensaje de fallo; un fichero al lado no anadia bytes, solo ocupacion;
- el stderr es lo que se descarta hoy en verde, y ahora queda en el log de la corrida, que es donde
  sobrevive una corrida roja.

**3.2 El riesgo de tuberia sigue presente y NO se corrige aqui.** El helper sigue haciendo
`stdout` a EOF, luego `waitFor(120 s)`, luego stderr. Migrarlo a `OwnedSubprocess` es trabajo de un
bloque propio y no se disfraza de hecho: lo que se hace en este bloque es **eliminacion del
acumulador**, no del riesgo. El `waitFor(120, ...)` aqui ya tiene plazo y hay un `@AfterEach` que mata
hijos, a diferencia de los cuatro sitios sin plazo de `UatDsl005`.

---

## 4. LECCION METODOLOGICA: dos corridas propias solapadas inventaron una regresion

Al medir la correccion lance la corrida de medicion **mientras la corrida dirigida anterior seguia
viva**. Los 16 ficheros que aparecieron estaban escritos con una clase compilada antes del cambio, y
la clase que se ejecutaba despues ya no contenia la cadena:

```text
ocurrencias de 'uat008-debug' en la clase compilada: 0
ficheros aparecidos: 16
```

Se leyo como regresion y se estuvo a punto de "corregir" algo ya corregido. La medicion limpia,
**con una sola corrida en vuelo**, dio:

```text
UatLocal008CredentialsTest   27 filas verdes, 150 s   ->  /tmp/uat008-debug no aparece
UatEvt002MultiStepReplayTest  2 filas verdes,  12 s   ->  uat-evt002-control*: 0
/tmp, seis patrones juntos: 0
```

### 4.1 Y la misma clase de error, en la comprobacion de vida

El primer gate de este bloque murio a los 14 minutos con

```
FAILURE: Build failed with an exception.
* What went wrong:
Gradle build daemon has been stopped: stop command received
```

Es un `gradle --stop` **externo**: no fallo ningun test y solo quedaron 345 XML de los 779 que produce
un gate completo, asi que la corrida es `NOT_RUN` y no aporta nada. Pero durante **80 minutos** se
sostuvo que estaba "EN CURSO", porque la comprobacion era `pgrep -f GradleWrapperMain`, y ese patron
encontraba el Gradle **del otro worktree**, que corre a la vez en esta maquina.

Las dos son la misma regla: **una comprobacion que no distingue lo mio de lo de al lado informa sobre
la maquina, no sobre mi corrida.** Ahora se exige `exe` = `java` **y** `cwd` dentro de esta raiz, que
ademas no puede contar a su propio llamante porque los wrappers de shell no son java.

```text
ficheros XML de un gate completo:  779
ficheros XML del gate abortado:   345
```

Un conteo de XML que no llega al total de un gate completo **no es un gate parcial que pasa**: es una
corrida que no termino.

---

## 5. Criterio de salida de la deuda de `/tmp`

Este bloque no cierra la ley, pero deja la deuda de `/tmp` en un estado que se puede enunciar:

| patron | gate 3.3 | gate 3.4 | tras 3.5 (medicion aislada) |
|---|---|---|---|
| `uat*.stdout` | 5 | 0 | **0** |
| `t21*` | 2 | 0 | **0** |
| `t22*` | 1 | 0 | **0** |
| `par-p6*` | 0 | 0 | **0** |
| `uat-evt002-control*` | 1 | 1 | **0** |
| `uat008-debug/*` | 34 | 34 | **0** |
| **total** | **43** | **35** | **0** |

Las 43 que el gate de 3.3 encontro, atribuidas una a una. Lo que queda por debajo de esta linea ya no
es una fuga: es la ley que no se sabe escribir todavia.

---

## 6. El gate atrapó un defecto mio, y eso es lo que una ley es

El segundo intento de gate termino en rojo por la ley S6-PRE, no por un test de producto:

```text
S6-PRE — todo harness de la distribucion instalada pasa por OwnedSubprocess > FAILED

these entries are migrated but were not removed from KNOWN_DEBT, so the
ledger now claims debt that does not exist:
  - dev/rubentxu/pipeline/v2/application/UatEvt002MultiStepReplayTest.kt
```

La migracion de `UatEvt002` lo habia sacado de la lista de infractores, pero no lo habia sacado de
`KNOWN_DEBT`. Mi razonamiento —"las dos clases afectadas no estaban en la allowlist"— era falso: lo
escribi sin haber mirado la lista, y `UatEvt002` estaba en ella desde el principio.

Es la segunda asercion de la ley, `stale = KNOWN_DEBT - offenders`, que existe precisamente para
esto: **una migracion que no borra su entrada deja el ledger mintiendo en la direccion contraria**, y
una allowlist que acumula entradas muertas es una ley que solo puede dar verde.

Corregido a **23**. La ley no se relajo, no se amplio la exencion y no se toco la asercion: se borro
la entrada que ya no describe una deuda.

---

## 7. Ficheros

```text
M v2/pipeline-application/.../UatEvt002MultiStepReplayTest.kt     OwnedSubprocess + @TempDir
M v2/pipeline-application/.../UatLocal008CredentialsTest.kt       fin del acumulador global
M v2/pipeline-application/.../support/InstalledDistributionHarnessFitnessTest.kt   KNOWN_DEBT 24 -> 23
A docs/v2/07-uat/S7_BLOCK_3_5_TMP_DEBT_LAST_TWO_HARNESSES_RECEIPT.md              este recibo
```

`KNOWN_DEBT` baja de 24 a **23**, y no por decision sino porque la ley exigio comprobarlo. Los 23
restantes son RED esperando migracion. `UatLocal008CredentialsTest` no estava y no esta en la lista:
lanza `MainKt` en el classpath de pruebas, no la distribucion instalada, y esa distinction es la
misma que 1.1.

---

## 8. Evidencia ejecutada

### 8.1 El gate que cierra el bloque

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

ventana [1791418593, 1791420409] = 1816 s
BUILD SUCCESSFUL in 30m 16s
320 actionable tasks: 320 executed
lineas '^e: ' en el log: 0
```

Recuento acotado por `mtime` sobre los XML de `test-results`:

```text
XMLs: 779    tests: 5157    fallos: 0    errores: 0    skips: 140
```

`5157` es el mismo total que en 3.3 y 3.4, y tiene que serlo: este bloque no anadio ni quito filas.

### 8.2 El numero que este bloque persigue

Los seis patrones, en la ventana del gate, acotados por `mtime`:

```text
uat-evt002-control*    0
uat008-debug/*         0
uat*.stdout            0
t21*                   0
t22*                   0
par-p6*                0
                    ---
                       0
```

**Primera vez que la suite completa no deja nada en `/tmp`.** La cadena medida queda en 43 (gate de
3.3) -> 35 (3.4) -> **0**, con las 43 de partida atribuidas una a una en 5.

### 8.3 Supervivientes

Por `exe` real en `/proc/<pid>/exe`:

```text
procesos con exe *pipelinek* o *SubprocessFixtureProgram*: 0
```

### 8.4 Las dos corridas anteriores NO cuentan, y por que

Se registran porque un recibo que solo guarde el verde pierde el modo de fallo mas instructivo del
bloque.

| intento | que paso | veredicto |
|---|---|---|
| 1 | `Gradle build daemon has been stopped: stop command received`, a los 14 min; 345 de 779 XML | **NOT_RUN**, sin evidencia |
| 2 | la ley S6-PRE en rojo por entrada caduca en `KNOWN_DEBT`; 675 de 779 XML | **RED real**, defecto mio |
| 3 | `BUILD SUCCESSFUL in 30m 16s`, 779 de 779 XML, 0 fugas | **PASS** |

El intento 1 fallo por un `gradle --stop` externo, no por el codigo. Se leeria como verde si se
contara el log; por eso el recuento se hace sobre XML y se compara con los 779 de un gate completo.

El intento 2 es el que mas importa: **la ley atrapó un defecto mio antes de que llegara a un commit**.
No fue un test de producto, fue la segunda asercion de la ley (`stale = KNOWN_DEBT - offenders`)
diciendo que la allowlist afirmaba una deuda que ya no existia. Una migracion que no borra su entrada
deja el ledger mintiendo en la direccion contraria, y una allowlist que acumula muertas es una ley
que solo puede dar verde.

---

## 9. Lo que este bloque NO cierra

**La ley de `/tmp` sigue sin existir.** Este bloque baja la deuda a cero **por medicion**, no por una
asercion que impida volver a subirla. Mañana un arnes puede volver a escribir ahi y nada lo dira. La
seccion 1 deja las tres distinciones que esa ley tiene que resolver, y ninguna esta resuelta:

1. comentario frente a codigo,
2. escritura frente a ruta de entrada,
3. API sin padre frente a ruta literal.

Ese es el bloque siguiente, con su propio gate.

**El riesgo de tuberia de `UatLocal008CredentialsTest` sigue abierto.** Aqui solo se elimino el
acumulador; el helper sigue leyendo stdout a EOF antes de drenar stderr, al reves que 3.4. Migrarlo
a `OwnedSubprocess` es trabajo propio y no se disfraza de hecho.