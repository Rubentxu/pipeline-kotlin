# Diagnóstico: `ObsBJvmDeathOutputRecoveryUatTest` — no hay defecto de producto

**Estado:** CLOSED como señal de producto. La fila sigue roja por una premisa obsoleta en el propio test.
**Base:** `5887065a` (main).
**Origen:** nota diagnóstica del `releaseGate` de `v0.61.0-rc2`, sección "Prioridad alta, seguridad".

Este documento cierra una investigación, no una fila. La fila sigue fallando; lo que cambia es que
ya se sabe **por qué**, y que la respuesta no es "el plano de salida pierde bytes".

---

## 1. La hipótesis que se descartó

La nota propone cambiar el seam de lectura del test de `OutputPlaneProvider.storeFor()` a
`storeForReading()`, porque `storeFor()` construye la tienda con recuperación habilitada mientras el
productor sigue vivo, y sospechaba que `reconcile()` fabricaba visibilidad.

**El cambio se probó y se revirtió.** Mover el seam no resolvió nada: movió el síntoma. La aserción
que fallaba pasó de "no ve el marcador sanitizado" a "no output was ever committed while the step
ran", que es una aserción anterior en el flujo. Cambiar el síntoma no es resolverlo.

La sospecha era además incorrecta en su premisa, y esto se verificó leyendo el código:

- `SegmentOutputStore.read` sirve `[cursor.committedOffset, min(cursor + max, committedLocked))`
  (`:320-326`), donde `committedLocked` es el fichero `cur.cmt` (`:727-730`).
  **La recuperación no está en el camino de lectura.**
- `reconcile()` solo trunca bytes *más allá* del commit (`:704-707`) y se niega a recortar un registro
  de commit por delante del payload, reportando `bytesUnbacked` en su lugar (`:708-717`).

Un lector estricto y un lector con recuperación leen el **mismo** `cur.cmt`. La semántica del seam no
explica nada del fallo.

## 2. El camino real de commit

La ruta de commit de esta fila **no es el agente de ingesta**. Es el pump in-JVM:

```text
ObsBJvmDeathProducer  ShellReturnMode.NONE      → captureStdout = false (ShExecution.kt:268)
                      pumpedViaAgent = false    → DurableShConfig por defecto (DurableShConfig.kt:154)
                      DurableShellExecutor.kt:681 pumpedThroughJvm = true
                      → canales como PIPEs independientes (:736-741)
                      → startTranscriptPumps, un hilo por canal (:817)
                      → redacted.read(window,0,window.size) → guarded.write(...) (:905-943)
                      → reserve → write → commit → appendFrame (RedactingOutputIngress.kt:92-105)
```

Los dos únicos llamantes que activan `pumpedViaAgent = true` son productores de test. La producción
usa el pump in-JVM, y ese pump está vivo en main. **El test no ejercita un camino que producción haya
reemplazado.**

## 3. La premisa que sí está muerta

`419f9192` elevó `TRANSCRIPT_LIVE_WINDOW_BYTES` de 1024 a 65536 (`ProcessOutputSink.kt:302`). Ese
commit reescribió los dos certificadores hermanos cuyas premisas se rompieron —
`ObsBLiveOutputIngressTest` y `ObsC23ChannelSeparationUatTest` — **sin tocar este**.

El comentario de la fila (líneas 150-152) sigue diciendo:

```text
// 400 matched lines is comfortably past the 1 KiB live window
```

400 líneas son ~13.6 KB, muy por debajo de una ventana de 64 KiB. **La premisa que justificaba el
tamaño del payload dejó de ser cierta en el mismo commit que la invalidó**, y la fila siguió
midiendo el caso que el cambio ya no representa.

## 4. La hipótesis que se investigó y se refutó

Durante esta investigación se planteó que el fallo revelara un defecto real en `StreamingRedactor`:
`drainOneHead` (`:278-324`) no tiene rama de emisión de bytes planos, de modo que el anillo se
llena hasta `maxLiteral` y la única salida es `relieveFullRing` (`:258-262`), que está **anidada
dentro** de `if (inputOffset < inputLimit)` (`:452-476`). Si el anillo está lleno y no queda entrada,
el bloque entero se salta y ningún brazo avanza `written`: un spin al 100 % de CPU devolviendo cero
bytes.

