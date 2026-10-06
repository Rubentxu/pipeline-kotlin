# Eventos y diagnóstico

**Documented against**: development branch, `pipelinek 0.47.0` (v2/build.gradle.kts:75), commit `b08fa948`
**No verificado contra un binario publicado.** La release publicada actual es la `0.47.0` (el digest de su ZIP está en el `SHA256SUMS` de la release); ver la nota de divergencia abajo.

> **Divergencia de documentación.** Esta página llevaba antes la cabecera *"Release verified against:
> `pipelinek 0.39.0`"*. Esa cabecera no se sostiene: describía una superficie que la rama actual ya no
> refleja, y nunca se reverificó contra un binario publicado. La página documenta ahora la rama de
> desarrollo (`0.47.0`). Registrado el 2026-10-06. Ver `docs/user/README.es.md` → "Divergencias
> conocidas". La afirmación más fuerte de que `parallel` / `retry` / `catchError` no existían aplicaba
> sólo a `pipeline-dsl.md` y `cli-reference.md`.

---

## Qué podrás hacer al terminar de leer esta página

- Explicar qué es un evento, y por qué no es una línea de log.
- Leer el historial de un run con `pipelinek events`, incluidos sus flags y sus dos trampas
  documentadas.
- Leer la transcripción de consola de una operación con `pipelinek console`.
- Comprobar el historial persistido contra un contrato con `pipelinek events verify`.
- Diagnosticar seis fallos habituales a partir del exit code y del mensaje, sin adivinar el código.

---

## La regla de oro del diagnóstico

> **Mira primero el exit code y el mensaje. Después confirma que los flags iban *antes* del script.
> Sólo cuando ambas cosas estén resueltas debes sospechar del código.**

La mayoría de los reportes de "PipelineK está roto" son uno de los seis casos de la tabla de abajo,
y ninguno es un fallo en un Step. Revisar el exit code y el orden de los flags cuesta diez segundos y
resuelve la mayoría.

---

## Qué es un evento

Un evento es un **registro de algo que ocurrió con significado**, no una línea de log.

| Un evento es | Una línea de log |
|---|---|
| Tipado, con un kind estable | Texto sin estructura |
| Se escribe en el journal | Se escribe en un stream |
| Estable a través de un reinicio | Desaparece cuando muere el proceso |
| Se relee con `pipelinek events` | Se busca con grep en una terminal |

Los eventos son el historial estructurado del run. Como se guardan en el journal, sobreviven al run,
pero sólo si le diste un journal con `--db`. **Sin `--db` no hay historial que leer después.** Míralo
en [configuration-and-workspace.es.md](configuration-and-workspace.es.md).

Los eventos necesitan que exista un journal. Un run sin `--db` emite eventos, pero no queda nada
persistido desde donde consultarlos más tarde.

---

## `pipelinek events`

Lee el historial estructurado del journal (`Main.kt:115`, `MainEventsCli.kt`).

| Flag | Argumento | Por defecto | Fuente |
|---|---|---|---|
| `--db` | path | — (obligatorio) | `MainEventsCli.kt:34` |
| `--kind` | K | todos | `:35` |
| `--subject` | `<v1:kind:segment…>` | todos | `:36` |
| `--limit` | N | **100** | `:37` |
| `--after-cursor` | TOKEN | inicio del historial | `:38` |
| *posicional* | `runId` | — (obligatorio) | `:39` |

Forma de la salida: un envelope JSON por línea en **stdout** (`MainEventsCli.kt:77`), y el cursor en
**stderr** (`MainEventsCli.kt:80`). Ese reparto es deliberado — significa que puedes canalizar stdout
directo a `jq` sin tener que filtrar el cursor.

```bash
pipelinek events --db .pipelinek/journal.sqlite RUN_ID
pipelinek events --db .pipelinek/journal.sqlite --kind StepStarted --limit 500 RUN_ID
pipelinek events --db .pipelinek/journal.sqlite --after-cursor TOKEN RUN_ID
```

Si falta `--db` o falta el `runId`, es **exit 2** (`MainEventsCli.kt:46`).

### Trampa 1: el flag es `--subject`, no `--subject-kind`

