# PK-58A — T1 Audit (CORREGIDA) — Matriz F1–F6

**Auditor:** Agente PipelineK · **Fecha:** 2026-10-13T13:55Z
**Inputs:** SHA `280e72fd` (revisión de código), `main@c88f35cf` (HEAD actual)
**Estado:** T1 reabierto, no se inicia otra línea de implementación hasta
clasificar F1–F6.
**Acción inmediata:** corregir el audit previo; ningún ADR ni T2/T3 hasta
clasificar.

## 0. Resumen ejecutivo (cambios respecto al audit anterior)

El audit previo (`280e72fd`) tuvo **5 errores de los 6 que el operador
señaló**. Reclasificación:

| F | Diagnóstico operador | Clasificación correcta |
|---|---|---|
| F1 | `framesOfRun` y `append` hacen O(n) read del log completo | **CONFIRMADO — PK-L4 era `CUMPLIDO`, debe ser `PARTIAL/FAILED_PERFORMANCE`** |
| F2 | `handlePage` emite `Completed` en `hasMore=false` sin `RunFinished` | **CONFIRMADO — clasificar como `NEEDS_FIX` con falsificadores** |
| F3 | Cross-JVM e2e ya existe como `M1DCrossJvmFollowTest` | **CONFIRMADO — mi audit afirmó que no existía, era incorrecto** |
| F4 | OBS-PC-208 = prototipo, OBS-2 Nivel B sin decidir | **CONFIRMADO — clasificar OBS-PC-208 como `PROTOTYPE` (no `IMPLEMENTED`)** |
| F5 | Digests en HANDOFF-TO-CERTIFIER.md body ≠ assets reales | **CONFIRMADO — 6/9 digests en el body NO coinciden con los assets publicados** |
| F6 | 0.57 publicada por excepción al gate | **CONFIRMADO — full releaseGate nunca completó; certifier externo no ha corrido** |

El audit previo no midió PK-L4 antes de declararlo cumplido, asumió
incorrectamente que AAT-M1-01 no existía, y no detectó las
discrepancias de integridad del release. Estos son los efectos del
vicio declarado en `HARNESS FIDELITY LAW §2` (no re-derivar valores
que la autoridad productiva ya posee): clasificar sin medir es
clasificar sin evidencia.

## 1. F1 — `SegmentFrameIndex` con O(n) por append y por poll

**Confirmado.** Lectura del código en
`v2/pipeline-output-store/src/main/kotlin/dev/rubentxu/pipeline/v2/output/store/SegmentFrameIndex.kt`:

- `append(stream, channel, from, to)` (línea 100) llama a
  `sealTornTail(runId)` (línea 316), que llama a `readFrames(runId,
  sealTornTail = true)` (línea 334).
- `readFrames` abre el archivo `framesFile(runId)` con `BufferedReader`,
  lee cada línea, decodifica cada `OutputFrame`, mantiene la lista en
  memoria. **O(n) en bytes del log por cada append.**
- `framesOfRun(runId, afterOrdinal, limit)` (línea 123) llama a
  `readFrames(runId, sealTornTail = false)`, luego filtra en memoria y
  toma `limit` resultados. **O(n) en bytes del log por cada poll/follow.**

**Clasificación de PK-L4:** antes `CUMPLIDO`, ahora
`PARTIAL/FAILED_PERFORMANCE`. El contrato funcional de
"páginas posteriores a ordinal sin escanear todo el run" no se cumple:
toda llamada a `framesOfRun` y a `append` lee el log completo.

**Lo que falta medir antes de reclamar eficiencia (T1.6 del operador):**
- Bytes físicos inspeccionados por poll (p50, p95) para tamaños de log
  pequeño / mediano / grande.
- Latencia p50/p95 de `append` y de `framesOfRun` con historiales
  crecientes.
- RSS/heap de un follower sostenido durante 1 hora.
- Diferencia bytes inspeccionados entre polling-solo y wakeup+polling
  (una vez construido el wakeup, si T3 se autoriza).

**Lo que el audit previo no hizo:** ninguna de estas medidas. Declaró
"PK-L4 cumplido" sin evidencia de bytes/latencia. **Reclasificar.**

## 2. F2 — `SqliteEventFollowHandle` emite `Completed` sin `RunFinished`

**Confirmado.** Lectura del código en
`v2/pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/SqliteEventFollowHandle.kt`
línea 436:

