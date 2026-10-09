# OBS-2 Nivel B — prototipo del agente de ingesta: la forma de ADR-OBS-003 funciona

| | |
|---|---|
| **Branch** | `par/cli-observation` |
| **Base SHA** | `975cd3db` |
| **Status** | **SHAPE CONFIRMED** — el mecanismo propuesto entrega; la producción sigue sin cablear |
| **Harness level** | HF3 — tres JVM reales, hijo real, FIFOs reales, redactor y `RedactingOutputIngress` genuinos |
| **Invierte** | `OBS2_LEVEL_B_PRODUCER_SURVIVAL_SPIKE.md` (`after_lines_written=0/50`) |
| **Blocks** | OBS-2 Nivel B / `OBS-PC-208` |

## Qué mide esta fila

El spike de Nivel B medió el defecto: el hijo sobrevive a su JVM y luego **muere** en su primera escritura
a stdout, porque su fd 1 es una tubería cuyo extremo de lectura vivía en la JVM muerta. `ADR-OBS-003`
propone un agente de ingesta —un proceso por run que posee los extremos de lectura, redacta en memoria y
confirma sólo bytes saneados— y esa fila pregunta si **la forma** funciona, antes de cablearla en
`DurableShellExecutor`.

Lo que esta fila **no** hace es cerrar el hueco de producción. `DurableShellExecutor` sigue dando
`Redirect.PIPE` al hijo en su propia JVM; el cableado real es implementación, no esta fila. Un spike que
se confunde con la implementación es la forma más común de certificar de más.

## El defecto que la propia fila tenía, y que casi certifica lo contrario

La primera versión del arnés **no era un prototipo del diseño, era el defecto con otros nombres**, y su
propiedad de seguridad central era falsa tal como estaba escrita:

```kotlin
if (!Files.exists(stdoutFifo)) Files.createFile(stdoutFifo)
```

`java.nio.file` no tiene `mkfifo`. `Files.createFile` crea un **fichero regular**, que en un `ls` es
indistinguible de una FIFO y se comporta al revés en todo lo que importa:

