---
type: receipt
scope: BLOCK_E
sha_attempted: 2f5ba9aaf3b3eb815a8ed1ce1bdca8a45ee10253
gate_command: "cd v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon"
gate_command_full: "$ cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2 && ./gradlew check --rerun-tasks --console=plain --no-daemon > /tmp/full-gate-2f5ba9aa.log 2>&1"
gate_exit: 1
gate_duration_seconds: 468
gate_log: /tmp/full-gate-2f5ba9aa.log
gate_log_bytes: 145169
gate_log_lines: 1591
gate_timestamp_end: 2026-10-10T05:01:57+02:00
verdict: NOT_RUN_BLOCKED_BY_LOAD
not_a_pass: true
load_1m_at_start: 11.42
load_1m_during_peak: 20.74
worktree: /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs
branch: integrate/main-obs
worktree_clean: true
work_item_sddk: f8fc07e6-6f98-4b4a-81c0-3f5b717bd146
cycle: rp7-sem-s6-plugin-sdk
date: 2026-10-10
autorizacion: option-4-of-plan
supersedes: none
related: BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md (sha anterior f2da79e3)
---

# Bloque E — Recibo del intento de full gate sobre `2f5ba9aa`

**Veredicto:** `NOT_RUN_BLOCKED_BY_LOAD`. **No es PASS.** El gate
`./gradlew check --rerun-tasks` cerró con `EXIT=1` por desaparición del
Gradle daemon (probable OOM-kill del kernel bajo presión de memoria), no por
defecto del producto. Los 2.805 tests que aparecen como "PASSED" en los XML
existentes representan módulos cuyo `:check` SÍ alcanzó a publicar resultados
antes del crash — NO equivalen a un `BUILD SUCCESSFUL` global del árbol.

## 0. Por qué este recibo existe

El plan `docs/ROADMAP_PIPELINEK_MAIN_OBS_2026-10-10.md` §0 dice:

> "el full `check --rerun-tasks` y UAT/AAT cross-process del mismo candidato
> están incompletos. Es incorrecto inferir un solo gate completo verde al
> SHA `c924af8c` de la suma de ejecuciones parciales".

La política AGENTS.md (2026-10-03) reza:

> "El gate que sustituye al remoto es, y se exige completo: `cd v2 &&
> ./gradlew check --rerun-tasks` sobre el SHA exacto, árbol limpio + recibo
> inmutable por SHA. Tests omitidos: SKIPPED con causa, no PASS."

Este recibo cumple el registro del intento exigido.

## 1. Argv ejecutado, exactamente

```bash
$ cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2 \
    && ./gradlew check --rerun-tasks --console=plain --no-daemon \
       > /tmp/full-gate-2f5ba9aa.log 2>&1
```

Argumentos relevantes:
- `check` — tarea gate (compilación + test + kover + lint por módulo cuando aplica).
- `--rerun-tasks` — fuerza re-ejecución incluso cuando Gradle cree nada cambió.
- `--console=plain` — salida sin barra de progreso rica (reduce overhead de TTY y mejora captura).
- `--no-daemon` — daemon de un solo uso; muere al terminar el build, ajustándose a una sesión de auditoría.

## 2. Resultado del wrapper

```
GATE-META: SHA=2f5ba9aa EXIT=1 DUR_S=468 LOG_BYTES=145169 TIMESTAMP_END=2026-10-10T05:01:57+02:00
```

- `EXIT=1` — el wrapper bash capturó `EXIT=$?` de gradle: el proceso de gradle
  cerró con código no-cero.
- `DUR_S=468` — 7 minutos 48 segundos de reloj desde START hasta END.
- `TIMESTAMP_END=2026-10-10T05:01:57+02:00` — cierre del wrapper.
- `LOG_BYTES=145169`, `LINES=1591`.

## 3. Modo de fallo

El log cierra con:

```
> Task :pipeline-application:compileTestJava NO-SOURCE
> Task :pipeline-application:testClasses
> Task :pipeline-application:test

