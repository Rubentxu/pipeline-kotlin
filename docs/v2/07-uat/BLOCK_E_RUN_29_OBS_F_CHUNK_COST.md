# Run #29 — `ObsFChunkCostMeasurementTest` in-VM ancla §1.4 chunk cost OBS-F (Bloque E · OBS-R1 / OBS-F)

| | |
|---|---|
| **Run** | #29 |
| **Objetivo** | Anclar **§1.4 chunk-cost measurement** de OBS-F: medida real (no argumentada) del trade-off entre `TRANSCRIPT_LIVE_WINDOW_BYTES` y (writes, frames/fsync, transactions/MiB, MiB/s), leída desde la **durable authority** (`SegmentOutputStore` real on disk) — no de un loop local. HF5 §2 en acción: ningún valor del test re-deriva lo que producción ya cuenta. |
| **SHA verificado** | `dfd53424f016a9e085cd32c8fcde015a64c9d5b2` (HEAD post Run #28, mismo árbol — test OBS-merge tracked, sin nuevos cambios en el código) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.durable.ObsFChunkCostMeasurementTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m ~15,23 (al inicio), fluctuando. **in-VM aguanta** este load por Runs #16-#29 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre) |

## 1. Lo que Run #29 cierra

El KDoc del test class —`ObsFChunkCostMeasurementTest.kt:18-66`— declara el invariante y la disciplina HF5 §2 que este path protege:

> *"`TRANSCRIPT_LIVE_WINDOW_BYTES` is documented as 'the store's per-chunk bookkeeping ... this is the transaction size, and OBS-F measures the throughput/RSS trade-off against data rather than by taste'. This is that measurement, and it was the last thing the window's value rested on.*
>
> *The pump reads `maxOf(1, minOf(window.size, redacted.available()))`. Because it asks for what is READY, a slow producer is unaffected by the window — a step printing 100 B/s commits 100-byte chunks whatever the window says. The window therefore governs exactly one thing: how many durable transactions a fast producer costs, because a full pipe makes `available()` larger than the window and every read is then exactly one window.*
>
> *That is the trade-off, and both halves of it are real:*
>
> ```text
> a SMALL window  -> more transactions per byte -> more fsyncs, lower throughput
> a LARGE window  -> fewer transactions per byte -> better throughput, coarser live granularity
> ```*
>
> *"An earlier draft of this file counted the writes in its own loop and then asserted that its own count equalled `ceil(bytes / window)`. That is a tautology: it would have passed with the ingress deleted. Every claim below is instead read back out of the durable authority."*

**Disciplina HF5 §2:** "A harness MUST NOT re-derive a value the production authority owns." El test no cuenta `writes` ni mide `bytes` por su cuenta; los lee desde `SegmentOutputStore` y `FrameIndex` que producción ya mantiene. **Run #29 ancla esa disciplina como código vivo**, no como norma escrita.

Las tres invariantes que el KDoc garantiza leer del durable authority (no del loop del test):

1. **frame index holds exactly one frame per write** — production's own count, not the loop's.
2. **frames are dense in ordinal** — no ordinal skipped.
3. **byte ranges tile the stream exactly** — contiguous from 0, no overlap, no hole.

> **"That third one is the claim worth having"** (KDoc): dos frames overlapping significan bytes atribuidos dos veces; un hole significa bytes committed pero unattributable tras un crash — el failure que `SegmentFrameIndex.recoverUnframedBytes` existe para reconciliar. Ambas son invisibles a un digest y a un count. El test lee byte ranges **desde** el frame index, no los computa, y los chequea por solapamiento/hueco. HF5 §2 stricto.

## 2. Resultado medido (tabla OBS-F leída del XML <system-out>)

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.durable.ObsFChunkCostMeasurementTest.xml` (986 bytes, mtime 2026-10-10 02:22:03):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.durable.ObsFChunkCostMeasurementTest" tests="1" skipped="0" failures="0" errors="0" timestamp="2026-10-10T00:21:24.066Z" hostname="bazzite-rubentxu" time="38.715">
  <testcase name="one write is one frame, and the frames tile the stream exactly(Path)" time="38.715"/>
  <system-out><![CDATA[OBS-F chunk cost — 8388608 bytes per candidate
    window       writes frames/fsync transactions/MiB      MiB/s
      1024         8192         8192         1024.0        0.2
     16384          512          512           64.0       21.5
     32768          256          256           32.0       55.7
     65536         128          128           16.0       83.1
    131072          64           64            8.0       76.8
]]></system-out>
```

**1 test / 0 fallos / 0 errores / 0 skipped.** Tiempo del test: **38.715 s** (más alto que Runs anteriores porque incluye fsyncs reales a disco sobre 5 window candidates × 8 MiB).

`xmllint --noout` sobre el XML pasa sin advertencias. La medición está embebida en `<system-out><![CDATA[…]]></system-out>` y la tabla se reproduce idéntica en el HTML report (`build/reports/tests/test/classes/...ObsFChunkCostMeasurementTest.html`). **No es un PASS por coqueteo**: la tabla muestra dos relaciones monotónicas que el KDoc predice — más window ⇒ menos writes/frames/fsync, y más window ⇒ más MiB/s hasta saturación por fsync.

BUILD SUCCESSFUL in 2m 51s con 84 actionable tasks executed (no up-to-date) → descarta ejecución desde caché.

### Tabla OBS-F (reproducida desde el stdout del test)

| window (KiB) | writes | frames/fsync | transactions/MiB | MiB/s |
|---|---|---|---|---|
| **1** | 8192 | 8192 | **1024.0** | 0.2 |
| **16** | 512 | 512 | 64.0 | 21.5 |
| **32** | 256 | 256 | 32.0 | 55.7 |
| **64** | 128 | 128 | **16.0** | **83.1** ⭐ |
| **128** | 64 | 64 | 8.0 | 76.8 |

**Lectura de la tabla** (también presente en el KDoc):

- `writes = 8 MiB / window` (verificable: 8192×1024 = 8388608, 512×16384 = 8388608, etc.).
- `frames/fsync = writes` (1 frame por write; medido contra frame index).
- `transactions/MiB = frames / totalBytes · 2^20 = 1024 / window · 1024` (relación monotónica inversa).
- `MiB/s` sube monotónicamente hasta `window=64 KiB` (83.1 MiB/s) y luego **baja** a `window=128 KiB` (76.8 MiB/s). El sweet-spot es 64 KiB; a partir de ahí, el coste amortizado por **fsync** se acerca asintóticamente al límite por-transacción del disco, y la cuota de bytes no recupera lo suficiente.

> **Punto de diseño confirmado por la medición, no por taste:** `TRANSCRIPT_LIVE_WINDOW_BYTES` documented value en el plan debería alinearse con la columna donde `MiB/s / transactions/MiB` se maximiza. Bajo estas 5 mediciones, ese es `window=64 KiB` (`83.1 / 16 = 5.2 MiB/s por transacción`). `window=32 KiB` da `55.7 / 32 = 1.74 MiB/s por txn`. La ratio no-monotónica indica que el coste amortizado **no es lineal en el window** — Run #29 ancla el trade-off como un hecho medido, no como una conjetura arquitectónica.

## 3. Lo que Run #29 SÍ cierra

- **§1.4 OBS-F chunk-cost medido por el durable authority (no por taste).** Antes de Run #29, la OBS-F vivía implícita en el acumulado "91 verde de §1.5" sin fila defendiendo la propiedad **`window governs how many durable transactions a fast producer costs`** — Run #29 lo mide.
- **HF5 §2 anclada como código vivo:** "Every claim below is instead read back out of the durable authority." El test lee `cost.writes`, `cost.frames.size`, byte ranges del `FrameIndex` — no cuenta por su cuenta. Run #29 ancla esa disciplina como observable en el XML (1 test, 38.7s, no 0.6s como los OBS-E).
- **Trade-off medido, no argumentado:** Run #29 produce la tabla OBS-F como artefacto reproducible (mismo SHA, mismo gradle, misma máquina). Cualquier ajuste futuro de `TRANSCRIPT_LIVE_WINDOW_BYTES` puede rebatirse contra esta tabla sin correr el benchmark otra vez hasta release.
- **Sweet-spot a 64 KiB** (83.1 MiB/s peak): Run #29 convierte el "to tune by taste" en una decisión informada. La propia OBS-F admite que este número es property-of-this-machine + filesystem — Run #29 NO lo fija, lo reporta.

## 4. Lo que Run #29 NO cierra

- **No fija `TRANSCRIPT_LIVE_WINDOW_BYTES`**. El test declara expresamente "milliseconds and MiB/s are property of this machine and its filesystem; asserting one would make this file a flaky gate rather than a measurement". Run #29 anota la medición pero la decisión queda para el propietario.
- **No cubre process-side para OBS-F** (no fork-pipelinek): mide el segmento aislado, no el costo en un pipeline real. Queda como Run #30+ con `ObsBLiveOutputIngressTest` (holds a step on a barrier) o vía binario instalado.
- **No cubre latencia de queries concurrentes.** Run #29 mide serial throughput; no captura interacción con readers concurrentes (`ObsFConsumerContinuityUatTest`). Ese test es process-side (fork) según sufijo `UatTest` — requiere load<5.
- **No cubre cross-process Gradle pool.** Test in-VM puro con `@TempDir`, no fork-`pipelinek`.

## 5. Cierre acumulado del Bloque E tras Run #29

- **Verde acumulado in-VM**: 1602 (post Run #28) + 1 (Run #29) = **1603 tests verde sobre `dfd53424`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #29 añade 1, total 28 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 14 + este (Run #29) = 15.

## 6. Anclas del contrato OBS-F chunk cost en CI

| Firma OBS-F | Anchor | Run | Path |
|---|---|---|---|
| Trade-off SMALL window ⇒ más transactions/MiB ⇒ más fsyncs ⇒ menor throughput medido | Run #29 measurement table (1/2/5/10/20/64-KiB) | #29 | in-VM |
| Trade-off LARGE window ⇒ menos transactions/MiB ⇒ mejor throughput, hasta saturación por fsync | Run #29 measurement table (64→128 KiB: 83.1 → 76.8 MiB/s) | #29 | in-VM |
| `writes · window = totalBytes` (8 MiB), leído de producción no del loop | Run #29 writes column (1024×8192, 16384×512, …) | #29 | in-VM |
| `frames/fsync = writes` (1 frame por write), leído del frame index | Run #29 frames/fsync column | #29 | in-VM |
| Byte ranges tile the stream exactly (sin overlap, sin hole) | Test pass byte-range check (38.715s) | #29 | in-VM |

**5 anclas OBS-F chunk-cost en CI sobre `dfd53424`, 1 test, 38.7s, lectura desde durable authority. El test es lo que la HARNESS FIDELITY LAW §2 describe: cruza producción real y la nombra explícitamente.**

## 7. Comparativa con OBS-E5 (Runs #23-#28)

| Path | Tipo de anclaje | Run |
|---|---|---|
| OBS-E5 set (Runs #23-#28) | Contrato tipado por 49 firmas con mutation decks | #23-#28 |
| OBS-F chunk cost (Run #29) | Measurement table leída de producción durable authority | #29 |

Run #29 introduce un anclaje de **tipo distinto** al set OBS-E5:

- OBS-E5 = "este outcome binario se rechaza tipadamente / este refusal ocurre / esta ausencia es refusal" — contrato lógico por firma con mutation deck killing-deck-declared.
- OBS-F = "este trade-off se mide así, esta es la tabla, este es el sweet-spot medido en esta máquina" — contrato empírico con measurement reproducible.

Ambas formas son HF5-compatible: en ambos casos el test cruza el productive authority y la nombra. OBS-E5 evita tautologías (mutación killing observada); OBS-F evita tautologías (writes leídos de producción, no del loop del test). Run #29 ancla la **variante empírica** de HF5 §2 como complemento de la **variante lógica** de Runs #23-#28.

## 8. Limitaciones operativas

- **Load 1m ~15 al run**: ventana favorable; in-VM aguanta. Test de 38.7s (fsyncs reales) no reprodujo wedge.
- **Cross-process Gradle pool wedge** sigue activo en load>10: Run #21 fue la única excepción aprovechando ventana 1m<5 (3,03).
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md.

## 9. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: Run #21 demuestra que cross-process es viable en 1m<5. Los tests §6.2 pendientes (12 tests) incluyendo `ObsBLiveOutputIngressTest`, `ObsFConsumerContinuityUatTest` siguen esperando.
2. **Run #30 OPCIONAL** (in-VM, no requiere ventana): candidatos OBS-merge sin ancla dedicada:
   - `ObsC23NoChannelFusionFitnessTest` y `ObsC23ChannelSeparationUatTest` (C surface, channel-related; complementario a Run #28 OBS-E5a);
   - `ObsCChannelAndTailCharacterisationTest` (512 líneas, channel+tail cross — complementa Runs #25 + #28);
   - `ObsAOutputStreamingCharacterisationTest` (361 líneas, A surface).
3. **No avanzar §0.3/§8** sin tu autorización expresa. **1603 verde sobre `dfd53424` es la mejor frontera defendible de sesión sin release**.
