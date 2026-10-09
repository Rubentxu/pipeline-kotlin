# S7 BLOCK 3.4 — UatDsl005TimeoutGrammarTest: el numero que sostenia la ley era falso

Rama `s6-plugin-sdk`. Base: `7dc8af79` (S7 BLOCK 3.3).

El bloque 3.3 encontró 43 ficheros fugados a `/tmp` y los atribuyó a cuatro arneses. Este bloque
toma el peor de los cuatro, y por el camino descubre que la cifra que justificaba la ley de arneses
era 8 veces más grande que la real en esta máquina.

---

## 1. HALLAZGO MEDIDO: el buffer de tubería no es 64 KiB

La ley S6-PRE y la primitiva `OwnedSubprocess` se construyeron sobre una frase que aparece en 15
ficheros Kotlin y 5 recibos: *"waiting first can deadlock when a child fills the 64 KiB pipe
buffer"*. Medido en esta máquina:

```text
F_GETPIPE_SZ sobre 20 tuberias nuevas: 8192 bytes (8 KiB) en 20 de 20
fs.pipe-max-size                      = 1048576
kernel                                = 7.2.7-ogc1.1.fc44.x86_64
```

`fs.pipe-max-size` es **el techo que un proceso puede solicitar** con `F_SETPIPE_SZ`, no el tamaño
que recibe por defecto. El defecto son 8192 bytes.

La dirección del argumento no cambia: cualquier buffer se puede rebasar, y a 8 KiB es más fácil que
a 64 KiB, así que esperar antes de drenar sigue estando mal. Lo que cambia es que un umbral escrito
como 8 veces la realidad **se lee como margen de seguridad que no existe**. Una afirmación que
empieza a ser segura para el lector que la hereda es peor que no dar el número.

### 1.1 Y el peligro resultaba latente, no activo

`UatDsl005TimeoutGrammarTest` leía stderr **después** de `waitFor()` y solo en la rama de fallo.
Medido contra el binario del árbol:

```text
binario:  v2/pipeline-application/build/install/pipelinek/bin/pipelinek   (pipeline 0.47.0)
script:   timeout-retry.pipeline.kts
exit=0    stdout 9876 B    stderr 653 B
```

653 de 8192 son el **8,0%** del buffer: margen de 12,5 veces. El arnés **no se está colgando hoy**, y
"todavía no se ha colgado" no es la propiedad que lo protege. [OwnedSubprocess] drena ambas tuberías
desde el instante en que arranca el hijo, así que la pregunta deja de importar.

**Lo que este bloque NO demuestra:** que el patrón se colgara. No hay ninguna corrida que lo
muestre, y esta clase pasó 7/7 en el gate de BLOCK 3.3. Afirmar el deadlock habría sido repetir el
error de la hipótesis de la sección 1 del recibo de 3.3, que se escribió antes de mirar.

### 1.2 Dónde se corrige y dónde no

Corregido, porque es documentación vigente que se lee hoy:

- `CliRun.kt` — los dos sitios que citaban 64 KiB.
- `InstalledDistributionHarnessFitnessTest.kt` — el KDoc de la ley.

**No corregido, a propósito:** los otros 13 ficheros Kotlin y los 5 recibos. Un recibo es evidencia
de la creencia que se tenia en su SHA; reescribirlo en silencio es una falsificacion. Quedan
inventariados en 9.4 para un bloque propio.

---

## 2. Lo que tenia el arnés: cuatro defectos, y el cuarto no era del proceso

**2.1 Cuatro `waitFor()` sin plazo.** Si el hijo se cuelga, se cuelga el test, y lo corta el
`@Timeout(120)` de clase, dejando un `pipelinek` vivo detrás sin decir por qué.

**2.2 stderr por `PIPE` y leído despues de esperar**, y solo cuando el código de salida no era cero.
Una corrida verde descartaba su stderr; una roja lo recibía por primera vez.

**2.3 Seis `Files.createTempFile` sin padre**, en `java.io.tmpdir`. Medido en el gate de BLOCK 3.3:
**8 ficheros por corrida** de esta clase (5 `uat*.stdout`, 1 `t21-marker`, 1 `t21*.stdout`,
1 `t22*.stdout`), más **142 más acumulados** por corridas anteriores que nadie había inventariado
(71 `t21*` + 71 `t22*`, 37 de ellos de días anteriores).

