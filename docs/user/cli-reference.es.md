# PipelineK — Referencia del CLI

> **Divergencia documental.** Esta página afirmaba antes el contrato de `0.39.0` y afirmaba que
> `parallel` / `retry` / `catchError` no existían. Esa afirmación no se sostenía contra el código: sí
> están declarados en el DSL de v2 y los ejercita `examples/run.sh`. La página documenta ahora la rama
> de desarrollo (`0.47.0`). Registrado el 2026-10-06. Ver `docs/user/README.es.md` → «Divergencias conocidas».

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**No verificado contra un binario publicado.** La release publicada actual es la `0.47.0` (el digest de su ZIP está en el `SHA256SUMS` de la release); ver la nota de divergencia abajo.

## Qué podrás hacer al terminar

- Invocar todos los subcomandos correctamente, con sus flags reales y sin
  inventar nada.
- Predecir el exit code antes de pulsar Enter — incluidos los dos casos que
  parecen iguales y no lo son.
- Ejecutar un pipeline y leer después su historial con `events`,
  `events verify` y `console` sin volver a ejecutar nada.
- Entender por qué un `VALIDATION SUCCESSFUL` de `validate` **no** significa que
  `run` vaya a aceptar el script.

## El binario

El comando es `pipelinek`. En un checkout compilado está en:

```text
v2/pipeline-application/build/install/pipelinek/bin/pipelinek
```

## Subcomandos

| Subcomando | Qué hace | Línea |
|---|---|---|
| `version` | Lee `Implementation-Version` del jar. Sin versión → exit `3` | `Main.kt:72`, `:80` |
| `doctor` | Comprueba JDK, SO y si el cwd es escribible. No escribible → exit `2` | `Main.kt:91`, `:110` |
| `events` | Historial estructurado del journal | `Main.kt:115` |
| `events verify` | Verifica el historial **persistido** contra un contrato YAML. **No** re-ejecuta | `Main.kt:116` |
| `console` | Transcripción de salida de un `opId` | `Main.kt:133` |
| `credentials` | `add` \| `list` \| `remove` | `Main.kt:140`, `MainCredentialsCli.kt:94` |
| `validate` | Compila e informa diagnósticos | `Main.kt:190` |
| `run` | Ejecuta | `Main.kt:232` |

Sólo `validate` y `run` pasan por `CliParser` (`CliParser.kt:135`). Los flags
de abajo pertenecen a esos dos y a nada más.

## Flags (sólo `run` y `validate`)

| Flag | Argumento | Efecto | Línea |
|---|---|---|---|
| `--db` | path | Journal SQLite. **Sin él, todo es en memoria** | `CliParser.kt:192` |
| `--resume` | — | Reanuda un run previo. **Exige `--db`**, si no exit `2` | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | Fuerza un run fresco. **Exige `--db`** | `CliParser.kt:202` |
| `--control-root` | path | Raíz de control del shell durable | `CliParser.kt:208` |
| `--workspace` | path | Directorio de trabajo | `CliParser.kt:212` |
| `--isolated` | — | Workspace scratch gestionado | `CliParser.kt:216` |
| `--plugin-jar` | path (repetible) | JARs de plugin | `CliParser.kt:220` |
| `--allow-network` | — | Permite egress. **Denegado por defecto** | `CliParser.kt:229` |
| `--sandbox-profile` | `none`\|`local`\|`os` | `os` → rechazado | `CliParser.kt:233` |

`--allow-network` **no** admite valor: `--allow-network=<value>` no se acepta
(`CliParser.kt:224`).

## Las seis trampas

Aquí es donde el CLI hace algo distinto de lo que espera quien lee con
cuidado. Las seis son hechos de comportamiento del parser, con la línea que
los produce.

### 1. Los flags van **antes** del script. Después se ignoran, en silencio.

El bucle del parser consume tokens mientras empiecen por `--`
(`CliParser.kt:144`), y el primer token que no empieza por `--` se toma como
script y **el parseo se detiene** (`:151`). Por tanto:

```bash
pipelinek run --db /tmp/j.db build.pipeline.kts   # correcto
pipelinek run build.pipeline.kts --db /tmp/j.db   # --db se ignora, sin error
```

