# PipelineK — Ejemplos grabados

**Grabado contra**: `pipelinek 0.47.0`, construido desde la rama de desarrollo, 2026-10-06
**No verificado contra un binario publicado.** Ver
[Cómo se grabaron](#cómo-se-grabaron).

Hay diez pipelines ejecutables en [`examples/`](../../examples/). Cada uno se ejecuta contra el
**binario real**, y esta página te enseña cada uno funcionando: la orden, el resultado y el código
de salida.

| Si quieres ver… | Ve a |
|---|---|
| El pipeline más corto que funciona | [01 — Hello](#01--hello) |
| Etapas ejecutándose en orden | [02 — Multi-stage](#02--multi-stage) |
| Procesos reales del sistema | [03 — Shell](#03--shell) |
| Control de flujo Kotlin dentro de un pipeline | [04 — Kotlin control flow](#04--kotlin-control-flow) |
| **Un fallo que detiene el run** | [05 — Failing step](#05--failing-step) |
| Reanudar desde un journal | [06 — Durable](#06--durable) |
| **Recuperarse de un fallo** | [07 — Catch error](#07--catch-error) |
| Dos ramas a la vez | [08 — Parallel](#08--parallel) |
| Reintentar un step inestable | [09 — Retry](#09--retry) |
| **Un timeout aplicado de verdad** | [10 — Timeout](#10--timeout) |

---

## Qué enseñan estos GIF y qué no

Léelo antes de fiarte de un fotograma.

**Lo que ves** es real: el binario `pipelinek` real, el fichero de pipeline real, la línea de
resultado real y el código de salida real.

**Lo que no ves** es el array JSON de eventos. `pipelinek run` lo imprime en **stdout** como una
única línea JSON, con un UUID y una marca de tiempo ISO por evento. Falta a propósito en estos GIF
por dos razones: a escala de GIF es ilegible, y **cambia en cada ejecución**, así que una demo que
lo contenga nunca podría regenerarse igual dos veces.

Ese array no es un log. Es la API de observabilidad, y es lo que vas a usar en CI. Está documentado
entero en
[Eventos y diagnóstico](events-and-troubleshooting.es.md), y `examples/run.sh` afirma sobre él.

La línea de resumen humano que **sí** ves viene de **stderr**:

```bash
# exactamente lo que grabó cada GIF
pipelinek run --workspace . 05-failing-step.pipeline.kts 2>&1 >/dev/null
echo $?
```

---

## Reproducirlos tú

Un GIF es una foto de ayer. Esto es lo que hoy sí puedes comprobar:

```bash
cd v2 && ./gradlew :pipeline-application:installDist
examples/run.sh                    # los diez, afirmando código de salida y contrato de eventos
examples/run.sh 05-failing-step.pipeline.kts   # sólo uno
```

`examples/run.sh` es el harness real. No es un GIF, es una comprobación: si cambia el comportamiento
del binario, se pone rojo. El contrato completo de cada ejemplo, incluidas sus limitaciones
conocidas, está en [`examples/README.md`](../../examples/README.md).

---

## 01 — Hello

El mínimo: una etapa, un `echo`. Nada que configurar, nada de lo que depender.

![PipelineK ejemplo 01 hello](assets/examples/01-hello.gif)

Resultado `success`, código de salida `0`.

## 02 — Multi-stage

Tres etapas. Se ejecutan en el orden que declaraste, no en orden alfabético — que es lo primero que
se supone mal cuando alguien da por hecho lo contrario.

![PipelineK ejemplo 02 multi-stage](assets/examples/02-multi-stage.gif)

Resultado `success`, código de salida `0`.

## 03 — Shell

`sh` lanza un proceso real del sistema, incluido un bucle `for` del shell. Esto no es un
intérprete fingiendo: es la shell de tu máquina.

![PipelineK ejemplo 03 shell](assets/examples/03-shell.gif)

Resultado `success`, código de salida `0`.

## 04 — Kotlin control flow

Kotlin de verdad dentro de un bloque `script {}`: bucles, condicionales, el lenguaje de siempre,
dentro de un pipeline en vez de al lado.

![PipelineK ejemplo 04 kotlin control flow](assets/examples/04-kotlin-control-flow.gif)

Resultado `success`, código de salida `0`.

## 05 — Failing step

**El primero por el que merece la pena parar.** Una etapa se ejecuta, el `sh` de la siguiente sale
con `3`, y el run se detiene. La etapa posterior al fallo nunca se ejecuta — no es "se registra y se
salta", es que no ocurre.

![PipelineK ejemplo 05 failing step](assets/examples/05-failing-step.gif)

Resultado `failure`, código de salida `1`. La línea del motivo es `shell exited with code 3`:
PipelineK informa del código que devolvió tu proceso, no aplana todo fallo en un error genérico.

## 06 — Durable

Con `--db`, cada operación se registra en SQLite junto con una huella de sus entradas. Mata el run a
la mitad y el siguiente lo retoma donde lo dejó en vez de empezar de cero.

![PipelineK ejemplo 06 durable](assets/examples/06-durable.gif)

Resultado `success`, código de salida `0`.

## 07 — Catch error

**El segundo por el que merece la pena parar.** Un `catchError` anidado: el bloque interior convierte
un fallo en `FAILURE`, el exterior lo degrada a `UNSTABLE`, y el run continúa.

![PipelineK ejemplo 07 catch error](assets/examples/07-catch-error.gif)

Resultado `unstable`, código de salida **`0`**. Por eso "código de salida 0" no es lo mismo que
"success": este run terminó, y te está diciendo que no quedó limpio. Los códigos de salida están en
la [referencia del CLI](cli-reference.es.md).

## 08 — Parallel

Dos ramas ejecutándose a la vez. Ejecútalo una segunda vez con el mismo `--db` y reutiliza el
resultado terminal en vez de relanzar el trabajo — la huella dice que las entradas no cambiaron.

![PipelineK ejemplo 08 parallel](assets/examples/08-parallel.gif)

Resultado `success`, código de salida `0`.

## 09 — Retry

`retry(3) { }`: el primer intento falla, el segundo funciona. El fichero marcador mantiene el
ejemplo determinista en vez de depender del tiempo.

![PipelineK ejemplo 09 retry](assets/examples/09-retry.gif)

Resultado `success`, código de salida `0`.

## 10 — Timeout

`timeout(2, "SECONDS")` aborta un `sh` que se pasa de tiempo. Fíjate en **cómo** falla: un timeout
es un `FAILURE`, no una muerte silenciosa.

![PipelineK ejemplo 10 timeout](assets/examples/10-timeout.gif)

Resultado `failure`, código de salida `1`. La línea del motivo dice `durable shell timed out`, que
es un mensaje distinto del de un fallo de script normal — porque es una causa distinta.

---

## Cómo se grabaron

Lo digo para que puedas juzgarlos: se produjeron con [asciinema](https://asciinema.org/) capturando
una sesión real y [agg](https://github.com/asciinema/agg) renderizándola. Ambas se ejecutan desde el
directorio personal del usuario y **nada de las herramientas queda dentro de este repositorio** —
ni scripts, ni ficheros de captura, ni binarios. Sólo se versionan los `.gif` terminados.

Dos detalles que deciden si una grabación así es honesta o ruido:

- **Se grabó stderr y se descartó stdout.** Motivo arriba: stdout es el array de eventos.
- **La grabación filtra los avisos del propio JDK.** Un JDK 23 o superior imprime
  `WARNING: sun.misc.Unsafe …` con la ruta absoluta de instalación, que filtra a quien grabó. El
  proyecto apunta a JDK 21, donde ese aviso no aparece. La salida de PipelineK no se filtra nunca.

Si un fotograma de aquí discrepa de lo que obtengas en tu máquina, quédate con `examples/run.sh`
antes que con el GIF.

---

## Siguiente

- [`quickstart.es.md`](quickstart.es.md) — escribe y ejecuta tu primer pipeline.
- [`pipeline-dsl.es.md`](pipeline-dsl.es.md) — todas las construcciones que ofrece el DSL.
- [`events-and-troubleshooting.es.md`](events-and-troubleshooting.es.md) — el array de eventos que los GIF omiten.
- Hub: [`docs/user/README.es.md`](README.es.md).