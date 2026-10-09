# S7 BLOCK 3.7 — UatLocal008CredentialsTest pasa a OwnedSubprocess, y aparece un hueco de ley

Rama `s6-plugin-sdk`. Base: `ee355a15` (S7 BLOCK 3.6).

```text
v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/UatLocal008CredentialsTest.kt
sha256 1f19de4acb3db8baafb515e0b76c0cbe0aae2ac513c7a2861420bd02385f44b6

v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/support/StrictCliRun.kt   (nuevo)
sha256 d7be05448d223aefb36582fe1ef9ce633fea54d36b9a9f796f386418b7f56feb
```

El hash de `UatLocal008CredentialsTest` que aparece en la seccion 10 es el de la version **anterior**
a la extraccion de `StrictCliRun` (capitulo 11): es el estado sobre el que se ejecuto la
mutacion. El gate de este bloque certify los bytes de aqui arriba.

---

## 1. POR QUE ESTE FICHERO Y POR QUE NO ESTABA EN NINGUN LIBRO MAYOR

Este arnes no aparecia en ninguna de las dos leyes:

```text
KNOWN_DEBT (23)          la ley se apoya en AppBinSupport; este fichero lanza MainKt
KNOWN_SANDBOX_LEAKS (25) desde 3.5 no escribe en /tmp
```

Es decir: **este bloque es proactivo**. Ninguna ley apuntaba aqui, y aun asi el fichero tenia la
forma que las leyes describen. Ese es el hallazgo de la seccion 8.

## 2. SEIS SITIOS DE LANZAMIENTO Y CUATRO FORMAS DE DEFECTO

```text
1. stdout a EOF, luego stderr            3 sitios   la forma invertida de 3.4
2. createTestKeystore: nadie drena NINGUNO de los dos tubos
3. waitFor(...) devolviendo un booleano descartado   4 sitios
4. IMP-001: waitFor() SIN plazo, y una lectura bloqueante a EOF antes
```

El segundo es el mas grave y no lo tiene el nombre de un defecto de tuberias. Redirect **ambos**
tubos a `PIPE`, arrancar keytool, llamar `waitFor(30, ...)` y tirar el booleano: no habia ningun
lector en ninguno de los dos. Y como el resultado se ignoraba, un keystore que no se crease se
descubria mas tarde, por la fila que casualmente necesitara el fichero.

## 3. LO QUE NO SE AFIRMA

El buffer de tuberia de esta maquina es de **8192 bytes** (medido en 3.4 con `F_GETPIPE_SZ`). Un hijo
que escribiera mas stderr que eso, mientras el padre espera un EOF de stdout, se bloquearia.

**No se midio que ninguna fila de esta clase llegue a ese caso.** Los pipelines de aqui emiten
cientos de bytes, no kilobytes. El riesgo era **latente, no activo**, y la migracion quita la FORMA,
no un fallo que se haya probado que ocurre. Decir otra cosa seria afirmar un mecanismo no medido.

## 4. UN SITIO QUE NO SE MIGRA POR LA MISMA RAZON, Y LA SEGUNDA ENTRADA

`gitCatFile` no puede pasar por la entrada que devuelve `String`. CP-001 afirma identidad de
**bytes**:

```kotlin
sha256(blob de git)  ==  sha256(Files.readAllBytes(fichero))
```

`CliRun.Completed.stdout` es un String, y el hash de un String es decodificar y recodificar: para
cualquier blob que no sea UTF-8 valido eso cambia el hash. La migracion habria cambiado en silencio
una afirmacion de BYTES por una de CARACTERES.

Asi que hay dos entradas y no una, y por eso **no comparten codigo**:

```text
StrictCliRun.text(...)               drena a String, NO exige exitCode == 0
StrictCliRun.bytesRequiringSuccess() drena a bytes, SI exige salida limpia
```

Unificarlas habria significado debilitar la asercion de bytes. El original ademas tenia la forma
invertida en su forma mas pura: `waitFor(10, SECONDS)` **antes** de `inputStream.readBytes()`. Un
blob mayor que el buffer habria bloqueado a git mientras el padre esperaba: un timeout sin
diagnostico y sin datos parciales.

Ambas viven en `support/StrictCliRun.kt`, junto a la primitiva que envuelven, porque las 29
migraciones que quedan en el capitulo 8 necesitan exactamente estas dos entradas.

## 5. UN HELPER QUE NO PASA POR EL ENVOLTORIO QUE AFIRMA

