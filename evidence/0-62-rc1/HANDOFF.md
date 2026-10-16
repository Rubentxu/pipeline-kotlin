# v0.62.0-rc1 — Apertura del tren 0.62.0 + ADRs aceptados + harness-verdict hook (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `8c2752757edd9b9bd1175389084899f00bfe9d75`.
**Tag:** `v0.62.0-rc1` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.62.0.zip`, SHA-256
`dd967b6b9776d45e32a48dfbc6d8755b0e172ad6cacf415b00b326eab1c9806c`.
**ProductVersion:** `0.62.0` (binario `pipeline 0.62.0`).
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC

Apertura del tren `0.62.0` tras el cierre del `0.61.0` en
`v0.61.0-rc6`. El primer candidato del nuevo tren incluye los
ADRs aceptados y el primer hook product-side del camino
`MigrateExternalPending → DeleteReady(proof)`.

### Cambio de tren (0.61.0 → 0.62.0)

`v2/build.gradle.kts` `version = "0.62.0"`. Por la
**PRODUCT IDENTITY LAW** (AGENTS.md), las cinco superficies
deben coincidir:

| Superficie | Valor |
|---|---|
| Asset name | `pipelinek-0.62.0.zip` |
| Archive root | `pipelinek-0.62.0/` |
| Application manifest | `pipeline-application-0.62.0.jar` |
| `pipelinek version` | `pipeline 0.62.0` |
| Distribution manifest | `ProductVersion = 0.62.0` |

Las cinco coinciden. Comparación por token exacto, nunca
substring (`0.62.0` no está contenido en `0.62.0-rc1`).

### ADRs aceptados

`ADR-PK-QV-001..004` aceptados por el decider (Rubentxu) el
2026-10-16:

- `ADR-PK-QV-001` — núcleo funcional / efectos.
- `ADR-PK-QV-002` — impacto / evidencia.
- `ADR-PK-QV-003` — retirada por equivalencia (con la
  enmienda a `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4.1).
- `ADR-PK-QV-004` — release tras limpieza.

### Enmienda a `RESPONSIBILITY_MIGRATION_ROADMAP.md` §4.1

`§4.1` añade la condición que `ADR-PK-QV-003` exige: una UAT
`MigrateExternalPending` puede pasar a `DeleteReady(proof)`
**únicamente** cuando el harness ha firmado un veredicto
`VERIFIED` sobre la misma candidata con `proofDigest` y
`failureFingerprint` exactos. La firma la exige `DeletionGuard`;
mientras `retirementAuthorised=false` o el `proofDigest` no
llegue, el guard sigue rechazando.

### Componentes nuevos (BQ5 retirement producer hook)

Cinco archivos en `v2/test-harness/src/main/kotlin/dev/rubentxu/pipeline/v2/harness/retirement/`:

- **`HarnessVerdict`** (sealed ADT) — `Verified` / `Blocked` /
  `Pending`, con `path: String` en la interfaz, `contentSha256`,
  `proofDigest`, `authority`, `candidateSha256` y
  `failureFingerprint: FailureFingerprint`. `FailureFingerprint`
  exige SHA-256 hex de 64 chars en `inputDigest` y no-blank en
  `symptom` / `type` / `cause`.
- **`HarnessVerdictApplier`** (función pura total) — toma
  `decision`, `record`, `verdict`, `lastFailureFingerprint`,
  `candidateSha256`. Devuelve `VerdictApplication`:
  - `Promoted(proofDigest)` cuando todo coincide;
  - `Refused.PathMismatch` si path o candidateSha256 no coinciden;
  - `Refused.ShaMismatch` si contentSha256 o proofDigest no son 64-char hex;
  - `Refused.FingerprintMissing` si no hay fingerprint del producto
    o no coincide con el del harness;
  - `Refused.AlreadyDecided` si la decisión ya no es
    `MigrateExternalPending` (idempotente);
  - `NotApplicable` para `Pending` y `Blocked`.
- **`HarnessVerdictReader`** (adapter) — JSON plano con un objeto
  anidado (`fingerprint`). Devuelve `ParseOutcome` tipado
  (`Parsed` / `Missing` / `Malformed` / `UnknownStatus`).
  Parser a mano para no añadir una dep: el formato del harness
  está congelado y la política fail-closed exige un parser
  auditable.

### Test count (post-rc6)

```
:test-harness:test --rerun-tasks
22 clases, 161 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: 18s
JDK: Temurin 21.0.8+9.0.LTS
```

Detalle de lo nuevo:

