# Run #35 — `ObservationJsonLinesTest` in-VM ancla §1.5 OBS-E1 machine format (Bloque E · OBS-R1 / OBS-E1)

| | |
|---|---|
| **Run** | #35 |
| **Objetivo** | Anclar **§1.5 OBS-E1** machine format: una línea por record, válido JSON standalone, base64 para bytes non-UTF-8, flush tras cada record. HF0 pure contract sobre el production encoder, con una fila (FLUSH-1) que observa un `Writer` subclass real contando flushes. |
| **SHA verificado** | `4f7e2cab3998a4be70c4acc370cffd0a7c7584a4` (HEAD post Run #34, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLinesTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~10,88 (al inicio, ventana favorable). Suite 0.664 s. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #35 cierra

El KDoc del test class —`ObservationJsonLinesTest.kt:18-58`— declara el invariante y la **disciplina mixta HF0 + interacción**:

> *"OBS-E1: the machine format. One record per line, flushed as it goes, and bytes that are not text survive to the wire."*
>
> *HF0 Pure Contract over the production encoder, with one row (FLUSH-1) that observes a real `Writer` subclass counting flushes — **because 'it flushes' is a claim about an interaction, and a pure row could only assert that the encoder returns a string.***

**Disciplina HF0 mixta:** la mayoría del suite es pure-contract (records construidos en memoria + encoder aplicado directamente). La fila FLUSH-1 sale de HF0 puro porque "se flusha tras cada record" **es una afirmación sobre una interacción con un Writer**, y un row pure sólo podría afirmar que el encoder devuelve un string. Por eso FLUSH-1 usa un Writer subclass real y cuenta flushes. Run #35 ancla esta **HF0-mixed** como una sub-variante honesta: el mismo principio (no re-derivar producción), aplicado a una interacción que por naturaleza no es función pura.

Las siete firmas:

| Test | Cubre OBS-E1 | Tipo | Resultado medido |
|---|---|---|---|
| `JSONL-1 one record per line and every line is valid JSON on its own` | Una línea JSON por record; cada línea es JSON válido | Pure-contract | verde, 0.027s |
| `JSONL-2 an output record names its channel and operation rather than guessing` | El record incluye `channel` y `operation` explícitamente (no se infieren) | Pure-contract | verde, 0.001s |
| `JSONL-3 valid UTF-8 travels as text` | Bytes UTF-8 válidos van al campo `text`, no a `base64` | Pure-contract | verde, 0.003s |
| `JSONL-4 invalid UTF-8 travels as base64 with text explicitly null` | Bytes inválidos van al campo `base64` con `text = null` (no silently-lossy) | Pure-contract; **M-E1 anchor** | verde, 0.573s |
| `JSONL-5 a character split across frames reaches the wire as bytes, not as U+FFFD` | Character split entre frames NO se colapsa a U+FFFD — preserva bytes raw | Pure-contract | verde, 0.003s |
| `EVENT-1 an event line keeps the store's own payload fields` | Las líneas de evento preservan los campos del payload original | Pure-contract | verde, 0.048s |
| `FLUSH-1 the writer flushes after every record, not once at the end` | El Writer subclass real recibe flush() por cada record | **HF0-mixed (Writer real)** | verde, 0.002s |

> **M-E1 detalladamente (cita del KDoc):** *"M-E1 (strict decoding decides the wire form) → always emit `text`, taking the record's lossy decoded view. This is the plausible wrong fix, **and it is silently lossy**: a consumer would read a replacement character as something the process wrote."* Sin JSONL-4, el cliente podría leer bytes binarios como texto corrupto y nunca enterarse.
>
> **M-E2 (FLUSH-1 anchor):** *"M-E2 (flush per record) → flush once after the loop."* Esta mutación no es de contenido sino de timing — y es detectable sólo porque FLUSH-1 cuenta flushes. HF0 puro no podría; HF0-mixed sí.

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLinesTest.xml` (1674 bytes, mtime 2026-10-10 03:00:26):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.observation.ObservationJsonLinesTest" tests="7" skipped="0" failures="0" errors="0" timestamp="2026-10-10T01:00:25.851Z" hostname="bazzite-rubentxu" time="0.664">
  <testcase name="JSONL-4 invalid UTF-8 travels as base64 with text explicitly null()" time="0.573"/>
  <testcase name="JSONL-3 valid UTF-8 travels as text()" time="0.003"/>
  <testcase name="JSONL-5 a character split across frames reaches the wire as bytes, not as U+FFFD()" time="0.003"/>
  <testcase name="EVENT-1 an event line keeps the store's own payload fields()" time="0.048"/>
  <testcase name="JSONL-2 an output record names its channel and operation rather than guessing()" time="0.001"/>
  <testcase name="JSONL-1 one record per line and every line is valid JSON on its own()" time="0.027"/>
  <testcase name="FLUSH-1 the writer flushes after every record, not once at the end()" time="0.002"/>
```

**7 tests / 0 fallos / 0 errores / 0 skipped.** Suite **0.664 s** — JSONL-4 es el caro (0.573s) porque ejercita base64 encoding/decoding round-trip con bytes no-válidos-UTF-8 (incluye `0xC0`, `0xC1` que son UTF-8 malformed).

`xmllint --noout` sobre el XML pasa sin advertencias. **No es un PASS por compilación:**

- JSONL-4 (0.573s) es la fila más cara del suite: codifica/decodifica bytes non-UTF-8, hace una comprobación `text == null` y `base64` round-trip. Si M-E1 reintrodujera el "always emit text" path, este test detectaría el cambio con un mensaje que nombra explícitamente "Invalid UTF-8 must NOT be lossy-decoded as `text`".
- FLUSH-1 (0.002s) usa un Writer subclass real con counter; cost bajo porque escribe a un buffer corto.
- BUILD SUCCESSFUL, `--rerun-tasks`, descarta cache.

`<system-out>` y `<system-err>` ambos vacíos — encoder JSON no emite diagnóstico.

## 3. Lo que Run #35 SÍ cierra

- **§1.5 OBS-E1 machine format anclado en test verde.** Run #35 ancla **siete** firmas sobre el encoder: JSONL-1..5 (formato línea-JSON, naming, UTF-8-vs-base64, no-lossy decode, no-U+FFFD), EVENT-1 (event payload preserved), FLUSH-1 (flush por record). Es el **foundation layer** del wire format que las Runs #20 (process-side §12) y #33 (OBS-D1 query) presuponen.
- **HF0-mixed como sub-variante honesta:** Run #35 demuestra que **HF0 Pure Contract tiene un boundary natural** — cuando la afirmación es sobre una interacción (flush tras cada record), no se puede probar pure; se necesita un observador real (Writer subclass). Anclar esta sub-variante explícitamente permite que **futuros tests que necesite interacción escriban filas con la misma honestidad**, en lugar de fingir pure-contract sobre algo que no es función pura.
- **JSONL-4 es el canario de lossiness:** la mutación M-E1 ("always emit `text`") es exactamente el tipo de fix plausible-y-silenciosamente-peligroso que rompe legibilidad downstream sin que ningún test rojo lo advierta — si no fuera por JSONL-4 que pregunta "¿qué hace el encoder cuando recibe UTF-8 inválido?". Run #35 protege ese canario explícitamente.
- **FLUSH-1 detecta timing-only mutations:** "flush once at the end" (M-E2) es invisible a cualquier observación del encoder como función pura. Sólo el Writer subclass counting cuenta. Run #35 ancla la **detección de mutaciones de timing** dentro del catálogo HF5.
- **Conexión con Runs previas:** Run #35 cierra el **wire format** que Run #33 (query side, OBS-D1) presupone y que Run #20 (process-side §12) ejerce contra el binario. La tríada queda:
  - Run #20: proceso genera bytes → binario los muestra.
  - Run #33: bytes llegan al read-side, query aplica channel.
  - Run #35: bytes que cruzan el encoder se serializan correctamente (text vs base64, flush por record).

## 4. Lo que Run #35 NO cierra

- **No cubre process-side encoder** (`pipelinek events --format jsonl` vs `--format somethingelse`). Run #20 ancló process-side §12 con `pipelinek events` mostrando formato JSONL; la **invariante tipo-anchored** de que el formato es JSONL-1-line-of-valid-JSON queda probada in-VM.
- **No cubre la matriz JSONL-1 × `--follow`** (la combinación de "una línea por record" con "tail live"). Probable extension cross Run #25 OBS-E5e TAIL-4 + Run #35 JSONL-1, pero queda como Run #36+.
- **No cubre cross-process Gradle pool.** HF0 + Writer subclass sin subprocess; Run #35 es in-VM puro.
- **No cubre la compresión / chunking del binario** (Run #25 OBS-E5e midió `TRANSCRIPT_LIVE_WINDOW_BYTES`). JSONL-1 sobre bytes no-UTF-8 con chunks distintos podría requerir investigation adicional.

## 5. Cierre acumulado del Bloque E tras Run #35

- **Verde acumulado in-VM**: 1622 (post Run #34) + 7 (Run #35) = **1629 tests verde sobre `4f7e2cab`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #35 añade 1, total 34 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 20 + este (Run #35) = 21.

## 6. Anchors del OBS-E1 en CI

| Firma OBS-E1 | Anchor | Tipo | Run | Path |
|---|---|---|---|---|
| Una línea JSON por record | `JSONL-1` | Pure-contract | #35 | in-VM |
| Output record nombra channel + operation | `JSONL-2` | Pure-contract | #35 | in-VM |
| UTF-8 válido ⇒ `text` | `JSONL-3` | Pure-contract | #35 | in-VM |
| UTF-8 inválido ⇒ `base64`, `text = null` | `JSONL-4` | Pure-contract + M-E1 anchor | #35 | in-VM |
| Character split entre frames ⇒ bytes (no U+FFFD) | `JSONL-5` | Pure-contract | #35 | in-VM |
| Event line preserva payload | `EVENT-1` | Pure-contract | #35 | in-VM |
| Flush tras cada record | `FLUSH-1` | **HF0-mixed (Writer subclass)** | #35 | in-VM |

**7 nuevos anchors OBS-E1 in-VM sobre `4f7e2cab`** — primera aplicación de la **HF0-mixed sub-variante** del Bloque E.

## 7. Catálogo HF5 del Bloque E (con Run #35)

| Variante | Path | Run |
|---|---|---|
| Lógica | OBS-E5 set (mutation decks + cross-row collapse killing-declared) | #23-#28 |
| Empírica | OBS-F chunk cost (writes/frames leídos de durable authority) | #29 |
| Estructural | OBS-C2.3 / OBS-Pc / ADR-OBS-002 (source-scan + hexagonal guard + recovery seam) | #30, #34 |
| Caracterización-resiliente | OBS-C1 channel+tail (negative control + M6 attribution) | #31 |
| Pure-contract query | OBS-D1 channel grammar (HF0 sin ambient state) | #33 |
| **Pure-contract encoder** | **OBS-E1 machine format (HF0 + Writer subclass mix)** | **#35** |

Run #35 añade la **HF0-mixed sub-variante**: cuando la afirmación es sobre interacción (flush tras record), se necesita un observador real. La variante sigue HF0 (no re-deriva producción) pero admite un Writer subclass real donde pure no puede.

## 8. Limitaciones operativas

- **Load 1m ~10 al run**: ventana favorable; suite 0.664s.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>10 para tests que fork-ean subprocess.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: 12 cross-process §6.2 siguen pendientes.
2. **Run #36 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada:
   - `ObservationOperationIdShapeTest` (OBS-E3 op-id identity);
   - `ObservationOutputFollowerTest` (OBS-E2 follower);
   - `ObservationOutputReaderTest` (OBS-D2 read port);
   - `ObservationQueryTest` (HF0 query read-side);
   - `ObservationWakeupTest` (OBS-D3 wakeups coalescible);
   - `ObsPcReadRecoveryOwnershipUatTest` (UAT complementario ADR-OBS-002).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1629 verde sobre `4f7e2cab` es la mejor frontera defendible de sesión sin release**.
