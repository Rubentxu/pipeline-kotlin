# B1 — cierre del bloque: hardening de runtime y convergencia semántica

**Rama:** `s6-plugin-sdk` · **Fecha:** 2026-10-08
**Rebanadas:** B1a (94de1e43), B1b (03b98b62), B1c (5aadf3f1), B1d (d1869963), y este cierre.
**Base de las mediciones:** `B1A_CHARACTERIZATION_RECEIPT.md`. Cada arreglo responde a un número medido allí, no a una lectura.

---

## 1. Gate de bloque (L4/L5) — ejecutado una vez, sobre el árbol exacto

```bash
cd v2 && timeout 1800 ./gradlew check        # presupuesto = techo de la regla 4
```

```text
BUILD SUCCESSFUL in 24m 41s
322 actionable tasks: 63 executed, 1 from cache, 258 up-to-date
tareas fallidas: ninguna
```

**Verdad de resultados desde los XML** (regla 25: el XML es la verdad, no la consola):

```text
clases XML: 790      tests: 5195      failures: 0      errors: 0      skipped: 140
```

Los 140 omitidos se declaran, no se cuentan como PASS. No se localizó ningún XML con fallos o errores.

---

## 2. Lo que B1 cierra

| hallazgo | disposición | evidencia |
|---|---|---|
| **AUD-02 (c)** presupuesto de timeout perdido | **CERRADO** | se propaga; negativo rechazado explícito; vencimiento = `Interrupted(TIMEOUT)` como el brazo durable. M1 rojo en filas (c) y (e) |
| **AUD-02 (d)** cancelación mapeada a `Failed(INFRASTRUCTURE)` | **CERRADO** | `CancellationException` se relanza (PAR-D) |
| **AUD-02** fuga del tmpdir propio | **CERRADO** | borrado determinista en `finally`, sólo del directorio que la ruta creó |
| **AUD-04** opciones ignoradas / valor ilegal al default | **CERRADO** | `events` rechaza opción desconocida y posicional de más; `console` rechaza `--max-bytes` ilegal. Mutantes M1/M2 rojos |
| **AUD-05** exit codes sin contrato | **CERRADO** | `CLI_OBSERVABILITY_SPEC.md` §12 + `exitCodeFor(Outcome)` total. Ningún código existente cambia |
| **AUD-06** dos relojes en `BodyExecutionEngine` | **PARCIAL, con dueño** | los 5 sitios de productor único usan el `Clock` inyectado; los 5 compartidos con `RetryEngine`/`WaitUntilEngine` quedan fuera con fila guarda (§4) |
| **AUD-07** segunda autoridad `kind`→`BoundPurpose` que mentía | **CERRADO** | una autoridad total en dominio; `file`/`certificate`/`zip`/`usernameColonPassword` dejan de reportarse como `API_KEY`. M2 rojo |
| **AUD-08** keying de locks | **CERRADO** | clave canónica `safe(stream.value)`; liberar reutiliza el lock del escritor. Mutación propia roja en `OutputPruneLockKeyingTest` |
| **AUD-08** retención del plano (`ExplicitReleaseOnly`) | **NO ES DEFECTO, documentado** | decisión deliberada del producto: el `console --control-dir` lee runs ya terminadas. La acumulación en disco es su consecuencia declarada, medida (80 ficheros / 40 runs) |
| **AUD-03** `WaitUntilOutput.resultOutcome` | **CON DUEÑO Y LEY** | `FArchE4b4WaitUntilTerminalAuthorityTest` allowlisteó la lectura por fichero y línea; es superficie de scripting publicada y su reemplazo tiene su propio gate |

---

## 3. La ley de arquitectura que el gate cazó (y por qué se refinó, no se borró)

La primera pasada de `check` **falló** en `FArch011V2NoCompileExcludesTest`, y el fallo era real y mío:
`findExcludeCalls` prohibía el token `exclude(` en cualquier build file, y B0.2 introdujo tres
`inputs.files(fileTree(...) { exclude(...) })` legítimos — declarar **inputs** de una tarea, no ocultar
código al compilador.

La ley no se debilitó: se hizo **precisa en ambos sentidos**, con fixture para cada dirección.

```text
PERMITIDO  fileTree(...) { exclude(...) }        es un filtro de patrones/inputs
CAZADO     KotlinCompile { exclude(...) }        la violación original
CAZADO     sourceSets { setExcludes(...) }       LA MISMA exclusión con el setter de Gradle, que un
                                                 escaneo de `exclude(` a secas no vería
CAZADO     filtro fileTree multilínea            sobra a propósito (ver KDoc del escáner)
```

Cobertura nueva verificada con mutación propia: quitando `setExcludes(` de la lista de tokens, cae
exactamente el fixture que lo cubre. `SourceScanner.kt` restaurado a `950d5b14…`, idéntico al medido.

Sobre-firar en el caso multilínea es deliberado: un lookback lo bastante ancho para permitirlo también
permitiría una exclusión de compilación colgando de una línea `fileTree`.

---

## 4. Fila guarda del grupo ambiguo (AUD-06)

`B1dBodyExecutionEngineClockSeparationTest` fila 5 afirma que `waitUntil`/`retry` **siguen** leyendo el
reloj de pared. No es una corrección: es la guarda que impide que alguien unifique la familia a medias
en este fichero y deje dos rutas del mismo Step con fuentes de tiempo distintas. El mutante M4 del
recibo de B1d demuestra que la fila no es decorativa.

Unificar la familia completa (`RetryEngine`, `WaitUntilEngine`, `ParallelStageEngine`,
`StepDispatchEngine`, emisores de Steps) es un WorkItem más ancho, con su propio gate.

---

## 5. Deuda que B1 deja dicha, con severidad y causa

```text
P2  El transcript de la ruta sh no durable sigue siendo O(salida) en heap (B1a lo midió: 17,0 MiB
    vivos para 16 MiB de payload). Acotarlo exige streaming acotado o rechazar la modalidad: es un
    cambio de comportamiento con su propio gate, no un arreglo de paso.
P2  `pipelinek console` no rechaza opciones desconocidas ni posicionales de más (B1c arregló solo
    `--max-bytes`). `--limit` sin valor al final sigue cayendo al default en silencio.
P2  La familia completa de relojes sigue mezclada fuera de `BodyExecutionEngine` (AUD-06 resto).
P3  `OutputPlaneProvider.forget`/`forgetAll` siguen sin llamante de producción (seam de test/restart).
P3  `UatLocal008SshPrivateKeyRoundGateTest` sale entero SKIPPED: no demuestra nada y no se cuenta.
P0  B0-F1: la release estable vive fuera de `main` (PR #99). Requiere autorización.
P0  B0-F2: `main` sin checks requeridos ni superficie que los produzca. Requiere autorización.
```

---

## 6. Lo que este cierre NO hace

```text
- No declara el PRODUCT-GATE verde: G10 sigue BLOCKED_EXTERNAL (ADR-0105), y un `check` local verde
  no demuestra certificación externa.
- No certifica ningún SHA: el gate es del árbol de trabajo, no un recibo de release.
- No cierra AUD-03 ni el resto de la familia de relojes, y no convierte sus deudas en PASS.
- No toca el remoto: cero push.
```