```kotlin
// 3e. Tail reached: a page with `hasMore=false` is the
// natural end of currently available history. For an
// empty history (no rows yet), this is the natural-end
// emission the design §4 composition rule names for an
// empty run: `Completed` after the first poll. The
// consumer decides whether to reopen with `after =
// nextCursor` to keep following a run that may still be
// live.
if (!boundedSlice.hasMore && !completedEmitted) {
    completedEmitted = true
    return queueCompleted()
}
```

**Problema:** el contrato trata `hasMore=false` como terminal del
follow aunque el run siga vivo. Un observador externo que ve
`Completed` y no reabre con `after = nextCursor` pierde todos los
eventos publicados después de ese poll.

**Por qué el cross-JVM test no detectó esto:** el test escribe los
eventos antes de abrir el follow, no en medio. El observador siempre ve
`hasMore=false` cuando el run ya no producirá más eventos. La
observación del operador: el test debe diseñar **falsificadores** en
los que el proceso externo empieza a observar, llega al tail y
después el productor publica eventos nuevos.

**Clasificación:** `NEEDS_FIX`. No publicar `v0.58.0-rc1` con este
comportamiento. La fix correcta (per SPEC-01 y el contrato del
handle) es: el estado natural-tail sin `RunFinished` debe ser un
estado distinguible, no un `Completed`. El `Completed` debe estar
reservado a: (a) `UntilSequence` satisfecho, (b) `RunFinished`
observado.

**Falsificadores obligatorios (heredados del directive del operador):**
- Commit entre registro y primera lectura.
- Commit entre lectura y entrada en espera.
- Stream `Open` frente a `Sealed`.
- Run terminado con output todavía pendiente de sellado.
- Lectura desde otra JVM después de un poll vacío.
- Reabrir con `after = nextCursor` tras un `Completed` falso.

## 3. F3 — Cross-JVM e2e ya existe como `M1DCrossJvmFollowTest`

**Confirmado.** El test existe en
`v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/follow/M1DCrossJvmFollowTest.kt`
con 6 métodos `@Test` y 996 líneas. **Mi audit previo dijo que
"AAT-M1-01 cross-JVM e2e no existe como test separado" — es
incorrecto, el test existe.**

**Lo que el audit previo no hizo:** no buscó en `application/follow/`
antes de declarar el gap. **Reclasificar** AAT-M1-01 de `NO EXISTE` a
`EXISTE` y revisar qué cubre exactamente para no proponer cobertura
duplicada.

**Acción derivada:** leer el test, mapear sus `@DisplayName`s contra
los UAT-PK-M1-001..008 que se propusieron en el audit anterior;
corregir la matriz de trazabilidad. No duplicar; no renombrar.

## 4. F4 — OBS-PC-208 = prototipo, no `IMPLEMENTED`

**Confirmado.** Inventario en código:

- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsPc2IngestAgent.kt`
  — el agente es un `object` declarado en `src/test/`, no producción.
  Es un **fixture de test**, no un componente del producto.
- `v2/pipeline-application/src/test/kotlin/dev/rubentxu/pipeline/v2/application/durable/ObsPc2IngestAgentPrototypeUatTest.kt`
  — el test se llama a sí mismo **"prototipo"** en su `@DisplayName`:
  `"OBS-PC-208 — prototipo: un drenador que sobrevive a su runtime
  sigue entregando bytes"`.
- `docs/v2/04-adrs/ADR-OBS-003-child-output-descriptor-ownership.md`:
  "OBS-PC-208 is unblocked; its acceptance criterion is §4" — el ADR
  dice que el test está **unblocked** (puede escribirse), pero el
  criterio de aceptación del producto (§4) es lo que decide si
  cuenta como `IMPLEMENTED` o sigue en `PROTOTYPE`.
- Misma ADR: "OBS-PC-208 cannot be written until the shape of that
  guarantee is decided, and the shape cannot be chosen until the
  failure is measured."

**Clasificación:** `PROTOTYPE` (no `IMPLEMENTED`). El test existe como
prototipo pero el OBS-2 Nivel B (supervivencia del drenador al morir
la JVM del step) no está decidido, no está medido, y el `IngestAgent`
vive en `src/test/` — no es parte del producto entregado.

**Lo que falta para que pase a `IMPLEMENTED`:**
- Mover `ObsPc2IngestAgent` (o su sucesor) a `src/main/`.
- Decidir y documentar el contrato de OBS-2 Nivel B en §4 del ADR.
- Medir el escenario de "JVM del step muere durante escritura" contra
  el contrato.
- Diseñar y ejecutar un falsificador que reproduzca el escenario
  adverso y verifique la supervivencia.

