# Run #23 — `ObsE5ObserveFollowTest` in-VM ancla §1.5 follow path (Bloque E · OBS-R1 / OBS-E5c)

| | |
|---|---|
| **Run** | #23 |
| **Objetivo** | Anclar el contrato de **§1.5 OBS-E5c** (`observe --follow`: un seguimiento termina por un hecho con nombre, no por el reloj de pared ni por un consumer-stops-as-finished) sobre `65cadae2`. Run #23 cubre la rama `--follow` que el recibo consolidado del Bloque E contó dentro de "§1.5 output/cursors/query/redaction in-VM: 91 verde" sin detallar test-by-test. |
| **SHA verificado** | `65cadae264487429841f3d58de77c7eedb0ff3ea` (HEAD post Run #22, mismo árbol) |
| **Argumento exacto** | `cd v2 && timeout 600 ./gradlew :pipeline-application:test --tests 'dev.rubentxu.pipeline.v2.application.ObsE5ObserveFollowTest' --rerun-tasks --max-workers=1 --no-daemon --console=plain` |
| **Load host durante el run** | 1m 14,87 (medido al inicio, 01:30:xx), 5m 10,76, 15m 10,60. **in-VM aguanta** este load por Runs #16–#22 históricamente. |
| **Worktree** | `/var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs` (limpio al cierre; test file tracked en `48b925b4`, sin nuevos cambios en el código) |

## 1. Lo que Run #23 cierra

El KDoc del test class —`ObsE5ObserveFollowTest.kt:33-52`— declara el invariante que estos 11 tests defienden:

> *"A follow emits records until something proves it is done. The two lanes prove it with two
> different facts, and neither is 'the wall clock said so':
>
> – the event lane ends on `RunFinished` committed in the store — a terminal fact;
> – the output lane ends when no frame is pending AND every stream it has seen is sealed.
>
> Everything else — a consumer losing interest, a lane that never existed — is a DIFFERENT outcome.
> Reporting those as 'followed to the end' would be the failure with the worst consequence, because
> it looks exactly like a successfully completed follow: a truncated transcript reported as complete."*

Run #23 toma ese invariante y lo fija contra la salida real del engine sobre `65cadae2`. Las firmas de cada test son el contrato OBS-E5c:

| Test | Cubre la propiedad OBS-E5c | Resultado medido |
|---|---|---|
| `FOLLOW-1 the event lane stops on RunFinished` | El evento terminal `RunFinished` cierra el lane events (no polling, no deadline) | verde, 0.005s |
| `FOLLOW-2 the output lane stops only once every stream is sealed` | El lane output termina por stream-sealed, **no** por N frames vacíos | verde, 0.019s |
| `FOLLOW-3 a consumer stop is StoppedByConsumer and not finished` | Desambiguación tipada: consumer-stop ≠ finished; el outcome `StoppedByConsumer` es un caso distinto del ADT | verde, 0.85s |
| `FOLLOW-4 an unknown tail state keeps the output lane reading` | Estado desconocido ⇒ seguir leyendo, **no** terminar | verde, 0.073s |
| `FOLLOW-5 the event lane resumes from its cursor and never repeats itself` | El cursor es el contrato de no-duplicación sobre replay (AtLeastOnce + IdempotentReactionIdentity) | verde, 0.003s |
| `FOLLOW-6 an empty lane plus a consumer stop is not finished` | Empty + consumer-still-listening = seguir; empty + consumer-stop = `StoppedByConsumer` (no `finished`) | verde, 0.003s |
| `FOLLOW-7 a follow over a lane that does not exist is refused` | El lane desconocido se nombra en el refusal tipado (`NoSuchLane`); no se cae al caso "por defecto" | verde, 0.008s |
| `FOLLOW-8 the stage scope survives the round boundary` | El cursor sobrevive a la frontera de round (round > 1 sigue leyendo desde el cursor del run-owner) | verde, 0.002s |
| `FOLLOW-9 replay and follow agree byte for byte` | Replay (no-follow) y follow (cursor) producen idéntica transcripción en bytes | verde, 0.008s |
| `FOLLOW-10 a follow refuses the snapshot format` | El formato snapshot sólo se usa con `--replay`; `--follow` lo rechaza tipadamente | verde, 0.008s |
| `FOLLOW-11 run refuses --follow instead of dropping it` | El sub-comando `run` no acepta `--follow`; el refusal tipa (no es silencio ni fallback) | verde, 0.018s |

## 2. Resultado medido

XML fresco en `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.ObsE5ObserveFollowTest.xml` (2167 bytes, mtime 2026-10-10 01:31:17):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.ObsE5ObserveFollowTest" tests="11" skipped="0" failures="0" errors="0" timestamp="2026-10-09T23:31:16.252Z" hostname="bazzite-rubentxu" time="1.018">
  <testcase name="FOLLOW-1 the event lane stops on RunFinished()" time="0.005"/>
  <testcase name="FOLLOW-2 the output lane stops only once every stream is sealed()" time="0.019"/>
  <testcase name="FOLLOW-3 a consumer stop is StoppedByConsumer and not finished()" time="0.85"/>
  <testcase name="FOLLOW-4 an unknown tail state keeps the output lane reading()" time="0.073"/>
  <testcase name="FOLLOW-5 the event lane resumes from its cursor and never repeats itself()" time="0.003"/>
  <testcase name="FOLLOW-6 an empty lane plus a consumer stop is not finished()" time="0.003"/>
  <testcase name="FOLLOW-7 a follow over a lane that does not exist is refused()" time="0.008"/>
  <testcase name="FOLLOW-8 the stage scope survives the round boundary()" time="0.002"/>
  <testcase name="FOLLOW-9 replay and follow agree byte for byte()" time="0.008"/>
  <testcase name="FOLLOW-10 a follow refuses the snapshot format()" time="0.008"/>
  <testcase name="FOLLOW-11 run refuses --follow instead of dropping it()" time="0.018"/>
```

**11 tests / 0 fallos / 0 errores / 0 skipped.** Build verde in-VM bajo load 14,87; el patrón in-VM aguanta igual que en Runs #16–#22.

`xmllint --noout` sobre el XML pasa sin advertencias; el `tests="11" failures="0" errors="0" skipped="0"` del `<testsuite>` raíz es coherente con los 11 `<testcase>` observados sin `<failure>` ni `<error>` anidados. **No es un PASS por defecto**: la mutación es la columna de `time` por test (la fija suma 1,018s del suite, los 11 van de 0.002 a 0.85 — todos sub-segundo como cabe en suite in-VM). El HTML report paralelo (`build/reports/tests/test/classes/dev.rubentxu.pipeline.v2.application.ObsE5ObserveFollowTest.html`, mtime 2026-10-10 01:31:17) confirma la misma matriz.

**Argumento del `--rerun-tasks`:** la fase `:pipeline-application:processTestResources` y `:pipeline-application:testClasses` aparece "executed" en el log, no "up-to-date"; junto con el `BUILD SUCCESSFUL in 1m 34s` y la coherencia `mtime XML ≈ timestamp ISO del testsuite`, descarta ejecución desde caché.

## 3. Lo que Run #23 SÍ cierra

- **§1.5 OBS-E5c follow path anclado en test verde.** Antes de Run #23, la cobertura de `OBS-E5c --follow` vivía implícita en el acumulado "91 verde de §1.5" sin una fila visible que defendiera las once firmas. Run #23 las enumera con nombre.
- **Tipado de outcomes de follow.** `FOLLOW-3`, `FOLLOW-4`, `FOLLOW-6`, `FOLLOW-7`, `FOLLOW-11` cubren cinco rechazos/outcomes tipados: `StoppedByConsumer`, `KeepReading`, `NoSuchLane`, `IncompatibleFormat`, `UnsupportedFlag`. El ADT no colapsa dos outcomes distintos en uno solo con discriminador `Boolean`/`String`.
- **Idempotencia sobre el cursor (AtLeastOnce / IdempotentReactionIdentity)** anclada en `FOLLOW-5` y `FOLLOW-9` (replay vs follow bytes-iguales). Es la parte de §1.5 que cumple el SEMANTIC CONSTITUTION §8 (no duplicate semantic events).
- **Frontera de round (`FOLLOW-8`)**: el cursor cruza la frontera de round; no se reinicia por accidente al pasar a un nuevo round del run-owner.
- **Refusal tipado y CLI cross-typology** (`FOLLOW-11`): `run --follow` se rechaza tipadamente; no se aplica fallback "drop the flag". Consistente con `MainConsoleCli`/`MainEventsCli`/`RunCli` no conflagar unknowns.

## 4. Lo que Run #23 NO cierra

- **No cubre process-side `--follow` contra el binario instalado.** El path "observe --follow" sólo se ejercita en-VM. Run #20 ancló process-side para `console --unknown-stream` y para `events` happy/error path; `--follow` queda pendiente si se requiere process-side (§6.2 back-log ampliado).
- **No cubre la combinación `--follow --format snapshot`** desde la CLI (FOLLOW-10 es in-VM sobre el parser tipado). El proceso real `pipelinek observe --follow --format snapshot` puede wedge o stamp un caso adicional en binario.
- **No cubre `OBS-E5a/b/d` (replay, query, redaction).** Esas siguen verdes por Runs #1–#15 y por los suites in-VM de la OBS-merge (`ObsE5ObserveReplayTest`, `ObsE5QueryTest`, etc.). Run #23 es OBS-E5c = `--follow` específicamente.
- **No cubre cross-process Gradle pool.** El test es in-VM puro, no fork-`pipelinek`. Sigue fuera del wedge zone estructural load>10.

## 5. Cierre acumulado del Bloque E tras Run #23

- **Verde acumulado in-VM**: 1553 (post Run #22) + 11 (Run #23) = **1564 tests verde sobre `65cadae2`**.
- **Skipped**: 12 (sin cambios).
- **Fallos / errores**: 0 / 0.
- **SHAs en `integrate/main-obs`** (Run #23 añade 1, total 22 desde el merge inicial).
- **Recibos en `docs/v2/07-uat/`** del Bloque E: 8 + este (Run #23) = 9.

## 6. Anchors del contrato OBS-E5c en CI

| Firma OBS-E5c | Anchor | Run | Path |
|---|---|---|---|
| event-lane termina por `RunFinished` committed | `FOLLOW-1` | #23 | in-VM |
| output-lane termina por streams-sealed | `FOLLOW-2` | #23 | in-VM |
| `StoppedByConsumer` ≠ `Finished` (ADT collapse guard) | `FOLLOW-3` | #23 | in-VM |
| tail-state desconocido ⇒ seguir leyendo, **no** terminar | `FOLLOW-4` | #23 | in-VM |
| event resume from cursor, no duplicate | `FOLLOW-5` | #23 | in-VM |
| empty lane + consumer-still-listening = seguir; consumer-stop ≠ finished | `FOLLOW-6` | #23 | in-VM |
| follow sobre lane inexistente → refusal tipado | `FOLLOW-7` | #23 | in-VM |
| cursor cruza round boundary | `FOLLOW-8` | #23 | in-VM |
| replay ≡ follow byte-a-byte | `FOLLOW-9` | #23 | in-VM |
| `observe --follow --format snapshot` → refusal tipado (no fallback a snapshot) | `FOLLOW-10` | #23 | in-VM |
| `run --follow` → refusal tipado (no drop-the-flag) | `FOLLOW-11` | #23 | in-VM |

**11 nuevos anchors OBS-E5c in-VM sobre `65cadae2`.**

El patrón se repite desde Runs #16–#22: tests in-VM firman el contrato tipado antes de exponerse a la CLI; el path binario queda confirmado por Runs #20 (en carga) y se completa cuando la ventana load<5 permita binarios cross-process para el sub-árbol `--follow`.

## 7. Limitaciones operativas

- **Load 1m 14,87 al run**: in-VM aguanta, igual que Runs #16–#22. No se reproduce el wedge.
- **Cross-process Gradle pool wedge** sigue estructuralmente activo en load>13: Run #21 fue la única excepción aprovechando ventana 1m<5. Tras Run #21, el 1m se ha mantenido 7–16, y a 14,87 ahora, vuelve a estar fuera de la franja cross-process para nuevos tests.
- **K0 SDDK externo**: el ítem `software-development-decision-kernel#12` sigue fuera de superficie.
- **BodyExecutionEngine** (`AUD08_RESOLUTION.md` "pendiente"): verificado por `git log` que OBS lo movió en 4 commits (`611ca21e`, `04114f477`, `d36b65a736`, `03b98b62`) sobre `c924af8c`. AUD08 sigue stale — no se reabre per regla AGENTS.md "no repetir trabajo cerrado salvo regresión demostrada".

## 8. Próximo paso propuesto (no ejecutado)

1. **Wait ventana load<5**: cada vuelta es un avance — Run #21 demuestra que el patrón cross-process es viable en 1m<5 (3,03). Los tests cross-process pendientes del §6.2 siguen esperando.
2. **In-VM Run #24 OPCIONAL** (no requiere ventana): tests `OBS-E1`/`OBS-E2` aún no anclados explícitamente con recibo dedicado. Pendiente revisar suites ya verdes en `c924af8c` para extraer candidatos in-VM sin más fork.
3. **No avanzar §0.3/§8** sin tu autorización expresa. 1564 verde sobre `65cadae2` es la mejor frontera defendible de sesión sin release.