El barrido `pgrep` del `teardown` llama a `OwnedSubprocess.run` directamente, no a `runOwned`. El
teardown envuelve su cuerpo en `catch (_: Exception)`, y eso **no** captura el `AssertionError` que
lanza `runOwned`: pasarlo por el helper que afirma haria que un fallo de teardown escapara del
manejador que existe justo para contenerlo. El resultado se ignora a proposito: de un barrido no hay
nada que afirmar, solo algo que no se quede colgado.

## 6. EL CODIGO DE SALIDA NO SE EXIGE, Y POR QUE

Dos filas esperan `RunFinished.outcome == "failure"` a proposito:

```text
CR-BD-034 mismatched credential kind throws   (skip)
CR-BD-035 missing credential ID throws
```

Si la CLI tradujera eso a un proceso con codigo distinto de cero, exigir `exitCode == 0` las romperia.
**No se midio si lo hace en esta clase**, asi que anadir la asercion seria un cambio de
comportamiento justificado por una suposicion. Lo que los llamadores juzgan es el flujo de eventos
de stdout, y ese contrato no cambia.

## 7. PRESUPUESTOS DESDE LA LINEA BASE MEDIDA

Antes de tocar nada se ejecuto la clase entera para tener una linea base:

```text
27 tests, 0 fallos, 1 skip, 145 s de tiempo de fila, fila mas lenta 8,38 s
fila de 6 lanzamientos UAT-L8-CR-BD-017   5,81 s
```

Seis lanzamientos en 5,81 s dice que una ejecucion de pipeline aqui cuesta uno o dos segundos. Los
plazos salen de ahi, no de copiar los `waitFor` que reemplazan:

```text
PIPELINE_RUN   30 s     KEYTOOL_RUN 30 s     GIT_RUN 10 s     GREP_RUN 60 s     SWEEP_RUN 10 s
```

El `@Timeout(120)` de clase **no se toca** y TC-001 sigue afirmandolo. Es el limite exterior, no el
contrato del subproceso. En la fila de seis lanzamientos la suma de contratos (4x30 + 30 + 10 = 160 s)
queda nominalmente por encima de el, y eso solo es inocuo por lo que esta migracion cambia: disparar
el watchdog ahora interrumpe `OwnedSubprocess.run`, cuyo `finally` rekepa. Antes de este cambio la
misma interrupcion dejaba un JVM vivo. Se registra como hecho, no se suaviza.

## 8. HALLAZGO: EL MARCADOR DE LA LEY HERMANA ES MAS ESTRECHO QUE SU PROPIEDAD

La ley de la distribucion instalada se apoya en `AppBinSupport`. Su propio KDoc dice que la forma
letal es `Main.kt:431` escribiendo todo el log de eventos en un unico `println`. Un arnes que
bifurca el runtime por **classpath** en vez de por el binario instalado tiene la misma exposicion a
8192 bytes, y es invisible para la ley.

Medido sobre las fuentes de test versionadas:

```text
30 ficheros en HEAD que bifurcan + nombran MainKt + NO tienen OwnedSubprocess
 0 de esos 30 contienen AppBinSupport
```

Este fichero era uno de los 30. Despues de migrarlo son **29**, y la unica diferencia entre los dos
conjuntos es precisamente este fichero. (Un primer recuento dio 30 en el arbol de trabajo tambien:
estaba mal acotado, porque el `os.walk` recogia ficheros no versionados. La cifra que se publica es
la comparacion HEAD contra arbol sobre el mismo conjunto versionado.)

Eso no es una observacion para el proximo bloque: es **29 arneses con la forma letal, fuera del
alcance de la ley que dice protegerla**.

## 9. RESULTADO

```text
POST-MIGRACION   27 tests, 0 fallos, 0 errores, 1 skip
identico a la linea base
fugas /tmp seis patrones   0
supervivientes             MUERTO
```

## 10. NO-VACUIDAD

`PIPELINE_RUN` a 1 ms, una sola mutacion:

```text
22 de 27 filas ROJAS, 5 pasan
```

Las 5 que pasan son exactamente las que NO dependen de esa constante:

```text
TC-001  la anotacion @Timeout
TC-002  el sleep crudo que caracteriza el teardown
IMP-001  usa su propio GREP_RUN
CP-001   usa su propio GIT_RUN
CR-BD-034  la fila skip
```

El mensaje es el clasificado, con pid, comando y descendientes:

```text
AssertionError: pipeline with credentials store did not finish within 0s and was destroyed.
pid=256963 descendants=[]
command=/.../bin/java -cp ...
```

Y lo mas importante: **0 supervivientes en la corrida donde fallan 22 filas**. La garantia de
rekepa se cumple precisamente cuando el arnes va mal, que es cuando importa.

