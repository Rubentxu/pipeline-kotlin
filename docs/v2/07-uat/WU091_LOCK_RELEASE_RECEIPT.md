# WU-091 `core.lock` — Recibo de release (RP6-A, ciclo `rp6a-lock`)

> status: RELEASED en `main` (push `d18d5e17..d0e56bdf`)
> verificación: `docs/v2/07-uat/WU091_LOCK_RECEIPT.md`,
> `docs/v2/07-uat/WU091_LOCK_VERIFICATION_REPORT.md`

## 1. Publicación

```text
repositorio  https://github.com/Rubentxu/pipeline-kotlin.git
rama         main
rango        d18d5e17..d0e56bdf   (fast-forward, 0 commits perdidos)
```

| SHA | Cambio |
| --- | --- |
| `20389cd2` | feat(dsl): superficie `lock` + `CoreLockWireCodec` como autoridad única de wire |
| `6165461e` | feat(events): los cinco eventos de la sección 6 |
| `e7343738` | fix(lock): registro en producción + deadline que acota la espera |
| `1907c07f` | test(lock): UAT WL-L1..WL-L9 contra la distribución real |
| `659e1caa` | docs(uat): recibo de certificación con los nueve criterios |
| `d0e56bdf` | docs(uat): informe de verificación |

## 2. Semver

```text
feat(dsl)   → MINOR   (superficie nueva: lock en StageScope y BranchScope)
feat(events)→ MINOR
fix(lock)   → PATCH   (el deadline del bloque ahora acota la espera)
test/docs   → sin efecto
```

Los commits mezclan `feat` y `fix` porque el `fix` no era un extra: sin él, la
superficie `feat` publicaba un `lock` que ejecutaba su cuerpo después del deadline
del bloque. Una release que sólo contuviera el `feat` habría publicado ese
defecto. La decisión de versión corresponde al corte de release, no a este ciclo.

## 3. Estado de la verificación

| Verificación | Estado | Evidencia |
| --- | --- | --- |
| Suite local (4 módulos) | **PASS** | 2021 tests, 0 fallos, 0 errores, 121 skipped (21m07s) |
| UAT `core.lock` | **PASS** | WL-L1..WL-L9, 9/9 |
| Mutación M-§4 | **DISCRIMINANTE** | 9 tests / 1 failed, exactamente WL-L9 |
| Ratchet del coordinador | **PASS** | 552 líneas, sin tocar |
| CI remoto | **NOT_RUN** | `.github/workflows/` está vacío en este repositorio: no existe workflow de build. No se afirma PASS remoto porque no hay gate remoto que ejecutar. |

## 4. Efectos pendientes

```text
ninguno abierto en este ciclo
```

Deuda registrada y triada, deliberadamente fuera de este train:

```text
bl-bl-01M3YGH3FE000387X13PG607G0  DEBT-WIRE-AUTHORITY  P2  Triaged
```

## 5. Reanudación

El ciclo queda cerrado con sus artefactos en SDDK
(`art-f2a583b5e381-ce22c3ae` specification, `art-5d0e7e7716a1-bd3b9134` design,
`art-b3e5ca8c6aa2-6006abdc` implementation-plan,
`art-d2c4b797d407-02e5776f` implementation-receipt,
`art-5e8752a7606b-25a67a61` verification-report). La siguiente unidad de la cola
RP-6 es **WU-092 `core.input`**, con el mismo patrón de gates que este ciclo acaba
de demostrar: superficie DSL con autoridad de wire única, G3.6 explícita, y
escenarios duros contra la distribución real antes de CERTIFIED.
