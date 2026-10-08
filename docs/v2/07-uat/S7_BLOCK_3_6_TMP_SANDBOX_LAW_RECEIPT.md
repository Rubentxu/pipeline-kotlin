# S7 BLOCK 3.6 — la ley de /tmp, escrita por fin como fitness mecanico

Rama `s6-plugin-sdk`. Base: `e87be13d` (S7 BLOCK 3.5).

El bloque 3.5 llevo la fuga de `/tmp` a **cero por medicion**, y el propio recibo de 3.5 cerraba
diciendo que una medicion no impide que el siguiente arnes empiece. Este bloque escribe la ley que
impide que empiece. No migra ningun arnes: no migra ninguno, y esa es la diferencia.

---

## 1. QUE CIERRA ESTE BLOQUE

```text
v2/pipeline-application/src/test/kotlin/.../support/TestSandboxFitnessTest.kt   (nuevo)
```

Una sola clase, un solo test, dos aserciones. Escanea los 677 ficheros de test authored de `v2` y
exige que ningun arnes que bifurca un proceso hijo escriba fuera de un directorio que sea suyo.

## 2. LA PROPIEDAD, NO LA API

El defecto de 3.5 no es `Files.createTempFile`. El defecto es **escribir fuera de su sandbox**, y
tiene dos formas que no se parecen en nada:

```kotlin
Files.createTempFile("x", ".log")                 // sin padre -> java.io.tmpdir
Paths.get("/tmp/uat008-debug/...log")             // ruta absoluta escrita a mano
```

La ley busca la propiedad en tres formas (temporal sin padre, ruta temporal absoluta, lectura de la
propiedad del JVM), no un metodo. Una ley escrita alrededor de la API habria pasado por alto al
peor infractor del repositorio, que acumulaba **2253 ficheros y 9,0 MB** y no la usaba.

## 3. LAS TRES DISTINCIONES MEDIDAS

El primer intento de esta ley no se pudo escribir, por tres motivos que solo aparecieron al medir.

### 3.1 Comentario no es codigo

Un arnes migrado nombra en su KDoc el patron que dejo de usar, precisamente para que el siguiente
lector entienda por que el codigo tiene esa forma. Un escaner de texto crudo convicta **la
documentacion de su propio arreglo**. Los comentarios se borran antes de decidir nada.

### 3.2 Entrada no es escritura

La ley no puede distinguir *escribir* de *pasar una ruta al producto como entrada*. Hay un caso
medido: `WULpr010CliCharacterizationTest` pasa a proposito una ruta inexistente a `validate` para que
la CLI la reporte como ausente, y esa ruta no es una fuga. En vez de construir analisis de flujo de
datos, **la distincion es dato**: esta escrita en el registro como una decision revisada. La lista
es el registro de revision, y anadir a ella es una afirmacion que hay que argumentar.

### 3.3 La aridad decide si hay padre, no el nombre del metodo

```text
Files.createTempFile(prefix, suffix)             2 argumentos -> SIN padre
Files.createTempFile(dir, prefix, suffix)        3 argumentos -> CON padre
```

Mismo metodo, comportamientos opuestos. La cuenta de argumentos es lo que decide. Regla aplicada:

```text
File      fuga si aridad < 3
Directory fuga si aridad < 2
```

Y aqui hay una **correccion material**: el contraste de la ley se cite antes como 37 ficheros que
caen a 25. Ese 37 estaba mal acotado — no exigia que el fichero bifurcara. Las cifras medidas son:

```text
112  ficheros con cualquier createTemp*            (sin exigir bifurcacion)
 34  que bifurcan y ademas tienen createTemp*
 25  que bifurcan y ademas tienen uno SIN padre     <-- el defecto
  9  que bifurcan y usan createTemp* CON padre      <-- siempre fueron correctos
```

Los 9 que desaparecen son `createTempFile(workdir, ...)`, que nunca tuvieron el defecto.

## 4. LA POBLACION MEDIDA

```text
ficheros de test authored en v2/**/src/test/**     677
  fork AND leak   (el defecto)                     25
  solo fuga                                       103
  solo fork                                        70
  ninguno                                         479
                                                 ----
                                                  677
```

El alcance es **la interseccion, no cada mitad sola**: 103 que solo fugas son un arnes con
libro-reca desordenado, y 70 que solo bifurcan son la forma correcta. Los 25 que hacen ambas cosas
son los que produjeron las 43 fugas de 3.5.

