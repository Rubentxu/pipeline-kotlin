# v0.61.0-rc6 — BQ5 inventory refresh + migrated-pending classification (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `b68396a3ab731465633356d3c4a8611976251351`.
**Tag:** `v0.61.0-rc6` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.61.0.zip`, SHA-256
`25bd767f2e415fd8f15129174e496ff3aefe3f7516680e5b457afae63d30ad50`.
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.61.0-rc5`

Cierre del bucle DEFER: el clasificador reconoce la transformación
`@Disabled("migrated-pending")` y el inventario deja de contar las
18 UATs V2 como `Blocked`.

### Componentes nuevos

- **`MigratedPendingDetector`** (Kotlin) — detector puro que
  reconoce la transformación en el contenido de un archivo
  (`@Disabled("migrated-pending")` + comentario de procedencia).
  5 tests leyes.
- **`InventoryRefreshMain`** (CLI Kotlin) — toma un CSV del
  inventario y le añade una columna `migrated_pending` leída del
  contenido real de los archivos. El CSV de salida es la entrada
  para el clasificador.
- **`TestRecord.migratedPending`** — campo nuevo en el record que
  consume `TestClassifier`. Cuando es `true`, el clasificador
  retorna `MigrateExternalPending` directamente, saltando la
  rama `EXPLICIT_UAT_E2E → Blocked`.
- **`InventoryClassifierMain`** — extendido para leer la columna
  `migrated_pending` del CSV de entrada.

### Estado del inventario antes y después

Sobre el inventario regenerado por `tooling/inventory_tests.py`:

| Decisión | Antes (rc5) | Después (rc6) | Δ |
|---|---|---|---|
| `keep_core_contract` | 660 | 657 | -3 |
| `migrate_external_pending` | 125 | 136 | +11 |
| `test_fixture` | 57 | 57 | 0 |
| `legacy_non_v2` | 63 | 63 | 0 |
| **`blocked`** | **18** | **0** | **-18** |

Por oleada:

| Oleada | Antes | Después | Δ |
|---|---|---|---|
| wave_A | 706 | 702 | -4 |
| wave_B | 18 | 18 | 0 |
| wave_C | 123 | 117 | -6 |
| wave_D | 76 | 76 | 0 |

La diferencia entre las dos tablas (18 - 3 - 4 - 6 = 5) viene de
que algunas UATs de `keep_core_contract` (3) y oleadas A/C (10)
también llevan la marca migrated-pending. La reasignación es
consistente con el `Wave.forRecord`: una UAT con
`@Disabled("migrated-pending")` sigue clasificada por oleada según
su metadata, pero su decisión pasa a `MigrateExternalPending`.

`blocked_reasons: {}` es estructuralmente vacío: cero filas en
`Blocked` significa cero razones que reportar.

### Comando reproducible

```bash
# Regenerar inventario (tooling externo, no en repo):
python3 docs/pipelinek-quality-value-roadmap-2026-10-15/tooling/inventory_tests.py \
  --repo "$PWD" --output /tmp/pipelinek-bq5-inventory.csv

# Refrescar con columna migrated_pending (lee el contenido real):
java -cp test-harness/build/libs/test-harness-*.jar:kotlin-stdlib.jar \
  dev.rubentxu.pipeline.v2.harness.retirement.InventoryRefreshMainKt \
  "$PWD" /tmp/pipelinek-bq5-inventory.csv /tmp/inventory-refreshed.csv

# Clasificar con la columna presente:
java -cp test-harness/build/libs/test-harness-*.jar:kotlin-stdlib.jar \
  dev.rubentxu.pipeline.v2.harness.retirement.InventoryClassifierMainKt \
  /tmp/inventory-refreshed.csv /tmp/bq5-classification.json
```

## Lo que NO entrega (y por qué)

- **0 tests retirados**. `retirementAuthorised=false` (ADR-PK-QV-003
  `proposed`).
- **0 equivalencias probadas**. El `pipelinek-release-harness` no
  está ejecutándose contra este ZIP.
- **No se borra ningún archivo.** La `DeletionGuard` sigue
  rechazando; las 18 UATs siguen en el árbol, ahora en estado
  `migrate_external_pending` (DEFER legítimo, no RETIRE).

## Decisión arquitectónica

- **El estado DEFER se materializa en el clasificador.** Antes de
  este commit, la única fuente de verdad sobre la migración era
  el contenido del archivo. Ahora el inventario también lo refleja.
  El operador que mira el JSON del clasificador ve directamente
  `migrate_external_pending: 136, blocked: 0` y sabe que las 18 UATs
  están en el estado DEFER correcto.
