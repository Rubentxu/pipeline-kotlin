# S7 BLOCK 3.8 — la ley que mide la propiedad, no el marcador

Rama `s6-plugin-sdk`. Base: `3fd0c235` (S7 BLOCK 3.7).

El bloque 3.7 cerró migrando `UatLocal008CredentialsTest` por la primitiva y dejó una medición más
incómoda que el propio fallo: **la ley hermana protege una propiedad, pero su marcador es una sola
de las dos grafías de esa propiedad.** Treinta ficheros de HEAD bifurcaban el runtime y eran
invisibles para ella. Este bloque amplía la ley para que los vea y —que no se puede dar por
hecho— demuestra que la ampliación tiene dientes antes de creerla.

No migra ningún arnés. Igual que 3.6, ése es el punto.

---

## 1. QUÉ CIERRA ESTE BLOQUE

```text
v2/pipeline-application/src/test/kotlin/.../support/InstalledDistributionHarnessFitnessTest.kt
```

Una clase, un test, **tres** aserciones (antes dos). El ledger pasa de 23 entradas a **53**.

## 2. LA PROPIEDAD Y SUS DOS PUERTAS

La propiedad es: *un arnés que bifurca el runtime de PipelineK debe pasar por `OwnedSubprocess`*.
El defecto es el pipe: `Main.kt:431` escribe el log de eventos entero de una corrida en un solo
`println`, así que cualquier corrida puede rebasar el buffer del pipe, y un arnés que espera antes
de drenar cuelga para siempre dejando el hijo vivo.

La ley **medía** esa propiedad por un solo camino:

```kotlin
code.contains("AppBinSupport")     // "este fichero lanza el binario INSTALADO"
```

Pero el mismo `Main.kt:431` se puede lanzar por su clase principal en el classpath de test, sin
`AppBinSupport` en el fichero. Mismo hijo, misma exposición de 8192 bytes, ley ciega. Medido sobre
las fuentes versionadas:

```text
puerta A  AppBinSupport            23 ficheros
puerta B  MainKt                   30 ficheros
A ∩ B                              0 ficheros     <-- intersección CERO
A ∪ B                             53 ficheros
```

La intersección cero es lo que hace que el total sea 53 y no "23, algunos por duplicado". También
es la razón de que `UatLocal008CredentialsTest` no hubiera saltado nunca por esta ley: se lanza
por `MainKt`.

## 3. LO QUE HIZO FALAR LA MEDICIÓN

### 3.1 Una ventana por sitio de lanzamiento se rechazó, y el motivo importa

La objeción obvia es que la ley decide por fichero y no sabe cuál de sus varios lanzamientos es el
del runtime. `UatLocal008CredentialsTest` es el caso vivo: sus lanzamientos del runtime ya pasan
por `StrictCliRun`, y lo único que le queda es el `sleep` que `TC-002` arranca a propósito, porque
esa fila existe para caracterizar el barrido de teardown.

Se midió una regla por sitio: *el lanzamiento cuenta si la puerta aparece en las N líneas
alrededor*. Cada ventana deja pasar infractores reales:

```text
ventana    sujeto    infractores reales que deja pasar
   6 lín      30              20
  20 lín      47               3
  40 lín      50               0
```

A 20 líneas se cae `UatS2R0RunOwnershipCliTest` y `UatStep004SleepTimingTest`, y a 6 líneas se caen
veinte. La respuesta además **depende del parámetro**, así que ningún valor es el correcto: 40
líneas es el único que no pierde ninguno, y es exactamente el ajuste que convierte cualquier ley en
una ley cuyo veredicto se elige con un dial. Se rechazó: la granularidad sigue siendo el fichero, y el
ledger lo declara en vez de fingir precisión por sitio.

### 3.2 El stripper quita un falso positivo y no ciega nada

Con texto crudo el sujeto ampliado daba **54** ficheros. El sobrante era
`WcScmE2EBothPluginsIntegrationTest`, que nombra el runtime sólo en prosa. Sin quitar comentarios,
la ley convicta la documentación de su propio arreglo — el mismo fallo que ya había mordido a la ley
de `/tmp` en 3.6.

Lo que había que comprobar era lo otro, y es la pregunta que separa "evita falsos positivos" de
"es ciego":

```text
sujeto con texto crudo             54
sujeto sin comentarios             53
convictados sólo por comentario      1   (el falso positivo)
PERDIDOS por el strip                0   <-- ningún infractor real se pierde
```

Un stripper que pierde sujetos cambia un falso positivo por un agujero. Este no.

## 4. NO-VACUIDAD: TRES MUTACIONES, UNA POR ASERCIÓN

Un detector ampliado es la cosa más fácil de poner verde que hay en un fitness suite. Las tres
direcciones se ejecutaron **contra la ley real**, no contra un modelo de ella.

### M1 — la segunda puerta desaparece → RED

Se borró `|| RUNTIME_MAIN_CLASS.containsMatchIn(code)` del predicado.

```text
BUILD FAILED in 8s
tests="1" skipped="0" failures="1" errors="0"
```

El mensaje es el de la aserción de deuda **caduca**, y nombra las 30 entradas de la puerta B que de
pronto nadie migra. Una aserción que sólo va en una dirección no distingue "todo migrado" de "ley
ciega"; por eso la segunda existe desde el principio y por eso este mutante la dispara.

### M2 — un infractor nuevo por la puerta que antes era invisible → RED

Se creó `M5DoorBProbe.kt`: un fichero que lanza `MainKt` con `ProcessBuilder` crudo y nada más. Sin
`AppBinSupport`, sin `OwnedSubprocess`, sin ninguna otra marca.

