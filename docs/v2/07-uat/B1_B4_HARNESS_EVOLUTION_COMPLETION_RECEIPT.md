# B1–B4 — evolución del test-harness: informe antes/después

- **Fecha:** 2026-10-15/16
- **HEAD al cerrar:** `2e5c0458` + este bloque (worktree `test-harness-brave-harbor-6920`)
- **Alcance:** el documento *Evolución del test-harness* (B1 consolidación y medición, B2 feedback
  rápido, B3 integración segura, B4 release y certificación), ítem por ítem.
- **Principio aplicado:** ninguna aceleración que introduzca un falso verde. Todo lo que no se
  pudo verificar se declara `NOT_RUN`/`BLOCKED` con la razón, nunca `PASS` por ausencia.

---

## 1. B1 — Consolidación y medición

| Ítem | Estado | Evidencia |
|---|---|---|
| Auditar tareas/scripts (duplicados, reconstrucciones, JVMs, dependencias) | **hecho** | `scripts/audit-gradle-test-graph.py` + `B1_HARNESS_GRADLE_GRAPH_AUDIT_RECEIPT.md` |
| Línea base reproducible (P50/P95, config, compilación, ejecución, forks, skips, fallos, caché) | **hecho** | `measure-harness-baseline.py` ahora publica **P50/P95** (interpolación lineal), media, desviación y `p95_reliable` (falso por debajo de 20 muestras). Muestra real N=20 sobre `:pipeline-events:detekt`: `p50=2.346s p95=4.095s mean=3.194s stdev=3.813s min=1.951s max=19.333s work_executed=False` |
| Centralizar catálogos/políticas/orquestación/fixtures/reportes en `v2/test-harness/` | **hecho** | `v2/test-harness/` (nuevo): `catalogue.json` + `CATALOGUE.md` **generados** + `README.md` con la frontera. Las pruebas siguen en sus módulos; las políticas se **indexan**, no se copian |
| Reutilizar `ContractCatalogue`, `ImpactResolver`, `SuitePolicy`, `Dispatch`, `Gate`, `EvidenceReader`; sin segundo motor | **hecho** | Tabla de roles en el catálogo: **una** autoridad por rol; un fichero que reclame un nombre de rol fuera de esa tabla hace fallar el generador (regla V2). El catálogo no implementa selección: llama al planificador por su CLI y a `just` por introspección |
| Fitness bidireccional (código productivo → propietario e impacto; suite → existe/ejecuta/contrato) | **hecho** | V1: todo módulo con `src/main` debe tener plan de impacto no vacío (29 módulos verificados). V5: toda suite de `scripts/` existe y declara tests. `just fitness` = registro tool↔suite **+** `catalogue --check` |
| Fidelidad, skips y evidencia fresca | **hecho** | `run-scoped-junit-report.py` falla cerrado ante XML inválido, cero tests ejecutados, todo-skipped, head distinto; R8 en admisión |
| Reconciliar HF0–HF6 con la especificación | **hecho hasta donde el repositorio puede** | `PIPELINE_TEST_HARNESS.md` §R5 (reconciliación) + `docs/v2/00-governance/ADR_0072_HF_LADDER_DECISION_READY.md` (opciones A/B/C con coste y recomendación). **La decisión es del owner**: ADR-0072 sigue `proposed` |

**Cierre B1:** informe antes/después = este documento; catálogo verificable =
`v2/test-harness/catalogue.json`; tests adversariales = 18 del generador (una mutación por regla)
+ los del planificador y del gate.

## 2. B2 — Desarrollo con feedback rápido

