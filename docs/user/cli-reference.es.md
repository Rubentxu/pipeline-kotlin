# PipelineK — Referencia del CLI

> **Divergencia documental.** Esta página afirmaba antes el contrato de `0.39.0` y afirmaba que
> `parallel` / `retry` / `catchError` no existían. Esa afirmación no se sostenía contra el código: sí
> están declarados en el DSL de v2 y los ejercita `examples/run.sh`. Registrado el 2026-10-06.
> Ver `docs/user/README.es.md` → «Divergencias conocidas».

**Verificado contra el binario instalado**, no sólo contra las fuentes: `pipelinek 0.47.0` instalado
por asdf (`~/.asdf/installs/pipelinek/0.47.0`), en esta máquina, 2026-10-06. El HEAD del repositorio
en ese momento era `54ae56f0`. Las afirmaciones marcadas **[ran]** abajo se ejecutaron contra ese
binario; el resto cita la línea de código que las produce.

## Qué podrás hacer al terminar

- Invocar cada subcomando correctamente, con sus flags reales y sin inventar nada.
- Predecir el código de salida antes de pulsar Enter — incluidos los pares que parecen iguales y no lo son.
- Ejecutar un pipeline y leer después su historial con `events`, `events verify` y `console`
  sin re-ejecutar nada.
- Producir un `opId` para `console` en lugar de adivinarlo.
- Entender por qué `validate` diciendo `VALIDATION SUCCESSFUL` no significa que `run` acepte el script.

## El binario

El comando es `pipelinek`. Cómo obtenerlo está en [`installation.es.md`](installation.es.md).
Instalado con asdf es un shim:

```text
~/.asdf/shims/pipelinek   →   ~/.asdf/installs/pipelinek/0.47.0
```

## No existe `help`

`pipelinek help` no imprime el uso. Falla: **[ran]**

```text
$ pipelinek help
Invalid CLI arguments: InvalidCommand(value=help)
Usage: pipeline <validate|run> [--db <path>] [--resume|--rerun] [--control-root <path>] <script>
$ echo $?
1
```

Sin argumentos pasa lo mismo, salvo que el error es `MissingCommand`. Si escribes un wrapper, no
lances `pipelinek help` esperando texto por stdout.

## Hueco conocido: `run` imprime un muro JSON, no un log legible

**Es un defecto abierto del producto, registrado aquí porque condiciona todo lo que ves.**

Lo que escribe hoy `pipelinek run`, verificado contra el binario instalado:

- **stdout**: todo el flujo de eventos, codificado como un único array JSON, sin condición —
  `println(JsonEventLog.encode(events))` en `Main.kt:436`. No hay bandera para cambiarlo ni para
  suprimirlo.
- **stderr**: dos o tres líneas de resumen — `Pipeline finished with SUCCESS`, o
  `cause [SCRIPT]: shell exited with code 3`.
- **La salida de un step `sh` no aparece en ninguno de los dos.** Sólo se alcanza por `console`,
  desde el plano de control durable.

Así que un usuario que escribe `pipelinek run hello.pipeline.kts` se come un muro de sobres JSON
con `eventId`, `occurredAt` y `runId`. Nada de esa salida se lee como la ejecución que informa.

Lo que el diseño ya dice que debería pasar. [`CLI_OBSERVABILITY_SPEC.md`](../../docs/v2/03-specifications/CLI_OBSERVABILITY_SPEC.md)
separa dos ejes:

- **`view`** — *qué* información: `normal` (la vista humana por defecto: ciclo de vida, fallos y una
  cola acotada de la transcripción de la operación que falló), `events`, `full`, `console`, `quiet`.
- **`format`** — *cómo* se renderiza: `text` (legible), `jsonl`, `json`.

La spec dice que en un `run` con éxito "no transmite toda la salida hija en modo `normal`. Muestra
los eventos de ciclo de vida importantes y el resumen final", y que el JSON aparece **sólo** cuando
se pide `--format jsonl|json`, con stdout reservado a la carga de máquina.

Nada de eso está implementado. No existe `--view`, `--format`, `--quiet`, `--follow` ni `--fields`
en toda la aplicación, ni los comandos `inspect` o `logs`. La spec está marcada `PROPOSED`.

