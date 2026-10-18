# Cierre del bloque de hermeticidad de harness: 15 filas rojas por el `$HOME` del operador

> estado: cerrado sobre el árbol de trabajo de este recibo.
> base del ciclo: `37c9cb41`.
> ley: `HARNESS FIDELITY LAW` §4 — *"A harness MUST NOT be able to make its own
> subject look broken. No ambient `cwd`/env…"*.
> alcance: **cuatro ficheros de test** y este recibo. Ningún fichero de producción.

## 1. El síntoma, medido

En la base `37c9cb41`, `:pipeline-application:test` deja **15 filas rojas** entre
dos clases:

```text
ErrorHandlingTest              8 filas   (ERR-S-001..004, 006, 007, 008,
                                          legacy spelling, misspelled kind)
UatLocal012ErrorHandlingTest   7 filas   (SC-012-02..08)
```

El cuerpo del fallo no es una aserción de contrato. Es esto:

```text
stdout: {"schema":"pipelinek.launch/1", … , "status":"Launched"}
stderr: Error: Passphrase required: set PIPELINE_STORE_PASSPHRASE or run
        interactively in a TTY
==> expected: <0> but was: <3>
```

`exit 3` junto a `status: Launched`. **El producto no llegó a ejecutar el
pipeline**: la CLI se negó antes. Una fila que falla así no mide el comportamiento
que dice medir.

## 2. La causa, con su mecanismo completo

```kotlin
// MainCredentialsSupport.kt:27-66 — composeWithCredentialsExecutor
val credentialsStorePath: Path = CredentialsStorePath.fromEnvironment(System.getenv())
if (!credentialsStorePath.toFile().exists()) return null          // rama documentada
val secretStore = try { LocalSecretStore(credentialsStorePath, PassphraseResolver.resolve()) }
catch (e: PassphraseResolver.CredentialsStorePassphraseUnavailableException) {
    System.err.println("Error: ${e.message}")
    System.exit(3)
    return null
}
```

`Main.kt:664` la invoca **incondicionalmente** en la rama in-memory, y
`CredentialsStorePath.resolve()` es:

```kotlin
envValue?.takeIf { it.isNotBlank() }?.let { Path.of(it) }
    ?: userHome.resolve(DIRECTORY).resolve(FILE)     // DIRECTORY = ".pipeline"
```

La condición de disparo es exactamente:

```text
existe  $HOME/.pipeline/credentials.bin
Y       el hijo generado no tiene PIPELINE_STORE_PASSPHRASE
  =>    exit 3 antes de ejecutar el pipeline
```

Que el fichero exista es un hecho de máquina, no de la prueba. En esta máquina
existe desde **2026-09-22**: alguien ejecutó `credentials add` una vez y, desde
entonces, cada fila de estos dos ficheros que arranca el binario falla «por la
carpeta personal del operador».

**Corrección de un error mío:** busqué primero en `~/.pipelinek` —el nombre que
usa la distribución— y concluí que la hipótesis no tenía apoyo. El directorio es
`~/.pipeline`, sin `k`. Un instrumento con el nombre equivocado produce un «no
existe», y un «no existe» se lee como refutación.

## 3. El arreglo

Los cuatro ficheros afectados comparten una copia literal del mismo helper
`runPipeline` (D16, regla de tres). El arreglo es un bloque por copia, con la
forma que ya usaba `UatLocal011`:

```kotlin
val pb = ProcessBuilder(args)
    .directory(tempDir.toFile())
    .redirectOutput(ProcessBuilder.Redirect.PIPE)
    .redirectError(ProcessBuilder.Redirect.PIPE)
    .also {
        it.environment().put(
            "PIPELINE_CREDENTIALS_STORE",
            tempDir.resolve("absent-credentials.bin").toString(),
        )
    }
```

Se apunta el almacén a una ruta **dentro del `@TempDir` de la prueba que no
existe**, lo que ejercita la rama documentada `store file does not exist -> null`.
No se requiere material de credenciales, no se crea ningún fichero de credenciales
y no se toca ninguna credencial real.