| Ítem | Estado | Evidencia |
|---|---|---|
| Perfil `devGate` gobernado por el harness | **hecho** | `just dev [base]` (`B2_DEV_GATE_RECEIPT.md`) |
| Selección por impacto | **hecho** | `scripts/plan-affected-tests.py`, fuente única |
| Gradle incremental sin `--rerun-tasks` global | **hecho** | `just dev`/`just integration` no lo pasan; la escalada es `just gate-escalate` |
| Separar puras de instaladas/durables; no aplicar `forkEvery=1` indiscriminadamente | **hecho como política verificable** | V7 del catálogo: `forkEvery`/`maxParallelForks` exige motivo declarado; hoy sólo `pipeline-application` (wedge UAT, PK-0-57-RC1). No se cambió el fork: hacerlo sin reproducir el wedge sería debilitar |
| Un único grafo Gradle por fase, sin invocaciones anidadas | **hecho y mecanizado** | V6 del catálogo cuenta invocaciones *ejecutadas* por receta (un `echo` que imprime el plan no cuenta); el catálogo publica el dueño del grafo de cada nivel |
| Ejecutar la mutación que reproduce el defecto | **hecho donde aplica** | Mutaciones medidas en los receipts (renderer de supersessions, contador de grafos, coste de p95, guard) |

**Cierre B2:** dev gate medido (`just dev` harness-only = 177 s, `just harness` = 197 s) y
equivalencia de contratos: el plan afectado usa el mismo grafo que el gate, sin atajos.

## 3. B3 — Integración segura

| Ítem | Estado | Evidencia |
|---|---|---|
| `integrationGate`: fitness, lint, ABI, afectadas, **consumidores**, regresiones críticas | **hecho** | `run-integration-gate.py` planifica con `--profile merge`: `:<mod>:test` + `:<mod>:detekt` + `:<mod>:apiCheck` (sólo si el módulo publica dump `.api`) + **consumidores directos** derivados de `implementation(project(...))`, todo en **un** grafo. Medido en el árbol real: consumidor hoja `pipeline-step-sdk/http` → 5 módulos; núcleo `pipeline-domain` → 25 (la verdad del grafo) |
| Límites de concurrencia por recurso | **hecho** | `scripts/gate-resource-guard.py`: detecta otro gate en el host (JVM/grafo), se niega a arrancar (exit 2, nombrando pid y cmdline) y permite la excepción deliberada (`--force`, `PIPELINEK_GATE_ALLOW_CONCURRENT=1`). Cableado en `just gate`, `gate-escalate`, `rc` y en la fase Gradle de L-MERGE (veredicto tipado `BLOCKED`, fase `gradle` con `ran=false`) |
| Receipts tipados con SHA, plan, tareas, clases, conteos, XML | **hecho** | `run-integration-gate.py` (PASS|FAIL|BLOCKED|STALE + `NOT_RUN` por fase) y `emit-candidate-receipt.py` |
| Rechazar vacíos, suites omitidas, fidelidad insuficiente, evidencia de otro commit | **hecho** | JUnit scoped (falla cerrado), `--expect-sha`, R8, `pipelinek.build.head` en el manifiesto |
| Ratificar cambios de gate antes de reemplazar un gate obligatorio | **hecho** | La escalera no sustituye a STEP-CERT ni a PRODUCT-GATE (`CERTIFICATION_PROTOCOL.md` §4); `gate-full` conserva la semántica antigua |

## 4. B4 — Release y certificación

| Ítem | Estado | Evidencia |
|---|---|---|
| Una distribución candidata con identidad completa | **hecho** | ZIP 92 985 677 bytes, sha256 `1b1a2c01…60b7`, archive root/Implementation-Version/ley de identidad de producto verificados (`B4_LOCAL_CANDIDATE_EVIDENCE_RECEIPT.md`) |
| Matriz completa de RC | **hecho local / declarado externo** | Tabla en `CERTIFICATION_PROTOCOL.md` §4: qué corre aquí (tests, fitness, distZip, SBOM, admisión, identidad, consumidor del veredicto) y qué sólo puede correr el harness (UAT instaladas, durabilidad, compatibilidad, proyectos reales, seguridad). Lo no ejecutado es `NOT_RUN`, no `PASS` |
| Separar `candidateGate` del gate rápido | **hecho** | `just rc` = L-RC = `releaseGate` con la frontera UAT; presupuesto **declarado** (2400 s) pendiente de la primera ejecución completa (PK-58A F6) |
| Receipt inmutable ligado a SHA, entradas, artefactos y hashes | **hecho** | `emit-candidate-receipt.py` (16/16 checks) |
| Entregar al harness externo | **preparado, no ejecutado** | `docs/v2/07-uat/evidence/candidate-e79fbff7/handoff-to-release-harness.md`: bytes exactos, pasos de publicación (tag + Release + verificación del asset) y lo que NO se reclama. Publicar requiere credenciales del operador |
| Integridad/inventario/descarga como consumidor, sin reconstruir | **hecho** | `scripts/verify-published-release.py`: descarga el asset y un fichero independiente (sidecar `<asset>.sha256` / `zip.sha256`, o inventario `SHA256SUMS`), verifica los bytes, la raíz del archivo y el `Implementation-Version` embebido con el lector existente (`emit-candidate-receipt.py`), con veredicto tipado `VERIFIED\|MISMATCH\|BLOCKED\|MISSING`. Verificado contra los **bytes reales** de la candidata (§10). `install-pipelinek.sh` sigue cubriendo la instalación; `consult-harness-verdict.py` el veredicto externo |