Los 25 se reparten en 21 de `pipeline-application`, 1 de `pipeline-events-store` y 3 de
`pipeline-step-sdk`. **11 de ellos estan tambien** en el `KNOWN_DEBT` (23) de
`InstalledDistributionHarnessFitnessTest`: migrar esos 11 encoge las dos leyes a la vez, que es la
razon por la que esta lista merece existir en vez de una prohibicion.

El solape medido son exactamente estos 11:

```text
S0SemanticWitnessMatrixTest          S3EnvironmentSemanticWitnessTest
TrapFormNegativeFixtureTest          UatDsl006BodyExecutionTest
cli/WULpr010CliCharacterizationTest  cli/WULpr011ResumeLifecycleUatTest
cli/WURp019GradleRealUatTest         cli/WURp020MavenRealUatTest
cli/WURp021NodeRealUatTest           cli/WURp023ObservationModesUatTest
support/PureBuilderProbe
```

Quedan **14** que solo infringen esta ley y **12** que solo infringen la hermana. Las dos leyes
miden propiedades distintas: aquella forbids bifurcar sin `OwnedSubprocess`; esta forbids bifurcar
y escribir fuera de tu directorio. Un fichero puede caer en una, en otra, en las dos o en ninguna,
y por eso los dos libros se miden por separado en vez de fusionarse en una lista unica.

## 5. DOS ASERCIONES, POR QUE LAS DOS

```text
1. ningun infractor fuera del registro   -> falla si aparece deuda nueva
2. ninguna entrada caduca en el registro -> falla si el registro miente
```

La segunda es la que impide que la ley se vuelva verde por construccion. Sin ella, migrar un arnes
dejaria su nombre en la lista, la lista solo creeria, y dentro de un mes la ley daria verde
mientras afirmaba deuda que ya no existe. Un libro mayor que solo crece es una ley que solo puede
dar verde.

Una ley con una excepcion ampliable en silencio no es una ley. Por eso los tokens que busca estan
armados por concatenacion de trozos en el propio fichero, en vez de una lista de exenciones que
pudiera crecer sola.

## 6. NO-VACUIDAD: DOS MUTACIONES, UNA POR ASERCION

Sin esto, un verde no prueba nada: probaria que la asercion no dispara.

### M1 — mata la asercion 1

Anadida una escritura sin padre a `A4_2ShellOperationsCapabilityTest`, que hasta entonces bifurcaba
sin fugar (de los 70):

```kotlin
private fun b36MutantLeak(): java.nio.file.Path =
    java.nio.file.Files.createTempFile("b36-mutant-", ".log")
```

```text
AssertionFailedError: these files fork a child AND write outside a directory they own:
  - pipeline-application/dev/rubentxu/pipeline/v2/application/A4_2ShellOperationsCapabilityTest.kt
(the law read 677 test sources and found 26 offenders)
```

Restaurado con `git checkout`; sha256 de vuelta en
`b2abe2729930dd89575b1c602d63c2c666b28636d6298cc6541614e72439b7af`.

### M2 — mata la asercion 2

Anadida al registro `B36StaleEntryProbeTest.kt`, que no existe y por tanto no infracta:

```text
AssertionFailedError: these entries no longer fork-and-leak but were not removed from
KNOWN_SANDBOX_LEAKS, so the ledger claims debt that does not exist:
  - pipeline-application/dev/rubentxu/pipeline/v2/application/B36StaleEntryProbeTest.kt
(the law read 677 test sources and found 25 offenders)
```

Restaurado; sha256 de vuelta en
`019cd6c2855fe468c4b08822530d3e8cf5b010c1e21f801d019d826d2dee53bb`.

Las dos restauraciones verificadas byte a byte y sin residuo de sonda en el arbol.

## 7. CALIBRACION: DOS IMPLEMENTACIONES INDEPENDIENTES

Las 25 entradas se derivaron con un escaner **Python** independiente (`/tmp/tmp_scan.py` mas su
driver). Que el test de Kotlin pase significa que el conjunto de infractores que calcula Kotlin es
igual, elemento por elemento, a la lista que produjo Python, y que no sobra ninguno. Dos
implementaciones que coinciden en 25 nombres es la prueba de que la ley esta bien; que una ley nueva
pase en su propia primera corrida no lo seria.

El propio mensaje de fallo confirma el alcance desde Kotlin: **677 ficheros leidos, 25 infractores**.

## 8. CUATRO DEFECTOS PROPIOS ATRAPADOS AL ESCRIBIRLA

Ninguno es hipotetico. Todos estan en el historial de este bloque.