Ficheros: `ErrorHandlingTest.kt`, `UatLocal012ErrorHandlingTest.kt`,
`UatLocal011WorkflowControlTest.kt`, `UatLocal009TopStepsTest.kt`.

## 4. El «control verde» que no era verde

`SC-011-12` de `UatLocal011WorkflowControlTest` estaba verde a las 21:54 con el
**mismo** harness y **la misma** máquina, y rojo después, con:

```text
UatLocal011WorkflowControlTest.kt:653  assertEquals(0, result.exitCode, …)
```

No era un control: era otra fila expuesta al mismo estado de máquina. Por eso la
hipótesis del passphrase parecía refutada mientras se escribía. Es la razón por la
que el arreglo se aplicó a las cuatro copias y no a las dos que fallaban.

## 5. La pregunta del `--tests`, y por qué el hallazgo inicial estaba sobredimensionado

Pregunta: ¿acotó `--tests` la corrida de `:pipeline-application`?

La respuesta importa **por corrida**, y por eso hubo que medir tres veces. La
primera lectura quedó sobredimensionada: generalicé «esta lane ignora `--tests`» a
partir de **una** corrida. Las tres mediciones, con su observación:

```text
corrida              patrones   clases FAILED observadas   tests   veredicto
------------------  ---------  --------------------------  ------  ------------
bs6oss3o7 (matada)  8          6 distintas, 5 fuera        n/d     NO acoto
bmog4ovyx (run2)    3          1, todas dentro             33      acoto
b0w9ifax0 (run4)    3          0                           46      acoto
```

Evidencia por corrida:

- **bs6oss3o7 NO acotó.** `exit=124` (matada por `timeout`), y el log de Gradle
  imprime FAILED **a medida que cada clase termina**. Contenido FAILED:
  `B1WURp053rContextRuntimeClosureTest`, `CanonicalInMemoryCliTest`,
  `ErrorHandlingTest`, `ObsAOutputStreamingCharacterisationTest`,
  `RunDefaultIsHumanEndToEndTest`, `UatInputBlockDurableTest`. Cinco de las seis no
  casa con ninguno de los patrones. Esto no depende de ningún recuento de XML.
- **bmog4ovyx acotó.** `33 tests completed`, y 33 es exactamente
  `ErrorHandlingTest(10) + UatLocal011(15) + UatLocal012(8)`. La única clase con
  FAILED fue `UatLocal011`, que sí estaba pedida.
- **b0w9ifax0 acotó.** `build/test-results/test/` contiene **4** XML, con nombre
  de las 4 clases pedidas, con `failures="0"`.

**Dos instrumentos que ya habían dado un número falso en este bloque**, y por qué:

1. *Contar XMLs presentes* para inferir ejecución. La tarea `Test` limpia el
   directorio de resultados al empezar, así que el recuento describe **la última
   corrida y nada más**. Contar XMLs mide el techo, no el contenido.
2. *Los 18 XML* que usé para «retractar» la conclusión sobre `bs6oss3o7`: eran de
   una corrida anterior, no de la observada.

**Lo que queda en pie y lo que no:**

```text
EN PIE   para bs6oss3o7: el filtro no acotó esa corrida.
EN PIE   la consecuencia material: toda la evidencia L1/L2 de esa corrida
         corrió contra una población más ancha que la declarada.
CAÍDO    «esta lane ignora --tests». Refutado por dos corridas posteriores
         que sí acotaron con el mismo filtro y el mismo repositorio.
NOT_RUN  por qué bs6oss3o7 no acotó y las otras dos sí. No hay hipótesis
         registrada: el argv de aquella invocación no se conserva.
```

`:pipeline-domain:test` acotó en la corrida medida (48 XML, todos con nombre de
patrón).

## 6. Censo con su denominador

Instrumento: `grep -l 'application.MainKt'` sobre
`v2/pipeline-application/src/test`, y ese mismo conjunto filtrado por
`PIPELINE_CREDENTIALS_STORE`.