No hay ningún aviso. Si un flag «no hace nada», mira primero su posición.

### 2. Flag desconocido → exit `1`. El resto de rechazos de entrada → exit `2`. No los unifiques.

Un `--algo` desconocido imprime el mensaje de uso y sale con `1`
(`CliParser.kt:242`, `Main.kt:151`). Todo lo demás que se rechaza — script
inexistente, `validate` fallido, `--resume` sin `--db`, `--control-root`
inválido, step no canónico, lease ya poseído, fallo de compilación, cwd no
escribible en `doctor` — sale con `2` (`Main.kt:186,225,241,269,430,830`).

Si escribes un script wrapper, tienes que ramificar sobre el código; no puedes
asumir que «entrada mala es siempre 2».

### 3. `--resume` y `--rerun` necesitan `--db`; los pares son excluyentes.

- `--resume` sin `--db` → exit `2` (`CliParser.kt:196`, `Main.kt:239`).
- `--rerun` sin `--db` → exit `2` (`CliParser.kt:202`).
- `--resume` **y** `--rerun` juntos → error; son excluyentes
  (`CliParser.kt:196,202`).
- `--isolated` **y** `--workspace` juntos → error; son excluyentes
  (`CliParser.kt:154`).

Sin `--db` el journal vive sólo en memoria, así que no hay nada de lo que
reanudar ni sobre lo que re-ejecutar.

### 4. `events verify` **no** re-ejecuta nada.

Lee el historial ya persistido en `--db` y lo compara con un fichero de
contrato. Si quieres que el pipeline se ejecute otra vez, eso es `run`, no
`events verify`.

### 5. `events` documenta un flag que no existe.

El KDoc de `MainEventsCli.kt:14` anuncia `--subject-kind`. **El flag real es
`--subject`.**

### 6. `events verify` anuncia un flag que no existe.

El mensaje de uso de `MainEventsVerifyCli.kt:37` menciona
`[--after-last-run-started]`, **que no existe**. El real es
`--scope last-segment`.

Ambas trampas tienen la misma causa: la documentación se quedó atrás respecto
al parser. Confía en la tabla de flags, no en la prosa de ayuda.

## Exit codes

| Code | Significado |
|---|---|
| `0` | Éxito, incluido `RunOutcome.Unstable` |
| `1` | Pipeline `Failure` / `Aborted`, **y argumentos CLI inválidos** (`Main.kt:151`) |
| `2` | Invocación / admisión: script no encontrado, `validate` fallido, `--resume`/`--rerun` sin `--db`, `--control-root` inválido, **step no canónico**, lease ya poseído, compilación fallida, `doctor` no escribible |
| `3` | Artefacto sin `Implementation-Version`; credenciales sin passphrase o con passphrase incorrecta |
| `4` | Almacén de credenciales adulterado |

Dos cosas que conviene memorizar:

- **`Unstable` es `0`.** Un pipeline que termina inestable no está «a medio
  fallar» a ojos del shell.
- **`1` y `2` no son intercambiables.** Ver la trampa 2.

## Variables de entorno

| Variable | Efecto | Línea |
|---|---|---|
| `PIPELINE_STORE_PASSPHRASE` | Passphrase del almacén de secretos | `credentials-local/.../PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Relajación del sandbox | `step-sdk/runtime/.../SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Conservación de rutas del sandbox | `SandboxConfig.kt:55` |
| `APP_HOME` | Classpath de plugins empaquetados | `scripting-api/.../ScriptDefinition.kt:81` |

No se lee ningún otro `System.getenv` en `src/main`. **Los ficheros `.env` no
se cargan automáticamente**: expórtalos tú si tu pipeline los necesita.

## `events`, `events verify`, `console`

### `events`

Historial estructurado del journal.

| Flag / posicional | Significado | Línea |
|---|---|---|
| `--db <path>` | Journal a leer. Si falta → exit `2` | `MainEventsCli.kt:34`, `:46` |
| `--kind K` | Filtra por kind de evento | `:35` |
| `--subject <v1:kind:seg…>` | Filtra por subject | `:36` |
| `--limit N` | Por defecto **100** | `:37` |
| `--after-cursor TOKEN` | Continúa tras un cursor | `:38` |
| posicional | `runId`; si falta → exit `2` | `:39`, `:46` |