El KDoc en `MainEventsCli.kt:14` anuncia `--subject-kind`. **Ese flag no existe.** El real es
`--subject` (`MainEventsCli.kt:36`). Sigue la implementación, no el comentario.

---

## `pipelinek events verify`

Comprueba el historial persistido contra un fichero de contrato. **No vuelve a ejecutar nada**
(`Main.kt:116`).

| Flag | Argumento | Fuente |
|---|---|---|
| `--db` | path | `MainEventsVerifyCli.kt:31` |
| `--run` | runId | `:31` |
| `--contract` | path | `:31` |
| `--from-sequence` | N | `:31` |
| `--scope` | `last-segment` | `:32` |

Imprime `contract:`, `observed-terminal-outcome:`, `acceptance:` y las violaciones.

| Exit | Significado |
|---|---|
| `0` | PASSED |
| `1` | FAILED |
| `2` | Problema de uso o de decodificación |

### Trampa 2: `--after-last-run-started` no existe

El mensaje de uso en `MainEventsVerifyCli.kt:37` anuncia `[--after-last-run-started]`. Ese flag no
está implementado. El real es `--scope last-segment` (`MainEventsVerifyCli.kt:32`).

Hay dos mensajes de uso erróneos en esta zona. Cuando un flag de una cadena de uso no haga nada,
mira `MainEventsVerifyCli.kt` o `MainEventsCli.kt` directamente antes de creerlo.

---

## `pipelinek console`

Lee la transcripción de salida de una operación (`Main.kt:133`, `MainConsoleCli.kt`).

| Flag | Argumento | Por defecto | Fuente |
|---|---|---|---|
| `--control-dir` | path | — | `MainConsoleCli.kt:140` |
| `--max-bytes` | N | **65536** | `:141` |
| `--after-cursor` | TOKEN | inicio | `:142` |
| `--range` | `FROM:TO` | completo | `:143` |
| *posicionales* | `runId`, `opId` | — | `:145` |

Los rechazos salen con exit `1` (`MainConsoleCli.kt:98`); el uso inválido sale con exit `2` (`:157`).
Fíjate en que este subcomando reparte su manejo de errores entre dos códigos: lee el mensaje.

```bash
pipelinek console --control-dir .pipelinek/control RUN_ID OP_ID
pipelinek console --control-dir .pipelinek/control --max-bytes 4096 RUN_ID OP_ID
```

Los secretos del almacén de credenciales se redactan en la costura durable del shell antes de
escribir la transcripción. Míralo en [credentials-and-security.es.md](credentials-and-security.es.md).

---

## `validate` no es un ensayo

Es el malentendido más caro de toda la herramienta, así que tiene su propia tabla.

| | `validate` | `run` |
|---|---|---|
| Lanza procesos del SO | **Nunca** (`Main.kt:191`) | Sí |
| Journal SQLite | Acepta `--db` y lo **ignora** (retorna en `Main.kt:228`, antes que `:233`) | Sí |
| Eventos | Emite a un store en memoria, impreso como JSON en stdout (`Main.kt:209`) | Persistidos en SQLite |
| Puente canónico | **No lo ejecuta** | Sí (`Main.kt:423` en memoria, `:827` durable) |
| Detecta `git()` / `load()` / `node { }` / `ansiColor { }` | **No** | Sí → exit 2 |
| Mensaje final | `VALIDATION SUCCESSFUL` a stderr (`Main.kt:227`) | `Pipeline finished with …` a stderr |

**Consecuencia:** `validate` puede imprimir `VALIDATION SUCCESSFUL` para un script que `run` después
rechaza con exit 2. Eso no es una contradicción ni un fallo en tu script. `validate` nunca cruza la
puerta que sí cruza `run`. La única comprobación real es `run`.

---

## Tabla de diagnóstico

Seis casos, cada uno con su firma real. Lee primero la columna de exit code.