**2.4 `/tmp/t21-marker` era una ruta global fija.** No era un fichero temporal: era **el estado que
la fila T21 existe para observar**. El intento 1 debe fallar porque el marcador no existe; el
intento 2 debe salir bien porque el intento 1 lo creó. Poner ese estado en un nombre fijo fuera del
directorio del test es estado mutable compartido, que Harness Fidelity 4 prohíbe. Y es peor que una
fuga: dos corridas concurrentes de T21 se pisan el marcador y la asercion de "2 intentos" falla por
causa externa.

---

## 3. La forma nueva

Los cuatro sitios de lanzamiento pasan por `OwnedSubprocess.run` con `cliDeadline = 90 s`, por
debajo del `@Timeout(120)` de clase. Los seis ficheros temporales desaparecen: `@TempDir` para los
dos fixtures `.pipeline.kts`, y el marcador pasa a ser un nombre de fichero dentro del `@TempDir` de
la fila, interpolado en el script. stderr queda siempre en mano y entra en el mensaje de fallo.

```kotlin
val marker = tempDir.resolve("t21-marker").toAbsolutePath().toString()
Files.writeString(fixture, """
    pipeline {
        stages {
            stage("t21") {
                retry(2) {
                    sh("test -f '$marker' && exit 0 || { touch '$marker'; exit 1; }")
                }
            }
        }
    }
""".trimIndent())
```

**Las aserciones no se cambian.** La migracion toca el manejo del proceso, no las afirmaciones; un
test que pasa porque se relajo una asercion es un test que se ha falseado.

---

## 4. No-vacuidad

Una migracion que dice "ahora el hang se clasifica" necesita una mutacion que lo refute si deja de
ser cierto. `cliDeadline` a 1 ms:

```text
7 de 7 filas ROJAS
cada una: CliRun.TimedOut clasificado, pid real nombrado, descendants=[]
huerfanos vivos despues (exe real en /proc): 0
ficheros fugados durante la mutacion: 0
```

Los pids fueron 3381340, 3382949, 3384193, 3385560, 3386555, 3387776 y 3389209, uno por fila, y cada
mensaje nombra el fixture exacto que ejecutaba — incluidas las dos filas de `@TempDir`
(`/tmp/junit-671.../t21.pipeline.kts`, `/tmp/junit-766.../t22.pipeline.kts`). Eso es lo que prueba
que el marcador y los fixtures ya no son globales.

Que la corrida **mutada** tampoco fugue nada importa más que el caso verde: la ruta de re reap es
limpia precisamente cuando falla.

Restaurado byte a byte, verificado:

```text
antes de mutar:  0bc59307968bcfb2a7db038347312d69ec0c952cea5987f256b4909f5093f402
despues:         0bc59307968bcfb2a7db038347312d69ec0c952cea5987f256b4909f5093f402   COINCIDE
```

---

## 5. Dos cosas que la migracion casi deja pasar

**5.1 Un fallo de compilacion no es un RED.** La primera corrida dirigida dio
`compileTestKotlin FAILED` con `Unresolved reference ... receiver type mismatch` en las dos escrituras
de fixture: `Path.writeText` no resuelve en este modulo. El estilo de la casa es
`Files.writeString(path, texto)`, que es lo que se uso. Sin leer los `^e: ` se habria reportado un
defecto del producto.

**5.2 Un RED mio, encontrado por la corrida dirigida y no por el gate.** `timeout-retry script
compiles and emits parseable JSON` falló con `stdout must end with ']'`. La causa no era el producto:
el original hacia `.trim()` sobre la salida antes de `startsWith`/`endsWith`, y la migracion le
quitó ese `.trim()`. 6 de 7 filas verdes y una roja, exactamente el reparto que produce un fallo de
escritura y no un fallo de sistema. Corregido con `val output = rawOutput.trim()`.

Se deja escrito porque un arnés que se reescribe es donde mas facil se pierde una fila sin
enterarse: el `.trim()` no estaba en ninguna asercion, estaba en la linea de arriba.

---

## 6. Ficheros

```text
M v2/pipeline-application/.../UatDsl005TimeoutGrammarTest.kt              migracion + @TempDir
M v2/pipeline-application/.../support/CliRun.kt                            KDoc: 64 KiB -> medido
M v2/pipeline-application/.../support/InstalledDistributionHarnessFitnessTest.kt   KNOWN_DEBT 25 -> 24, KDoc
A docs/v2/07-uat/S7_BLOCK_3_4_TIMEOUT_GRAMMAR_HARNESS_MIGRATION_RECEIPT.md        este recibo
```

`KNOWN_DEBT` baja de 25 a **24**. Los 24 restantes son RED esperando migracion.

---

## 7. Evidencia ejecutada

### 7.1 Gate prescrito

