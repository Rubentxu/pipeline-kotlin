# S7 AUDIT REVIEW — los 11 hallazgos del informe contrastados contra el código

Rama `s6-plugin-sdk`. Base: `a84832c6` (S7 BLOCK 3.8).

Un informe de auditoría llegó sobre `main` en `b66bf7c` con once hallazgos (AUD-01..AUD-11). Este
recibo no reescribe el informe: lo **contrasta contra el código de producción**, porque un hallazgo
verificado es deuda con dueño, y uno sin verificar es una hipótesis sin comprobar.

Cada fila dice lo que el código muestra, no lo que el informe recomienda.

---

## 0. LA LIMITACIÓN QUE VA POR DELANTE

El informe se hizo sobre `main` en `b66bf7c`, sin acceso a red, sin checkout local y sin poder
ejecutar la suite. No ve los **29 commits sin publicar** de la rama `s6-plugin-sdk`, así que:

- su recuento de "24 commits mencionados en el último informe del agente" **no es verificable**;
- sus cifras de test (5.078 / 0 fallos / 140 omitidos) son evidencia del recibo de S5 para
  `880a5287`, **no de HEAD**;
- "20 módulos" lo corrigió él mismo a 27; el conteo válido es el de `v2/settings.gradle.kts`.

Verificación propia disponible en esta rama: `:pipeline-application:check`, BUILD SUCCESSFUL in
26m 39s, 331 clases XML, 2449 tests, 0 fallos, 0 errores, 121 omitidos.

## 1. CLASIFICACIÓN DE LOS ONCE

```text
ID      ¿existe el código?   ¿es alcanzable?    dueño / veredicto
------  -------------------   -----------------   ---------------------------
AUD-01  sí, literal          sí                 P1 nuevo. El más serio.
AUD-02  sí, literal          NO por el CLI      deuda real, severidad mal puesta
AUD-03  sí, literal          sí                 P1 CON dueño y ley, no nuevo
AUD-04  sí, literal          sí                 P2 nuevo, pequeño
AUD-05  sí, literal          sí                 contrato por diseño, no defecto
AUD-06  sí, literal          sí                 deuda reconocida en el propio KDoc
AUD-07  sí, literal          sí                 deuda de tipado, pequeña y local
AUD-08  no verificado        sin medir          plausible, no se ejecutó nada
AUD-09  sí, declarado        por diseño         governance ya lo reconoce
AUD-10  sí, declarado        por política        contradicción ya resuelta antes
AUD-11  no verificado        —                  deriva documental, sin medir
```

## 2. AUD-01 — P1 NUEVO, Y EL MÁS SERIO

`v2/pipeline-step-sdk/http/build.gradle.kts:88`, textual:

```kotlin
commandLine = listOf("sh", "-c", "sha256sum $all | sha256sum | awk '{print $1}' > '...'")
```

`$all` se construye con rutas absolutas, y la salida de `sha256sum` incluye el nombre del fichero.
Dos checkouts de los mismos bytes producen digests distintos. Además la expansión es sin quoting por
fichero, así que un espacio en el path altera el cálculo.

Por qué es el primero, y la auditoría no da esta razón: **es la identidad de los artefactos que
certificamos**. Si el digest depende de la ruta del checkout, el recibo que liga candidato y SHA
pierde su fuerza probatoria, y cualquier otro checksum de la cadena queda decorativo por debajo.
El resto de B0 se apoya en esto.

## 3. AUD-02 — EL CÓDIGO LO DICE, PERO EL CLI NUNCA LLEGA

El informe acierta en el texto. `ShExecution.executeNonDurableInvocation` tiene exactamente lo que
describe:

```kotlin
timeoutMs = null,                                  // el presupuesto no se traslada
val stdoutBuilder = StringBuilder()                // O(salida total), no O(chunk)
val stderrBuilder = StringBuilder()
```

Y no está ausente: es alcanzable desde `controlDirRoot == null`. Lo que **no** es alcanzable es por
el camino del producto. `Main.kt:271` y `Main.kt:492`:

```kotlin
val controlDirRoot: Path = if (config.controlRoot != null) { … } else {
    java.nio.file.Files.createTempDirectory("pipelinek-inmem-run")
}
```

El tipo declarado es `Path`, **no nulable**, y ambas ramas producen un directorio real. Y el
adaptador de producción (`CanonicalRuntimeCapabilityAccess.kt:203`) lo toma de
`context.controlDirRoot`, no de una decisión del llamante.

Veredicto: la deuda existe y el arreglo que propone la auditoría es el correcto (alcanzar la misma
garantía, o rechazar lo que esa ruta no cumple). Lo que cambia es la prioridad: **no es una pérdida
de garantía alcanzable por el CLI**, sino una ruta que sólo se activa por composición de test o
plataforma no-Linux. Sigue siendo P1 de cobertura —porque en ese caso sí pierde el timeout—, pero no
es el P1 que el informe anuncia.

## 4. AUD-03 — CONFIRMADO, PERO NO ES DEUDA NUEVA

`CoreWaitUntilStep.kt:65-70` es literalmente lo descrito:

```kotlin
val resultOutcome: String, // "completed" or "deadline-exceeded"
get() = if (resultOutcome == "completed") StepOutcome.Success else …
```

El informe recomienda cerrar un ADT tipado. **Ese ADT ya existe**: `WaitUntilCompletion` es un
`sealed interface` en `pipeline-domain`, y ya proyecta las tres cosas.

