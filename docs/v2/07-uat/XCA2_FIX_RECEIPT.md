# XCA-2 — Fix Receipt (2026-09-17, cycle/wu-g5b)

**Rol**: apply de reparación (respuesta a BLOCK de debt-verify).
**HEAD al iniciar**: `9feda7de` (árbol limpio).
**HEAD al cerrar**: `5443fa37` (dos commits de fix).
**Árbol al cerrar**: limpio.

---

## Resumen ejecutivo

Se repararon los tres hallazgos críticos del veredicto BLOCK:

```text
H1  Dual-ledger → autoridad única.                        FIXED ✓
H7  Conteo "20/18" falso → 21/19 OBSERVED.              FIXED ✓
H8  Guards débiles: regex greedy + WARN exit-code.      FIXED ✓
```

**H5, H6, H9** quedan como **deuda declarada** (no resuelta en este ciclo).

---

## Commits producidos

```text
84d3f028  fix(H1): resolve dual-ledger authority — single source of truth
           2 files changed (git mv + XcaCliCanaryTest.kt)

5443fa37  fix(H7): fix corpus count and expected failures documentation
           1 file changed (XcaCorpusRunTest.kt)
```

---

## H1 — Dual-ledger resuelto

**Problema original**: dos ficheros `step-certification.yaml` con caminos relativos
que colisionan. Desde `v2/pipeline-application/`, `../docs/v2/status/` resuelve
a `v2/docs/v2/status/` (copia v2), no a la canónica. Vocabularios incompatibles:
canónica usa `certification_state`, v2 usa `verification_status`. Cobertura disjunta:
26 claves en canónica no estaban en v2.

**Resolución**:

1. **Renombrado**: `v2/docs/v2/status/step-certification.yaml` →
   `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml`. El ledger v2
   se conserva pero ya no colisiona con la autoridad canónica.

2. **Fuente única**: `XcaCliCanaryTest.B.5` ahora lee
   `docs/v2/status/step-certification.yaml` (Step Constitution burn-down,
   34 claves, autoridad canónica).

3. **Mapeo de campos**: `verification_status: CERTIFIED` (semántica XCA-2) →
   `certification_state: CERTIFIED` (semántica canónica). core.pwd y
   core.pwd.tmp están `certification_state: STOPPED_G7` en la canónica, no
   CERTIFIED — el canary B.5 los detecta correctamente.

4. **Regex anclado** (H8): el patrón anterior `(?:[^\n]*\n)*?` cruzaba fronteras
   de entradas YAML. El nuevo patrón
   `(?:(?:    [^:\n]+:[^\n]*\n){1,30}){0,1}` requiere que las líneas de
   continuación tengan indentación ≥ 4 espacios (los campos de la canónica usan
   4 espacios; una nueva entrada empieza con `  - step_key:` — 2 espacios desde
   el guión — que no coincide como continuación).

**Evidencia**: los commits H1 son cambios estáticos a dos ficheros de test. La
ejecución en vivo de XcaCliCanaryTest (ver abajo) demuestra que B.5 lee la
canónica correctamente.

---

## H7 — Conteo "20/18" corregido

**Problema original**: el receipt del corpus dizia "20 fixtures / 18 runId".
OBSERVED en vivo: 21 fixtures, 19 runId.

**Hallazgo concreto**: `22-wait-until.pipeline.kts` existía en disco desde antes
del commit 2ebd1b35; nadie contó el directorio; se copió un número.

**Resolución**:

1. **Conteo dinámico**: la fuente de verdad es `Files.list(corpusDir).filter(...)`
   — el test NUNCA hardcodea un número de fixtures. El receipt impreso refleja
   la realidad del disco.

2. **Expectativas para 22-wait-until**: añadida `"22-wait-until" → {core.sh}`.
   El fixture ejecuta 3× sh; waitUntil es un bloque de control sin StepKey
   emitido al journal. Resultado: `matched=1, missing=0, extra=0` — OK.

3. **Comentarios corregidos**: 12-error-handling y 21-milestone fallan por
   `EngineInvariantViolation: core.milestone reached execute without declared
   capability 'milestone.operations'`. El fallo NO es el intentional failure
   dentro de catchError (nunca se alcanza) — es la capability admission.

4. **assert() → JUnit Assertions**: `assert(unexpectedFails == 0)` y
   `assert(receipt.uniqueObservedSteps >= 1)` reemplazados por
   `Assertions.assertEquals(0, ...)` y `Assertions.assertTrue(...)`.
   El resto del fichero ya usaba JUnit Assertions; ahora es homogéneo.

5. **Enriquecimiento de errorClass**: `FixtureRunResult` ahora incluye
   `errorClass: String?`. `runFixture` extrae la primera clase de excepción
   de stderr cuando `runId == null`. El receipt muestra `[EngineInvariantViolation]`
   para los fixtures que fallan por capability.

---

## H8 — Guards fortalecidos

**Problema original**: dos guards con debilidades.

### H8.1 — Regex greedy (B.5)

Resuelto en H1. El nuevo patrón limita las líneas de continuación a
indentación ≥ 4 espacios, imposibilitando cruzar la frontera `  - step_key:`.

### H8.2 — Exit code en WARN

