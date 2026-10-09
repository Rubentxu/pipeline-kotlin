# OBS-2 — STEP-CERT, intento 1: no superable en este SHA, y por qué

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **SHA** | `604d06248eb9fe8124081653f1a625e3bcd57aac` |
| **Tree** | `5f639b471b030ba4dd18d02e9b43caacb208de0e` |
| **Árbol** | limpio (`git status --porcelain` vacío) |
| **Veredicto** | **STEP-CERT: NOT_RUN** · **PRODUCT-GATE: BLOCKED_EXTERNAL** |
| **Bloquea** | la certificación formal de OBS-2 Nivel A |

## Lo que este recibo NO es

No es un informe de "todo va bien". Es el registro de un gate que **no se pudo pasar**, escrito porque
`CERTIFICATION_PROTOCOL.md` exige que un veredicto `NOT_RUN` deje constancia en un recibo ligado al SHA, y
porque un veredicto que sólo existe en una conversación no es evidencia de nada.

## Qué se ejecutó

```bash
cd v2 && GRADLE_OPTS="-Djava.io.tmpdir=/var/home/rubentxu/.cache/gradle-tmp" \
  ./gradlew check --rerun-tasks --console=plain
```

Resultado en el árbol de trabajo de este recibo:

```text
BUILD FAILED in 10s · EXIT=1
tareas :test ejecutadas = 2        <- por fin se empezó a ejecutar tests
tarea FAILED: :pipeline-application:detekt   ("Analysis failed with 5 issues")
```

Las dos ejecuciones anteriores de este gate —en `1777f8f9` y `967e30e9`— murieron **antes** de llegar a
una sola tarea `:test`, y no por culpa de los tests.

## Los seis bloqueos que sí se arrastraron

Cada uno era deuda previa, y cada arreglo destapó el siguiente. Ninguno era un fallo de test.

| # | Bloqueo | Tareas que morían antes de correr un test | Arreglado en |
|---|---|---|---|
| 1 | detekt: 8 ficheros sin newline final | `:pipeline-output`, `:pipeline-output-store`, `:pipeline-architecture-tests` | `ebe340eb` |
| 2 | `SegmentOutputStore` con 31 funciones (máx. 30) | `:pipeline-output-store` | `84343199` |
| 3 | 4 tests rojos del store | — | `967e30e9` |
| 4 | `apiCheck`: superficie pública sin registrar | `:pipeline-output` | `eaa9bc67` |
| 5 | `DurableShellExecutor.launch`: 132 líneas, complejidad 30 | `:pipeline-step-sdk:runtime` | `0fef0d82` |
| 6 | `pipeline-application`: 70 hallazgos de detekt | `:pipeline-application` | `604d0624` |

El hallazgo 3 merece su propia línea porque no era un test roto: los cuatro tests **simulaban una muerte que
no ocurría**. `crashAfterWriting` soltaba el handle sin confirmar, pero el store seguía vivo y por tanto
seguía con `cur.own` tomado, así que `recover()` se saltaba el stream. Que eso sea lo correcto lo confirma
que `tryWithStreamOwnership` capturó un `OverlappingFileLockException` — el comportamiento que `M-OWN-1`
defiende. Un quinto test, `I2`, estaba **verde por la razón equivocada**: pasaba por el salto, no por una
reconciliación.

## Los cinco bloqueos que quedan

```
CliParser.kt:687          applyOption   167 líneas (máx. 120)   complejidad 58 (máx. 25)
MainConsoleCli.kt:276     main                              complejidad 26 (máx. 25)
HumanConsoleRenderer.kt:199  line       147 líneas (máx. 120)   complejidad 76 (máx. 25)
```

Son **lógica acumulada**, no estilo. `config/detekt/detekt.yml` dice de esta regla, textualmente, que
"the honest response to one of those is an extraction, not a wider number", así que subir el umbral queda
descartado por política propia del repositorio. Los tres ficheros son ajenos a OBS y su refactorización es
otro bloque de trabajo con riesgo de regresión propio.

## Fallos de test, con atribución

Suite completa de `pipeline-application`, leída del XML (`tests=2675 failures=errors=5 skipped=121`):

