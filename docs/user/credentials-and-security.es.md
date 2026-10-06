# Credenciales y seguridad

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

- Guardar un secreto en el almacén de credenciales y usarlo desde un pipeline.
- Poner la passphrase que necesita el almacén, y entender qué pasa si falta o es incorrecta.
- Usar correctamente `pipelinek credentials add`, `list` y `remove`.
- Explicar por qué la capa del shell durable redacta los secretos de su transcripción.
- Aplicar la regla que mantiene los secretos fuera de tus ficheros `.pipeline.kts`.
- Reconocer los exit codes `3` y `4`, que pertenecen a esta página y no a la de configuración.

---

## La regla de oro

> **Nunca escribas un secreto en un fichero `.pipeline.kts` que vaya a acabar cerca de un repositorio.**

Un script de pipeline es código fuente. Se diffea, se revisa, se copia entre máquinas, se pega en
tickets y se commitea. Un literal como `token = "ghp_..."` dentro de un script tiene la misma vida
que el propio repositorio.

**Analogía.** Un script es un tablón de anuncios público. Un secreto es una llave. No clavas la llave
al tablón, ni siquiera a uno servicial.

Qué hacer en su lugar:

| En lugar de | Haz |
|---|---|
| `token = "abc123"` en el script | Guárdalo: `pipelinek credentials add …`, léelo con `withCredentials(…)` |
| `password = "hunter2"` en el script | Expórtalo desde el shell, o léelo del almacén |
| Un `.env` commiteado en Git | Fuera de Git; además PipelineK **no** carga `.env` automáticamente |

---

## El almacén de credenciales

Los secretos viven en un almacén local cifrado, que se abre con una passphrase proporcionada por la
variable de entorno `PIPELINE_STORE_PASSPHRASE` (`PassphraseResolver.kt:20`).

| Hecho | Detalle |
|---|---|
| De dónde sale la passphrase | `PIPELINE_STORE_PASSPHRASE`, leída del entorno |
| Qué pasa sin passphrase | Rechazado, **exit 3** |
| Qué pasa con passphrase incorrecta | Rechazado, **exit 3** |
| Qué pasa con el almacén dañado | **Exit 4** |

La passphrase nunca se lee del script, nunca se lee de un fichero dentro del repositorio y nunca se
pide interactivamente en las vías del CLI descritas aquí. Si la variable no está puesta en el shell
que lanza `pipelinek`, el almacén no se puede abrir.

---

## El comando

```bash
pipelinek credentials add
pipelinek credentials list
pipelinek credentials remove
```

El subcomando se despacha en `Main.kt:140`; las tres acciones se atienden en `MainCredentialsCli.kt:94`.

| Acción | Qué hace |
|---|---|
| `add` | Lee un secreto desde stdin y lo guarda bajo un identificador nuevo |
| `list` | Muestra los identificadores del almacén, nunca el material secreto |
| `remove` | Elimina una entrada |

`PIPELINE_STORE_PASSPHRASE` debe estar puesta y ser correcta para las tres.

### Checklist

- [ ] `PIPELINE_STORE_PASSPHRASE` está exportada **en el shell que ejecuta el comando**.
- [ ] La passphrase no está guardada en el repositorio.
- [ ] Los secretos se pasan por stdin, nunca se teclean en un script ni en un fichero versionado.
- [ ] Has revisado el working tree en busca de literales preexistentes antes de empezar, no después.

---

## Usar un secreto guardado desde un pipeline

`withCredentials(vararg bindings, block)` (`StageScopeBuilders.kt:240`) es la forma DSL declarada.

`withCredentials` tiene un descriptor de bloque registrado (`StepDescriptorRegistry.standard()`,
`core.withCredentials`), así que forma parte del conjunto de bloques soportado.

> **Declarado, no demostrado.** Ningún fichero de `examples/` usa `withCredentials`. La declaración
> es real y está registrada, pero en este commit no hay un ejemplo de referencia que afirme su
> comportamiento en runtime. Trátalo como constructo declarado y valídalo en tu propio pipeline antes
> de depender de él.

