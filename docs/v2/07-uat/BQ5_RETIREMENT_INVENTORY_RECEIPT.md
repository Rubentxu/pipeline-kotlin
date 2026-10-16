# BQ5 — Retirada heredada por equivalencia externa (closure receipt)

**Fecha de cierre:** 2026-10-16.
**Rama:** `feat/bq1-impact-evidence` desde `origin/main@52b9c4915c8a2403750c62deed4abf5afe2f8323`.
**Estado:** BQ5 IMPLEMENTED a nivel de clasificador puro + guard + tarea Gradle.
**Retirada material de tests: `BLOCKED_EXTERNAL`** — el equivalente externo
no existe (no hay `pipelinek-release-harness` ejecutándose contra esta
candidata) y `ADR-PK-QV-003` sigue `proposed`, no `accepted`.

## Inventario real sobre `origin/main@52b9c491`

Ejecutado con `python3 ../../docs/pipelinek-quality-value-roadmap-2026-10-15/tooling/inventory_tests.py --repo $PWD --output /tmp/pipelinek-bq5-inventory.csv`:

```
INVENTORY_WRITTEN files=913 path=/tmp/pipelinek-bq5-inventory.csv
```

913 archivos `src/test` en el árbol limpio a 52b9c491. La baseline del paquete
de 920 ± 7 cuadra; las diferencias son archivos nuevos o borrados entre el
corte del paquete y este SHA.

## Clasificación del clasificador (Kotlin, en este commit)

`./gradlew :test-harness:classifyInventory -Ppk.inventoryCsv=$PWD/tmp/pipelinek-bq5-inventory.csv`

```json
{
  "total": 913,
  "by_decision": {
    "migrate_external_pending": 118,
    "blocked": 18,
    "legacy_non_v2": 63,
    "keep_core_contract": 657,
    "test_fixture": 57
  },
  "blocked_reasons": {
    "explicit UAT/E2E name without release-scale tag or process API; needs harness verdict": 18
  },
  "retirement_authorised": false
}
```

### Lectura de la distribución

| Decisión | Cuenta | % | Destino |
|---|---|---|---|
| `keep_core_contract` | 657 | 72% | se queda en el repo |
| `migrate_external_pending` | 118 | 13% | candidato a `pipelinek-release-harness` |
| `legacy_non_v2` | 63 | 7% | V1 (no parte del contrato V2) |
| `test_fixture` | 57 | 6% | clases de soporte/abstract |
| `blocked` | 18 | 2% | UAT/E2E con nombre explícito pero sin tag/proceso; requiere veredicto del harness |
| `redundant_with_proof` | 0 | 0% | nadie ha presentado equivalencia |
| `temporary_cycle` | 0 | 0% | este BQ no creó probes |

### Por qué 0 retirados

`DeletionGuard.check(decision, record, retirementAuthorised=false)` **rechaza
siempre** mientras `ADR-PK-QV-003` siga en `proposed`. El guard exige:

- `decision is DeletionDecision.RedundantWithProof` (no se alcanzó para
  ningún test de los 913),
- `proofDigest` de 64 chars hex (no se aportó),
- `authority` no blank (no hay harness corriendo),
- `retirementAuthorised=true` (sólo cuando el ADR esté `accepted`).

Aún con todo lo anterior cumplido, el `retirement_guard.py` exige un
`cycle_id` en una ruta fuera de Git y un `manifest.json` con `probes[]`
que apunte a SHA + `replacement_evidence`. Nada de eso se ha presentado;
sin él, el guard rechaza con `RETIREMENT_REFUSED` (`tooling/test_tools.py`
lo prueba con 9 tests).

## Lo entregado en BQ5 (en este commit)

- **`DeletionDecision`** ADT cerrado de 7 casos (BQ5 TODO §1 + §5):
  `KeepCoreContract`, `TemporaryCycle`, `MigrateExternalPending`,
  `RedundantWithProof(proofDigest, authority)`, `TestFixture`,
  `LegacyNonV2`, `Blocked(reason)`. Añadir uno requiere un ADR.
- **`TestClassifier`** puro: una `TestRecord` (path + heurísticas) →
  `DeletionDecision`. Sin I/O; el caller compone la `TestRecord` desde el
  CSV. Conservador: nunca dice `DELETE_READY`; sólo la guardia
  `RedundantWithProof` puede promover.