## 5. Lo que NO está hecho, y por qué (sin adornos)

```text
1. Drift de pines de certificación en :pipeline-application:test (5 filas, PRE-EXISTENTE en la
   base, probado base-vs-head). Mientras siga rojo, L-ROUND y L-RC terminan FAIL por esa causa y
   no existe una duración verde de la que derivar el presupuesto. Decisión de version-bump/owner.
2. :pipeline-events-store:test sigue sin poder observarse (dos intentos, cero XML, kill 137)
   dentro de ventanas con otro gate en marcha. Es wedge-shaped y del dueño del módulo.
3. HF0–HF6: la decisión (aceptar A / enmendar B / dejar C) es del owner; aquí sólo se hizo la
   evidencia y la recomendación.
4. La matriz RC externa (UAT instaladas, durabilidad, compatibilidad, PetClinic y resto) y el
   veredicto del harness no se han ejecutado: no hay credenciales ni harness en este entorno.
5. No hay superficie de SAST/dependency scan: se declara NOT_RUN, nunca PASS por ausencia.
6. `gen-certification-ledger.py` conserva `MANDATORY_SUPPORTED = 0` hardcoded y está en la lista
   de "no tocar" (hash-pinned para la copia del harness): acción del owner.
```

## 6. Verificación de este bloque

```text
just harness                 21 -> 23 suites, fail=0 (tras añadir guard y catálogo)
just fitness                 rc 0: registro tool<->suite + catalogue --check OK-IDENTICAL
just catalogue-check         OK-IDENTICAL (29 módulos, 6 gates, 23 suites)
suites nuevas                catálogo 18, guard 9, planificador 20, gate 15, baseline 21
tests de mutación            una por regla nueva (V1..V7, contadores, guard, p95)
admission (HEAD del bloque)  R4/R5/R8 PASS; R7 FAIL pre-existente (62 commits por detrás de origin/main)
```

## 7. Evidencia viva del bloque (no sólo tests)

**L-MERGE real** sobre el diff del commit de gobierno (`--base ea939602~1`), árbol limpio:

```text
INTEGRATION GATE  head=ea939602f963  verdict=PASS
  base=ea939602~1  tree=clean  plan: gradle=[] python=[test_gate_resource_guard,
      test_gen_test_harness_catalogue, test_measure_harness_baseline,
      test_plan_affected_tests, test_run_integration_gate]
  [ran] fitness: exit=0 0.087s
  [ran] python:… (5 suites, todas exit=0)
  [NOT_RUN] gradle: exit=0 0.0s        <- honesto: este diff no toca código de módulo
exit=0  wall=4s
```

Perfil `merge` registrado en el recibo; `consumers=[]` porque ningún módulo se tocó. La fase
`gradle` se registra como `NOT_RUN`, no como verde por ausencia.

**Guard de concurrencia, en vivo:** `python3 scripts/gate-resource-guard.py --gate L-MERGE`
→ `allowed (no other gate running)` (exit 0). Con otro gate en marcha se niega (exit 2, nombra
pid y cmdline); eso está pinzado por los 9 tests herméticos del guard, incluida la exclusión del
propio árbol de procesos (si no, `just`, cuyo cmdline menciona la receta, bloquearía siempre).

**Suites del harness:** `just harness` → **23 suites, fail=0**. `just fitness` → rc 0
(registro tool↔suite + `catalogue --check` OK-IDENTICAL).