Esto además diverge de un ADR aceptado. ADR-0077 §8 exige que "execution output (stdout/stderr/
transcript) stays on a separate channel", y su lista de *Rejected* excluye explícitamente "events
encoded into console logs". Hoy los eventos **son** la salida de consola.

Tampoco cumple la regla de familiaridad con Jenkins que este repositorio se impone a sí mismo, que
es la razón de que un `pipelinek run` no se parezca a `+ echo hello` / `hello` / `Finished: SUCCESS`.

**Hasta que eso se decida, esto es lo que funciona.** Desmonta el JSON tú mismo, y lee la salida de
los shells desde el plano durable:

```bash
# cada evento, una línea, en orden
pipelinek run --db ./.d/j.sqlite --workspace . hello.pipeline.kts \
  | jq -r '.[] | (.sequence|tostring) as $s | (($s+"        ")[0:4]) + "  " + .kind'

# lo que un step sh imprimió de verdad
pipelinek console --control-dir ./.d/durable-shell "$RUN_ID" "$OP_ID"
```

## Subcomandos

| Subcomando | Qué hace | Línea |
|---|---|---|
| `version` | Lee `Implementation-Version` del jar. Sin versión → exit `3` | `Main.kt:72`, `:80` |
| `doctor` | Imprime `jdk:` / `os:` / `workdir:`. No escribible → exit `2` | `Main.kt:91`, `:110` |
| `events` | Historial estructurado desde el journal | `Main.kt:115` |
| `events verify` | Verifica el historial **persistido** contra un contrato YAML. **No** re-ejecuta | `Main.kt:116` |
| `console` | Transcripción de salida de un `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` \| `rotate` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | Compila e informa diagnósticos | `Main.kt:190` |
| `run` | Ejecuta | `Main.kt:232` |

Los seis primeros se despachan **antes** del parser de argumentos (`Main.kt:72`–`:142`); sólo
`validate` y `run` pasan por `CliParser` (`CliParser.kt:135`). Los flags de la sección siguiente
pertenecen a esos dos y a nada más.

La salida de `doctor` son tres líneas fijas: **[ran]**

```text
jdk: 24.0.2 (Eclipse Adoptium)
os: Linux 7.2.7-ogc1.1.fc44.x86_64
workdir: /ruta/a/tu/checkout (writable)
```

Escribe un fichero sonda en el directorio actual y lo borra, así que también es una prueba de
permiso de escritura.

## Flags (sólo `run` y `validate`)

| Flag | Argumento | Efecto | Línea |
|---|---|---|---|
| `--db` | ruta | Journal SQLite. **Sin él, todo queda en memoria**. Debe incluir un directorio — ver trampa 11 | `CliParser.kt:192` |
| `--resume` | — | Reanuda un run anterior. **Requiere `--db`**, si no exit `2` | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | Fuerza un run nuevo. **Requiere `--db`** | `CliParser.kt:202` |
| `--control-root` | ruta | Control root del shell durable | `CliParser.kt:208` |
| `--workspace` | ruta | Directorio de trabajo | `CliParser.kt:212` |
| `--isolated` | — | Workspace temporal gestionado | `CliParser.kt:216` |
| `--plugin-jar` | ruta (repetible) | JARs de plugin | `CliParser.kt:220` |
| `--allow-network` | — | Permite salida a red. **Denegado por defecto** | `CliParser.kt:229` |
| `--sandbox-profile` | `none`\|`local`\|`os` | `os` → rechazado | `CliParser.kt:233` |

`--allow-network` **no** toma valor: `--allow-network=<valor>` no se acepta (`CliParser.kt:224`).

**El control root por defecto se deriva de `--db`, no es fijo.** Sin `--control-root` es
`dirname(<ruta de --db>)/durable-shell` (`Main.kt:501`). Así que estas dos ejecuciones usan
directorios de estado durable distintos, lo cual importa para las credenciales:

```bash
pipelinek run --db ./.pipelinek/run.sqlite p.kts    # durable shell → ./.pipelinek/durable-shell
pipelinek run --db /tmp/other.sqlite     p.kts    # durable shell → /tmp/durable-shell
```

