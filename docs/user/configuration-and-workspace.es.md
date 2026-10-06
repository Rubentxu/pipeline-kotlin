# Configuración y espacio de trabajo

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

- Explicar **dónde se ejecuta un run** (el workspace) y **dónde recuerda las cosas** (el journal).
- Elegir entre `--workspace` e `--isolated`, y saber por qué no puedes usar los dos.
- Decidir si tu run es **durable** (`--db`) o **en memoria**, y saber exactamente qué pierdes sin `--db`.
- Activar y desactivar el acceso a red de forma deliberada.
- Leer las cuatro variables de entorno que cambian el comportamiento del runtime.
- Reconocer el exit code `2` cuando viene de la configuración y no de tu script.

---

## La regla que explica casi todas las sorpresas

> **Sin `--db`, tu run no tiene memoria.**

No es una decisión de política: es lo que hace el código. `--db` es lo que selecciona el journal
SQLite (`CliParser.kt:192`). Sin él, el estado del run vive sólo en el proceso, y cuando el proceso
termina, el estado termina con él.

| | Con `--db` | Sin `--db` |
|---|---|---|
| Dónde vive el estado | Journal SQLite en disco | Sólo memoria del proceso |
| Sobrevive a un reinicio | Sí | No |
| `--resume` / `--rerun` | Disponibles | **Rechazados, exit 2** |
| `pipelinek events` después | Sí | No hay nada que leer |
| `examples/06-durable.pipeline.kts` | El segundo run reutiliza trabajo | No aplica |

**Analogía.** El workspace es el banco de trabajo donde están tus herramientas. El journal es el
cuaderno del operario: si el cuaderno no está, el turno siguiente empieza de cero por muy cuidadoso
que fuera el anterior.

---

## Flags: pertenecen sólo a `run` y a `validate`

Sólo `validate` y `run` pasan por el parser del CLI (`CliParser.kt:135`). Los demás subcomandos
(`version`, `doctor`, `events`, `console`, `credentials`) tienen su propio conjunto de flags.

| Flag | Argumento | Efecto | Fuente |
|---|---|---|---|
| `--db` | path | Selecciona el journal SQLite. Sin él, todo es en memoria | `CliParser.kt:192` |
| `--resume` | — | Reanuda un run previo. Exige `--db`, si no exit 2 | `CliParser.kt:196`, `Main.kt:239` |
| `--rerun` | — | Fuerza un run fresco. Exige `--db` | `CliParser.kt:202` |
| `--control-root` | path | Raíz del área de control del shell durable | `CliParser.kt:208` |
| `--workspace` | path | Directorio de trabajo del run | `CliParser.kt:212` |
| `--isolated` | — | Workspace scratch gestionado por el motor | `CliParser.kt:216` |
| `--plugin-jar` | path, repetible | JARs de plugin a cargar | `CliParser.kt:220` |
| `--allow-network` | — | Permite egress. **Denegado por defecto** | `CliParser.kt:229` |
| `--sandbox-profile` | `none` \| `local` \| `os` | `os` es rechazado | `CliParser.kt:233` |

### Los flags van **antes** del script. Siempre.

Es el error silencioso más habitual, así que tiene su propia sección.

El bucle del parser consume tokens mientras empiecen por `--` (`CliParser.kt:144`). El primer token
que no empieza por `--` se toma como ruta del script y **el parseo se detiene ahí**
(`CliParser.kt:151`). No se lanza ningún error. El resto de la línea simplemente se ignora.

```bash
# Correcto: los flags primero.
pipelinek run --db journal.sqlite --workspace ./build pipeline.kts

# Incorrecto: no-op silencioso. --db nunca se lee.
pipelinek run pipeline.kts --db journal.sqlite
```

El segundo comando termina como si todo estuviera configurado. Obtienes un run en memoria y ningún
aviso. **Pon los flags primero, siempre.**

### Pares mutuamente excluyentes

| Combinación | Resultado |
|---|---|
| `--isolated` + `--workspace` | Error. Significan lo contrario (`CliParser.kt:154`) |
| `--resume` + `--rerun` | Error. Son modos opuestos (`CliParser.kt:196`, `:202`) |
| `--allow-network=<value>` | No soportado. El flag no toma valor (`CliParser.kt:224`) |
| `--sandbox-profile os` | Rechazado |

Los flags desconocidos son otra cosa: producen un mensaje de uso y **exit 1**
(`CliParser.kt:242`, `Main.kt:151`).

---

## Workspace: elige uno, no los dos

`--workspace <path>` e `--isolated` son excluyentes (`CliParser.kt:154`).

| | `--workspace <path>` | `--isolated` |
|---|---|---|
| Quién crea el directorio | Tú, o debe existir ya | El motor |
| Bueno para | Checkouts reales, builds que necesitan ficheros reales | Tests, runs desechables, aislamiento |
| Sobrevive tras el run | Sí | No |

**Usa `--isolated` cuando** estés probando un script y no quieras que el run toque tu checkout.

---

## `--db`: convertir el run en durable

Pasa una ruta a cualquier ubicación escribible:

```bash
pipelinek run --db .pipelinek/journal.sqlite pipeline.kts
```

Con journal disponible obtienes además:

- `--resume` — continuar un run que no terminó.
- `--rerun` — empezar un run fresco conservando el journal como historial.
- Un historial legible después con `pipelinek events --db <path>`.

