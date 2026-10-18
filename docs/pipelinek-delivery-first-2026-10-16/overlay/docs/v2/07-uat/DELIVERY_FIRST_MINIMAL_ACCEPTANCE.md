# UAT / E2E / AAT **mínimos y efímeros** por hito

**Normativa superior:** `BQ_ACCEPTANCE_MATRIX.md`, `PRODUCTION_READY_UAT_MATRIX.md`, `CERTIFICATION_PROTOCOL.md`, `EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`, `TEST_SANDBOX_PROFILES.md`, `ADR-0071`, `ADR-0072`, `ADR-PK-QV-003`, `ADR-PK-QV-004`, `ADR-0105` y `RESPONSIBILITY_MIGRATION_ROADMAP.md`. Esta tabla **NO reduce** gates obligatorios; únicamente define los experimentos nuevos que crea este ciclo. Reusar pruebas existentes con oráculo equivalente antes de crear un nuevo archivo.

## Matriz de **máximo** 2 probes efímeros por hito (no se ejecutan todos en cada commit)

| Hito | Experimento nuevo efímero | Nivel mínimo fiel | Oráculo discriminante / negativo | Ley mínima que SÍ permanece |
|---|---|---|---|---|
| **H0** | **AAT-H0:** un lote parametrizado que intenta `BlockedExternal` con exit 0, missing evidence, sustituto inexistente, ID `../`, symlink ajeno y cleanup interrumpido. **UAT-H0:** invocar `:test-harness:cycle` real con input normal + anormal. | HF0 decisión + HF1 CLI/herramienta; no afirmar HF2 | No se promueve ni borra ante bloqueo; CLI y Gradle devuelven no cero en error; archivo ajeno intacto | `CycleDecisionLawTest` + `CycleOwnedDeletionTest` o equivalentes existentes, sin duplicar suite |
| **H1** | **AAT-H1:** tabla XML 0/all-skipped/otro SHA + cambio `scripts/release` o `v2/test-harness` + archivo desconocido. **UAT-H1:** checkout limpio de desarrollo modifica dominio y lanza fast/installed por separado. | HF0 selector / HF1 Gradle/JUnit real; instalado HF2 cuando se requiera | No hay PASS vacío ni escape por metadatos; fast no instala ni requiere plugin previo; installed `executed>0` y test apropiado | `ImpactResolverAdversarialTest` y test de configuración/dry-run, añadir solo nuevos casos faltantes |
| **H2** | **E2E-H2:** consumidor instala *un solo* ZIP descargado de RC y ejecuta `version --json` + pipeline básico. **AAT-H2:** falta/extra/duplicado de asset o ZIP SDK corrupto. UAT consumidor Maven remoto se ejecuta solo cuando haya destino autorizado; puede sustituir AAT si cubre riesgo principal. | HF2 ZIP; HF externo requerido por certifier cuando aplique | Asset-set exacto, SHA y ejecución desde mismos bytes; SDK remoto compila sin depender del productor; `BLOCKED` si no hay repo | Ley BOM 5/5, inventario exacto y prueba de extracción `.m2` ya existente |
| **H3** | **UAT-H3:** pipeline real con stdout/stderr, frames paginados y follow en la ruta existente `--frames`. **AAT-H3:** mutar ordinal/cursor ajeno o frame intermedio (escoger 1 que mate el defecto observado). | HF1/2 según reader/CLI realmente usado | Cada frame una sola vez, orden observacional real, límites/cursor coherentes; no fingir orden del modo legado | Ley pura de read decision y 1 regresión de observabilidad al nivel mínimo fiable |
| **H4** | **UAT-H4:** comparación del mismo ZIP/defecto antes/después de mover UNA familia de UAT heredadas al certifier. **AAT-H4:** reintroducir fingerprint del defecto para confirmar que el sustituto falla. | HF según garantía, incluso HF3–HF6 si replay/seguridad | `CandidateId`, `inputDigest`, `failureKind`, `symptom`, `causalBoundary` iguales y verdict externo VERIFIED. Si no, NO borrar | conservar tests core/fitness, y un canario de consumidor por frontera crítica si sigue siendo necesario |

**Economía:** un experimento parametrizado con varios casos negativos cuenta como una familia, no como diez harnesses permanentes. El E2E pesado vive solo en RC/certifier; evitar E2E de instalación por cada función `echo`, `sh`, `retry` ya respaldada por contratos. No añadir un AAT automático global si no existe riesgo nuevo. `Uat...`, `E2e...`, `Aat...` antiguos NO son, por su nombre, pruebas del ciclo ni borrables.

## Router de `delivery-first-testing` — instrucciones ejecutables, no fork de la skill

**Ubicación vigente observada:** `Rubentxu/agent-skill/skills/delivery-first-testing/SKILL.md`; `references/lens-registry.json`; `references/navigation.md`; `scripts/affordances.py`.

