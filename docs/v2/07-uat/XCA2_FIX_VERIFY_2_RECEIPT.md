# XCA-2 — Fix-Verify-2 Receipt (2026-09-18, cycle/wu-g5b)

**Rol**: verify de la apply-fix-2 (testigo M3, distinto del autor M2.7-highspeed).
**Misión**: re-validar los 4 commits de hibiscus sobre el checkpoint 3ebc4a3b.
**Base verificado**: `943e3769..74b309dc` (3 fixes + 1 doc).
**HEAD al cerrar**: `74b309dc` (NO incluir el handoff `b65ee102` en este verify; es read-only).
**Árbol al cerrar**: limpio (`git status --short` = vacío).

**Leyes metodológicas aplicadas**:
- L1 el exit code del CLI NUNCA es evidencia de ejecución → corroborado con XML canary fresco.
- L2 la presencia en el fuente NUNCA es evidencia de ejecución → corroborado con L1 real.
- L3 la expectativa del ledger NUNCA es evidencia de ejecución → corroborado con mutación real.
- ML «todo guard que pretende evitar falsos verdes ha demostrado primero que el puede
  producir un rojo por la causa exacta que afirma detectar» → aplicado a H1.

---

## Veredicto por hallazgo

### H1 PATH-BUG (d29b65f6) — **CONFIRMADO**

**ANTES** (apply-fix clover, 84d3f028):
```kotlin
val ledgerPath = Path.of("../docs/v2/status/step-certification.yaml")
```
Desde `v2/pipeline-application/` (cwd de Gradle para ese módulo), `..` sube a `v2/`,
no a la raíz del repo. El path resolvía a `v2/docs/v2/status/step-certification.yaml`
— el fichero **renombrado** por la propia apply-fix (`step-certification-XCA2-EVIDENCE.yaml`),
que ya NO contiene `certification_state:` (solo `verification_status:`). El `Files.exists()`
devolvía FALSE silenciosamente, el `if` se saltaba, los `assertFalse` se omitían: B.5 era
**vacuously TRUE**, false-green estructural.

**DESPUÉS** (apply-fix-2 hibiscus, d29b65f6):
```kotlin
val repoRoot = Path.of("../../")
val ledgerPath = repoRoot.resolve("docs/v2/status/step-certification.yaml")
```
Dos `..` suben de `v2/pipeline-application/` a la raíz del repo. La autoridad canónica
(`docs/v2/status/step-certification.yaml`, 65641 bytes) reside ahí. El path resuelve
correctamente.

**TEST EJECUTADO**: `timeout 300 ./v2/gradlew -p v2 :pipeline-application:test --tests 'XcaCliCanaryTest.B5*' --rerun-tasks`

**FALSIFICACIÓN OBSERVED** (RED→GREEN con dos SHAs/estados distintos):
```text
RED  (mutación: certification_state: STOPPED_G7 → CERTIFIED en líneas 519 y 549):
  XcaCliCanaryTest > B5 20-pwd-tmp produces evidence but STOPPED_G7 is not certifiable() FAILED
      org.opentest4j.AssertionFailedError at XcaCliCanaryTest.kt:326
  1 test completed, 1 failed
  BUILD FAILED in 1m 18s

GREEN (revertido: certification_state: STOPPED_G7 restaurado, md5 = b84fdf3c...):
  XcaCliCanaryTest > B5 20-pwd-tmp produces evidence but STOPPED_G7 is not certifiable() PASSED
  BUILD SUCCESSFUL in 48s
  XML timestamp: 2026-09-18T06:29:54.225Z (fresco)
```

El guard ahora produce un rojo por la **causa exacta** que afirma detectar (un
`core.pwd` CERTIFIED en el canónico dispara `assertFalse corePwdCertified`). El
cambio del path ya no es moot: B.5 está vivo.

**Mutación revertida**: `diff /tmp/canon-backup-fv2.yaml docs/v2/status/step-certification.yaml` = vacío;
md5 = `b84fdf3c7ae7e9518701bbd9372bfedf` en backup y en destino.

**CONFIRMADO**.

---

### H7 ERRORCLASS-REGEX (cb011ebd) — **CONFIRMADO**

**ANTES** (apply-fix clover, 5443fa37):
```kotlin
Regex("""([A-Z][A-Za-z0-9_]*(?:Exception|Error))""").find(stderr)?.groupValues?.get(1)
```
El sufijo `Exception|Error` no captura clases que terminan en `Violation`. La causa real
de los fallos de `12-error-handling` y `21-milestone` es
`dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation`, no termina en `Exception`
ni `Error`. El campo `errorClass` quedaba en `null`, el receipt mostraba la línea
SKIP sin la anotación `[EngineInvariantViolation]`.