| Fila | Atribución | Cómo se midió |
|---|---|---|
| `ShStepContractSuiteTest > returnStdout (C4 empty stderr)` | **previa, no regresión** | se revirtió `DurableShellExecutor.kt` a HEAD con hash verificado y siguió roja |
| `ObsBJvmDeathOutputRecoveryUatTest` | **previa, flake P2 conocido** (`bl-bl-01M4BM43QY000388Q8AE9EN740`) | ídem; sigue roja sin la extracción |
| `UatRunConcurrencyCharacterisationTest > two concurrent resume owners` | **previa, sin atribuir** | ya registrada como abierta antes de este bloque |
| `DirectivePluginContractSuiteTest > plugin jar is the certified build` | **previa, deriva de artefacto** | el jar certificado no coincide con el reconstruido |
| `ObsPc2IngestAgentPrototypeUatTest` (OBS-PC-208) | **defecto de arnés propio, corregido** | ver abajo |

El quinto **era mío**, y era el más caro de todos porque se presentó como un fallo de producto:
la fila leía el plano en cuanto el hijo tocaba `DONE`. `DONE` sólo dice que los bytes están en la FIFO; el
agente todavía tiene que leerlos y confirmarlos. Aislada pasaba, bajo carga de suite fallaba — que es
exactamente la firma de una aserción sobre una carrera. Ahora sondea con espera acotada. Medido: **5 verdes
de 5** con `--rerun-tasks`. La misma fila tenía además una sonda que se tragaba un `OutputReadResult.Refused`
y devolvía una vista truncada como si fuera el stream completo.

## Qué queda certificada, y por qué no es STEP-CERT

Lo que **sí** está establecido en este SHA, por fila y por mutación medida:

- `pipeline-step-sdk:runtime` — 198 tests, 0 fallos.
- `pipeline-output-store` — 64 tests, 0 fallos, con `M-STORE-1` (2 filas) y `M-STORE-2` (4 filas)
  demuestran que las aserciones tienen dientes y no son decorativas.
- `pipeline-architecture-tests` — 788 tests, 0 fallos.
- OBS-PC-208 — 5/5, e invertida por `M-PC2-1` y `M-PC2-2`, que la tumban en aserciones distintas.

Eso es **certificación por fila con mutaciones**, que es un nivel real de evidencia. Lo que no es es
STEP-CERT, que exige el Step completo y ejecutable en el mismo SHA. Un gate que nunca ha estado verde desde
OBS-1 no puede producir un veredicto sobre sí mismo: certificar ahí sería un falso verde por construcción.

## Por qué `check --rerun-tasks` es el gate y no otro

`754ddda0` retiró `lpr0-ci.yml`, `release.yml`, `v2-baseline.yml` y `sdkman-publish.yml` tras 60 runs
cancelados con los runners self-hosted offline, y `.github/workflows/` está vacío. No hay veredicto
automático que consultar, y **la ausencia de veredicto no es un veredicto**: durante toda la sesión OBS se
asumió que el árbol estaba en verde porque nadie miraba, y no lo estaba. Los seis bloqueos de la tabla
anterior son la medida de cuánto tiempo llevaba así.

## PRODUCT-GATE: `BLOCKED_EXTERNAL`

Distinto del anterior y no mixto. El PRODUCT-GATE exige UAT sobre la distribución instalada, cobertura, SAST
y `distZip` reproducible, y su propio "CI real del SHA". Sin CI remota ese último requisito no puede
cumplirse, así que queda `BLOCKED_EXTERNAL` con independencia de lo que se haga con los cinco hallazgos de
detekt. Arreglarlos no lo vuelve verde; sólo lo despeja.

## Para repetir esto

```bash
cd v2 && GRADLE_OPTS="-Djava.io.tmpdir=/var/home/rubentxu/.cache/gradle-tmp" \
  ./gradlew check --rerun-tasks --console=plain
# los conteos salen del XML, no de BUILD SUCCESSFUL:
#   v2/*/build/test-results/test/TEST-*.xml
```

Tres trampas de arnés que costaron tiempo en esta sesión y que conviene no repetir:

1. Los hallazgos de detekt se imprimen con prefijo `e: `, **igual que un error de compilación de Kotlin**.
   Un comprobador que use `^e: ` para detectar "la mutación no compilaba" fechará mal un fallo que sólo
   rompe lint.
2. `mutate.sh` fija `pipeline-application` tanto en la tarea como en el directorio del que lee los XML; con
   clases de otro módulo devuelve `sin XML`, que **no es verde ni rojo: es veredicto desconocido**.
3. Un `BUILD FAILED` temprano deja los XML **rancios** de la corrida anterior. Comparar la marca de tiempo
   del XML con la hora actual antes de leerlo.