Un envelope JSON por línea en **stdout** (`:77`); el cursor va a **stderr**
(`:80`).

### `events verify`

Compara el historial persistido con un contrato YAML.

| Flag | Significado | Línea |
|---|---|---|
| `--db` | Journal a leer | `MainEventsVerifyCli.kt:31` |
| `--run` | Run id a verificar | `:31` |
| `--contract` | Fichero de contrato | `:31` |
| `--from-sequence N` | Secuencia de inicio | `:31` |
| `--scope last-segment` | Alcance de la comprobación (el flag real; ver trampa 6) | `:32` |

Imprime `contract:`, `observed-terminal-outcome:`, `acceptance:` y las
violaciones. Exit `0` = PASSED, `1` = FAILED, `2` = uso o decode inválido.

### `console`

La transcripción de salida de un único `opId`.

| Flag / posicional | Significado | Línea |
|---|---|---|
| `--control-dir <path>` | Directorio de control | `MainConsoleCli.kt:140` |
| `--max-bytes N` | Por defecto 65536 | `:141` |
| `--after-cursor TOKEN` | Continúa tras un cursor | `:142` |
| `--range FROM:TO` | Rango de bytes | `:143` |
| posicionales | `runId` y `opId` | `:145` |

Los rechazos salen con `1` (`:98`); el uso inválido sale con `2` (`:157`).

## `validate` vs `run` — la diferencia que más confunde

| | `validate` | `run` |
|---|---|---|
| Lanza procesos del SO | **Nunca** (`Main.kt:191`) | Sí |
| Journal SQLite | No: `--db` se acepta y se **ignora** (retorna en `:228`, antes que `:233`) | Sí |
| Eventos | Emite a un store **en memoria** y los imprime como JSON en stdout `:209` | Persiste en SQLite |
| Ejecuta el puente canónico | **NO** | Sí: `Main.kt:423` (memoria) y `:827` (durable) |
| Detecta `git()` / `load()` / `node{}` / `ansiColor{}` | **No** | Sí → exit `2` |
| Mensaje final | `VALIDATION SUCCESSFUL` a stderr `:227` | `Pipeline finished with …` a stderr |

La consecuencia que debes interiorizar: **`validate` no es un ensayo.** Puede
imprimir `VALIDATION SUCCESSFUL` para un script que `run` después rechaza con
exit `2`, porque `validate` nunca llega al puente canónico donde se rechazan
esos cuatro constructos. **La comprobación real es `run`.**

Y si pasas `--db` a `validate` esperando un journal, no obtienes ninguno: el
flag se acepta y se descarta.

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** (`.github/workflows/`
no existe; el commit `754ddda0` eliminó los workflows de CI). Por eso esta página nunca afirma
«CI verde» ni «listo para producción»: el PRODUCT-GATE está
`BLOCKED_EXTERNAL`. Cada afirmación de arriba es una cita al código, no un
recibo de una build publicada.

## Checklist

- [ ] Mis flags van **antes** de la ruta del script.
- [ ] `--resume` / `--rerun` vienen con `--db`, y nunca los dos.
- [ ] He elegido `--isolated` **o** `--workspace`, no los dos.
- [ ] `--allow-network` no lleva `=value`.
- [ ] Ramifico por exit `1` y exit `2` por separado.
- [ ] Sé que `events verify` lee historial; no re-ejecuta nada.
- [ ] He usado `--subject` y `--scope last-segment`, no los nombres de la prosa de ayuda.
- [ ] He validado con `run`, no sólo con `validate`.

## Siguiente

- [`pipeline-dsl.es.md`](pipeline-dsl.es.md) — la superficie del DSL, y qué
  constructos están probados, declarados o fallan cerrado.
- [`README.es.md`](README.es.md) — el hub de toda la documentación de usuario.

_Esta página describe la rama de desarrollo. La release publicada actual es la `0.47.0`; la
diferencia está registrada en la nota de divergencia de arriba._