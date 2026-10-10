# Arbitraje del CLI: la spec zanja medio problema y deja otro abierto

Investigación de 2026-10-09 sobre el conflicto de `MainConsoleCli.kt` / `MainEventsCli.kt`
descrito en `OBS_COMMIT_AUDIT.md` y `MAIN_COMMIT_AUDIT.md`.

## §12 es una adición de `main`, no un borrado de OBS

Primer hallazgo, y corrige una hipótesis del propio borrador:

```text
git log main..origin/par/cli-observation -- CLI_OBSERVABILITY_SPEC.md   → (vacío)
git log origin/par/cli-observation..main -- CLI_OBSERVABILITY_SPEC.md
  → 559948cc  fix(cli): console refuses what it cannot honour
  → 5aadf3f1  fix(b1c): one canonical stream lock...
```

**Ningún commit de OBS tocó la spec.** El diff `main → OBS` que muestra 36 líneas eliminadas es
el efecto de que `main` **añadió** §12 el 8-oct y OBS sigue en §11.

Esto significa que **no hay dos lecturas incompatibles del contrato**: OBS simplemente nunca
supo que §12 existía. No es un desacuerdo de criterio entre las dos ramas, es información que
viaja en una sola dirección.

## Qué decide la §12

`## 12. Exit-code contract (observation commands)` fija un vocabulario cerrado:

```text
0 — el comando corrió y reportó. Una página con rechazos, un filtro sin coincidencias,
    o una continuación estancada sigue siendo 0: el rechazo es un token estructurado en
    stderr, no un fallo. 0 nunca afirma que la consulta encontró algo.
1 — reservado para una lectura que el plano rechazó (stream desconocido), cuando el tipo
    de resultado distingue "rechazado" de "respondido".
2 — el comando NO se ejecutó: error de uso o argumentos.
cualquier otro — una excepción sin manejar escapó de main; es un defecto.
```

Y por comando:

| comando | statuses |
|---|---|
| `pipelinek events` | `0`, `2` |
| `pipelinek console` | `0`, `1`, `2` |

## Cumplimiento medido

### Ejecutado sobre la distribución instalada

Artefacto: `v2/pipeline-application/build/install/pipelinek/bin/pipelinek` (2026-10-09 20:10,
más nuevo que `Main.kt` a las 15:21, luego el binario corresponde al código actual).

| Caso §12 | Invocación real | Exit observado | ¿Conforme? |
|---|---|---|---|
| uso inválido, sin args | `console` | **2** | sí |
| uso inválido, args incorrectos | `console --db X run` | **2** | sí |
| uso inválido, rango corrupto | `console --control-dir X --range bogus` | **2** | sí |
| plano rechaza la lectura | `console --control-dir X no-run no-op` | **1** | sí |

Salida del caso de rechazo:

```text
console-refused: unknown-stream no-run/no-op/transcript
```

**§12 está implementada en `console`.** Los cuatro valores del contrato se observan sobre el
binario, no inferidos de `grep`.

### Corrección de una afirmación anterior

Este documento decía antes que *"`main` tiene un exit code en `MainConsoleCli`; OBS no tiene
ninguno. Ninguna de las dos ramas cumple §12 en `console`"*. **Era falso, y el error fue medir con
`grep` en vez de ejecutar el comando.** El recuento de ocurrencias de `return 1|2|0` no dice si
el comando devuelve ese código: aquí la ruta de rechazo pasa por `System.exit(exitCode)` desde
`Main.kt:141`, fuera del fichero que yo contaba.

Lo que sí era cierto, y sigue siendo: `console` **no acepta `--workspace`** (su interfaz real es
`--control-dir <path> <runId> <opId>`), y `events` usa `--db <path> <runId>`.

## Consecuencia para la reconciliación

1. **El arbitraje de `MainEventsCli` no es decisión de producto.** La §12 dice qué tiene que pasar,
   y ambas ramas cumplen el mismo contrato. Se resuelve por integración de las secciones no
   solapadas, no por elegir un ganador.

2. **`MainConsoleCli` no necesita trabajo nuevo.** El exit code `1` ya existe y emite
   `console-refused:`; el `2` también. Lo que integrarlo exige es no perderlos al fusionar con el
   `--view`/`--format` de OBS, que es una superficie **adyitiva**: `view`/`format` eligen la
   proyección, los exit codes eligen el resultado.

3. **El riesgo real es de test, no de código.** La tabla de §12 tiene cuatro valores y los cuatro
   se observan hoy sobre el binario instalado. Lo que falta es una fila de test que fije el
   `1` para `console` en CI, o seguirá siendo un comportamiento observado y no protegido.

## Lo que este documento NO decide

- Si `pipelinek events` cumple la mitad de su tabla (`0`, `2`) con la misma disciplina. Se verificó
  que devuelve `2` ante uso inválido; no se ejercitó el caso `0` con una página real.
- Si §12 debe aplicarse también a `Main.kt` y a cualquier comando de observación futuro. El título
  dice "observation commands" y la tabla nombra dos; el alcance exacto no está escrito.