**DESPUÉS** (apply-fix-2 hibiscus, cb011ebd):
```kotlin
Regex("""([A-Z][A-Za-z0-9_]*(?:Exception|Error|Violation))""").find(stderr)?.groupValues?.get(1)
```
Sufijo ampliado con `|Violation`. Ahora captura `EngineInvariantViolation`.

**TEST EJECUTADO**: `timeout 600 ./v2/gradlew -p v2 :pipeline-application:test --tests 'XcaCorpusRunTest' --rerun-tasks`

**FALSIFICACIÓN OBSERVED** (RED→GREEN vía shell):
```text
$ echo 'Exception in thread "main" dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation: ...' \
    | grep -oE '[A-Z][A-Za-z0-9_]*(Exception|Error)' || echo "OLD REGEX: no match"
OLD REGEX: no match    ← RED: la regex vieja NO captura

$ echo 'Exception in thread "main" dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation: ...' \
    | grep -oE '[A-Z][A-Za-z0-9_]*(Exception|Error|Violation)'
EngineInvariantViolation  ← GREEN: la regex nueva captura
```

**Ejecución real del corpus** (XML `TEST-dev.rubentxu.pipeline.v2.application.XcaCorpusRunTest.xml`,
timestamp `2026-09-18T06:30:44.361Z`, fresco, 1/1 PASS):
```text
Fixtures total:   21
Runs OK:          19
Runs FAIL:        2
Fully covered:    9
Unique steps:     12
Total matched:    15
  ...
  SKIP      12-error-handling               matched=0  missing=2  extra=0 [EngineInvariantViolation]
  ...
  SKIP      21-milestone                    matched=0  missing=1  extra=0 [EngineInvariantViolation]
  ...
```

La anotación `[EngineInvariantViolation]` aparece en los dos fixtures que la
apply-fix-1 documentaba pero su regex no podía mostrar.

**CONFIRMADO**.

---

### H9 DOC (3cf22d59) — **CONFIRMADO**

**ANTES** (apply-fix clover, 943e3769 línea 203):
```text
| H9 | Mutante del brief no quedó como test separado | La falsificación existe como par
      de commits git; el KDoc apunta a los SHAs. Práctica, no bloqueo. Anotado. |
```
Afirmación inexacta: el KDoc no contiene los SHAs. Sunflower (M3) lo demostró con
`grep eb40d5cb\|7d725aca v2/ = 0 matches`.

**DESPUÉS** (apply-fix-2 hibiscus, 3cf22d59):
```text
| H9 | Mutante del brief no quedó como test separado | La falsificación existe como par
      de commits git; los SHAs reales no están en KDoc (OBSERVED: grep eb40d5cb\|7d725aca
      = 0 matches). Práctica, no bloqueo. Anotado. |
```

**Verificación**:
```bash
$ grep -nE "KDoc.*SHA|apunta a.*SHA|puntos a.*SHA" docs/v2/07-uat/XCA2_FIX_RECEIPT.md
(no output, exit code 1)   ← la frase inexacta está ELIMINADA del apply-receipt

$ grep -rn "eb40d5cb\|7d725aca" v2/
(no output, exit code 1)   ← la afirmación honesta del nuevo texto es VERDADERA
```

**CONFIRMADO**.

---

### Estado de deuda declarada (no arreglar, NO es regresión)

**H5** — guard débil en `reconcile()` sin `else`:
- Estado actual: `v2/pipeline-events/.../JournalRunExecutionEvidenceReaderInMemoryTest.kt`
  sigue conteniendo el comentario sobre `isTerminal` shortcut y el test local que
  computa `lawResult`/`shortcutResult` sin invocar producción.
- Aplicar-fix-2 no toca este test ✓ (diff scoped a `XcaCliCanaryTest.kt`, `XcaCorpusRunTest.kt`,
  `docs/v2/07-uat/XCA2_FIX_RECEIPT.md`, `docs/v2/07-uat/XCA2_FIX_VERIFY_RECEIPT.md`).
- Sigue declarado, no arreglado.

**H6** — vocabulario del ledger v2 fuera de la progresión:
- Estado actual: `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml` línea 3
  mantiene `Verification status progression:` y línea 51 mantiene
  `verification_status: IMPLEMENTED_UNCERTIFIED` (no pertenece a la progresión).
- Aplicar-fix-2 no toca este fichero ✓.
- Sigue declarado, no arreglado.

**H9-mutante** — mutante del brief sin test separado:
- Estado actual: `grep -rn "eb40d5cb\|7d725aca" v2/` = 0 matches (la falsificación es
  solo el par de commits git, no un test dedicado).
- Aplicar-fix-2 elimina la afirmación inexacta del apply-receipt pero NO añade
  el test separado (queda como deuda declarada).
- Sigue declarado, no arreglado.

---

## Cadena de custodia (RED/GREEN con dos SHAs/estados distintos por fix)

### H1 — falsificación del guard

