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
| Integridad/inventario/descarga como consumidor, sin reconstruir | **parcial** | `install-pipelinek.sh` (digest + identidad exacta, transaccional) y `consult-harness-verdict.py` (verifica el ZIP contra GitHub Releases bajo una autoridad de publisher comprometida). La descarga+inventario del asset publicado se ejecuta en el camino de instalación, no hay aún un verificador de release independiente |

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