## Las once trampas

Son los puntos donde el CLI hace algo distinto de lo que espera un lector cuidadoso. Las once son
hechos de comportamiento, citados por línea u observados contra el binario.

### 1. Los flags van **antes** del script. Después se ignoran, en silencio.

El bucle del parser consume tokens mientras empiecen por `--` (`CliParser.kt:144`), y el primer
token que no empieza por `--` se toma como script y **el parseo termina** (`:151`). Entonces:

```bash
pipelinek run --db /tmp/j.db build.pipeline.kts   # correcto
pipelinek run build.pipeline.kts --db /tmp/j.db   # --db se ignora, sin error
```

No hay aviso. Si un flag «no hace nada», mira primero su posición.

### 2. Flag desconocido → exit `1`. El resto de rechazos de entrada → exit `2`. No los unifiques.

Un `--algo` desconocido imprime el uso y sale con `1` (`CliParser.kt:242`, `Main.kt:151`). Todo lo
demás que se rechaza en `run`/`validate` — script inexistente, `validate` fallido, `--resume` sin
`--db`, `--control-root` inválido, step no canónico, lease ya tomado, fallo de compilación — sale con
`2` (`Main.kt:186,225,241,269,430,830`). `doctor` con directorio no escribible también sale con `2`
(`Main.kt:110`).

Si escribes un wrapper tienes que ramificar sobre el código, no asumir que «entrada mala es siempre 2».

### 3. `--resume` y `--rerun` necesitan `--db`; los pares son excluyentes.

- `--resume` sin `--db` → exit `2` (`CliParser.kt:196`, `Main.kt:239`).
- `--rerun` sin `--db` → exit `2` (`CliParser.kt:202`).
- `--resume` **y** `--rerun` juntos → error; son excluyentes (`CliParser.kt:196,202`).
- `--isolated` **y** `--workspace` juntos → error; son excluyentes (`CliParser.kt:154`).

Sin `--db` el journal vive sólo en memoria, así que no hay nada de qué reanudar ni contra qué re-ejecutar.

### 4. `events verify` **no** re-ejecuta nada.

Lee el historial ya persistido en `--db` y lo compara con un fichero de contrato. Si quieres que el
pipeline vuelva a ejecutarse de verdad, eso es `run`, no `events verify`.

### 5. `events` documenta un flag que no existe.

El KDoc en `MainEventsCli.kt:14` anuncia `--subject-kind`. **El flag real es `--subject`.**

### 6. `events verify` anuncia un flag que no existe.

La línea de uso que imprime el propio binario menciona `[--after-last-run-started]`, y ese flag
**no existe** **[ran]**. El real es `--scope last-segment`.

Ambas trampas tienen la misma causa: la prosa de ayuda se separó del parser. Confía en las tablas de
flags, no en el texto de ayuda.

### 7. `opId` no es un número que inventes.

`console` recibe un `runId` y un `opId`. El `opId` se deriva del run y aparece en el historial de
eventos; nunca es un índice suelto. Su formato es (`OpId.kt:60`):

```text
<runId>-s<stageIndex>-<stepIndex>[-b<branchIndex>][-bp<N>-<segment>…]
```

Para un pipeline normal de dos stages las suposiciones obvias — `0`, `1`, `op-1` — fallan todas con
`console-refused: unknown-stream`.

**En runs lineales** se puede componer a partir del subject del evento **[ran]**:

```bash
RUN_ID=$(jq -r '.[0].runId' run.json | head -1)
OP_ID=$(pipelinek events --db ./run.sqlite "$RUN_ID" --kind StepStarted \
        | jq -r 'select(.subject.kind=="STEP") | "\(.subject.segments[2])-s\(.subject.segments[4])-\(.subject.segments[6])"' \
        | head -1)
pipelinek console --control-dir ./.pipelinek/durable-shell "$RUN_ID" "$OP_ID"
```

**En runs con ramas no**, y no es obvio: un step de `parallel` lleva segmentos `-b` y `-bp` extra, y
los subjects de `StepStarted` sólo registran `stage` y `step` — así que componerlos da un id que
`console` rechaza **[ran]**. El historial de eventos no lleva el `opId`.

