# XCA-2 — Release Receipt (2026-09-18, cycle/wu-g5b)

**Status**: READY_FOR_PUSH_AND_TAG · NOT PUSHED YET (esperando GO del operador)

## Ciclo cerrado

| Fase | Testigo | SHA | Envelope | Veredicto |
|---|---|---|---|---|
| apply-fix-2 | M2.7 (hibiscus) | `74b309dc` | n/a (aplica) | código committed |
| verify-fix-2 | M3 (hibiscus re-spawn) | `b65ee102` | `XCA2_FIX_VERIFY_2_RECEIPT.md` | **CONFIRMA** |
| debt-verify-2 | glm-5-turbo (blossom re-spawn) | `b91a2e57` | `XCA2_DEBT_VERIFY_2_RECEIPT.md` | **PASS** |
| closure-receipts | M2.7-highspeed (este commit) | `b91a2e57` | este fichero | OK

## SHAs a publicar (20 commits ahead de origin/cycle/wu-g5b)

```
b91a2e57  docs(XCA2): fix-verify-2 + debt-verify-2 receipts — verify CONFIRMA, debt-verify PASS  [M2.7-highspeed]
b65ee102  docs(XCA2): handoff de sesion para continuacion 2026-09-17                            [M2.7-highspeed]
74b309dc  docs(XCA2_FIX_VERIFY): close H1/H7/H9 from apply-fix-2                                [hibiscus/M2.7]
3cf22d59  fix(H9-DOC): remove false claim that KDoc points to SHAs                              [hibiscus/M2.7]
cb011ebd  fix(H7-ERRORCLASS-REGEX): extend errorClass regex to capture EngineInvariantViolation  [hibiscus/M2.7]
d29b65f6  fix(H1-PATH-BUG): resolve ledger path from repo root, not relative from v2/           [hibiscus/M2.7]
2b78e3f3  verify(XCA-2): XCA2_FIX_VERIFY_RECEIPT — H1 path bug detectado                        [sunflower/M3]
943e3769  docs: XCA2_FIX_RECEIPT — cierra H1/H7/H8, declara H5/H6/H9                            [clover/M2.7]
5443fa37  fix(H7): fix corpus count and expected failures documentation                         [clover/M2.7]
84d3f028  fix(H1): resolve dual-ledger authority — single source of truth                       [clover/M2.7]
9feda7de  debt-verify: XCA2_DEBT_VERIFY_RECEIPT — 9 hallazgos, H1 dual-ledger BLOCK              [cactus/glm-5]
6d4cb17c  docs: XCA-2 verify receipt (verify-2026-09-17)                                        [retriever/M3]
cf5dda04  verify: demote core.echo CERTIFIED -> IMPLEMENTED_UNCERTIFIED in XCA-2 ledger         [retriever/M3]
7d725aca  GREEN: revert isObserved to status != OperationStatus.PENDING                         [retriever/M3]
eb40d5cb  RED: mutate isObserved to status.isTerminal (XCA-2 falsification)                     [retriever/M3]
ca4f299c  verify: fix XcaCliCanaryTest B.5/B.6 false-green assertions                           [retriever/M3]
9b25e5ea  docs: update TESTING-STATE with XCA-2 workstream E completion                         [kikazaru/M2.7]
2ebd1b35  XCA-2 E: full corpus run test with evidence reconciliation                            [kikazaru/M2.7]
81e49112  XCA-2 D: structured step-certification evidence ledger                                [kikazaru/M2.7]
47db55f3  XCA-2 B.4/B.5/B.6 CLI canary + C reconciliation ADTs + D evidence ledger              [kikazaru/M2.7]
```

## Cadena de custodia (cadena OBSERVED completa)