1. **Fallo de compilacion, no RED.** `it.extension()` — se importo `kotlin.io.path.extension` como
   propiedad y se invoco como funcion. Un `compileTestKotlin` fallido no es evidencia de nada.

2. **Los comentarios de bloque de Kotlin anidan.** El KDoc de `governedId` escribia la ruta generada
   como `build/generated/ksp/**`. Ese `/**` **abrio un comentario anidado**, asi que el `*/` de
   cierre del KDoc solo cerro el interno, y todo lo que va de la linea 114 a la 226 quedo engullido
   como comentario. Sintoma: cinco declaraciones posteriores marcadas `unresolved reference` mas tres
   errores de sintaxis en las columnas 55, 56 y 86 de la linea 226. No era una cascada, era un
   atrapamiento lexical. Se diagnostico compilando el fichero aislado con `kotlinc` para obtener
   errores sin el ruido de JUnit, y razonando sobre el censo de llaves y comentarios.

   **Regla que sale de ahi:** un glob escrito como `dir/**` dentro de un KDoc abre un comentario. No
   escribir globs con `/**` en prosa de Kotlin.

3. **`mapNotNull` no existe en `java.util.stream.Stream`** — solo en `Array`, `Iterable` y `Sequence`.
   Sustituido por `forEach` con acumulador.

4. **El escaner recorria `build/generated/ksp`.** La primera ejecucion del test lanzo
   `no module above 'src'` sobre un fichero que no ha escrito ninguna persona. Ahora la ley gobierna
   solo fuentes authored: un defecto en fuente generada pertenece al generador, y nombrarlo por su
   ruta de salida pondria una deuda en un fichero que no se puede editar en el sitio.

Este lo atrapo la ejecucion, no la revision: un test que lanza no es un test que pasa.

## 9. LO QUE LA LEY NO AFIRMA

- No dice que escribir en un directorio temporal este mal. Dice que un arnes que bifurca debe
  escribir en un directorio **que posea**.
- No distingue escribir de pasar una ruta como entrada. Esa distincion esta en el registro, como
  dato revisado, no en el codigo.
- No es una prohibicion. Es un registro de 25 deudas medidas, con las dos aserciones que lo
  mantienen honesto.

## 10. GATE

Base `e87be13d`. Bytes del fichero de la ley:

```text
sha256 TestSandboxFitnessTest.kt = c571dd807435d9b802455395c7a7ae8cf3804006638f43e0bbf93a9830563201
```

```text
argv     cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks
ventana  [1791423844, 1791425534]   1690 s
resultado BUILD SUCCESSFUL in 28m 9s
         320 actionable tasks: 320 executed
         780 XML / 5158 tests / 0 failures / 0 errors / 140 skipped
fugas    0 en los seis patrones, acotado por ventana Y sin acotar
supervivientes  MUERTO
```

Los 780 XML son los 779 de siempre **mas el de esta ley**. Los 5158 tests son los 5157 de siempre mas
el de esta ley. Un recuento que no llega a esa cifra es `NOT_RUN`, no un verde parcial.

La ley dentro de esa corrida:

```text
TEST-dev...support.TestSandboxFitnessTest.xml
tests=1 failures=0 errors=0 skipped=0
PASS  ningun arnes que bifurca escribe fuera de su directorio propio
```

### 10.1 Dos gates anteriores se tiraron a proposito

Esta seccion no es decorativa: **dos corridas completas se detuvieron porque el codigo afirmaba cosas
que no habia medido.** Se cuentan porque un gate que valida bytes que despues cambian no es un gate.

```text
corrida 1  ventana [1791422401, ...]  Detenida al leer el KDoc: afirmaba 37 -> 25,
                                        y la medicion da 34 -> 25. La cifra de 37 venia de
                                        un recuento que no exigia que el fichero bifurcara.
corrida 2  ventana [1791423217, ...]   Detenida al leer el KDoc: afirmaba "2479 ficheros",
                                        que era mi suma de dos poblaciones distintas
                                        (224 entradas de primer nivel + 2255 ficheros dentro
                                        de un directorio de depuracion). Presentado como un
                                        unico recuento de ficheros es falso.
corrida 3  ventana [1791423844, ...]   Verde. Es la que se cierra aqui.
```

En los dos casos la afirmacion estaba en un **comentario**, no en la logica, asi que ninguna de las
dos corridas habria detectado el problema por si misma: el test pasaba igual. Se pararon porque el
codigo iba a quedar como registro, y un registro con una cifra inventada es peor que no tener ley.