La fuente de verdad en todos los casos es el nombre del fichero de stream, que es
`<runId>_<opId>_transcript`:

```bash
for OP in $(ls -1 ./.pipelinek/durable-shell/output-plane/streams/ \
            | sed -E "s/^${RUN_ID}_//; s/_transcript$//"); do
  pipelinek console --control-dir ./.pipelinek/durable-shell "$RUN_ID" "$OP"
done
```

Fíjate en que el `opId` repite el `runId` como prefijo. Copia el nombre entero menos ambos envoltorios;
no quites el prefijo.

### 11. `--db` sin componente de directorio revienta y luego se cuelga. **[defecto verificado]**

`Main.kt:501` deriva el control root por defecto como `dbPath.parent.resolve("durable-shell")`. Un
nombre de fichero desnudo no tiene padre, así que `Paths.get("run.sqlite").parent` es `null` y la
ejecución muere con un `NullPointerException` no gestionado:

```text
$ pipelinek run --db run.sqlite --workspace . pipeline.kts
Exception in thread "main" java.lang.NullPointerException: Cannot invoke
  "java.nio.file.Path.resolve(String)" because the return value of
  "java.nio.file.Path.getParent()" is null
        at dev.rubentxu.pipeline.v2.application.MainKt.main(Main.kt:501)
```

Peor: el proceso **no termina** tras morir el hilo principal — hay que matarlo **[ran]**:

```text
$ timeout -s KILL 45 pipelinek run --db run.sqlite --workspace . pipeline.kts; echo $?
137          # 137 = lo mató el timeout; la orden nunca volvió sola
```

Añadiendo cualquier componente de directorio funciona con normalidad **[ran]**:

```bash
pipelinek run --db ./.d/run.sqlite --workspace . pipeline.kts    # exit 0
```

Así que: **dale siempre un directorio a `--db`**, y `mkdir -p` antes. Esto es un defecto abierto del
producto, no un apaño documental: la entrada no se valida y el fallo no es tipado ni se reporta por el
contrato de códigos de salida.

### 8. `credentials add` y `rotate` no se pueden automatizar.

Ambos piden el secreto con `Console.readPassword()` (`MainCredentialsCli.kt:32,35`), que exige una
terminal real. **[ran]** Mandarlo por tubería o redirigir desde `/dev/null` no ayuda:

```text
$ printf 'sekrit\n' | pipelinek credentials add pipedcred
Enter secret value: Error: no TTY available
$ echo $?
1
```

Si aprovisionas credenciales en un job sin terminal, este CLI no puede hacerlo. Usa
`PIPELINE_STORE_PASSPHRASE` para la passphrase, pero el secreto en sí sigue necesitando una TTY.

### 9. `credentials` rechaza una invocación mala con `1`, no con `2`.

`pipelinek credentials` sin subcomando imprime su uso y devuelve `1` (`MainCredentialsCli.kt:99`),
mientras que `pipelinek events`, `pipelinek events verify` y `pipelinek console` con uso inválido
devuelven `2`. **[ran]** Los tres subcomandos no coinciden, así que ramifica sobre el subcomando, no
sobre «error de uso».

### 10. El almacén de credenciales que escribe `add` y el que lee `run` son ficheros distintos.

- `credentials add` usa por defecto `~/.pipeline/credentials.bin` (`MainCredentialsCli.kt:62`).
- `run` usa por defecto `<control-root>/../credentials.bin` (`Main.kt:705`,
  `MainCredentialsSupport.kt:28`) — con el control root por defecto derivado, eso es
  `dirname(<--db>)/credentials.bin`.

Así que `credentials add` seguido de `run` con rutas por defecto te deja un almacén que el run nunca
abre, y el run sigue en silencio **sin credenciales** (`Main.kt:707` devuelve `null` cuando el
fichero no existe). Nada te avisa.

Fija la ruta explícitamente para que ambos coincidan. **[ran]** El override funciona:

```bash
export PIPELINE_CREDENTIALS_STORE="$HOME/.pipeline/credentials.bin"
pipelinek credentials list     # lo lee
```

## Códigos de salida

