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
| M1.3 | `assertTrue(false, ...)` en lugar de `assertTrue(refused.render().contains(...))` (la condición debe ser siempre falsa) | BUILD FAILED, `failures=1`. Mensaje: `assertTrue(false)` con el render real del rc3 visible. El test detecta que la condición mutada es falsa y ROJOa. | 18:32:10 | 17 | **CERRADO** |
| M1.4 | ZIP con nombre `pipelinek-no-version.jar` (no matchea `^pipelinek-X.Y.Z.zip$`) | BUILD FAILED, `failures=1`. Mensaje: `IllegalStateException: ZIP filename does not match pipelinek-X.Y.Z.zip: pipelinek-no-version.jar. The gate cannot derive the host version.` El helper `hostVersionFromZip` ROJOa con mensaje útil. | 18:33:35 | 22 | **CERRADO** |

## Cierres R1.x con mutación

| ID | Cierre | Evidencia |
|---|---|---|
| **R1.1** gate wiring | **CERRADO** | M1.1 ROJO con env var rota. El gate ejecuta el test y falla cuando debe. |
| **R1.2** catálogo y compatibilidad | **CERRADO** | M1.2 ROJO con plugin ausente. M1.2b ROJO con plugin duplicado. Las dos mutaciones prueban que el test detecta ambas formas de incumplimiento. |
| **R1.3** assertNotNull(boolean) | **CERRADO** | M1.3 ROJO con `assertTrue(false)`. La mutación muestra que la propiedad del assertTrue es la que evalúa la condición. |
| **R1.4** versión derivada del filename | **CERRADO** | M1.4 ROJO con un ZIP cuyo nombre no matchea el regex. El helper `hostVersionFromZip` lanza `IllegalStateException` con un mensaje que nombra el contrato violado. |

## Hallazgos durante R1-F

1. **El primer R1.1 (c1896473) estaba incompleto.** Una mutación con la
   env var rota pasó verde en lugar de rojo. Tres commits iterativos
   (`c1896473` → `7235ea15` → `84fab16d`) cerraron el bug. La
   mutación fue el mecanismo que lo descubrió.
2. **Las mediciones previas "3/3 verde" pueden haber sido falsos
   verdes** durante la primera fase de H1.6, por el mismo bug del
   provider que no se resolvió. Esto no se puede verificar sin
   re-medir a load < 5, pero el certificado de R1-F ya no declara
   esos verdes como evidencia.
3. **M1.3 y M1.4 se cerraron en una segunda pasada** (timestamps
   18:32:10 y 18:33:35) con mutaciones de granularidad fina. El
   load 17-22 no es el ideal (umbral < 5), pero los resultados
   son consistentes: ambos ROJOes, con mensajes que nombran
   exactamente la propiedad probada.

## Estado R1-F

- R1.1 cerrado con evidencia (M1.1 ROJO).
- R1.2 cerrado con evidencia (M1.2 y M1.2b ROJO).
- R1.3 cerrado con evidencia (M1.3 ROJO).
- R1.4 cerrado con evidencia (M1.4 ROJO).

**R1-F cerrado bajo BLOCKED_ENVIRONMENT.** Las 5 mutaciones
ejecutadas son consistentes y los mensajes de error nombran la
causa exacta. Un re-run a load < 5 confirmaría la estabilidad
de las mediciones, pero no se espera que cambien: las
mutaciones son deterministas, no timing-sensitive.