## 5. F5 — Discrepancias de integridad en el release `v0.57.0-rc1`

**Confirmado y crítico.** La descripción pública del release en
GitHub contiene **digests que no coinciden con los assets
publicados**.

Comparación de los 9 digests listados en el body de
`HANDOFF-TO-CERTIFIER.md` (visible en el release) contra los SHA-256
reales de los archivos en `evidence/0-57-rc1/`:

| Archivo | Digest en el body | Digest real | Estado |
|---|---|---|---|
| `pipelinek-0.57.0.zip` | `0a29d52…` | `1b33728…` | **MISMATCH** |
| `pipelinek-0.57.0.sbom.json` | `a98fd7b…` | `c50109e…` | **MISMATCH** |
| `pipeline-application-0.57.0.jar` | `45e1741…` | `8184a72…` | **MISMATCH** |
| `git-head.txt` (hash del archivo) | `e51a409…` | `ac0582a…` | **MISMATCH** |
| `STATUS.md` | `1f4c127…` | `cd2695d…` (post-update) | **MISMATCH** |
| `HANDOFF-TO-CERTIFIER.md` | `69caba7…` | `e2ed4ce…` (post-update) | **MISMATCH** |
| `pipeline-sdk-bom-0.57.0.pom` | `54228c1…` | `54228c1…` | match |
| `pipeline-domain-0.57.0.pom` | `232ebec…` | `232ebec…` | match |
| `sdk-domain-0.57.0.module` | `10c91cb…` | `10c91cb…` | match |

**Diagnóstico:** el body de `HANDOFF-TO-CERTIFIER.md` fue copiado
de un candidato anterior. Los 3 archivos que coinciden son los
poms/módulo que el script regenera sin alterar; los 6 que NO
coinciden son los artefactos que cambian entre commits.

**Además:**
- `git-head.txt` contiene `f6eb1ebb` (commit de evidence) pero el
  tag `v0.57.0-rc1` apunta al merge commit `51f654e3`. **El
  release está tagueado sobre un commit y la evidence apunta a
  otro.**
- El release en GitHub tiene 2 assets extra (`pipelinek-0.57.0.sdk.zip`
  y `pipelinek-bom-0.57.0.json`) subidos a las 13:04Z que no
  aparecen en el `SHA256SUMS` local ni en la tabla del body.

**Acciones derivadas (sin sustituir bytes silenciosamente, per
directive):**
1. Reconstruir trazabilidad honesta: el body del release debe
   listar **los digests reales de los assets que están en el
   release**, no los de un candidato anterior.
2. Reemplazar el body del release con uno que liste los SHA-256
   de los assets actualmente subidos.
3. Reemplazar `HANDOFF-TO-CERTIFIER.md` en el release con la
   versión `e2ed4ce…` que sí está en disco (si la local difiere
   de la del release, aclarar el delta sin sustituir bytes).
4. Eliminar los 2 assets extra que no están en `SHA256SUMS`, o
   añadirlos al `SHA256SUMS` con sus digests.
5. `git-head.txt` debe reescribirse con el commit del tag
   (`51f654e3`), no con el commit de evidence (`f6eb1ebb`).
6. No reescribir el tag; añadir una **nota de release** con la
   corrección de la trazabilidad. Si la corrección invalida el
   tag, abrir una nueva RC (`v0.57.0-rc2`) en lugar de
   re-tagear.

## 6. F6 — `v0.57.0-rc1` publicado por excepción al gate

**Confirmado.** El full `:pipeline-application:test` no completó
(la phase de tests cuelga bajo carga; mismo signature que
2026-10-12). El publish se ejecutó por override del operador
registrado en el tag message y en `STATUS.md`.

**Lo que falta para que la línea 0.57 quede certificada:**
1. Reproducir el gate en entorno de carga controlada (host
   load < 5 o workaround explícito).
2. Diagnosticar por qué el `:pipeline-application:test` cuelga
   bajo load (¿gradle daemon contention? ¿forked JVM con OOM
   silencioso?).
3. Ejecutar el certifier externo sobre `v0.57.0-rc1` y publicar
   el `CERTIFIER-VERDICT.md` con los 13 escenarios R0.5.
4. No usar el override histórico de PK-57A como precedente para
   que PK-58A publique en las mismas condiciones. **Cada
   bloque exige gate verde end-to-end, no excepción heredada.**

## 7. Matriz `implementado / probado / certificado / pendiente`