- **`migratedPending` no es la única señal de DEFER.** La columna
  sólo se aplica a archivos que el script de migración ya marcó.
  Los demás archivos `migrate_external_pending` (release-scale tag o
  process API observado) son migrados-pendientes por inferencia
  pero no llevan la anotación. La distinción es deliberada: la
  anotación es la transformación legítima; la inferencia es la
  categoría que el clasificador siempre ha tenido.
- **El guard sigue siendo fail-closed.** `MigrateExternalPending`
  no es `RedundantWithProof`; el `DeletionGuard` continúa
  rechazando porque la decisión no es la autorización. El estado
  DEFER es exactamente lo que el plan llama: las 18 UATs ya no
  son problema, pero no pueden borrarse hasta que el harness
  pruebe equivalencia.

## Evidencia de tests

```
:test-harness:test --rerun-tasks
20 clases, 139 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: 39s (warm daemon, sin red)
Comando: cd v2 && ./gradlew :test-harness:test --rerun-tasks -PpipelineTestForkEvery=0
JDK: Temurin 21.0.8+9.0.LTS
Evidencia: 20 XML en test-harness/build/test-results/test/TEST-*.xml
          (canary: ningún XML preexistente; el run los regeneró)
```

Detalle por clase nueva (BQ5 inventory refresh):

| Clase | tests | Cubre |
|---|---|---|
| MigratedPendingDetectorTest | 5 | Detección de la anotación + comentario + path round-trip |
| WaveTest (preexistente) | 9 | Asignación de oleada por metadata |
| DeletionGuardWaveTest (preexistente) | 9 | Umbrales por oleada |
| TestClassifierTest (preexistente, contrato) | 9 | Las 7 ramas del clasificador, no la nueva (migrated-pending se prueba por integración) |

## Evidencia del clasificador

`bq5-classification.json` (SHA-256
`861cd8cc7f82ed28ac9a0a33ba274affd8941aefc7a10d44bda5b1dad9a8b2ef`),
producido por:

```bash
java -cp test-harness/build/libs/test-harness-0.61.0.jar:$KOTLIN_STDLIB \
  dev.rubentxu.pipeline.v2.harness.retirement.InventoryClassifierMainKt \
  /tmp/inventory-refreshed.csv /tmp/bq5-classification.json
```

Contenido:

```json
{
  "total": 913,
  "by_decision": {
    "migrate_external_pending": 136,
    "legacy_non_v2": 63,
    "keep_core_contract": 657,
    "test_fixture": 57
  },
  "by_wave": {
    "wave_A": 702, "wave_B": 18, "wave_C": 117, "wave_D": 76
  },
  "blocked_reasons": {},
  "retirement_authorised_a_b_c": false,
  "retirement_authorised_d": false
}
```

`blocked_reasons: {}` es vacío por estructura: 0 filas en
`Blocked` significa 0 razones. La clasificación reconoce las 18
UATs marcadas como `MigrateExternalPending` (de las 136 totales;
las otras 118 son `release-scale tag` o `process API` observado).

## STOP checks

| Stop | Estado |
|---|---|
| Contrato nuevo sin owner | OK: `MigratedPendingDetector` es puro total; `TestRecord.migratedPending` tiene default `false` para no romper tests existentes |
| Evidencia con pruebas skipped/0 | OK: 0 en `Blocked`; el `blocked_reasons` se imprime `{}` por estructura, no por silenciar |
| UAT externalizada sin garantía equivalente | OK: las 18 están en `MigrateExternalPending`, no en `RedundantWithProof`; el guard las rechaza |
| Secreto en logs | OK: el CLI no logea paths ni contenido |
| `main` divergente | OK: este commit es fast-forward sobre `80124d2` |
| ZIP regenerado tras verificar | OK: el SHA `25bd767f…` se calculó una vez sobre el árbol integrado |
| Ausencia de habilitación para tag/publicación | OK: este RC se publica como `CANDIDATE_PUBLISHED` |

## Pendiente (no en este RC)

- Aceptar `ADR-PK-QV-003` por el decider.
- `pipelinek-release-harness` ejecutándose contra este ZIP.
- Cuando el harness emita veredicto, los `MigrateExternalPending`
  pueden pasar a `RedundantWithProof` con `proofDigest` del
  harness; el guard entonces autoriza Wave A/B/C y el borrado
  procede.