```text
Estado canónico (líneas 519, 549):
  certification_state: STOPPED_G7           (core.pwd, core.pwd.tmp)

SHA "RED" — mutación del guard:
  sed -i '519s/STOPPED_G7/CERTIFIED/' docs/v2/status/step-certification.yaml
  sed -i '549s/STOPPED_G7/CERTIFIED/' docs/v2/status/step-certification.yaml
  diff /tmp/canon-backup-fv2.yaml docs/v2/status/step-certification.yaml → 2 líneas cambiadas

Test:
  XcaCliCanaryTest > B5 20-pwd-tmp produces evidence but STOPPED_G7 is not certifiable() FAILED
      org.opentest4j.AssertionFailedError at XcaCliCanaryTest.kt:326

SHA "GREEN" — restauración:
  cp /tmp/canon-backup-fv2.yaml docs/v2/status/step-certification.yaml
  diff /tmp/canon-backup-fv2.yaml docs/v2/status/step-certification.yaml → vacío
  md5 = b84fdf3c7ae7e9518701bbd9372bfedf (idéntico)

Test:
  XcaCliCanaryTest > B5 ... PASSED  (BUILD SUCCESSFUL in 48s, XML fresh)
```

### H7 — falsificación del regex

```text
"RED" regex:
  echo '... EngineInvariantViolation: ...' | grep -oE '[A-Z][A-Za-z0-9_]*(Exception|Error)'
  → no match (exit 1)

"GREEN" regex:
  echo '... EngineInvariantViolation: ...' | grep -oE '[A-Z][A-Za-z0-9_]*(Exception|Error|Violation)'
  → EngineInvariantViolation

Live corpus run (post-fix):
  SKIP 12-error-handling  ... [EngineInvariantViolation]
  SKIP 21-milestone       ... [EngineInvariantViolation]
  XML timestamp: 2026-09-18T06:30:44.361Z (fresh)
```

### H9 — falsificación de la afirmación

```text
"RED" afirmación (943e3769 línea 203):
  "... el KDoc apunta a los SHAs ..."

"GRAY" (afirmación corregida por 3cf22d59, 74b309dc):
  "... los SHAs reales no están en KDoc (OBSERVED: grep eb40d5cb|7d725aca = 0 matches) ..."

Verificación de la corrección:
  grep -nE "KDoc.*SHA|apunta a.*SHA|puntos a.*SHA" docs/v2/07-uat/XCA2_FIX_RECEIPT.md
  → 0 matches (exit 1) ✓

Verificación del claim nuevo:
  grep -rn "eb40d5cb|7d725aca" v2/
  → 0 matches (exit 1) ✓
```

---

## Resumen ejecutivo

| Hallazgo | Commit | Estado |
|---|---|---|
| H1-PATH-BUG | d29b65f6 | **CONFIRMADO** — falsificación RED→GREEN con causa exacta (`assertFalse corePwdCertified` línea 326). El guard B.5 lee la autoridad canónica y es ejecutable, no vacuous. |
| H7-ERRORCLASS-REGEX | cb011ebd | **CONFIRMADO** — falsificación regex RED→GREEN + ejecución real del corpus muestra `[EngineInvariantViolation]` en 12-error-handling y 21-milestone. |
| H9-DOC | 3cf22d59 | **CONFIRMADO** — afirmación inexacta eliminada del apply-receipt; nueva afirmación honesta respaldada por evidencia (`grep = 0 matches` re-verificado). |
| 74b309dc (doc) | docs-only | **OK** — actualiza verify-receipt con cierre de H1/H7/H9. No toca código. |

H5, H6, H9-deuda siguen declarados y no tocados.

**Veredicto: CONFIRMA**.

---

## Próximo paso

- si CONFIRMA → debt-verify-2 (`glm-5-turbo`) sobre los mismos 4 commits.
- si HALLAZGO → parar y consultar.

(Este verify NO encontró hallazgos; el veredicto es CONFIRMA.)

---

## Estado del árbol al cierre

```text
HEAD: 74b309dc (cycle/wu-g5b), árbol limpio
Mutaciones aplicadas durante esta verificación: TODAS revertidas
  - docs/v2/status/step-certification.yaml: STOPPED_G7 mutado a CERTIFIED (líneas 519, 549)
    y revertido (md5 b84fdf3c7ae7e9518701bbd9372bfedf idéntico al backup)
  - ninguna otra mutación
XMLs frescos generados:
  - TEST-dev.rubentxu.pipeline.v2.application.XcaCliCanaryTest.xml (2026-09-18T06:29:54.225Z)
  - TEST-dev.rubentxu.pipeline.v2.application.XcaCorpusRunTest.xml (2026-09-18T06:30:44.361Z)
Nada pusheado
```

**XCA2_FIX_VERIFY_2_RECEIPT.md** (este documento) cierra los hallazgos H1-PATH-BUG,
H7-ERRORCLASS-REGEX y H9-DOC de apply-fix-2. H5/H6/H9-deuda siguen como deuda
declarada — no tocadas.