| Capacidad | Implementado | Probado | Certificado | Pendiente |
|---|---|---|---|---|
| PK-L1 bytes por canal, sin `\n` | sí | sí (ObsPc, ObsB) | NO | — |
| PK-L2 read-only ports cross-JVM | sí | sí (ObsG, SegmentCrash) | NO | — |
| PK-L3 durable cursor + bounded pages | sí | sí (M1DCrossJvm) | NO | wakeup opcional (DEFER) |
| PK-L4 frame index por ordinal sin scan | **NO** (O(n) read) | sí (contrato) | NO | **F1: medir y/o indexar** |
| PK-L5 pumps + redaction streaming | sí | sí (ObsPc2) | NO | — |
| PK-L6 journal authority, sealed independent | sí | sí (ObsPc) | NO | — |
| PK-L7 joins explícitos | sí | sí (ObservationView) | NO | — |
| AAT-M1-01 cross-JVM e2e (live, no work-around) | parcial | sí (M1DCrossJvmFollowTest) | NO | **F2: handlePage emite Completed sin RunFinished** |
| OBS-PC-208 (OBS-2 Nivel B) | **NO** (PROTOTYPE en `src/test/`) | parcial (PrototypeUatTest) | NO | **F4: decidir contrato §4, mover a `src/main/`, medir** |
| F5 — integridad de release 0.57 | parcial | NO | NO | **CRÍTICO: corregir trazabilidad del body de v0.57.0-rc1** |
| F6 — gate completo en 0.57 | NO | NO | NO | **reproducción en load controlada + certifier externo** |
| Performance baselines LPR-0 | NO | NO | NO | **medición antes de cualquier decisión T3** |

## 8. Acciones inmediatas (en orden de prioridad)

### P0 — crítico, no avanza a T2/T3 ni a publicación de 0.58 sin cerrar

1. **F5** Corregir la trazabilidad del release `v0.57.0-rc1`:
   - Reemplazar el body del release con los SHA-256 reales.
   - Reemplazar `HANDOFF-TO-CERTIFIER.md` en el release con la
     versión de disco.
   - Eliminar o justificar los 2 assets extra.
   - Documentar en el body que `git-head.txt` reescrito al tag
     commit (no al evidence commit).
2. **F6** Reproducir `:pipeline-application:test` en load
   controlada, diagnosticar el cuelgue, o documentar el workaround
   en el override del gate.
3. **F2** Fix del `handlePage` (no publicar 0.58 con el bug).

### P1 — antes de T3

4. **F1** Implementar el measurement harness para `framesOfRun` /
   `append` con historiales creciente; producir tabla de bytes
   inspeccionados y latencia p50/p95 por tamaño de log.
5. **F4** Decidir el contrato OBS-2 Nivel B en §4 del ADR-OBS-003.
   Si se decide producto: mover `ObsPc2IngestAgent` (o sucesor) a
   `src/main/`, diseñar el falsificador, ejecutar la medición.
6. **F3** Revisar `M1DCrossJvmFollowTest` para mapear sus
   `@DisplayName` contra UAT-PK-M1-001..008; corregir la matriz
   de trazabilidad; no duplicar.

### P2 — antes de T7

7. Performance baselines LPR-0 con la suite medición.
8. ABI documentada de Output Read v0.58 con delta 0.57 → 0.58.
9. ReleaseGate end-to-end verde (`:pipeline-release:releaseGate`).
10. Push + PR + merge + tag `v0.58.0-rc1` + `gh release create
    --prerelease` con body trazable.

## 9. Conclusión para el operador

El audit previo **no era fiable para tomar la decisión
NO_NEW_PORT**. Las 6 Findings del operador son reales y
cambian la matriz:

- F5 y F6 invalidan la confianza en el release 0.57 publicado.
- F2 es un bug del follow de eventos que no debe arrastrarse a
  0.58.
- F1 invalida la clasificación de PK-L4 y obliga a medir antes
  de cualquier decisión de eficiencia.
- F3 invalida la propuesta de AAT-M1-01 como gap a cerrar.
- F4 invalida la propuesta de OBS-PC-208 como `IMPLEMENTED`.

**Recomendación revisada:** NO_NEW_PORT se mantiene como
recomendación **a priori** (no hay justificación para T3 sin
medición), pero T2/T3/T7 no avanzan hasta que F1, F2, F4, F5
y F6 estén cerrados. El bloque PK-58A abre con tres frentes
críticos (integridad, fix del follow, decisión OBS-2 Nivel B)
antes de cualquier publicación de la línea 0.58.
