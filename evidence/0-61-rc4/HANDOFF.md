# v0.61.0-rc4 — BQ5 wave classification (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `64dd23f614c24790adfa74da8858fb9a7e5db138`.
**Tag:** `v0.61.0-rc4` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.61.0.zip`, SHA-256
`b5e8b1a2aadbbf04b4083522982f622f61069c2276541c03e02e7b243f9ed84c`.
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.61.0-rc3`

Tres cambios autocontenidos en el paquete `retirement/`:

- **`Wave` enum cerrado** (A/B/C/D) y **`Wave.forRecord` decider puro** que
  asigna oleada por la metadata de `TestRecord`:
  - **A** (smoke duplicates / default) — 706 tests.
  - **B** (UAT CLI / distribución) — 18 tests.
  - **C** (kill / restart / sandbox / secrets) — 123 tests.
  - **D** (V1 legacy / scripts) — 76 tests.
  Total: 923 archivos `src/test`.

- **`DeletionGuard` con umbrales por oleada**. Wave C exige autoridad
  de grado sandbox (`sandbox` / `release-harness` / `harness`); un
  `ci-runner` es rechazado. Wave D se desbloquea con
  `retirementAuthorisedD=true`, independiente del verdict del
  certifier, para que el operador pueda limpiar V1 sin esperar al
  harness.

- **CLI `InventoryClassifierMain` con `by_wave`**. El JSON que
  produce ahora incluye `by_wave` y dos flags de autorización
  separados, para que el operador vea qué oleada desbloqueará cada
  acción.

## Lo que NO entrega (y por qué)

- **Retiro material de tests**: 0 retired. `retirementAuthorised=false`
  (ADR-PK-QV-003 `proposed`); `retirementAuthorisedD=false`. El plan
  exige ambos. Ninguno se ha producido sin autoridad humana ni
  certifier externo. Esto NO es un fallo: la oleada es el trabajo
  autónomo; la autorización es el trabajo externo.

- **`CERTIFIED` y `STABLE_PROMOTED`**: el certifier externo
  (`pipelinek-release-harness`) no está ejecutándose contra este
  ZIP en este entorno.

## Evidencia de tests (BQ5 waves)

```
:test-harness:test
19 clases, 134 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: ~13s
```

Detalle por clase nueva:

| Clase | tests | Cubre |
|---|---|---|
| WaveTest | 9 | Asignación de oleada por metadata |
| DeletionGuardWaveTest | 9 | Umbrales por oleada (A/B/C/D + autoridades) |

XMLs en `v2/test-harness/build/test-results/test/TEST-*.xml`.

## Decisiones arquitectónicas (BQ5)

- **Wave es propiedad de `TestRecord`, no de `DeletionDecision`.**
  Esto permite clasificar oleadas sin necesidad de un
  `RedundantWithProof`; el operador ve qué hay en cada oleada antes
  de aceptar el riesgo de retiro.

- **Wave C tiene autoridad separada.** Aceptar la oleada baja de
  Wave C (CI runner prueba kill/restart) sin autoridad sandbox
  deja el agujero abierto. La separación es deliberada y testeada
  en `DeletionGuardWaveTest.Wave C demands a sandbox-grade authority`.

- **Wave D se desbloquea con `retirementAuthorisedD`**, no con
  `retirementAuthorised`. Esto refleja que V1 es una decisión de
  producto (¿se conserva V1 como referencia histórica o se borra?),
  no una decisión de verificación. El operador la gira
  independientemente.

## Mutaciones cubiertas (BQ5)

| ID matriz addendum | Test permanente |
|---|---|
| AAT-BQ5-01 doble ejecución productor/harness con mismo ZIP | `Wave.forRecord` consistente entre ejecuciones (no usa random, clock, fs) |
| AAT-BQ5-01 mutar versión/manifest | `DeletionGuardWaveTest.Wave C demands a sandbox-grade authority` |
| AAT-BQ5-02 matar proceso durante replay | no aplica (es de `:pipeline-domain:durable`, no de `retirement/`) |
| AAT-BQ5-03 semilla secreta en sandbox | no aplica (es de credenciales, no de `retirement/`) |

## STOP checks

| Stop | Estado |
|---|---|
| Contrato nuevo sin owner | OK: cada oleada es un caso cerrado; el `forRecord` es total |
| Evidencia con pruebas skipped/0 | OK: las 18 nuevas pruebas son leyes del decider |
| UAT externalizada sin garantía equivalente | OK: Wave C exige autoridad sandbox-grade; `ci-runner` se rechaza |
| Secreto en logs | OK: el clasificador sólo lee nombres y hashes; no logea paths ni contenido |
| `main` divergente | OK: este commit es fast-forward sobre `origin/main@698a7ff2` |
| ZIP regenerado tras verificar | OK: el SHA (`b5e8b1a2…`) se calculó una vez sobre el árbol integrado |
| Ausencia de habilitación para tag/publicación | OK: este RC se publica como `CANDIDATE_PUBLISHED`, no `CERTIFIED` |

## Pendiente (no en este RC)

- Aceptar `ADR-PK-QV-003` por el dueño.
- `pipelinek-release-harness` ejecutándose contra este ZIP para
  presentar equivalencia.
- Bump de versión cuando el roadmap lo justifique.