- **`DeletionGuard`** puro: exige prueba + autoridad + autorización ADR.
  `retirementAuthorised=false` por defecto (el ADR sigue `proposed`).
- **CLI `InventoryClassifierMain`**: corre el clasificador sobre el CSV
  del paquete; emite un JSON con totales y razones de bloqueo. NO
  borra nada.
- **Tarea Gradle `:test-harness:classifyInventory`** con `-Ppk.inventoryCsv=...`
  para integrar el paso en pipelines.
- **Tests**: 14 nuevos en `TestClassifierTest` (9) + `DeletionGuardTest`
  (5). Cubren los 7 caminos del clasificador y los 5 caminos de la
  guardia (no autorizado / decisión incorrecta / proofDigest mal
  formado / proofDigest no coincide con content / autorizado).

## Mapeo BQ5 → leyes

| TODO addendum | Implementación |
|---|---|
| Consumir el inventario de PR #140 sin asumir mergeado; regenerar completo y fusionar | el script `inventory_tests.py` del paquete genera el CSV completo; PR #140 NO está mergeada (ver `git log --all`), por lo que este commit se basa en el CSV regenerado, no en la PR |
| Clasificar cada test: KEEP_CORE_CONTRACT / TEMPORARY_CYCLE / MIGRATE_EXTERNAL_PENDING / REDUNDANT_WITH_PROOF / TEST_FIXTURE / LEGACY_NON_V2 / BLOCKED | las 7 decisiones se implementan; el clasificador conservador produce 657/118/63/57/18 sobre los 913 |
| Por oleadas: (a) duplicados, (b) UAT de CLI/distribución, (c) kill/restart/sandbox/secrets, (d) scripts V1 | el clasificador no es "oleada-aware"; un orquestador de oleadas es trabajo posterior, no exigido por el addendum |
| `@Disabled("migrated...")` y NO borrar hasta aprobar ADR-PK-QV-003 | el `DeletionGuard` rechaza mientras `retirementAuthorised=false`; ningún test se borra |
| Comparar tiempos de PR, RC y defectos | sin RC material en este commit (las RC requieren BQ4 instalado contra ZIP); comparación se difiere a BQ4 + RC |

## Probes temporales

BQ5 no creó probes UAT/AAT. El CSV del paquete (913 filas) es el input
del clasificador; el JSON que emite el CLI es la evidencia. No se borra
nada en `src/test`. La matriz `BQ_ACCEPTANCE_MATRIX.md` para BQ5
incluye `UAT-BQ5-01 doble ejecución productor/harness con mismo ZIP` —
esa fila queda `BLOCKED_EXTERNAL` por la misma razón: el harness no
está corriendo.

## Evidencia de tests

```
:test-harness:test
13 clases, 67 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~6s
```

Detalle por clase (BQ5-specific):

| Clase | tests | skipped | failures | errors |
|---|---|---|---|---|
| TestClassifierTest | 9 | 0 | 0 | 0 |
| DeletionGuardTest | 5 | 0 | 0 | 0 |

## Pendiente (no BQ5)

- Aceptación formal de `ADR-PK-QV-003` por el dueño. Mientras siga
  `proposed`, `DeletionGuard.check(... retirementAuthorised=true)` no se
  invoca. Eso NO se falsifica.
- Equivalencia externa (correr el mismo caso contra el ZIP desde el
  harness). No hay harness ejecutándose en este entorno; por lo tanto
  `redundant_with_proof` queda en 0.
- BQ4 + RC instalada contra un ZIP canario: cuando exista, el ciclo
  `Cycle.decide` (BQ3+BQ4) y el `retirement_guard.py` podrán retirar
  probes temporales; este clasificador decidirá qué tests pasan al
  harness y cuáles se borran.

## STOP checks

| Stop | Estado |
|---|---|
| Equivalencia no demostrada | OK: 0 `redundant_with_proof`; 0 borrados |
| Secreto en logs | OK: el clasificador sólo lee nombres de archivo y hashes; no hay logging de paths ni de contenido |
| `main` divergente | OK: 4 commits propios sobre `origin/main@52b9c491`; merge por PR, sin force-push |
| ZIP regenerado tras verificar | N/A: no se publica RC en BQ5 (la RC requiere BQ4 contra ZIP canario) |
| Ausencia de habilitación para tag/publicación | OK: `CANDIDATE_PUBLISHED` no se declara en este commit |