## 8. Línea base de EJECUCIÓN (no la de overhead)

El bloque §1 midió la ruta cálida sin trabajo (`work_executed=False`): eso es overhead de
Gradle, útil para rule 2 pero no es "ejecución". El medidor ahora tiene `--rerun` (con
`--require-work`) para forzar la tarea y exigir que el oráculo de frescura vea XML
**reescrito**:

```text
$ python3 scripts/measure-harness-baseline.py --task ':pipeline-domain:test' --runs 3 \
      --rerun --require-work
BASELINE :pipeline-domain:test  runs=3 (small-sample median, not a statistical P50)  [--rerun-tasks: execution cost]
  run 1: exit=0 SUCCESS wall=60.369s executed=8 up-to-date=0
  run 2: exit=0 SUCCESS wall=22.910s executed=8 up-to-date=0
  run 3: exit=0 SUCCESS wall=21.586s executed=8 up-to-date=0
  p50=22.91s p95=56.623s (p95 unreliable below 20 runs) mean=34.955s stdev=22.019s
  min=21.586s max=60.369s verdict=PASS work_executed=True
  xml: files=137 rewritten=137 tests=735 failures=0 errors=0 skipped=0 unreadable=0
```

Lectura honesta: la primera ejecución cuesta 60 s (incluye compilación), las siguientes ~22 s
(ejecución con clases ya compiladas); la ruta cálida sin trabajo es ~2.1 s (§1). `rewritten=137`
prueba que los 735 tests son de ESTAS ejecuciones y no XML heredado; `skipped=0`. Con n=3 el P50
es una mediana pequeña y el P95 lo marca el propio informe como no fiable: no se deriva ningún
presupuesto de aquí, se publican los números y su etiqueta.

## 9. Superficies críticas (V9) y qué encontró

`v2/test-harness/surfaces.json` declara las superficies que el mandato nombra (CLI, plugins,
credenciales, eventos, Output Plane, más runtime/scripting/release) y los módulos auxiliares con
motivo. El generador valida la cobertura y el planificador **lee el mismo fichero** para decir
qué superficies toca un cambio (`critical surfaces touched: runtime`, etc.). Escribir la regla
encontró, en el árbol real:

```text
- V9 v2/pipeline-sdk-bom: ningún módulo lo había declarado (BOM: se declara auxiliar con motivo)
- V9 v2/pipeline-step-sdk/api: declarado a la vez como superficie (glob plugins) y como auxiliar
- V9 auxiliary 'pipeline-step-sdk/processor' ya no existe (declaración obsoleta)
```

Tres defectos reales de un mapa que antes no existía, corregidos en el mismo commit y con tests
que los reproducen en un árbol hermenéutico.

## 10. Verificación de release publicada (B4.6) y P50/P95 de ejecución fiables