| Código | Significado |
|---|---|
| `0` | Correcto, incluido `RunOutcome.Unstable` |
| `1` | `Failure` / `Aborted` del pipeline; argumentos CLI inválidos (`Main.kt:151`); fallo operativo del subcomando `credentials` — sin TTY, `--kind` desconocido, sin passphrase para `list` **[ran]** |
| `2` | Rechazo de invocación/admisión en `run`/`validate`, más los errores de **uso** de `events`, `events verify` y `console` **[ran]** |
| `3` | Manifiesto del jar sin `Implementation-Version` (`Main.kt:80`); o, **durante un run**, un almacén de credenciales existente con passphrase no disponible o incorrecta (`Main.kt:723,728`) |
| `4` | Almacén de credenciales manipulado, **durante un run** (`Main.kt:732`) |

Tres cosas que conviene memorizar:

- **`Unstable` es `0`.** Un pipeline que termina inestable no ha «fallado a medias» a ojos del shell.
- **`1` y `2` no son intercambiables.** Ver trampa 2.
- **Los códigos `3` y `4` son sólo de run.** `pipelinek credentials list` sin passphrase devuelve `1`
  y una explicación **[ran]**, no `3`. Y una passphrase **incorrecta** tampoco falla: lista el
  almacén con `KIND` y `SCOPE` como `Unknown` y sale con `0` **[ran]**.

## Variables de entorno

| Variable | Efecto | Línea |
|---|---|---|
| `PIPELINE_CREDENTIALS_STORE` | Ruta del almacén de credenciales para **tanto** el CLI como un run. Es la única forma de eliminar la trampa 10 | `MainCredentialsCli.kt:62`, `Main.kt:704` |
| `PIPELINE_STORE_PASSPHRASE` | Passphrase del almacén de secretos | `PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Relajación del sandbox | `SandboxConfig.kt:89` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Conservación de rutas del sandbox | `SandboxConfig.kt:90` |
| `APP_HOME` | Classpath de los plugins incluidos | `scripting-api/.../ScriptDefinition.kt:81` |

`PATH` también se lee como respaldo al componer el entorno de un step
(`EnvironmentComposer.kt:104`, `EnvModel.kt:61,148`), y `PIPELINE_CREDENTIALS_STORE` se lee desde
tres sitios distintos en vez de uno. **Los ficheros `.env` no se cargan automáticamente** — expórtalos
tú si tu pipeline los necesita.

## `events`, `events verify`, `console`

### `events`

Historial estructurado desde el journal. Un sobre JSON por línea en **stdout**
(`MainEventsCli.kt:77`); el cursor de continuación va a **stderr** (`:80`).

| Flag / posicional | Significado | Línea |
|---|---|---|
| `--db <ruta>` | Journal a leer. Ausente → exit `2` | `MainEventsCli.kt:34`, `:46` |
| `--kind K` | Filtrar por tipo de evento | `:35` |
| `--subject <v1:kind:seg…>` | Filtrar por subject | `:36` |
| `--limit N` | Por defecto **100** | `:37` |
| `--after-cursor TOKEN` | Continuar después de un cursor | `:38` |
| posicional | `runId`; ausente → exit `2` | `:39`, `:46` |

Como el cursor va a stderr, `... | jq` queda limpio. Eso es lo que hace legible el espinazo de eventos:

```bash
pipelinek events --db ./run.sqlite "$RUN_ID" \
  | jq -r '[.occurredAt[11:19], .kind, (.subject.value // "-")] | @tsv'