Lo que el informe no vio es que la decisión ya está escrita y protegida:
`FArchE4b4WaitUntilTerminalAuthorityTest` **allowlisteó esa lectura por fichero y línea** con la
razón escrita, la asigna a S7, y falla si aparece una segunda lectura en ese fichero. Su KDoc
explica por qué la línea no se arregla aquí: `WaitUntilOutput.resultOutcome` es superficie de
scripting publicada, y reemplazarla es un cambio de contrato de Step con su propio gate.

Veredicto: el hallazgo es cierto y el trabajo es el mismo. No es un P1 nuevo; es deuda con dueño,
con ley y con motivo. Tratarlo como descubrimiento sería duplicar un trabajo ya gobernado.

## 5. AUD-04 — CONFIRMADO, Y ES LO MÁS BARATO DE ARREGLAR

```text
MainEventsCli.kt:90    else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]
MainConsoleCli.kt:141  "--max-bytes" -> args.getOrNull(++i)?.toIntOrNull() ?: DEFAULT_PAGE_BYTES
```

Una opción desconocida se ignora en silencio; un `--max-bytes` no convertible vuelve al default.
Ironía útil: el fichero de consola **sí** rechaza `maxBytes <= 0` explícitamente en la línea 166,
así que el trato es incoherente dentro del mismo archivo.

## 6. AUD-05 — CONFIRMADO LITERALMENTE, Y ES CONTRATO

`MainEventsCli.kt:153-166` — la auditoría acierta en que `return 0` sigue a las dos ramas:

```kotlin
is EventPageDrain.Outcome.Stalled -> {
    // Answering as if history ended would be a silent truncation, so it is named on the wire.
    … System.err.println("evt-stalled-v1:$runId:… history past this point was NOT read")
}
return 0
```

Discrepo de la causa. El código explica la decisión en el sitio, y la UAT fija el contrato:
`EventPageDrainTest` afirma que `Stalled` trae `hasMore` y que la página no se reclama completa
(`"the store did claim more rows, so claiming completeness would be false"`). Es diseño: el
diagnóstico viaja como token estructurado por stderr, para que un cliente pueda distinguir
"terminó" de "no sé".

Lo que sí es cierto, y es la parte útil del hallazgo: **un cliente que sólo mira el exit code se
engaña**. Eso no se arregla cambiando el contrato de `Stalled`, sino declarando el contrato del
exit code —que hoy no existe— y documentándolo. La auditoría acierta en el riesgo y discrepa
en el remedio.

## 7. AUD-06 Y AUD-07 — CONFIRMADOS, Y UNO YA ESTÁ DOCUMENTADO

`BodyExecutionEngine.kt` mezcla dos relojes: `clock.now()` en las líneas 111 y 168 (durable) y
`Instant.now()` en diez sitios más. Y el propio KDoc lo declara:

```kotlin
* including its quirks (DirEntered timestamps via Instant.now() while
* TimeoutScheduled uses the durable clock).
```

Es deuda reconocida, no hallazgo. La separación que la auditoría pide —reloj de negocio
reproducible frente a reloj de medición— es la correcta; el trabajo es ejecutarla, no decidirla.

AUD-07 es literal: `BodyExecutionEngine.kt:113-118` clasifica con un `else -> BoundPurpose.API_KEY`.
Un tipo de credencial desconocido deja una observación de auditoría mentirosa. Local y pequeño: un
ADT exhaustivo con el codec rechazando tokens desconocidos.

## 8. LO NO VERIFICADO, DICHO COMO NO VERIFICADO

```text
AUD-08  cache de OutputPlaneProvider  — plausible (riesgo de proceso persistente). NO medido:
                                        no se ejecutó nada ni se inspeccionó el ciclo de vida.
AUD-11  deriva documental             — no revisada. CURRENT_STATE.md y v2/README.md se citan
                                        como desactualizados; no se comprobató ninguna de las dos.
```

Ninguno de los dos se cuenta como confirmado en este recibo. La clasificación los marca
`no verificado` a propósito, porque un hallazgo que no se comprobó no puede entrar en un plan como
si lo estuviera.

## 9. CÓMO SE ORDENA, Y POR QUÉ DISCREPO DEL INFORME

El informe propone B0 (identidad + admisión) antes de B1 (semántica). **Coincido, y por una razón
que el informe no da:** AUD-01 no es ripeado, es la cadena de confianza de todo lo demás. Con
digest dependiente de la ruta, un recibo por SHA no prueba lo que dice probar.

Donde discrepo del severizado, en tres puntos concretos:

```text
AUD-03  de P1 nuevo  ->  deuda con dueño y ley (FArchE4b4…); no abrir trabajo paralelo
AUD-02  de P1 alcanzable -> P1 de cobertura; el CLI nunca entra en esa ruta
AUD-05  de defecto   ->  contrato con exit code sin declarar; el remedio es declararlo
```

Y una coincidencia: **AUD-10 no es un descubrimiento**. La política que retiró las workflows de CI
(`754ddda0`) y el `PRODUCT-GATE` que exige check remoto se contradicen desde antes de este informe, y
la política ya lo registra como `BLOCKED_EXTERNAL`. Rebajarlo sería fabricar un verde, y el propio
informe lo dice. La salida es una autoridad de certificación nueva, que es lo que ya hace
`pipelinek-release-harness`.

## 10. LO QUE ESTE RECIBO NO HACE

```text
- No reescribe el informe: lo contrasta y dice dónde discrepa.
- No cierra ningún hallazgo. AUD-01 sigue abierto y es el primero.
- No certifica ningún SHA: el gate ejecutado es de :pipeline-application, no el PRODUCT-GATE.
- No afirma nada sobre AUD-08 ni AUD-11: no se midieron.
```