`examples/06-durable.pipeline.kts` existe para demostrar exactamente esto: ejecútalo dos veces con el
mismo `--db` y el segundo run reutiliza el trabajo del primero. `examples/08-parallel.pipeline.kts` y
`examples/09-retry.pipeline.kts` hacen lo mismo.

---

## `--control-root`: dónde guarda sus ficheros el shell durable

`--control-root <path>` selecciona la raíz de control que usa la capa de shell durable
(`CliParser.kt:208`). Piénsalo como el escritorio del operario, separado del banco de trabajo.

Una raíz de control inválida se rechaza en la admisión con **exit 2**.

Rara vez necesitas ponerla a mano. Si lo haces, dale un directorio real y escribible, del mismo tipo
que le darías a `--db`.

---

## El acceso a red está denegado por defecto

`--allow-network` es la única forma de permitir egress (`CliParser.kt:229`). Sin él, el run es
offline.

| Comando | Red |
|---|---|
| `pipelinek run pipeline.kts` | Denegada |
| `pipelinek run --allow-network pipeline.kts` | Permitida |

Fíjate en la sintaxis. `--allow-network` no toma argumento. Escribir `--allow-network=true` no concede
nada; es otro token distinto (`CliParser.kt:224`).

Mantenlo denegado salvo que tu pipeline necesite descargar algo de verdad. Es la protección más
barata de la herramienta.

---

## Perfiles de sandbox

`--sandbox-profile` acepta `none`, `local` y `os` (`CliParser.kt:233`).

**`os` es rechazado.** El perfil a nivel de sistema operativo no está disponible, así que pasarlo
falla en lugar de degradarse en silencio. Si encuentras un script antiguo con `--sandbox-profile os`,
ese script es anterior al parser actual.

Dos variables de entorno relajan el comportamiento del sandbox:

| Variable | Efecto | Fuente |
|---|---|---|
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Relaja el sandbox | `SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Conserva las rutas del sandbox en lugar de descartarlas | `SandboxConfig.kt:55` |

Trata ambas como ayudas de diagnóstico. Amplían lo que el sandbox permite.

---

## Variables de entorno

| Variable | Efecto | Fuente |
|---|---|---|
| `PIPELINE_STORE_PASSPHRASE` | Passphrase del almacén de secretos | `PassphraseResolver.kt:20` |
| `PIPELINE_SANDBOX_ALLOW_EXTRA` | Relajación del sandbox | `SandboxConfig.kt:54` |
| `PIPELINE_SANDBOX_PATH_KEEP` | Conservación de rutas del sandbox | `SandboxConfig.kt:55` |
| `APP_HOME` | Classpath de plugins empaquetados | `ScriptDefinition.kt:81` |

Dos hechos que conviene saber:

- **No se carga ningún fichero `.env` automáticamente.** Si una variable no está exportada en tu
  shell, el proceso no la ve.
- **No existe ninguna otra llamada a `System.getenv` en `src/main`.** Las cuatro de arriba son la
  lista completa a fecha de este commit.

`PIPELINE_STORE_PASSPHRASE` se explica en detalle en la página de credenciales.

---

## Plugins: `--plugin-jar`

`--plugin-jar <path>` es repetible (`CliParser.kt:220`). Cada aparición añade un JAR.

Los Steps aportados por plugins descubiertos así (`http.request`, `junit.results`,
`scm-git.checkout`, `core-utils.*`) recorren exactamente la misma vía de ejecución que los Steps
core. No hay ninguna ruta privilegiada para ellos.

---

## Exit codes por problemas de configuración

| Code | Significado |
|---|---|
| `0` | Éxito |
| `1` | Fallo o aborto del pipeline, **y argumentos CLI inválidos** (`Main.kt:151`) |
| `2` | Rechazo en admisión: script no encontrado, `--resume`/`--rerun` sin `--db`, `--control-root` inválido, Step no canónico, lease ya poseído, compilación fallida |
| `3` | Artefacto sin `Implementation-Version` |
| `4` | Almacén de credenciales adulterado |

La trampa: **los argumentos CLI inválidos dan `1`, mientras que casi todos los demás rechazos de
entrada dan `2`.** No los unifiques en un solo número (`Main.kt:151` frente a
`Main.kt:186,225,241,269,430,830`).

La lista completa está en la página de eventos y diagnóstico.

---

## Checklist antes de ejecutar

- [ ] Los flags van **antes** de la ruta del script.
- [ ] `--db` presente si vas a usar `--resume`, `--rerun` o `events` después.
- [ ] `--workspace` e `--isolated` no están ambos presentes.
- [ ] `--resume` y `--rerun` no están ambos presentes.
- [ ] `--sandbox-profile` no es `os`.
- [ ] `--allow-network` no lleva ningún `=<value>` detrás.
- [ ] Cualquier secreto que necesite tu script viene del almacén, no de un literal en el script.

---

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** — el commit `754ddda0` eliminó
`lpr0-ci.yml`, `release.yml`, `v2-baseline.yml` y `sdkman-publish.yml`. No esperes un badge de CI en
verde respaldando estas páginas, ni trates el product gate como superado. Todo lo de aquí es una
lectura estática del código en `b08fa948`.

---

## Siguiente

- **Credenciales y secretos** → [credentials-and-security.es.md](credentials-and-security.es.md)
- **Todas las páginas de usuario** → [README.es.md](README.es.md)