```text
ficheros que lanzan application.MainKt          33
de esos, fijan PIPELINE_CREDENTIALS_STORE       5
no la fijan                                   28
```

Los 28 quedan **sin tocar** y registrados como deuda con su lista nominal. La
razón no es pereza: algunas UAT de credenciales pueden querer el almacén real, y
cambiar tests para obtener verde está prohibido por `AGENTS.md` §15 y por
`delivery-first-testing` (*«No cambiar el producto para satisfacer tests
obsoletos»*). Editar 28 harnesses a ciegas sería cambiar pruebas para pintar verde,
que es exactamente lo que la ley prohíbe.

## 7. Verificación

Corrida `b0w9ifax0`, sobre el árbol de trabajo de este recibo, con el log
redirigido a fichero (nunca a través de `| tail` bajo `timeout`):

```text
argv    timeout 900 ./gradlew :pipeline-application:test \
          --tests '*ErrorHandlingTest*' \
          --tests '*UatLocal011WorkflowControlTest*' \
          --tests '*UatLocal009TopStepsTest*'
cwd     v2/
salida  /tmp/hermetic-run4.log
sha256  8ed6bef7056cf32974953cd67d7d1cc1c8156cd99d533d2c39cccfe4cb53e95c  (9438 bytes)
exit    0
BUILD   SUCCESSFUL in 4m 41s
```

`BUILD SUCCESSFUL` **no es la verdad del resultado**; la verdad son los XML. Los
cuatro XML canario se **borraron antes** de la corrida, así que su regeneración es
la prueba de que la tarea se ejecutó y no devolvió una caché:

```text
ErrorHandlingTest              2026-10-18T20:35:21Z  tests=10 skipped=0 failures=0 errors=0
UatLocal012ErrorHandlingTest   2026-10-18T20:35:21Z  tests= 8 skipped=1 failures=0 errors=0
UatLocal011WorkflowControlTest 2026-10-18T20:35:21Z  tests=15 skipped=1 failures=0 errors=0
UatLocal009TopStepsTest        2026-10-18T20:35:21Z  tests=13 skipped=1 failures=0 errors=0
```

Los `skipped` son filas `@Disabled(migrated-pending …)` preexistentes; no las
introduce este bloque.

### La confirmación por el arreglo

```text
base 37c9cb41   15 filas rojas  (ErrorHandlingTest 8 + UatLocal012 7)
tras el arreglo  0 filas rojas en las cuatro clases
```

Una fila que pasa a verde porque su causa —un fichero de credenciales en el `$HOME`
de otra persona— desapareció del camino es una fila cuya causa estaba **medida**, no
inferida. Ese es el cierre.

## 8. Lo que este recibo NO afirma

- Que el fallo sea «de la máquina» sin más: es una dependencia ambiental
  **medida**, con su condición de disparo escrita. Un `$HOME` sin
  `credentials.bin` no habría producido estas filas.
- Que las otras 28 clases estén sanas. No se han ejecutado. `NOT_RUN`.
- Que el comportamiento del `--tests` esté explicado. `NOT_RUN`.
- Nada sobre el producto: **no hay ninguna modificación de producción en este
  bloque**. Si el producto estuviera mal, este arreglo no lo habría tocado.

## 9. Cierre de la tarea de referencia (AGENTS.md)

```text
Implementación de referencia consultada:  ninguna aplicable — el defecto está en
                                          el harness, no en un Step.
Comportamiento adoptado:                 el almacén de credenciales se resuelve a
                                          una ruta ausente dentro del @TempDir, y se
                                          ejercita la rama "no existe -> null".
Desviaciones intencionadas:              ninguna.
Implicaciones de seguridad revisadas:     ninguna credencial real se lee, escribe ni
                                          expone; el arreglo reduce la dependencia
                                          ambiental del harness.
Pruebas que demuestran el contrato:      los cuatro XML canario de la sección 7
                                          (failures=0, errors=0, regenerados).
```