| # | Síntoma | Exit | Causa real | Solución |
|---|---|---|---|---|
| 1 | Flags puestos después del script, ignorados sin error | `0` (o plausible pero equivocado) | El parser se detiene en el primer token que no empieza por `--` (`CliParser.kt:144,151`) | Mueve todos los flags **antes** de la ruta del script |
| 2 | `validate` dice `VALIDATION SUCCESSFUL`, `run` falla | `2` | `validate` nunca cruza el puente canónico ni detecta las construcciones no canónicas | Confía en `run`. `validate` es una comprobación de compilación, no un ensayo |
| 3 | `--resume` o `--rerun` sin `--db` | `2` | Ambos flags exigen el journal (`CliParser.kt:196,202`, `Main.kt:239`) | Añade `--db <path>`, o quita el flag |
| 4 | Usaste `git()`, `load()`, `node { }` o `ansiColor { }` | `2` | Compila y luego se rechaza: sin descriptor o registro válido (`StageScopeBuilders.kt:193`, `StageScope.kt:349,435,449`) | Quítalo. Esto es **fallar cerrado por diseño**, no una función perdida |
| 5 | Usaste `retry(count, delaySeconds)` sin bloque | excepción en el punto de llamada | La sobrecarga de nivel de Step lanza `IllegalArgumentException` al invocarla (`StageScopeBuilders.kt:306`) | Usa la forma de bloque `retry(count) { }`, que es la que ejercita el ejemplo 09 |
| 6 | El binario no trae `Implementation-Version` | `3` | `version` lee la entrada del manifiesto; si falta → exit 3 (`Main.kt:72,80`) | Reconstruye, o usa la distribución instalada |

### Notas sobre las filas más delicadas

**Fila 4 — fallar cerrado es el contrato.** Estas cuatro construcciones compilan y luego son
rechazadas antes de cualquier efecto, porque el registro efectivo no lleva descriptor para ellas. La
puerta está en `Main.kt:423` y `Main.kt:827`, y la elegibilidad se calcula en
`CanonicalStructuralDecisions.kt:154`. El mensaje de error enumera 11 claves fijas, pero **el criterio
real es el registro efectivo**, no esa lista impresa. No trates esas 11 claves como la regla.

**Fila 5 — dos `retry` distintos.** `retry(count) { }` toma un bloque y funciona;
`examples/09-retry.pipeline.kts` depende de él. `retry(count, delaySeconds)` es la forma de nivel de
Step y lanza en la llamada. Mismo nombre, formas distintas, resultados opuestos. Si tu script lanza en
el punto de llamada y no lo esperabas, tienes la segunda.

**La fila 1 es la cara**, porque falla *con éxito*. Todo parece correcto y el run simplemente no es
durable.

---

## Referencia de exit codes

| Code | Significado |
|---|---|
| `0` | Éxito, incluido un run `Unstable` |
| `1` | `Failure`/`Aborted` del pipeline, argumentos CLI inválidos, rechazos de `console`, `events verify` FAILED |
| `2` | Invocación y admisión: script no encontrado, `validate` fallido, `--resume`/`--rerun` sin `--db`, `--control-root` inválido, Step no canónico, lease ya poseído, compilación fallida, `doctor` con directorio no escribible |
| `3` | Artefacto sin `Implementation-Version`; credenciales sin passphrase o con passphrase incorrecta |
| `4` | Almacén de credenciales adulterado |

La trampa: los argumentos CLI inválidos dan `1`, casi todos los demás rechazos de entrada dan `2`
(`Main.kt:151` frente a `Main.kt:186,225,241,269,430,830`). No los unifiques.

Los códigos `3` y `4` se explican en la página de credenciales.

---

## Otros subcomandos

| Comando | Para qué | Notas |
|---|---|---|
| `version` | Lee `Implementation-Version` del jar | Exit 3 si no está (`Main.kt:72,80`) |
| `doctor` | Comprueba JDK, SO y si el cwd es escribible | cwd no escribible → exit 2 (`Main.kt:91,110`) |

`doctor` es la primera comprobación más rápida cuando no sabes qué pasa. Merece la pena ejecutarlo
antes de una sesión larga de depuración.

---

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** — el commit `754ddda0` eliminó los workflows de CI No esperes un badge de CI en
verde respaldando estas páginas. Todo lo de aquí es una lectura estática del código en `b08fa948`.

---

## Siguiente

- **Todas las páginas de usuario** → [README.es.md](README.es.md)
- **Workspace y flags** → [configuration-and-workspace.es.md](configuration-and-workspace.es.md)
- **Credenciales y secretos** → [credentials-and-security.es.md](credentials-and-security.es.md)