```text
$ stat -c '%n  type=%F  mode=%A' stdout.fifo
stdout.fifo  type=fichero regular  mode=-rw-r--r--
$ stat -c '%n  type=%F  mode=%A' real.fifo        # una FIFO de verdad
real.fifo    type=`fifo'          mode=prw-r--r--
```

Tres consecuencias, todas medidas:

1. **El spool en claro que `ADR-OBS-003` excluye estaba implementado.** El hijo escribió sus 97 bytes
   crudos —incluido `canary=GHS_DBG_SECRET`— a disco. La razón de peso de todo el diseño ("una FIFO no es
   un fichero, así que nada sin redactar llega al almacenamiento durable") era exactamente lo que el
   prototipo no cumplía.
2. **El `EOF` que dejó el plano vacío no era un problema de temporización.** Un fichero regular vacío
   devuelve `-1` al instante, así que los dos hilos del agente saltaron antes de que el hijo escribiera una
   línea y el agente imprimió `pc2-agent-drained` sin haber drenado nada. Se lee como "el drenador no
   funciona"; es "el rendezvous es el spool".
3. **La aserción que debía detectarlo no podía detectarlo.** `rawSecretBytesOnDisk()` recorría
   `controlRoot/output-plane` —justo donde los bytes *legítimamente saneados* viven—, así que daba `0`
   por construcción y nunca podía ver el `fifo/` del workspace. Al ensancharla a toda la raíz devolvió `1`
   de inmediato y localizó la verdad.

La corrección no es "usar una FIFO", que era lo escrito, sino **comprobar el tipo**: `mkfifo(1)` para
crear y `PosixFileAttributes.isOther` para verificar, en ambos lados del traspaso. `isOther` es
exactamente "ni fichero regular ni directorio", que es lo que una FIFO es, y evita pedirlo a `test -p` y
parsear su código de salida. La comprobación es `fail closed` a propósito: si el rendezvous no es una
tubería con nombre, el agente **rechaza drenar**, porque drenar un fichero regular es aceptar el spool.

## El coste declarado, y por qué está escrito en el código

El agente abre la FIFO `O_RDWR`. Abrirla sólo-lectura tiene éxito de inmediato pero reporta `EOF` hasta que
aparece un escritor, y un extremo de lectura sin escritor es indistinguible de un hijo terminado. Con
`O_RDWR` la apertura nunca bloquea y se mantiene una referencia de escritor, así que "todavía no hay datos"
no se confunde con "el hijo terminó".

El precio es real y está en el KDoc, no escondido: **el agente no puede ver `EOF`**, porque él mismo es
escritor. La finalización es un hecho aparte —el terminal del step, o un sellado— y un agente de producción
necesita un sellado explícito que este prototipo no tiene. Consecuencia medida por M-PC2-2: al morir el
agente, que era el único lector, el hijo **muere por `SIGPIPE`** en su siguiente escritura. La FIFO no
enmascara el problema; lo traslada intacto al proceso que debe sobrevivir.

## Atribución de las mutaciones

Cada mutación tumba **una** aserción, y son distintas, que es lo que las hace merecedoras:

| Mutación | Qué cambia | Fila que tumba | Aserción |
|---|---|---|---|
| **M-PC2-1** | el rendezvous vuelve a ser fichero regular **y se quitan las tres guardas estructurales** (launcher, agente, test) | 1/1 | `no output reached the plane while the runtime was alive` |
| **M-PC2-2** | matar el drenador junto con el runtime (`pkill -f <fifoDir>` tras el `kill -9`) | 1/1 | `the child died instead of being served` |

M-PC2-1 se diseñó a propósito contra las guardas y no contra las aserciones: si la fila siguiera verde tras
convertir el rendezvous en fichero regular **y borrar los tres `isFifo`/`require`**, su columna vertebral
sería decorativa y estaríamos creyéndola por los guardas en lugar de por lo que mide. Se pone roja en la
aserción conductual, luego el plano no se llena por construcción sino porque el drenador funciona.

M-PC2-2 separa las dos propiedades que la fila distingue a propósito: perder el hijo y perder los bytes
presentan ambos como "no hay `plain-after-`". La aserción que salta es la que nombra la causa, y es la
correcta.

### Una predicción propia corregida por medición

Antes de ejecutar M-PC2-2 afirmé que la FIFO bufferizaría la escritura del hijo y éste terminaría, de modo
que la fila caería en `after.length > before.length`. **Es falso.** El kernel entrega `SIGPIPE` en cuanto
el último lector cierra, y el agente lo era. La fila cayó en `child died`, tras 121 s esperando `DONE`.
La distinción importa para `ADR-OBS-003`: cambiar de tubería a FIFO no compra ninguna tolerancia; lo
único que decide es quién posee el extremo de lectura cuando el runtime muere.

## Lo que queda sin demostrar

- **El cableado de producción.** `DurableShellExecutor` sigue dando `Redirect.PIPE` al hijo en su propia
  JVM. El agente existe y funciona; nadie lo lanza todavía.
- **El sellado.** El agente no puede observar `EOF` y este prototipo termina siendo matado. La decisión de
  superficie que implica (sellado explícito, o reenganche tras reinicio) está explícitamente sin decidir
  en `ADR-OBS-003` y requiere al propietario.
- **`STEP-CERT` y `PRODUCT-GATE`: `NOT_RUN` en todos los SHA.** Sin CI remota en el repositorio
  (`754ddda0` la retiró); el sustituto es `./gradlew check --rerun-tasks` sobre el SHA exacto más UAT
  contra la distribución instalada.

## Reproducir

```bash
cd v2 && GRADLE_OPTS="-Djava.io.tmpdir=/var/home/rubentxu/.cache/gradle-tmp" \
  ./gradlew :pipeline-application:test --tests '*ObsPc2IngestAgentPrototypeUatTest*' --rerun

# Las dos mutaciones, con hash verificado antes y después
bash ~/obsE4diag/mutate.sh M-PC2-2 \
  v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsPc2IngestAgentPrototypeUatTest.kt \
  ~/obsPcdiag208/mPC2_2.py ObsPc2IngestAgentPrototypeUatTest
bash ~/obsPcdiag208/runM2.sh   # M-PC2-1, tres ficheros
```

`BUILD SUCCESSFUL` no es evidencia aquí: los conteos salen del XML en
`v2/pipeline-application/build/test-results/test/`.