| Clase | tests | Cubre |
|---|---|---|
| HarnessVerdictApplierTest | 11 | Pending/Blocked → NotApplicable; Verified matching → Promoted; path/content/candidate/fingerprint mismatches → typed Refusal; idempotencia |
| HarnessVerdictReaderTest | 11 | verified/blocked/pending parses; unknown status → UnknownStatus; missing/malformed → typed Malformed; read() round-trips a real file |

## Estado del inventario (sin cambios desde rc6)

Sobre el inventario regenerado por `tooling/inventory_tests.py`:

| Decisión | rc6 | 0.62.0-rc1 | Δ |
|---|---|---|---|
| `keep_core_contract` | 657 | 657 | 0 |
| `migrate_external_pending` | 136 | 136 | 0 |
| `test_fixture` | 57 | 57 | 0 |
| `legacy_non_v2` | 63 | 63 | 0 |
| **`blocked`** | **0** | **0** | **0** |

`bq5-classification.json` SHA-256
`861cd8cc7f82ed28ac9a0a33ba274affd8941aefc7a10d44bda5b1dad9a8b2ef`
(la nueva lógica no cambió la clasificación; la transformación
`@Disabled("migrated-pending")` ya estaba integrada en el
clasificador desde `b68396a3`).

## Lo que NO entrega (y por qué)

- **0 tests retirados.** El `proofDigest` del harness no existe
  todavía para ninguna UAT; el `DeletionGuard` sigue
  rechazando. Esto es deliberado, no regresión.
- **0 equivalencias probadas.** El `pipelinek-release-harness`
  no se está ejecutando en este entorno.
- **0 nuevas clasificaciones.** El inventario y la
  transformación son los mismos; este RC es código + ADRs +
  hook + nuevo tren, no transformación.

## Decisión arquitectónica

- **El productor está listo pero no activado.** El camino
  `MigrateExternalPending → RedundantWithProof → Authorised`
  requiere:
  1. `retirementAuthorised = true` en el guard (no se ha
     hecho; queda a la decisión del decider cuando el harness
     emita los primeros `proofDigest` firmados);
  2. una llamada al reader por cada UAT en
     `MigrateExternalPending`;
  3. una llamada al apper que devuelva `Promoted(proofDigest)`;
  4. una transición de la decisión a `RedundantWithProof` con
     `proofDigest` y `authority = "pipelinek-release-harness"`.

  Esto NO se automatiza en este RC: la transición se hace a
  mano cuando el harness emita el primer veredicto, porque la
  firma del `proofDigest` debe ser cotejada por el decider
  con el recibo del harness. **El código está; el interruptor
  está apagado.**

- **El parser no es JSON genérico.** Es un parser de un
  subconjunto plano + un objeto anidado. La justificación es
  fail-closed: cualquier desviación del formato congelado del
  harness se reporta como `Malformed` con razón tipada, no como
  excepción o parse silencioso. Si el formato crece, se sustituye
  por `kotlinx-serialization` con schema pinned.

## STOP checks

| Stop | Estado |
|---|---|
| ADRs auto-aceptados | OK: aceptados por el decider (Rubentxu) el 2026-10-16; sin auto-aprobación |
| Decisión que cambia la frontera sin evidencia | OK: el apper es fail-closed por fingerprint; ningún `Promoted` posible sin 4 matches exactos |
| Borrado implícito por nombre o por edad | OK: solo el apper con `Promoted` puede promover; el guard sigue rechazando |
| PR/issue de harness fabricada | OK: ningún issue number fabricado; las UATs `@Disabled("migrated-pending")` siguen apuntando al doc `RESPONSIBILITY_MIGRATION_ROADMAP.md` |
| ZIP regenerado tras verificar | OK: el SHA `dd967b6b…` se calculó una vez sobre el árbol integrado |
| `pipelinek version` 0.61.0 vs AssetVersion 0.62.0 | OK: el binario corre `pipeline 0.62.0` |
| Tag/publicación sin gate | OK: 161/0/0/0 en `:test-harness:test` antes de tag |

## Pendiente (no en este RC)

- `pipelinek-release-harness` ejecutándose contra el ZIP
  `dd967b6b…` y emitiendo veredictos `VERIFIED` con
  `proofDigest` por UAT.
- Cuando lleguen los primeros veredictos:
  1. leerlos con `HarnessVerdictReader`;
  2. aplicarlos con `HarnessVerdictApplier`;
  3. **el decider decide** si `retirementAuthorised = true`;
  4. una sola PR atómica borra las UATs promovidas y sus
     fixtures exclusivas, con `Refs: pipelinek-release-harness#N`
     en el mensaje.
- La `v0.62.0-rc2` se publicará cuando el primer lote de
  veredictos llegue y la promoción de Wave A esté verde.