The message received from the daemon indicates that the daemon has disappeared.
Build request sent: Build{id=abee0563-1559-4632-b3ae-b2e242b24bd8, currentDir=/.../v2}
Attempting to read last messages from the daemon log...
Daemon pid: 1787556
  log file: /home/rubentxu/.gradle/daemon/8.14.5/daemon-1787556.out.log
----- Last 20 lines from daemon log file - daemon-1787556.out.log -----
<warnings de compilador Kotlin, ningún FAILED entre ellos>
----- End of the daemon log -----

FAILURE: Build failed with an exception.

* What went wrong:
Gradle build daemon disappeared unexpectedly (it may have been killed or may have crashed)
```

Diagnóstico probable, ordenado por probabilidad:

1. **OOM del kernel.** Load 1m llegó a `20.74` durante la ejecución del gate
   (arranque medido: `11.42`). El daemon `1787556` (configurado con `-Xmx4g`
   por `v2/gradle.properties`) compitió por memoria con el resto de procesos
   JVM del host compartido. `ps` durante la ejecución mostraba otros 9+
   procesos `java` activos (workers de gradle, Kotlin compile daemon,
   MainKt run residual de sesiones previas). El OOM-killer del kernel no
   deja un mensaje accesible para procesos no-priv; el síntoma observable
   es "daemon disappeared".
2. **OOM interno de Gradle Worker.** Gradle forkea un worker JVM por
   módulo con `--max-workers` ajustables. Bajo carga, el worker puede
   morir antes que el daemon padre.
3. **Watchdog del entorno compartido.** El pipeline-kotlin-cli-obs vive
   en `bazzite-rubentxu` con otros agentes; un cgroup restrictivo
   podría haber matado el proceso por cuota. Sin trazas dmesg/journal
   accesibles, no se descarta.

Lo que se descarta por inspección:

- **No hay `FAILED` test** en el log. La búsqueda `grep -E '(FAILED|FAIL|BUILD FAILED|exception:|Error)'` solo encuentra deprecation warnings del compilador Kotlin.
- **No hay "compilation failed"**: el último Task observado fue `:pipeline-application:test`. Las warnings de compilador son las mismas que las Runs #16-#36, heredadas del árbol.
- **No hay timeout declarado**: no aparece `--timeout` y gradle no se quejó de uno.

## 4. Lo que SÍ quedó como artefacto de la corrida

A la hora de cierre del wrapper, `find ... build/test-results/test/*.xml`
arrojó **468 ficheros** repartidos en 27 módulos. Esto NO significa
"BUILD SUCCESSFUL" (entre otras cosas porque gradle publica XMLs por
clase a medida que avanza el task `:test`, no como compromiso global al
finalizar). Lo que sí muestra es el HISTORIAL de clases que terminaron
sus tests antes del crash.

| Módulo | Ficheros | Tests | Fail | Err | Skip |
|---|---:|---:|---:|---:|---:|
| pipeline-domain | 137 | 735 | 0 | 0 | 0 |
| pipeline-architecture-tests | 110 | 550 | 0 | 0 | 10 |
| pipeline-events-store | 40 | 246 | 0 | 0 | 0 |
| runtime | 20 | 201 | 0 | 0 | 0 |
| pipeline-events | 29 | 131 | 0 | 0 | 0 |
| utilities | 2 | 119 | 0 | 0 | 0 |
| http | 11 | 118 | 0 | 0 | 0 |
| pipeline-scripting-api | 18 | 102 | 0 | 0 | 0 |
| pipeline-release | 23 | 84 | 0 | 0 | 2 |
| pipeline-credentials-local | 12 | 71 | 0 | 0 | 0 |
| pipeline-output-store | 7 | 67 | 0 | 0 | 0 |
| pipeline-scripting-kotlin24 | 15 | 60 | 0 | 0 | 0 |
| pipeline-credentials-api | 10 | 52 | 0 | 0 | 0 |
| files | 5 | 52 | 0 | 0 | 0 |
| scm-git | 8 | 47 | 0 | 0 | 8 |
| pipeline-binding-factory | 3 | 37 | 0 | 0 | 0 |
| pipeline-artefacts-local | 3 | 32 | 0 | 0 | 0 |
| pipeline-credentials-multipart | 3 | 30 | 0 | 0 | 0 |
| pipeline-event-harness | 2 | 18 | 0 | 0 | 0 |
| pipeline-credentials-executor | 3 | 15 | 0 | 0 | 0 |
| workflow-control | 1 | 10 | 0 | 0 | 0 |
| api | 2 | 8 | 0 | 0 | 0 |
| junit | 1 | 8 | 0 | 0 | 0 |
| pipeline-application | 1 | 7 | 0 | 0 | 0 |
| pipeline-output | 1 | 3 | 0 | 0 | 0 |
| pipeline-testkit | 1 | 2 | 0 | 0 | 0 |
| **TOTAL parcial** | **468** | **2.805** | **0** | **0** | **20** |

Comparación con la integración previa (`BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md`,
SHA `f2da79e3`): aquella integración sostenía 1.636 tests in-VM verde con 12
skips. La cifra de 2.805 aquí incluye módulos que la integración previa no
tocó (pipeline-domain, pipeline-architecture-tests, runtime, etc.), por lo
que la comparación directa no aplica — son SHA distintos.

**Lo que falta para considerar el gate verde:** el módulo
`pipeline-application` solo tiene 1 fichero XML (de 7 tests, todos
pasados), mientras que la Runs #16-#36 documentaron clases que en la
integración previa cubrían cientos de tests en ese módulo. La
mayoría de las clases de `pipeline-application/src/test/kotlin/`
quedan **`NOT_RUN`** respecto a este SHA: el daemon murió durante
`:pipeline-application:test` antes de que las clases siguientes a
`ObservationOperationIdShapeTest` escribieran su XML. Y los módulos
no cubiertos en ningún momento (los que no aparecen en la tabla)
quedan **`NOT_RUN`** sin ambigüedad.

**Esto NO se interpreta como "2.805 PASS, gate verde".** Se interpreta
como "27 módulos completaron la fase de test antes del crash; los demás
quedaron sin publicar resultado". El plan §0 lo dice textual: "no se
inventa un `PASS` por ausencia de fallos observables".

## 5. Estado del binario instalado

`v2/pipeline-application/build/install/pipelinek/bin/pipelinek`:

- mtime: `2026-10-10 04:55:10` — es decir, durante la corrida del gate
  (el task `:pipeline-application:installDist` precede a `:test` en el
  orden canónico de Gradle).
- tamaño: `10.575 bytes`.
- No es publicación; es el artefacto del propio task `installDist`
  generado por el build. Se preserva en disco para UATs contra el SHA,
  pero su sola existencia no certifica el gate: el `check` posterior
  fue el que reventó.

## 6. Relación con `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md`

| | `BLOCK_E_INTEGRATE_MAIN_OBS_RECEIPT.md` | este recibo |
|---|---|---|
| SHA probado | `f2da79e3` | `2f5ba9aa` |
| Naturaleza | integración local Bloque E | intento de full gate posterior |
| Status | `INTEGRATION_VERIFIED_LOCAL` **pendiente** | `NOT_RUN_BLOCKED_BY_LOAD` |
| Tests in-VM contados | 1.636 (suma de Runs #16-#36 sobre clases específicas) | 2.805 (suma sobre XMLs parciales — no es verde) |
| Pasos posteriores | ejecuta Runs #23-#28, #29-#36 sobre clases ancladas | sin próximos pasos hasta re-correr el gate con load<5 |

Los dos no se contradicen: el primero registra el trabajo técnico realizado
localmente; el segundo documenta el intento de certificar el estado agregado.

## 7. Implicación para §0.3/§8

`§0.3/§8` (push a `origin/main` + tag `v0.49.0-obs` + Prerelease en GitHub)
sigue **`BLOCKED_EXTERNAL`** por dos razones que no han cambiado:

1. El plan §0 explícitamente exige `cd v2 && ./gradlew check --rerun-tasks`
   verde sobre el SHA candidato antes de autorizar publicación; este intento
   EXIT=1 no cumple.
2. Aun con el gate verde, `§6.2` (batería cross-process de 12 tests) debe
   ejecutarse cuando load<5 esté sostenido, cosa que el presente intento
   no satisfizo porque la corrida murió a load 1m ~20.

`§0.3/§8` se reabre solo cuando (a) `cd v2 && ./gradlew check --rerun-tasks`
sobre `2f5ba9aa` (o SHA posterior equivalente) cierre EXIT=0 a load<5
sostenido, y (b) la batería `§6.2` pase verde sobre el mismo SHA. Cada
uno de los dos pasos requiere su propio recibo inmutable atado al SHA.

## 8. Próximo intento — precondiciones

Cuando se reintente, las precondiciones de entorno que habrían prevenido
el incidente son, ordenadas por impacto:

1. **Load sostenido <5.** El wedge por host compartido es estructural;
   `nice -n19` o límite de `pgrep -f gradle` no sustituyen a un host con
   margen. La Runs #21 golpeó 1m=3,03 y completó sin wedge — el sweet
   spot del runner.
2. **Daemon persistente con `--max-workers=2` y `-Xmx2g`.** Reduce el
   footprint de memoria por instancia y por `forkEvery`. Configurable en
   `v2/gradle.properties` o vía `-Pgradle.workers.max=2`.
3. **`-Pkotlin.incremental=false`.** El compilador de Kotlin, bajo carga,
   compila reposiciones incrementales; deshabilitarlo sube el tiempo pero
   baja la presión.
4. **Ejecución de `installDist` antes de `test` separada.** Si el task
   `installDist` se ejecuta con éxito independientemente, un crash en
   `test` no compromete el binario (que sí queda del run anterior de
   Run #19, sin necesidad de re-instalar en cada intento).

Quien retome la próxima sesión debería invocar el siguiente gate, en este
orden, y NO continuar sin ver `EXIT=0`:

```bash
$ up # esperar load<5 sostenido durante 5+ minutos
$ git -C /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs \
    status --short  # confirmar clean
$ git -C ... rev-parse HEAD  # confirmar SHA=2f5ba9aa (o lo que toque)
$ cd /var/home/rubentxu/Proyectos/kotlin/pipeline-kotlin-cli-obs/v2 \
    && ./gradlew check --rerun-tasks --console=plain \
       --max-workers=2 -Dorg.gradle.jvmargs='-Xmx2g' \
       > /tmp/full-gate-<sha>.log 2>&1
$ test $? -eq 0 || echo GATE_FAILED  # honesto, sin reinterpretación
```

## 9. Lo que este recibo NO afirma

- NO afirma que `2f5ba9aa` esté roto. No hay evidencia de defecto del
  producto; la única evidencia es la desaparición del daemon bajo carga.
- NO afirma que los 2.805 tests "pasaron" en sentido de certificación;
  pasaron dentro de módulos que alcanzaron a publicar sus XMLs antes
  del crash.
- NO autoriza `§0.3/§8`. Esa autorización es del propietario y solo
  cabe tras un `BUILD SUCCESSFUL` del gate sobre el mismo SHA.
- NO descalifica los Runs #16-#36. Sus 22 recibos
  (`BLOCK_E_RUN_*`) siguen certificando las clases que cubrieron
  individualmente.

## 10. Receipt per AGENTS.md

- argv, exit, tareas ejecutadas, clases, tests, failures, errors, skips
  registrados en §1-§5 con SHA literal.
- Política de verificación 2026-10-03 cumplida: recibo inmutable por SHA,
  no se reinterpreta evidencia parcial como PASS global.
- La constitución AGENTS "harness fidelity" aplicada: el veredicto se
  ata a observations discretas (mtime de XML, EXIT del wrapper,
  número de tests por módulo a través de `glob` + `ET.parse`), no a
  tiempos ni a tamaños.