`withCredentials` exige que el secreto venga del almacén. Si intentas enlazar un literal que has
escrito en el script, has saltado la regla del principio de esta página.

---

## Redacción de secretos en la costura durable del shell

Cuando un Step de shell se ejecuta de forma durable, su salida se escribe en una transcripción de
consola. Esa transcripción forma parte del historial del run y se puede leer después con
`pipelinek console`.

Los secretos se redactan en esa costura durable antes de que se escriba la transcripción, de modo que
un valor que venía del almacén de credenciales no aparece en la salida de consola persistida.

Dos consecuencias:

| Expectativa | Realidad |
|---|---|
| Secretos en la transcripción | Redactados en la costura durable |
| Secretos en el journal o en el flujo de eventos | No prometido aquí. **NO VERIFICADO.** |

**Analogía.** La transcripción es una grabación de la voz del operario. La redacción ocurre antes de
archivar la grabación, así que la copia archivada se puede leer en un turno posterior.

Como la redacción vive en el límite durable del shell, no la supongas válida para la salida
producida por otras vías. Todo lo que imprima un Step de plugin queda fuera de lo que esta página
puede certificar.

---

## Lo que `list` no te va a mostrar

`credentials list` informa de identificadores. No imprime material secreto. Si un secreto apareciera
en un listado, algo está mal y la salida debe considerarse comprometida: rota ese secreto.

---

## Exit codes `3` y `4`

Estos dos códigos pertenecen a esta página. No los confundas con el `2`, que cubre los rechazos de
configuración y admisión.

| Code | Significado | Disparador |
|---|---|---|
| `3` | Passphrase ausente o incorrecta | `credentials` sin `PIPELINE_STORE_PASSPHRASE`, o con un valor incorrecto |
| `3` | Artefacto sin `Implementation-Version` | `pipelinek version` sobre un artefacto construido sin la entrada del manifiesto (`Main.kt:80`) |
| `4` | Almacén de credenciales adulterado | El almacén falló su comprobación de integridad |

La segunda fila del `3` está aquí porque el número es compartido. Cuando veas `3`, lee el mensaje
antes de decidir cuál de las dos situaciones tienes.

`4` es distinto en naturaleza: una passphrase ausente o incorrecta es un problema de **no la
abrió**, mientras que `4` significa que la cosa misma no es fiable. No intentes "arreglar" un `4`
borrando el almacén y reañadiendo todo sin entender antes por qué falló la comprobación.

---

## Higiene práctica

- [ ] Ningún literal secreto en ningún fichero `.pipeline.kts`, en ningún commit del historial.
- [ ] `PIPELINE_STORE_PASSPHRASE` exportada desde el shell, no desde un dotfile versionado.
- [ ] Red denegada salvo que un Step la necesite de verdad (`--allow-network`, denegado por defecto).
- [ ] `git()`, `load()`, `node { }` y `ansiColor { }` fallan cerrado en runtime con exit 2. Una
      construcción que devuelve algo en silencio es peor que una que se niega; la negativa es el
      comportamiento diseñado, y es la razón por la que esas cuatro nunca llegan a tu sistema de
      ficheros ni a tu shell (`StageScopeBuilders.kt:193`, `StageScope.kt:349,435,449`).
- [ ] Las rutas del journal (`--db`) y las raíces de control son directorios escribibles y privados.

---

## Nota de autoridad

Este repositorio **no tiene CI remota desde 2026-09-30** — el commit `754ddda0` eliminó los workflows de CI No esperes un badge de CI en
verde respaldando estas páginas. Todo lo de aquí es una lectura estática del código en `b08fa948`.

---

## Siguiente

- **Eventos y diagnóstico** → [events-and-troubleshooting.es.md](events-and-troubleshooting.es.md)
- **Workspace y flags** → [configuration-and-workspace.es.md](configuration-and-workspace.es.md)
- **Todas las páginas de usuario** → [README.es.md](README.es.md)