1. **Al abrir feature/H0…H3:** descubrir skill instalada en entorno agente. Si no lo está, enlazar repo y aplicar procedimientos manuales sin afirmar que se ha ejecutado el router. Informar `{intent: feature, changed: [harness/ci/release/domain], risk: high|critical, basis: current, impact: known|unknown, affected: not_run}` y consultar las lenses `change-impact`, `failure-triage` solo si hay rojo, `ephemeral-uat`, `regression-promotion`.
2. **Antes del merge:** `merge-assurance`. Debe enlazar los comandos de Gradle/Git realmente observados y nunca marcar PASS porque se seleccionó `run-merge`.
3. **Tras integración:** `integration-assurance` usando el pipeline/gates existentes. `harness-economics` se aplica a auditoría del coste BQ2/BQ5, no crea otra autoridad.
4. **Al publicar:** `release-assurance`. Input incluye SHA final, `CandidateId`, artefacto disponible, checks reales y lista remota observada; sin artefacto o certifier, `BLOCKED/NOT_RUN`.
5. **Al cerrar:** `regression-promotion` decide **PROMOTE/REPLACE/RETIRE/DEFER** por garantía (no por filename). `planned` lenses (`mutation-guidance`, `cross-repo-consumer`, `historical-test-impact`) no deben ejecutarse como si existiesen.

Estado usado por navegación: `NOT_RUN | PASSED | FAILED | BLOCKED | STALE` con mismo ámbito/revisión; la herramienta de affordances NO ejecuta ni certifica. Nunca mantener una prueba que depende de snapshots privados o tiempos fijos cuando su garantía se protege más barato con un ADT puro.

## Modelo mínimo de recibo de ciclo (fuera de repositorio)

```json
{
  "schema": "delivery-first-cycle-v1",
  "cycle_id": "H1-<id-unico>",
  "repo_commit": "<SHA-real>",
  "candidate_sha256": null,
  "scenario_id": "AAT-H1",
  "guarantee": "Impact.UnknownProductionRefused",
  "observed_hf": "HF1",
  "argv": ["<comando-real>"],
  "exit_code": null,
  "executed": null,
  "skipped": null,
  "verdict": "NOT_RUN",
  "oracle": "<resultado-observable>",
  "decision": "DEFER",
  "replacement_ref": null,
  "evidence_digest": null,
  "cleanup_paths": []
}
```

Los placeholders nunca son evidencia. `null`, `NOT_RUN` o un script no ejecutado no pueden convertirse en PASS; el agente debe escribir los valores realmente observados, con integridad verificada, y mantener evidencia en almacenamiento autorizado. No subir credenciales, secretos, XML masivos o zip de pruebas al repo o GitHub Release.

## Ritual de creación y **borrado al final de CADA hito**

0. Durante desarrollo, usar `mktemp -d` fuera del checkout (o el root externo del ciclo) y `trap` seguro; archivos creados por el ciclo explícitos en manifiesto. Nunca crear scripts permanentes salvo que una garantía requiera regresión mínima.
1. Ejecutar contra implementación/artefacto **actual**, recabar código de salida, oráculo, negative pair y digest de evidencia. Si el test fracasa, antes clasificar primera causa con lente `failure-triage`, no subir timeout ni `@Disabled` automáticamente.
2. Identificar el mínimo test permanente que mata el defecto: contrato puro o componente HF1; si solo el binario puede demostrarlo, canario HF2 en certifier. `PROMOTE`/`REPLACE` exige ejecutar sustituto rojo ante mutación y verde después; si `DEFER`, no borrar garantía crítica.
3. **Borrar al cierre** solo `cycle_id` → `cleanup_paths` listados y verificados; no hacer `rm -rf *uat*` ni borrar heredados por coincidencia textual. No tocar datos fuera de ownership, links o paths transversales. Verificar con `git status --short`, `git ls-files` y escáner de pruebas temporales del manifiesto.
4. Correr gates sobre SHA **después del borrado** (tests de código efímero ya no existen). Guardar resumen externo: pruebas ejecutadas, cobertura, fallos, reemplazo, cero efímeras persistentes y ruta evidencia.
5. Mergear a `main`, crear nueva RC conforme **política vigente**, verificar asset-set exacto y consumidor. El test temporal no se incluye en el tag/release.

### Prohibiciones explícitas

- No borrar tests de credenciales, protección de secretos, recovery, concurrency, filesystem integrity, pureza, replay, preservación de bytes, ABI o checksum sin equivalencia demostrada del MISMO fallo.
- No convertir `assumeTrue`, `@Disabled`, runner que no llega al assert, `tests=0`, caracteres redactados o ausencia de logs en PASS.
- No exigir `--rerun-tasks --no-build-cache` universal; sí cuando hay que comprobar reproducibilidad real y se explica el coste.
- No borrar recibo/evidencia de failure hasta adjudicación y storage seguro. No convertir un E2E temporal fallido en deuda perpetua en `src/test`.
