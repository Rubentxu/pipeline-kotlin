# PipelineK — Documentación de usuario

**Documentado contra**: rama de desarrollo, `pipelinek 0.47.0` (`v2/build.gradle.kts:75`), commit `b08fa948`

> **Qué es este directorio.** El punto de entrada a todo lo que necesitas para escribir, ejecutar y
> operar PipelineK. Si has llegado desde el [README principal](../../README.es.md), aquí empieza la
> versión larga. Si ya conoces Jenkins, empieza por la [Ruta A](#ruta-a--quiero-ejecutar-pipelines).

**¿Qué es?** Un motor CI/CD local-first con un DSL de Kotlin familiar con Jenkins. Escribes un
fichero `.pipeline.kts` y PipelineK lo compila, valida y ejecuta **en tu máquina**.

---

## Elige tu ruta

Tres recorridos, según por qué estés aquí. No hace falta que los leas todos.

| | Ruta | Léela cuando | Páginas |
|---|---|---|---|
| **A** | [**Quiero ejecutar pipelines**](#ruta-a--quiero-ejecutar-pipelines) | Quieres tu primer pipeline funcionando hoy | 4 páginas, ~25 min |
| **B** | [**Quiero operarlo en condiciones**](#ruta-b--quiero-operarlo-en-condiciones) | Tus runs tienen estado, usan secretos, o depuras fallos | 3 páginas |
| **C** | [**Quiero contribuir con código**](#ruta-c--quiero-contribuir-con-código) | Vas a cambiar el código, no sólo a usarlo | README principal + 2 documentos |

---

## Ruta A — Quiero ejecutar pipelines

El camino más corto desde "no tengo nada instalado" hasta "un pipeline que se ejecuta". Léelo en este
orden; cada página asume la anterior.

| # | Página | Qué podrás hacer después | Tiempo |
|---|---|---|---|
| 1 | [Instalación](installation.es.md) | Instalar el binario y verificar que está íntegro | 5 min |
| 2 | [Primeros pasos](quickstart.es.md) | Escribir un pipeline, ejecutarlo, verlo fallar a propósito y leer sus eventos | 15 min |
| 3 | [DSL de pipelines](pipeline-dsl.es.md) | Saber qué construcciones existen y **cuáles están probadas** | 20 min |
| 4 | [Referencia del CLI](cli-reference.es.md) | Manejar el binario desde un script con confianza | 15 min |

Ten [la chuleta](cheat-sheet.es.md) abierta en otra pestaña. Es la tabla copiable de códigos de
salida, subcomandos, flags y ejemplos.

Si prefieres **mirar en vez de leer**, [Ejemplos grabados](examples.es.md) ejecuta los diez pipelines
de ejemplo contra el binario real, un GIF animado por pipeline: la orden, el resultado y el código de
salida. Cada GIF declara qué omite, y `examples/run.sh` es la comprobación que hay detrás.

**English** · [Español](README.es.md)

---

## Ruta B — Quiero operarlo en condiciones

Todo lo relativo a *dónde* se ejecuta un run, *qué* puede tocar, y *qué hacer cuando se rompe*.

| # | Página | Qué responde |
|---|---|---|
| 1 | [Configuración y workspace](configuration-and-workspace.es.md) | ¿Dónde trabaja mi run? ¿Por qué desaparecieron mis flags? ¿Qué me aporta `--db`? |
| 2 | [Credenciales y seguridad](credentials-and-security.es.md) | ¿Cómo uso un secreto sin filtrarlo? ¿Por qué obtengo exit 3? |
| 3 | [Eventos y diagnóstico](events-and-troubleshooting.es.md) | Mi run falló — ¿qué pasó y qué ejecuto para averiguarlo? |

---

## Ruta C — Quiero contribuir con código

La documentación de usuario termina aquí. A partir de este punto la autoridad es otra.

| Paso | Documento | Por qué |
|---|---|---|
| 1 | [`../../CONTEXT.md`](../../CONTEXT.md) | El vocabulario canónico, con anti-términos. 45 líneas. |
| 2 | [Constitución semántica](../pipelinek-semantic-evolution/01-semantic-constitution.md) | **Las leyes.** Léelas antes de escribir código. |
| 3 | [Protocolo de certificación](../v2/07-uat/CERTIFICATION_PROTOCOL.md) | Qué significa `STEP-CERT` y qué exige de verdad una puerta. |
| 4 | [`../../AGENTS.md`](../../AGENTS.md) | Disciplina de work units y puertas de calidad. |

Dos cosas que debes interiorizar antes de tu primer PR:

- **Un recibo de certificación es evidencia de su propio SHA y no hereda nada.** Si el código se movió,
  la evidencia caducó.
- **No hay CI remota en este repositorio.** Un gate local en verde es la afirmación más fuerte
  disponible, y está atada a un commit exacto.

---

## Cómo etiquetamos el comportamiento — léelo antes de fiarte de una afirmación

El DSL contiene tres tipos muy distintos de construcción, y la documentación etiqueta cada una.
Equivocarse en esta distinción es la forma más habitual de perder una tarde.

| Etiqueta | Qué significa | ¿Puedo fiarme? |
|---|---|---|
| **Probado por los ejemplos** | Lo ejecuta `examples/run.sh`, que comprueba el exit code, el outcome terminal y, en los interesantes, el contrato de eventos | Sí. Éste es el subconjunto por el que hay que empezar |
| **Declarado, sin ejemplo de referencia** | Existe en el DSL de v2 con firma y descriptor completos, pero **ningún pipeline de ejemplo lo usa** | No está probado. Existe; nadie lo ha demostrado de extremo a extremo |
| **Falla cerrado** | Compila y luego es **rechazado en tiempo de ejecución con exit 2**. Es una negativa deliberada, no una funcionalidad pendiente | Puedes fiarte de que **se niega**, nunca de que funciona |

[DSL de pipelines →](pipeline-dsl.es.md) tiene las tablas completas.

---

## Divergencias conocidas

Registradas para que nadie vuelva a confiar en una afirmación caducada.

### D1 — La verificación contra `0.39.0` no se sostenía · registrado 2026-10-06

`pipeline-dsl.md` y `cli-reference.md` llevaban la cabecera *"Release verified against:
`pipelinek 0.39.0`"* y afirmaban que `parallel`, `retry`, `timeout`, `catchError`, `waitUntil`,
`milestone`, `stash` y `cleanWs` **no existían**, y que sólo `core.echo` y `core.sh` estaban
certificados.

Esa afirmación **no se sostenía contra el código**. Esas construcciones están declaradas en el DSL de
v2 (`StageScopeBuilders.kt:230`, `StageScope.kt:141,391,473,515,638,652`) y las ejercitan
`examples/07`–`examples/10`, cuyos exit codes y contratos de eventos `examples/run.sh` comprueba.

**Resolución:** ambas páginas documentan ahora la rama de desarrollo (`0.47.0`) con la cabecera
canónica y llevan esta nota. La cabecera antigua se eliminó en lugar de reutilizarse en silencio.

### D2 — Sin CI remota, sin afirmaciones de "build en verde" · registrado 2026-10-06

`.github/workflows/` no existe: el commit `754ddda0` (2026-09-30) eliminó los workflows de CI El PRODUCT-GATE está `BLOCKED_EXTERNAL`.

Ninguna página de este directorio puede afirmar un build en verde ni preparación para producción. La
verificación es local, manual y atada a un commit.

### D3 — `docs/user/` estaba escrito para quien ya sabía CI/CD · registrado 2026-10-06

Las páginas eran precisas pero asumían un vocabulario que nunca definían. Se han reescrito para que cada
una abra con lo que podrás hacer, defina cada término la primera vez que aparece, y separe el
comportamiento probado del declarado.

---

## Qué significa aquí "documentado contra"

| Afirmación | Estado |
|---|---|
| Versión documentada | `0.47.0`, la **rama de desarrollo** (`v2/build.gradle.kts:75`) |
| Última release publicada | `0.47.0`, confirmado el 2026-10-06 (`releases/latest` → `releases/tag/v0.47.0`) |
| Verificado contra un binario publicado | **No.** El digest del ZIP `0.47.0` viene del `SHA256SUMS` de la release, pero ese ZIP nunca se ejecutó |
| Última release con un recibo ejecutado | `0.39.0` (commit `951b3cb5…`) — sus digests de ZIP *y* de binario están registrados aquí |
| Cómo se comprobó | Leyendo el código. Cada afirmación de comportamiento cita `ruta:línea` |
| Qué no se hizo | Nunca se ejecutó Gradle ni el binario al escribir estas páginas |
| Última actualización | 2026-10-06, commit `b08fa948` |

Si necesitas una afirmación atada a un artefacto publicado, usa la [página de instalación](installation.es.md).
Lee con atención su distinción en dos cajas: el digest de `0.47.0` tiene buena *procedencia* pero
ninguna *ejecución*, y los digests de `0.39.0` tienen ambas.

---

## Convenciones usadas en estas páginas

- Números de versión, digests SHA, flags, subcomandos, claves de evento y rutas de fichero son
  **idénticos en todos los idiomas**. Sólo se traduce la prosa.
- El código que puedes copiar va en bloques delimitados. Nunca parafrasees un comando dentro de un bloque.
- `NOT VERIFIED` nunca aparece como atajo. Si una afirmación no se pudo comprobar en el código, se
  omite o se escribe como una laguna explícita.
- Cada página termina con un enlace a este índice y a "qué viene después".

**English** · [Español](README.es.md)