La premisa del código es cierta. **El spin es inalcanzable**, y se verificó así:

- `StreamingRedactor.kt:441` refilla desde la fuente siempre que `inputOffset >= inputLimit &&
  !sourceExhausted`. El contrato del JDK garantiza que `InputStream.read(byte[],off,len)` con
  `len > 0` devuelve **≥1 o −1, nunca 0**, así que al salir del refill o bien `inputLimit == -1`
  (→ `finish` → drenaje) o bien `inputOffset == 0 < inputLimit` (→ entra al bloque).
- 60.000 casos aleatorios (registro aleatorio, secreto en offset aleatorio, `chunkSize` 1-64,
  `readSize` 1-256, troceado de fuente 1-200) sobre una transcripción instrumentada a Java:
  **0 entradas en el estado alegado, 0 lecturas de 0 bytes, 0 iteraciones sin progreso, 0 fugas de
  secreto.**
- Filas dirigidas a la forma "último chunk" (payload `maxLiteral`−1, `maxLiteral`, `maxLiteral`+1,
  2×`maxLiteral` × fuente 1/7/26/27/28/8192): la rama `else` se entró **0 veces**, byte a byte
  exacto.
- Medido sobre fuentes reales de producción (`Process.inputStream`, `PipedInputStream`,
  `FileInputStream`/`BufferedInputStream`): **cero devoluciones de 0**.

Además, `git log 7c0d0fba..HEAD -- '*StreamingRedactor.kt'` está vacío: el fichero es
byte-idéntico a `7c0d0fba`, ese commit es el que añadió `:460-475`, y su mensaje describe
explícitamente este spin como "THE SPIN — the whole bug", desbloqueando REDACTOR-2/3 en el mismo
commit.

## 5. Lo que sí queda, y con qué severidad

El invariante que evita el spin **no es del redactor**: es un contrato del JDK que el redactor no
posee. `relieveFullRing` queda atrapada dentro de `if (inputOffset < inputLimit)`, así que la única
vía de escape del anillo lleno desaparece en el instante en que una fuente devuelve 0. Los dos
consumidores (`DurableShellExecutor.kt:927`, `OutputWritePorts.kt:902`) no tienen guarda `n == 0` y
entrarían en spin.

**Severidad: baja, y es hardening, no un bug de producto.** Requiere un `InputStream` que viole el
contrato del JDK, y no existe ninguna subclase así bajo `v2/*/src/main`. Se registra como deuda
técnica: elevar el invariante de local a heredado (hoistear la comprobación de anillo lleno fuera del
bloque `:452`, o una guarda `n == 0` en el pump) convierte la seguridad en algo que el redactor
controla en vez de algo que depende de una garantía ajena.

## 6. Clasificación final

| Afirmación | Veredicto |
|---|---|
| La recuperación fabrica visibilidad que un lector estricto no ve | **Falsa.** Ambos leen `cur.cmt`; la recuperación no está en el camino de lectura. |
| El plano de salida pierde bytes mientras el productor corre | **Sin evidencia.** La fila no llega a medir eso; falla antes, en su premisa. |
| Hay un spin del redactor | **Refutada.** Inalcanzable bajo el contrato del JDK; 0 casos en 60.000. |
| La fila mide el caso que su comentario dice medir | **Falsa.** La ventana viva pasó de 1 KiB a 64 KiB en `419f9192` y la fila no se actualizó. |

**La fila no se corrige aquí.** Actualizarla exige decidir cuál es el caso que OBS-B quiere certificar
con la ventana de 64 KiB, y eso es una decisión de diseño de la UAT, no un arreglo de seam. Se
propone como WU propio. Lo que este documento cierra es la pregunta que la nota dejó abierta: **no
hay defecto de producto detrás de este rojo.**

## 7. Lo que la nota acertó y lo que no

- Acertó en que el resultado «no basta para afirmar un bug ni para descartarlo». La investigación
  confirma que la duda era legítima y la descarta con evidencia.
- No acertó al suggestivear `storeForReading()` como corrección: mueve el síntoma y no toca la causa.
- Su recomendación de verificar si el marcador seguía ausente «tratándolo como defecto del plano de
  salida y de seguridad» era una hipótesis razonable que la verificación refutó. Se llegó a ella sin
  el dato que la desmintía: que el fallo ocurre **antes** de la lectura del marcador, en la premisa
  del payload.