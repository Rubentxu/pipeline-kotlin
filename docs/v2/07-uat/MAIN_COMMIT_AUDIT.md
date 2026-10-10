# Auditoría de los 113 commits de `main` — 2026-10-09

Contraparte de `OBS_COMMIT_AUDIT.md`. Aquí está el trabajo que OBS **no** ve, y por qué la
reconciliación no es un merge.

Base: `git log origin/par/cli-observation..main`.

## Caracterización: no es la misma clase de trabajo

| Tipo | `main` (113) | OBS (68) |
|---|---|---|
| `docs` | **53** | 7 |
| `fix` | **26** | 11 |
| `test` | 20 | **20** |
| `feat` | 7 | **20** |
| `refactor` | 4 | 5 |
| `perf` | 0 | 3 |
| `chore` / `release` / merge | 4 | 2 |

`main` es mitad documentación y correcciones. OBS es mitad construcción de sistema y tests.
**No compiten por el mismo trabajo**, y por eso un merge masivo no es "incorporar lo pendiente":
es intercalar dos programas distintos.

## Fases del trabajo en `main`

```text
07-oct  Harness de pruebas: OwnedSubprocess, y los UAT que migran a la primitiva
08-oct  B0 Auditoría: 11 hallazgos contrastados contra el código; digest de procedencia
08-oct  B1..B5 Bloques closures uno a uno, cada uno con receipt y deuda nombrada
09-oct  B6/B7, roadmap, y el trabajo de C8 (workspace root deletion)
```

## Cruce crítico: `main` también tocó la zona de OBS

Tres commits de `main` trabajan exactamente donde OBS construyó su sistema:

- `5aadf3f1` — `fix(b1c): one canonical stream lock, and an observation CLI`
- `559948cc` — `fix(cli): console refuses what it cannot honour`
- `d1869963` — `fix(b1d): read the observation clock from the injected seam`

Esto **sí** es solape semántico, y es el más peligroso de toda la reconciliación: no son
ficheros distintos describiendo lo mismo, es el mismo fichero resuelto dos veces por dos vías.

### AUD-08 — dos estrategias incompatibles para el mismo defecto

`main` (commit `5aadf3f1`) detectó que `SegmentOutputStore` indexaba el lock de forma distinta a
como `prune` lo encontraba: el writer usaba el `OutputStreamId` crudo y el prune el nombre de
directorio ya plegado con `safe()`. Dos claves para un mismo stream.

| | `main` | OBS |
|---|---|---|
| Tipo del mapa | `HashMap<String, ReentrantLock>` | `HashMap<OutputStreamId, ReentrantLock>` |
| Clave | `streamKey(stream) = safe(stream.value)` | `OutputStreamId` crudo |
| ¿Usa `safe()`? | sí, explícito | vía `safeStreamName` |

**Cada uno resuelve el mismo bug AUD-08 y ninguno acepta la estrategia del otro.**

### Hallazgo de bloqueante: `safeStreamName` no existe en `main`

```text
git grep -l safeStreamName origin/par/cli-observation
  → SegmentFrameIndex.kt
  → SegmentOutputStore.kt
  → CrashedResidue.kt (test)

git grep -l safeStreamName main
  → (vacío)
```

`safeStreamName` es **código nuevo de OBS**. `SegmentOutputStore` de OBS la invoca, y `main` no
tiene esa función.

Consecuencia directa: **cualquier merge que traiga el `SegmentOutputStore` de OBS sin `safeStreamName`
no compila.** No es un conflicto textual que Git pueda marcar; es una dependencia de símbolos que solo
aparece al compilar. Un merge masivo no lo detectaría hasta `compileKotlin`, es decir tarde y
con un error que no señala la causa real.

## El cruce de `MainConsoleCli` es del mismo tipo

`559948cc` (`main`) cierra en `pipelinek console` exactamente la clase de defecto que B1 había
cerrado en `pipelinek events`, y lo hace invocando `CLI_OBSERVABILITY_SPEC` §12 — que **ya lo
exigía**. OBS también reescribió `MainConsoleCli` con `--view/--format`.

Los dos lados citando la misma especificación llegan a conclusiones sobre qué debe rechazar el CLI.
Ese arbitraje no es mecánico: es una decisión de producto sobre qué contradicciones incumplen el
contrato.

## Riesgo abierto que esto deja

Los 113 commits de `main` ya no son un Unknown genérico. El riesgo es concreto y tiene tres
puntos:

1. **AUD-08** tiene dos resolvers incompatibles y OBS depende de un símbolo que `main` no tiene.
   Requiere decisión de arquitectura, no de merge.
2. **`MainConsoleCli` / `MainEventsCli`** requieren decisión de producto, y ambos lados citan la
   misma espec.
3. **`BodyExecutionEngine`** (`d1869963`) auditó 15 sitios de reloj y decidió uno por uno;
   hay que comprobar si OBS movió esa misma superficie.

## Qué NO se ha hecho

- No se ha compilado ninguna mezcla. No hay evidencia de que hoy compile, y no se afirma ninguna.
- No se ha ejecutado ningún test sobre un árbol mixto.
- No se ha mergesado nada.

## Cambio en el orden propuesto

La auditoría de `main` **añade** un paso antes de cualquier merge:

0. **Resolver AUD-08** — elegir una estrategia de keying y portar `safeStreamName` si procede.
   Es bloqueante de compilación, así que va primero de todo.
1. **Las 4 ADR de OBS**, que gobiernan el código que se va a integrar.
2. **Los 2 conflictos de CLI**, por decisión de producto.
3. **Los 7 conflictos de test**, que son legados del harness.
4. **El resto de `main`** (53 docs, la mayoría receipts) entra por su propio peso.