```
b91a2e57  ← este release-receipt, sobre b65ee102
b65ee102  ← handoff de sesión, sobre 74b309dc
74b309dc  ← doc closure, sobre 3cf22d59
3cf22d59  ← fix(H9-DOC), sobre cb011ebd
cb011ebd  ← fix(H7-REGEX), sobre d29b65f6
d29b65f6  ← fix(H1-PATH), sobre 2b78e3f3
2b78e3f3  ← verify(sunflower), detecta H1 path bug
... [apply-fix clover 84d3f028, 5443fa37, 943e3769]
... [debt-verify-1 cactus 9feda7de]
... [apply kikazaru XCA-2 B.4..E: 47db55f3, 81e49112, 2ebd1b35, 9b25e5ea]
... [verify retriever XCA-2: ca4f299c, eb40d5cb, 7d725aca, cf5dda04, 6d4cb17c]
```

## Estado del ledger canónico (md5 esperado)

```text
md5sum docs/v2/status/step-certification.yaml
b84fdf3c7ae7e9518701bbd9372bfedf  docs/v2/status/step-certification.yaml
```

Coincide con el digest declarado en `XCA2_FIX_VERIFY_2_RECEIPT.md` (línea 199).
Las mutaciones de falsificación RED→GREEN se revertieron correctamente.

Copia renombrada `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml`
md5: `db7360038a1b12a130cc0e1b5bc3b0ed` — NO autoridad (deuda declarada H6 intacta).

## Deuda declarada tras el ciclo

```text
H5   guard débil en reconcile() sin else                              declarado
H6   vocabulario del ledger v2 fuera de la progresion                  declarado (H1 resuelto)
H9-deuda mutante del brief sin test separado                           declarado (H9-DOC resuelto)
N1   guard B.5 sin else-fail sobre Files.exists (misma familia que H5) declarado (este ciclo)
N2   sobre-captura teórica regex (misma familia que H8, cosmético)    declarado (este ciclo)
```

`HISTORICAL_CEILING = 18` (inmutable). Deuda viva tras XCA-2: sin cambios netos
(2 cierres de la deuda histórica de XCA-1, 2 nuevas declaraciones del mismo
ciclo; el pin a 18 sigue válido como provenance).

## Decisiones pendientes (operador)

1. **Tag**: sugerencia del handoff `xca2-audit-2026-09-17` — usa "audit" en vez de
   "closed" porque N1/N2 siguen declaradas y el tag no debe sobreafirmar el cierre.
2. **Modo SDDK**: `undeclared` se mantiene (decisión explícita del operador anterior;
   el orquestador no fuerza modos persistentes sin GO).
3. **Ledger copy**: `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml` se
   mantiene como está (deuda declarada H6; borrarla sería decisión irreversible).

## Comandos de release (cuando llegue GO)

```bash
cd /var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b

# 1. push de la rama
git push origin cycle/wu-g5b

# 2. tag (nombre sugerido del handoff)
git tag -a xca2-audit-2026-09-17 -m "XCA-2 audit closure: verify CONFIRMA, debt-verify PASS, 2 deudas nuevas declaradas (N1, N2)"
git push origin xca2-audit-2026-09-17

# 3. archive (no destructivo)
# ver docs/v2/07-uat/ARCHIVE_PROTOCOL.md
```

## Lo que NO se debe hacer al retomar

- NO pushear antes de GO del operador con los 3 puntos resueltos.
- NO cambiar el nombre del tag sin consultar (la sugerencia del handoff es deliberada).
- NO escribir el modo SDDK sin GO explícito.
- NO borrar la copia renombrada del ledger (deuda declarada, decisión del operador).
- NO saltar validate-envelope entre fases en el próximo ciclo (XCA-3 si lo hay).

## Hand-back

Cerrado el ciclo XCA-2 a las 08:34 hora local del 2026-09-18 (verify) y 08:39 (debt-verify).
20 commits sin pushear. 3 decisiones explícitas pendientes (re-iteradas arriba).
HEAD local: `b91a2e57`. Divergencia con trunk: 20 ahead (NOT merged).