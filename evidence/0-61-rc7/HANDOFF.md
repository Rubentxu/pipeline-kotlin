# v0.61.0-rc7 — ADRs accepted + harness-verdict producer hook (HANDOFF-TO-CERTIFIER)

**Fecha:** 2026-10-16.
**SHA:** `ce4b178a94b9eab4e0378ace7b98f593234195ac`.
**Tag:** `v0.61.0-rc7` (inmutable, alcanzable desde `main`).
**Producto publicado:** `pipelinek-0.61.0.zip`, SHA-256
`9328b56642d0442b04b7e419ab9d3eafe32de99da3578806bd475438dffd4a3d`.
**Estado de certificación:** `CANDIDATE_PUBLISHED`, pendiente del
veredicto del certifier externo.

## Lo que entrega este RC sobre `0.61.0-rc6`

Aceptación formal de los cuatro ADRs que cierran los BQ del
roadmap de calidad, y la primera implementación product-side
del hook que el certifier externo (harness) necesita para
retirar UATs de este repo.

### ADRs aceptados (decider approval 2026-10-16)

- **`ADR-PK-QV-001`** (núcleo funcional / efectos): ya estaba
  implementado en la base H0; el ADR formaliza la regla.
- **`ADR-PK-QV-002`** (impacto / evidencia): el `ImpactPlan` /
  `EvidenceVerdict` ya están en `:test-harness`.
- **`ADR-PK-QV-003`** (retirada por equivalencia): introduce el
  camino `MigrateExternalPending → RedundantWithProof`; el
  *único* cambio que el agente puede ejecutar por sí mismo es
  el **productor de ingest** (este RC).
- **`ADR-PK-QV-004`** (release tras limpieza): este RC es la
  primera prerelease con ADRs aceptados y gate de pruebas
  fresco.

### Enmienda a `RESPONSIBILITY_MIGRATION_ROADMAP.md`

`§4.1` añade la condición que `ADR-PK-QV-003` exige
explícitamente: una UAT `MigrateExternalPending` puede pasar a
`DeleteReady(proof)` **únicamente** cuando el `harness` ha
firmado un veredicto `VERIFIED` sobre la misma candidata con
`proofDigest` y `failureFingerprint` exactos. La firma es
exigida por `DeletionGuard`; mientras `retirementAuthorised=false`
o el `proofDigest` no llegue, el guard sigue rechazando y los
`@Disabled("migrated-pending")` permanecen en el árbol como
estado DEFER legítimo.

`§9.1` actualiza el estado: 18/46 UATs marcadas, 0/46 retiradas,
ADRs aceptados, rc6 publicado, **rc7 pendiente** del veredicto
del harness.

`§10` aclara que borrar un `@Disabled("migrated-pending")` sin
`proofDigest` del harness sigue prohibido.

### Componentes nuevos (BQ5 retirement producer hook)

- **`HarnessVerdict`** (sealed ADT) — `Verified` / `Blocked` /
  `Pending`, con un campo `path` en la interfaz (todas las
  variantes lo exponen) y `FailureFingerprint` como dato
  cerrado. Hex SHA-256 validado en construcción; `symptom` /
  `type` / `cause` no blank.
- **`HarnessVerdictApplier`** (función pura total) — toma la
  decisión actual, el `TestRecord`, el veredicto, el fingerprint
  del último RED del producto, y el `candidateSha256` que se
  quiere liberar. Devuelve `VerdictApplication`:
  - `Promoted(proofDigest)` cuando todo coincide;
  - `Refused.PathMismatch` si el path o el `candidateSha256`
    no coinciden;
  - `Refused.ShaMismatch` si `contentSha256` o `proofDigest`
    no son 64-char hex;
  - `Refused.FingerprintMissing` si no hay fingerprint del
    producto o no coincide con el del harness;
  - `Refused.AlreadyDecided` si la decisión ya no es
    `MigrateExternalPending` (idempotente, no re-promueve);
  - `NotApplicable` para `Pending` y `Blocked`.
- **`HarnessVerdictReader`** (adapter) — JSON plano con un
  solo objeto anidado (`fingerprint`). Devuelve `ParseOutcome`
  tipado (`Parsed` / `Missing` / `Malformed` / `UnknownStatus`).
  Parser a mano para no añadir una dep: el formato del harness
  está congelado y la política fail-closed exige un parser
  auditable.

### Test count

```
:test-harness:test --rerun-tasks (post-rc6)
22 clases, 161 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: 12s (warm daemon)
JDK: Temurin 21.0.8+9.0.LTS
```

Detalle de lo nuevo:

| Clase | tests | Cubre |
|---|---|---|
| HarnessVerdictApplierTest | 11 | Pending/Blocked → NotApplicable; Verified matching → Promoted; path/content/candidate/fingerprint mismatches → typed Refusal; idempotencia |
| HarnessVerdictReaderTest | 11 | verified/blocked/pending parses; unknown status → UnknownStatus; missing/malformed → typed Malformed; read() round-trips a real file |

## Estado del inventario (sin cambios desde rc6)

Sobre el inventario regenerado por `tooling/inventory_tests.py`:

| Decisión | rc6 | rc7 | Δ |
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
  hook, no transformación.

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

## Evidencia de tests

```
:test-harness:test --rerun-tasks
22 clases, 161 tests, 0 skipped, 0 failures, 0 errors
Tiempo wall: 12s
Comando: cd v2 && ./gradlew :test-harness:test --rerun-tasks -PpipelineTestForkEvery=0
JDK: Temurin 21.0.8+9.0.LTS
Evidencia: 22 XML en v2/test-harness/build/test-results/test/TEST-*.xml
          (canary: ningún XML preexistente; el run los regeneró)
```

## STOP checks

| Stop | Estado |
|---|---|
| ADRs auto-aceptados | OK: aceptados por el decider (Rubentxu) el 2026-10-16; sin auto-aprobación |
| Decisión que cambia la frontera sin evidencia | OK: el apper es fail-closed por fingerprint; ningún `Promoted` posible sin 4 matches exactos |
| Borrado implícito por nombre o por edad | OK: solo el apper con `Promoted` puede promover; el guard sigue rechazando |
| PR/issue de harness fabricada | OK: ningún issue number fabricado; las UATs `@Disabled("migrated-pending")` siguen apuntando al doc `RESPONSIBILITY_MIGRATION_ROADMAP.md` |
| ZIP regenerado tras verificar | OK: el SHA `9328b566…` se calculó una vez sobre el árbol integrado |
| Tag/publicación sin gate | OK: 161/0/0/0 en `:test-harness:test` antes de tag |

## Pendiente (no en este RC)

- `pipelinek-release-harness` ejecutándose contra el ZIP
  `9328b566…` y emitiendo veredictos `VERIFIED` con
  `proofDigest` por UAT.
- Cuando lleguen los primeros veredictos:
  1. leerlos con `HarnessVerdictReader`;
  2. aplicarlos con `HarnessVerdictApplier`;
  3. **el decider decide** si `retirementAuthorised = true`;
  4. una sola PR atómica borra las UATs promovidas y sus
     fixtures exclusivas, con `Refs: pipelinek-release-harness#N`
     en el mensaje.
- La `v0.61.0-rc8` se publicará cuando el primer lote de
  veredictos llegue y la promoción de Wave A esté verde.