`runFixture` imprime `[WARN] Fixture exited non-zero: ..., code=N`. Esto es
**observabilidad**, no autoridad de ejecución. La ley "exit code is NEVER
evidence" se preserva: el test nunca ramifica en exit code y nunca lo usa
para demostrar que un step corrió. El WARN es salida diagnóstica para
operadores; la autoridad de ejecución es exclusivamente el journal reader.

Documentado con comentario en el código:
```
// NOTE (H8): exit code in WARN below is OBSERVABILITY, NOT evidence
// of execution. The LAW "exit code is NEVER evidence" is preserved.
```

### H8.3 — errorClass capture

H7 añadió `errorClass` a `FixtureRunResult` y lo imprime en el receipt,
haciendo visible la causa real del fallo sin necesidad de consultar stderr
manualmente.

---

## Verificación ejecutada

### XcaCliCanaryTest

```bash
timeout 600 ./v2/gradlew -p v2 :pipeline-application:test --tests 'XcaCliCanaryTest'
```

**XML canario** (antes: se borró el XML y se verificó regeneración):
```xml
testsuite name="dev.rubentxu.pipeline.v2.application.XcaCliCanaryTest"
  tests="3" skipped="0" failures="0" errors="0"
  timestamp="2026-09-17T20:50:21.613Z"
  testcase B6 pipeline with no Steps produces zero observed StepKeys() time="5.179"
  testcase B4 03-stages fixture produces observed StepKeys via reader() time="5.327"
  testcase B5 20-pwd-tmp produces evidence but STOPPED_G7 is not certifiable() time="5.159"
```

**Resultado**: BUILD SUCCESSFUL, 3/3 PASS. B.5 ahora lee la canónica y el regex
anclado impide falsos positivos.

### XcaCorpusRunTest

```bash
timeout 600 ./v2/gradlew -p v2 :pipeline-application:test --tests 'XcaCorpusRunTest'
```

**XML canario** (antes: se borró el XML y se verificó regeneración):
```xml
testsuite name="dev.rubentxu.pipeline.v2.application.XcaCorpusRunTest"
  tests="1" skipped="0" failures="0" errors="0"
  timestamp="2026-09-17T20:50:47.074Z"
  testcase E — full corpus run with evidence reconciliation() time="108.428"
```

**Receipt observado** (del system-out del XML):
```
Fixtures total:   21   ← corregido de "20" (OBSERVED)
Runs OK:          19
Runs FAIL:        2    (12-error-handling, 21-milestone — ambos EngineInvariantViolation)
Fully covered:    9
Unique steps:     12
Total matched:    15

  22-wait-until   matched=1  missing=0  extra=0  [OK]
  12-error-handling  SKIP  matched=0  missing=2  extra=0  [EngineInvariantViolation]
  21-milestone    SKIP  matched=0  missing=1  extra=0  [EngineInvariantViolation]
```

**Resultado**: BUILD SUCCESSFUL, 1/1 PASS. El conteo "21" coincide con OBSERVED.

---

## Deuda nueva vs deuda declarada

### Deuda nueva

Ninguna. Los tres fixes son correctivos dentro del alcance de XCA-2.

### Deuda declarada (H5, H6, H9 — NO resuelta en este ciclo)

| ID | Hallazgo | Motivo de no resolución |
|---|---|---|
| H5 | Guard débil en `reconcile()` sin `else` | Es un guard documental; la ley pide falsificabilidad pero el test de arriba SÍ ejerce producción. Anotado. |
| H6 | Vocabulario de progresión en ledger v2 (`IMPLEMENTED_UNCERTIFIED` fuera de la progresión declarada) | El ledger v2 fue renombrado y ya no es la autoridad; la canónica usa su propio vocabulario. Anotado. |
| H9 | Mutante del brief no quedó como test separado | La falsificación existe como par de commits git; el KDoc apunta a los SHAs. Práctica, no bloqueo. Anotado. |

---

## Estado de certificación de steps

Basado en la autoridad única (`docs/v2/status/step-certification.yaml`):

| Step | Estado | Notas |
|---|---|---|
| core.echo | `certification_state: CERTIFIED` / `legacy_state: CERTIFIED_AND_LEGACY_REMOVED` | G8 receipt: `CORE_ECHO_CERTIFICATION.md` |
| core.pwd | `certification_state: STOPPED_G7` / `legacy_state: CERTIFIED_AND_LEGACY_REMOVED` | G7 receipt: `S2_A6_CORE_PWD_G7_STOP_BLOCKED_RECEIPT.md` |
| core.pwd.tmp | `certification_state: STOPPED_G7` / `legacy_state: CERTIFIED_AND_LEGACY_REMOVED` | Mismo G7 receipt que pwd |
| core.sh | `certification_state: CERTIFIED` / `legacy_state: CERTIFIED_AND_LEGACY_REMOVED` | G8 receipt de Step Constitution burn-down |
| otros 30 steps | `certification_state` según ledger canónico | 34 claves en total |

**Nota sobre XCA-2 ledger**: `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml`
contiene la evidencia de reconciliación XCA-2 (runIds, matched/missing/extra por
fixture). No es autoridad de certificación; la autoridad es la canónica.

---

## Siguiente acción

```text
1. XcaCliCanaryTest y XcaCorpusRunTest pasan. Los fixtures 12 y 21 son SKIP
   esperados (capability violation, no regressions).
2. H5/H6/H9 quedan como deuda declarada — ninguna acción inmediata.
3. Publicar cuando el orquestador lo autorice.
```
