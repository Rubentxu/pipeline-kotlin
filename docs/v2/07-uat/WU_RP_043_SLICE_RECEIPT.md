# WU-RP-043 Slice Receipt — Self-hosted CI dogfooding (N1+N2+N3)

- **WU:** WU-RP-043 (RP-4, self-hosted CI / dogfooding progresivo)
- **Date:** 2026-09-22
- **Head SHA:** `74617ff8` (commit `74617ff8` adds the dogfood job + red-path fixture)
- **CI:** run `35781433830` — final conclusion **SUCCESS** (10 jobs incl. `dogfood`).

## Criteria mapping (ROADMAP §6, WU-RP-043 charter)

| Criterio | Implementación | Evidencia |
|---|---|---|
| (a) pipelinek construido desde checkout limpio | Job `dogfood` step N1: `checkout@v4` + `setup-java` + `installDist --no-daemon`, sin depender de ningún otro job ni caché de engine | CI job success on 74617ff8 |
| (b) `.pipeline.kts` real del mismo SHA ejecutado | N2 success path: el binario instalado del SHA ejecuta `v2/compatibility/01-basic.pipeline.kts` con `--workspace .` | exit 0; job green |
| (c) salida/artefactos verificados fuera del motor | N3: `jq` assertions sobre el events-jsonl capturado a fichero — `.[0].kind=="CompilationStarted"`, `.[-1].kind=="RunFinished" && .[-1].outcome=="success"`, presencia de `StepFinished stepName=="hello/echo-0"`; stream de fallo: `RunFinished outcome=="failure"` + `StepFailed failureKind=="SCRIPT"` | 5/5 assertions validadas localmente sobre streams reales (exit 0 y exit 1) y en CI |
| (d) fallo intencional deja CI en rojo | Fixture `ci/dogfood-fail.pipeline.kts` (sh → exit 1); el job falla SI el script sale 0 (`::error::WU-RP-043(d)`) | Proba local: pipelinek exit 1, eventos `StepFailed`/`RunFinished failure` observados |
| (e) un error de DSL no impide diagnósticos del bootstrap | N1 es autónomo: si el motor rompe, `installDist` falla en este job con logs de Gradle, independiente del resto | Estructura del job (independencia, sin `needs`) |

Nota N1: la cláusula charter "válido aunque el DSL/registro/ejecutor estén rotos"
se cumple por construcción: N1 compila el compilador/DSL pero no EJECUTA ningún
script; un DSL roto que compile sigue dejando N1 verde y los diagnósticos salen
del fallo de N2/N3, no del bootstrap.

## Decisiones de diseño

1. **Job separado en paralelo** (no `needs: compile`): el charter exige que el
   bootstrap sea independiente; añadir dependencias recrearía la cadena serial
   que WU-RP-005 r5 eliminó. Coste: una compilación extra (~3 min runner), el
   max-paralelo del workflow apenas cambia.
2. **Fixture de fallo en `ci/`** (raíz del repo), no en `v2/compatibility/`: es
   un artefacto de CI, no un fixture del corpus de compatibilidad; mantenerlo
   fuera evita que el corpus test lo arrastre.
3. **N3 via jq sobre stdout capturado** y artefacto publicado con `if: always()`
   para inspección externa post-fallo.
4. **Corrección propia durante la implementación**: la primera versión de N3
   usaba `head -c 2000 | jq` (truncaría el JSON a mitad de array y fallaría
   espuriamente); corregido a `jq` sobre el fichero completo antes del commit.

## Estado por nivel

- **N1 (bootstrap independiente): DONE** — job dogfood step 1.
- **N2 (dogfooding): DONE (parcial por diseño)** — 1 script de éxito + 1 de
  fallo del repo. El charter permite crecer el set de scripts dogfood
  gradualmente; ampliar cobertura per-shard es evolución, no gap de salida.
- **N3 (verificación externa): DONE** — outcomes y forma del stream verificados
  fuera del motor; artefactos publicados.
- **ADR-0094 (selección por impacto) como fuente común de política de testing**:
  NO en este slice — es dependencia separada declarada en el charter ("será la
  fuente común"); queda como trabajo posterior, no como criterio de salida
  incumplido (los criterios (a)-(e) no la mencionan).

## Flakes observados en este slice

- CI `35781433830` primer intento: `SqliteEventStoreRoundTripTest` falló en
  runner de CI; **5/5 verdes en local con `--rerun-tasks`** sobre el mismo SHA;
  rerun de CI limpio sin cambios de código. Clasificado FLAKE (coherente con el
  patrón histórico de este test en runners compartidos); sin evidencia
  reproducible no se debilita nada. Queda anotado; si recurre, abrir
  caracterización WU propia (regla: flake repetido = defecto hasta demostrar
  lo contrario).

## Result

**WU-RP-043 closure evidence COMPLETE** (criterios (a)-(e) con evidencia
OBSERVED en CI sobre `74617ff8`). RP-4 restante: evaluar en RP-5 si el charter
exige más scripts dogfood antes del gate production-ready.