```text
BUILD FAILED in 8s
these files fork the PipelineK runtime with a raw ProcessBuilder and bypass OwnedSubprocess:
  - dev/rubentxu/pipeline/v2/application/M5DoorBProbe.kt
```

Éste es el resultado que la ampliación venía a conseguir y que la ley anterior no daba. Fichero
borrado después.

### M3 — la segunda puerta presente pero ciega → RED (y ésa faltaba)

Las dos anteriores demuestran que la ampliación *mata*. No demuestran que sobre **vivir**. Un
`Regex("\\bNoSuchRuntimeSymbol\\b")` deja las aserciones 1 y 2 intactas: los 30 ficheros salen del
sujeto, el ledger los marca caducos, y el rojo llega por el motivo equivocado o por ninguno.

Por eso este bloque añade la **tercera** aserción:

```text
only 0 harnesses fork the runtime by MainKt, below the measured floor of 3:
this law has stopped seeing the second door, so its green no longer means what it says.
```

Verde restaurado por hash tras cada mutación:

```text
c2e2737a54283734272832ce1f96222561807da7cdd72f692b9f2b6e6c35c935   (tras M1/M2)
67cd88c0eaf3836d6d719f5a2600d161ca718b66ef3f393ae4fe5956d9bb0392   (tras M3, con la 3ª aserción)
```

### El suelo es 3, y no 30

El suelo medido es 30, y se puso en 3 a propósito.

Ponerlo en 30 lo convierte en un contador de migraciones y **mata la ley en la primera migración**:
migrar `UatLocal004TimeoutTest` lleva el sujeto a 29 y convierte un verde truthful en un fallo que
pide que el ledger mienta. `UatLocal008CredentialsTest` es la prueba viva: ya está migrada y sigue
en el sujeto sólo por su `sleep` de `TC-002`.

Un suelo debe estar donde **no se puede alcanzar haciendo el trabajo que la ley exige**, y debe
detectar el narrowing real: un detector que pierde media segunda puerta cae a 15, muy por debajo de
30 y muy por encima de 3. Por debajo de 3, un regex roto y un módulo sobre-migrado se vuelven
indistinguibles.

## 5. LO QUE ESTA LEY NO HACE, DICHO

Tres cosas que el tipo `Set<String>` no puede llevar, y que no se inventan:

1. **Razón por entrada.** El mensaje de fallo exige "la razón por la que aún no se puede migrar", y
   el tipo no tiene dónde ponerla. Las 23 preexistentes nunca tuvieron razón registrada; inventar 23
   verosímiles ahora sería fabricación, así que quedan sin razón y se nombra el hueco.
2. **Precisión por sitio.** Medida y rechazada (3.1). El fichero es la granularidad, y el ledger lo
   declara.
3. **Distinción por puerta en el ledger.** Las 53 entradas son una lista plana: 23 por puerta A, 30
   por puerta B, con intersección cero medida. El código, sin embargo, no lo afirma en ningún sitio
   salvo por el suelo, que es una cota baja a propósito.

## 6. VERIFICACIÓN

```text
:pipeline-application:test --tests InstalledDistributionHarnessFitnessTest
  BUILD SUCCESSFUL in 10s
  tests="1" skipped="0" failures="0" errors="0"      (canario borrado y regenerado)

:pipeline-application:test --tests TestSandboxFitnessTest      (ley hermana del /tmp)
  tests="1" skipped="0" failures="0" errors="0"

:pipeline-application:detekt
  BUILD SUCCESSFUL in 12s
```

Detekt corre por una razón concreta: en 3.7 este mismo gate mató el bloque con `LargeClass` al
pasar la clase de 1487 a 1631 líneas. El fichero actual está en **339 líneas**; creció sin acercarse
al umbral de 1200 porque lo que 설명했다 el motivo, no la lógica de decisión.

Alcance del impacto: el único consumidor de este fichero es el propio detector.
`BannedImportsGateTest` y `RetentionAuthorityFitnessTest` barren `src/main`, no `src/test`, así que
no les afecta la ampliación.

## 7. LO QUE QUEDA, CON SU EXPOSICIÓN CONTADA

La medición del handoff de 3.7 sigue vigente y no se ha tocado en este bloque:

```text
58 sitios en total
12 leen stdout a EOF antes de esperar
 6 usan waitFor() sin plazo
peor: UatLocal007SandboxProfileTest — 8 sitios, 4 invertidos, 5 sin contrato
```

Migrar los 53 a mano **no** era el plan de este bloque, y sin esta ampliación habría sido el error:
cada uno sería el número 30 que nadie ve. Con la ley ya midiendo la propiedad, cada migración
decrece un número que el gate verifica en ambos sentidos.

## 8. CIERRE DE LEY (end-of-work-unit)

```text
Reference implementation consulted:  ninguno aplicable — no es un Step, es una ley de fitness
Behaviour adopted:                  la ley mide "bifurca el runtime por cualquiera de sus dos
                                    puertas", no "este fichero menciona AppBinSupport"
Intentional deviations:             granularidad por fichero en vez de por sitio de lanzamiento,
                                    porque la versión por sitio dejaba pasar infractores reales en
                                    toda ventana por debajo de 40
Security implications reviewed:     n/a — sin I/O, sin procesos, sin red; sólo lectura de texto
Tests demonstrating the contract:   el propio fichero, 3 aserciones y 3 mutaciones RED
                                    (M1 puerta B fuera, M2 infractor nuevo, M3 puerta B ciega)
```