```text
cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks

ventana [1791408130, 1791409988] = 1858 s
BUILD SUCCESSFUL in 30m 57s
320 actionable tasks: 320 executed
lineas '^e: ' en el log: 0
```

Ninguna tarea `UP-TO-DATE` y `^e: ` a cero, que es lo que descarta el falso verde de una compilacion
fallida. El log lleva lineas `BUILD SUCCESSFUL` de 2s, 3s, 4s y 6s: las de segundos las producen
fixtures que invocan Gradle por su cuenta, igual que se vio en 3.3 §9.1.

### 7.2 Recuento acotado por `mtime`

XML de `test-results` con `mtime` dentro de `[inicio, fin]`:

```text
ficheros XML: 779    tests: 5157    fallos: 0    errores: 0    skips: 140
```

`5157` es identico a BLOCK 3.3 y tiene que serlo: este bloque no anadio ni quito filas. Las dos
clases que tocan el cambio:

```text
UatDsl005TimeoutGrammarTest                  tests=7  fail=0  err=0
InstalledDistributionHarnessFitnessTest      tests=1  fail=0        (KNOWN_DEBT = 24)
```

### 7.3 Fugas: 43 -> 35, y las 8 que faltan son las 8 de esta clase

Mismo metodo que 3.3 §9.4: `mtime` dentro de la ventana, por prefijo, sin acotar el residuo previo.

| patron | gate de 3.3 | gate de este bloque | dueno |
|---|---|---|---|
| `uat*.stdout` | 5 | **0** | `UatDsl005` — corregido |
| `t21*` | 2 | **0** | `UatDsl005` T21 — corregido |
| `t22*` | 1 | **0** | `UatDsl005` T22 — corregido |
| `par-p6*` | 0 | 0 | `UatDsl003ParallelTest` |
| `uat-evt002-control*` | 1 | 1 | `UatEvt002MultiStepReplayTest` — sin migrar |
| `uat008-debug/*` | 34 | 34 | `UatLocal008CredentialsTest` — sin migrar |
| **total** | **43** | **35** | |

5 + 2 + 1 = **8**, exactamente las que el gate de 3.3 atribuyo a esta clase por aritmetica. El
resultado no es "bajo un poco": es que las 8 previstas desaparecen y las 35 que quedan son de dos
arneses que este bloque no toca. Las dos filas que siguen escribiendo en `/tmp` son el trabajo que
queda, y ahora estan medidas con nombre y fichero.

### 7.4 Supervivientes

Por `exe` real en `/proc/<pid>/exe`:

```text
procesos con exe *pipelinek* o *SubprocessFixtureProgram*: 0
```

### 7.5 HALLAZGO COLATERAL: la version no identifica el artefacto

Apareció al medir el stderr de la seccion 1 y conviene que no se pierda, porque decide como se
diseña el SDK del bloque siguiente.

Dos artefactos distintos, **la misma cadena de version**, comportamientos distintos sobre el mismo
script:

```text
/opt/pipelinek-0.47.0/bin/pipelinek   "pipeline 0.47.0"   timeout-retry.pipeline.kts -> exit 1
    Argument type mismatch: actual type is 'FailureKind', but 'String' was expected.

v2/pipeline-application/build/install/pipelinek/bin/pipelinek   "pipeline 0.47.0"
    exit 0
```

Los bytes difieren (`pipeline-domain-0.47.0.jar`: 1.445.733 en el arbol, 1.322.697 en el release), y
la causa del error del release es visible en el historial: `93029b6f refactor(scripting): failureKind
deja de ser una cadena que el autor puede equivocar` es el commit que tipifico `FailureKind`, y el
error es exactamente el choque entre esa forma y la anterior.

El dato estructural:

```text
tag v0.47.0        -> 3ec99a4c
commits v0.47.0..HEAD -> 78
version en v2/build.gradle.kts:75 -> "0.47.0"   (sin cambios desde el tag)
```

**Setenta y ocho commits de desarrollo detras de una cadena de version que ya consume un
artefacto publicado.** Por eso se dice "la version no identifica el artefacto".

Consecuencia directa para el bloque siguiente, y es la razon de que el roadmap ponga S6 -> 0.48 antes
que S7/S8: **derivar `apiRange` de `rootProject.version` no seria derivarlo de nada**, porque la
version no distingue el codigo que la produce. Seria la misma clase de defecto que `DEFAULT_JVM_OPTS`
y que el `apiRange` tecleado: una propiedad que parece autoritativa y no identifica lo que dice
identificar. La version tiene que subir a 0.48 **antes** de que exista un `apiRange` derivado, o el
derivado saldra con el mismo defecto que el tecleado.