```

### `events verify`

Compara el historial persistido con un contrato YAML.

| Flag | Significado | Línea |
|---|---|---|
| `--db` | Journal a leer | `MainEventsVerifyCli.kt:31` |
| `--run` | Run id a verificar | `:31` |
| `--contract` | Fichero de contrato | `:31` |
| `--from-sequence N` | Secuencia inicial | `:31` |
| `--scope last-segment` | Acotar la comprobación (el flag real; ver trampa 6) | `:32` |

Imprime `contract:`, `observed-terminal-outcome:`, `acceptance:` y las violaciones.
Exit `0` = PASSED, `1` = FAILED, `2` = error de uso o de decodificación.

### `console`

La transcripción de salida de un único `opId`.

| Flag / posicional | Significado | Línea |
|---|---|---|
| `--control-dir <ruta>` | Directorio de control | `MainConsoleCli.kt:140` |
| `--max-bytes N` | Por defecto 65536 | `:141` |
| `--after-cursor TOKEN` | Continuar después de un cursor | `:142` |
| `--range FROM:TO` | Rango de bytes | `:143` |
| posicionales | `runId` y `opId` | `:145` |

Un stream desconocido — incluido un `opId` equivocado — sale con `1` (`:98`); el uso inválido sale
con `2` (`:157`).

## `credentials`

| Subcomando | Significado |
|---|---|
| `add [--kind <tipo>] <id>` | Pide el secreto (necesita TTY) y lo almacena |
| `list` | Imprime id, tipo y ámbito de cada entrada |
| `remove <id>` | Borra una entrada |
| `rotate [--kind <tipo>] <id>` | Re-cifra con un secreto nuevo (necesita TTY) |

`--kind` acepta `secret-text`, `username-password`, `ssh-private-key`, `secret-file`, `certificate`,
`zip` y `username-colon-password` (`MainCredentialsCli.kt:76`). Un `--kind` equivocado sale con `1`
**[ran]**.

## `validate` frente a `run` — la diferencia que más confunde

| | `validate` | `run` |
|---|---|---|
| Lanza procesos del SO | **Nunca** (`Main.kt:191`) | Sí |
| Journal SQLite | No: `--db` se acepta y **se ignora** (retorna en `:228`, antes que `:233`) | Sí |
| Eventos | Emite a un almacén **en memoria** y los imprime como JSON por stdout `:209` | Persiste en SQLite |
| Ejecuta el puente canónico | **NO** | Sí: `Main.kt:423` (en memoria) y `:827` (durable) |
| Detecta `git()` / `load()` / `node{}` / `ansiColor{}` | **No** | Sí → exit `2` |
| Mensaje final | `VALIDATION SUCCESSFUL` por stderr `:227` | `Pipeline finished with …` por stderr |

La consecuencia que debes interiorizar: **`validate` no es un ensayo general.**
Puede imprimir `VALIDATION SUCCESSFUL` para un script que `run` después rechaza con exit `2`, porque
`validate` nunca llega al puente canónico donde se rechazan esas cuatro construcciones. **La
comprobación real es `run`.**

Y si pasas `--db` a `validate` esperando un journal, no obtienes ninguno: el flag se acepta y se
descarta.

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** (`.github/workflows/` no existe; el commit
`754ddda0` retiró los workflows). Por eso esta página nunca afirma «CI en verde» ni «production
ready»: el PRODUCT-GATE está `BLOCKED_EXTERNAL`. Toda afirmación arriba es o una cita de código o una
observación del binario instalado — no un recibo de build publicado.

## Checklist

- [ ] Mis flags van **antes** de la ruta del script.
- [ ] `--resume` / `--rerun` vienen con `--db`, y nunca los dos.
- [ ] Elegí `--isolated` **o** `--workspace`, no los dos.
- [ ] `--allow-network` no lleva `=valor`.
- [ ] Ramifico sobre exit `1` y exit `2` por separado.
- [ ] No lanzo `pipelinek help` — sale con `1`.
- [ ] Sé que `events verify` lee historial; no re-ejecuta nada.
- [ ] Usé `--subject` y `--scope last-segment`, no los nombres de la prosa de ayuda.
- [ ] Mi `opId` salió del historial de eventos, no de una suposición.
- [ ] `PIPELINE_CREDENTIALS_STORE` está definido, para que `credentials add` y `run` compartan almacén.
- [ ] Validé con `run`, no sólo con `validate`.

## Siguiente

- [`installation.es.md`](installation.es.md) — cómo conseguir este binario.
- [`events-and-troubleshooting.es.md`](events-and-troubleshooting.es.md) — leer un run fallido.
- [`pipeline-dsl.es.md`](pipeline-dsl.es.md) — la superficie del DSL, y qué construcciones están probadas,
  declaradas o fallan cerradas.
- [`README.es.md`](README.es.md) — el índice de toda la documentación de usuario.