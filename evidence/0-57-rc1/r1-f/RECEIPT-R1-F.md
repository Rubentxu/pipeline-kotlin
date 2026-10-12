# R1-F RECEIPT — Mutaciones sobre el releaseGate

**SHA evaluado:** `84fab16d` (origin/pk-release-0-57-consolidation)
**Fecha:** 2026-10-12 19:50Z
**Entorno:** host load 14-19 (umbral seguro < 5). Se midió bajo el
consentimiento del operador (`Forzar con load actual`). Los
resultados a load > 5 son **no autoritativos** y deben repetirse
a load < 5 antes de R2.

## Tabla de mutaciones

| ID | Mutación | Resultado | XML timestamp | Load | Estado |
|---|---|---|---|---|---|
| M1.1 | `PIPELINEK_DIST_ZIP=/tmp/nonexistent-zzz-999.zip` en bash, env var rota apuntando a path inexistente | BUILD FAILED, `failures=1`. Mensaje: `expected 7 plugins, was []`. El gate respetó la env var del operador, no la del build script. | 17:48:02 | 14 | **CERRADO** |
| M1.2 | ZIP mutado sin `utilities-0.57.0.jar` (6 de 7 plugins presentes) | BUILD FAILED, `failures=1`. Mensaje: `expected 7 plugins, was [example-block-plugin, example-directive-plugin, example-uppercase-plugin, pipeline-plugin-http, pipeline.junit, pipeline.scm-git]`. El catálogo detecta el plugin ausente. | 17:50:45 | 18 | **CERRADO** |
| M1.2b | ZIP mutado con `http-0.57.0.jar` duplicado como `http-dup-0.57.0.jar` (mismo pluginId en dos JARs) | BUILD FAILED, `failures=1`. Mensaje: `Duplicates: {pipeline-plugin-http=2}`. El test estructural de duplicados se dispara **antes** del set-equality. | 17:52:00 | 17 | **CERRADO** |
| M1.3 | assertTrue con substring falsa `[9.99.9, 9.99.9]` (assertion debería fallar porque el render del rc3 no contiene esa substring) | Test no se ejecutó: la compilación de `pipeline-events-store:compileKotlin` no terminó dentro del timeout de 120s. | — | 17 | **inconclusive** |
| M1.4 | ZIP con nombre `pipelinek-no-version.zip` (no matchea `^pipelinek-X.Y.Z.zip$`) | Test no se ejecutó: misma razón que M1.3. | — | 17 | **inconclusive** |

## Cierres R1.x con mutación

| ID | Cierre | Evidencia |
|---|---|---|
| **R1.1** gate wiring | **CERRADO** | M1.1 ROJO con env var rota. El gate ejecuta el test y falla cuando debe. |
| **R1.2** catálogo y compatibilidad | **CERRADO** (parcial) | M1.2 ROJO con plugin ausente. M1.2b ROJO con plugin duplicado. Las dos mutaciones prueban que el test detecta ambas formas de incumplimiento. |
| **R1.3** assertNotNull(boolean) | **CERRADO** por inspección de código | El código actual usa `assertTrue(...)` (commit `cb67efdd`). M1.3 inconclusivo por timeout de compilación. La inspección del commit confirma el fix. |
| **R1.4** versión derivada del filename | **CERRADO** por inspección de código | El código actual usa `hostVersionFromZip(zip)` con regex `^pipelinek-(\d+\.\d+\.\d+)\.zip$` (commit `a95956b2`). M1.4 inconclusivo por timeout. La inspección del commit confirma el fix. |

## Hallazgos durante R1-F

1. **El primer R1.1 (c1896473) estaba incompleto.** Una mutación con la
   env var rota pasó verde en lugar de rojo. La causa fue que
   `environment(String, String)` recibe el path como String, pero
   el `provider.get()` se evalúa en `tasks.test {}` en el momento
   correcto. La mutación demostró que el primer fix no respetaba
   la env var del operador. Tres commits iterativos
   (`c1896473` → `7235ea15` → `84fab16d`) cerraron el bug.
2. **Las mediciones previas "3/3 verde" pueden haber sido falsos
   verdes** durante la primera fase de H1.6, por el mismo bug del
   provider que no se resolvió. Esto no se puede verificar sin
   re-medir a load < 5.
3. **M1.3 y M1.4 quedaron inconclusivos** por timeout de compilación
   bajo load 17. No es razonable seguir forzando el gate con
   load 119 (estado actual del host al cierre de R1-F).

## Estado R1-F

- R1.1 cerrado con evidencia (M1.1 ROJO).
- R1.2 cerrado con evidencia (M1.2 y M1.2b ROJO).
- R1.3 cerrado por inspección de código; la mutación runtime quedó
  inconclusiva.
- R1.4 cerrado por inspección de código; la mutación runtime quedó
  inconclusiva.

**No declaro R1-F cerrado** hasta que M1.3 y M1.4 se midan a
load < 5. El operador autorizó forzar el gate; el host pasó de
load 17 a load 119 en el mismo turno, lo que excede cualquier
umbral razonable. Las dos mutaciones inconclusivas se reintentan
a load aceptable.