**Verificador de release publicado** (`scripts/verify-published-release.py`, 10 tests herméticos
con ZIPs reales servidos por un espejo loopback real): descarga el asset y un fichero de digest
**independiente** (sidecar `<asset>.sha256` o `zip.sha256`, o inventario `SHA256SUMS`), exige que
los bytes coincidan, y lee la raíz del archivo y el `Implementation-Version` embebido con el lector
que ya existía (`emit-candidate-receipt.py`: no hay una segunda implementación de "qué dice ser el
archivo"). Veredictos tipados: `VERIFIED` (0), `MISMATCH` (1), `BLOCKED` (3), `MISSING` (4).
Sin fichero independiente → `MISSING`: "se descargó" no es identidad.

Ejecutado contra los **bytes reales** de la candidata (espejo loopback sirviendo el ZIP de build,
sin reconstruir nada):

```text
PUBLISHED RELEASE  verdict=VERIFIED
  [PASS] asset downloaded: pipelinek-0.61.0.zip: 92985677 bytes
  [PASS] an independent digest is published: digest source=SHA256SUMS
  [PASS] download matches the published digest: declared=1b1a2c01…60b7 downloaded=1b1a2c01…60b7
  [PASS] archive root is an exact token: zip=pipelinek-0.61.0 expected=pipelinek-0.61.0
  [PASS] embedded Implementation-Version is an exact token:
         jar=pipeline-application-0.61.0.jar version=0.61.0 expected=0.61.0
exit=0
```

**P50/P95 de ejecución, con muestra suficiente** (`--rerun --require-work`, n=20, XML reescrito):

```text
BASELINE :pipeline-domain:test  runs=20  [--rerun-tasks: execution cost]
  p50=17.145s p95=18.131s mean=17.315s stdev=0.464s min=16.805s max=18.583s
  verdict=PASS work_executed=True
  xml: files=137 rewritten=137 tests=735 failures=0 errors=0 skipped=0 unreadable=0
```

Comparación honesta de las tres regímenes medidos en este host: **cálido sin trabajo ~2.1 s**
(§1; overhead), **ejecución en caliente ~17.1 s de mediana** (aquí, con n=20 y dispersión 0.46 s)
y **ejecución en frío 60.4 s** (§8, incluye compilación; n=3). El n=3 previo mostraba stdev 22 s
porque mezclaba una compilación con dos ejecuciones y contención: con n=20 el P95 ya es un dato
de cola y no un máximo con modales.

## 11. Prueba de aceptación: los comandos documentados, ejecutados como los usa un agente

No es inspección: son las recetas que aparecen en AGENTS.md / la escalera, corridas sobre diffs y
bytes reales en este worktree.

| Comando ejecutado | Comportamiento observado |
|---|---|
| `just changed HEAD~1` | plan advisory que nombra exactamente las dos suites afectadas |
| `just dev HEAD~1` | **exit 0** en 6 s: corre las suites afectadas, sin Gradle (no toca módulos) |
| `just integration HEAD~1` | **verdict=PASS** (exit 0, 7 s): fitness + suites del plan, `gradle NOT_RUN` |
| `just catalogue-check` | `OK-IDENTICAL (30 módulos, 6 gates, 24 suites)` |
| `just fitness` | rc 0: registro tool↔suite **y** catálogo |
| `just harness` | **24 suite(s), fail=0** |
| `just rc 45` | guard `allowed` → `releaseGate` arranca (`:buildSrc:*` ejecutándose) → muere por su propio presupuesto (exit 124) |
| `just rc budget=60` | **REFUSED** con mensaje accionable (`pásalo posicionalmente: just rc 300`), exit 2 |
| `just harness budget=600` | **REFUSED** igual (antes producía `timeout budget=600 ...`) |
| `just rc` con otro gate (ajeno) corriendo | **REFUSED** nombrando pid y cmdline del gate ajeno: `timeout 180 ./gradlew :testkit:conformance:test …` |

### Dos defectos reales que sólo aparecieron al ejecutar la ruta de aceptación

1. **`just rc force=1 budget=90` mal-asignó los parámetros**: `just` asigna por orden de
   declaración, así que quedó `budget="force=1"` y `force="budget=90"` → `timeout force=1
   v2/gradlew …` (exit 125) **y un bypass de concurrencia que nadie pidió**. Causa raíz: un
   parámetro con default vacío que acaba en la línea de comandos. Arreglado (parámetro eliminado;
   el bypass es la variable de entorno documentada) y mecanizado por **V10** (ninguna receta de
   gate declara parámetros con default vacío).
2. **`just harness budget=600` no validaba el presupuesto**: producía `timeout budget=600 …`. Lo
   detectó **V11** (toda receta que interpola el presupuesto debe validarlo numéricamente) en
   cuanto la regla existió, y ahora las seis recetas del catálogo lo validan y fallan cerrado con
   un mensaje que dice cómo se pasa.

Estos dos son exactamente el tipo de defecto que la inspección no ve: el primero sólo se manifiesta
con una invocación concreta y el segundo sólo cuando alguien escribe el argumento con `=`.

### Rutas de aceptación externamente bloqueadas (declaradas, no simuladas)

```text
- GitHub Actions: retirado por decisión registrada (754ddda0); no hay superficie de CI remota.
- Ejecución del harness externo y publicación del Release: requieren credenciales del operador.
- SAST / dependency scan: no existe superficie aprobada en este repositorio.
- PRODUCT-GATE: no puede declararse verde mientras los pines de certificación estén rojos (§5).
```