Restaurado byte a byte, sha256 de vuelta en
`689c81d0b0fd60e4a8fdc891c3d1a1c622a0e0297cc49d917dac6401ac043709`.

## 11. EL GATE ATRAJO A ESTE BLOQUE: detekt LargeClass

La primera corrida del gate completo fallo en **26 s**, con 4 XML de 780:

```text
e: ...UatLocal008CredentialsTest.kt:64:7 Class UatLocal008CredentialsTest is too large.
   Consider splitting it into smaller pieces. [LargeClass]
> Task :pipeline-application:detekt FAILED
```

Umbral en `v2/config/detekt/detekt.yml`: `LargeClass.allowedLines: 1200`. La migracion anadio ~36
lineas de codigo a una clase que ya estaba cerca, y la paso.

No se subio el umbral ni se silencio la regla, que es lo que habria sido facil. Se **extrajo la
fontaneria de subprocesos** a `support/StrictCliRun.kt`, que ademas es lo correcto por una razon que
no es de tamano: `runOwned`, sus presupuestos y los drenos de `gitCatFile` no son aserciones de
UAT, son fontaneria de arnes, y las 29 migraciones del capitulo 8 necesitan las mismas dos entradas.
La clase baja de 1630 a 1590 lineas y el gate vuelve a pasar.

Medir el «antes» con cuatro reglas distintas de conteo no coincidio con la regla real de detekt
(HEAD: 1141 sin comentarios y en blanco; ahora: 1177, ambos bajo 1200). No se publico un numero
inventado sobre cual era el conteo: lo que se sabe con certeza es que la reglaultural dispara con
estos bytes y no con los de HEAD.

## 12. GATE

Base `ee355a15`. Bytes certificados:

```text
UatLocal008CredentialsTest.kt  1f19de4acb3db8baafb515e0b76c0cbe0aae2ac513c7a2861420bd02385f44b6
support/StrictCliRun.kt        d7be05448d223aefb36582fe1ef9ce633fea54d36b9a9f796f386418b7f56feb
```

```text
argv     cd v2 && ./gradlew -p . --no-daemon --offline check --rerun-tasks
ventana  [1791428343, 1791430007]   1664 s
resultado BUILD SUCCESSFUL in 27m 44s
         320 actionable tasks: 320 executed
         780 XML / 5158 tests / 0 failures / 0 errors / 140 skipped
fugas    0 en los seis patrones, acotado por ventana Y sin acotar
supervivientes  MUERTO
```

780 XML y 5158 tests son los mismos del bloque 3.6: este bloque no anade ninguna fila, solo
sustituye como se lanza un hijo. `StrictCliRun.kt` no lleva `@Test`, asi que no produce XML por si
mismo, y la ley de `/tmp` sube su poblacion de 677 a 678 ficheros leidos sin que ninguna asercion
dependa de esa cifra.

Las tres clases tocadas, dentro de esa corrida:

```text
UatLocal008CredentialsTest                     tests=27 failures=0 errors=0 skipped=1
TestSandboxFitnessTest                         tests=1  failures=0 errors=0
InstalledDistributionHarnessFitnessTest        tests=1  failures=0 errors=0
```

### 12.1 La primera corrida del gate fallo, y por que se cuenta

```text
BUILD FAILED in 26s,  4 XML de 780
e: ...UatLocal008CredentialsTest.kt:64:7 Class UatLocal008CredentialsTest is too large. [LargeClass]
```

Esta seccion existe porque el fallo es la parte interesante del bloque: la migracion era correcta y
aun asi habia que arreglar algo. El arreglo no fue subir el umbral ni silenciar la regla, sino
extraer la fontaneria, que es lo que habria que hacer igual.

## 13. LO QUE QUEDA, MEDIDO

Los 29 de la seccion 8, con su exposicion contada sobre el codigo (sin comentarios):

```text
58 sitios ProcessBuilder en total
12 leen stdout a EOF antes de esperar      la forma invertida
 6 usan waitFor() sin plazo                 sin contrato y sin dueno
el mas expuesto: UatLocal007SandboxProfileTest  8 sitios, 4 invertidos, 5 sin plazo
```

Ninguno de ellos aparece en `KNOWN_DEBT`, porque la ley se apoya en `AppBinSupport` y ninguno lo
contiene. Corregir eso —que la ley mida la propiedad y no el marcador— es trabajo del bloque
siguiente, y probablemente empieza por lo que este bloqueurredejo: `StrictCliRun` ya esta escrito
para las 29.
