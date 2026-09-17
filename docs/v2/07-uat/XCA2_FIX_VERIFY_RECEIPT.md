# XCA-2 — Fix-Verify Receipt (2026-09-17, cycle/wu-g5b)

**Rol**: verify de la apply-fix (M3, testigo distinto del autor M2.7-highspeed).
**Misión**: validar los 3 commits de apply-fix, demostrar la falsificación de H8,
y confirmar que H5/H6/H9 siguen declaradas (no arregladas).
**Base verificado**: `84d3f028..943e3769` (3 commits de apply-fix).
**HEAD al cerrar**: `943e3769` (sin mutaciones persistidas).
**Árbol al cerrar**: limpio.

---

## Resumen ejecutivo

H1 / H7 / H8 PARCIALMENTE cerrados. Se detecta un **defecto crítico** que el
apply-fix no cerró: la ruta del ledger en `XcaCliCanaryTest.B.5` está mal
construida y resuelve a un fichero que NO EXISTE, dejando el canary en
**false-green** estructural.

```text
H1  Dual-ledger → autoridad única.                                PARCIAL ✗
    ├── Rename: hecho ✓
    ├── canónica sigue siendo autoridad ✓
    ├── B.5 lee canónica: FALSO ✗ (BUG: path resuelve a fichero inexistente)
    ├── Regex anclado: hecho ✓ (pero moot por BUG de path)
    └── core.pwd / core.pwd.tmp siguen STOPPED_G7 ✓

H7  Conteo "20/18" → "21/19" OBSERVED.                            CERRADO ✓ (con reservas)
    ├── 21 fixtures / 19 OK / 2 SKIP OBSERVED en vivo ✓
    ├── 22-wait-until con expectativas {core.sh} ✓
    ├── assert() Kotlin eliminado ✓
    ├── Comentarios 12/21 corregidos ✓
    └── errorClass enrichment: REGEX no captura "EngineInvariantViolation" ✗

H8  Regex greedy + WARN exit-code.                               PARCIAL ✗
    ├── Regex anclado a indent ≥4: hecho ✓
    ├── Moot porque el ledger path no existe (BUG de H1)
    └── WARN exit-code como observabilidad: marcado correctamente ✓
```

**H5, H6, H9**: siguen declaradas, no arregladas ✓.

---

## Hallazgo CRÍTICO nuevo — B.5 canary es false-green

### Defecto

`XcaCliCanaryTest.B.5` (línea 304):

```kotlin
val ledgerPath = Path.of("../docs/v2/status/step-certification.yaml")
if (Files.exists(ledgerPath)) {
    val ledgerContent = Files.readString(ledgerPath)
    val corePwdCertified = Regex(...).containsMatchIn(ledgerContent)
    ...
    assertFalse(corePwdCertified, "B.5 falsification: core.pwd is STOPPED_G7 and MUST NOT be CERTIFIED...")
}
```

Desde el cwd de Gradle para `:pipeline-application:test` (que es
`v2/pipeline-application/`), la ruta `../docs/v2/status/step-certification.yaml`
resuelve a `v2/docs/v2/status/step-certification.yaml` — el camino ANTIGUO, no
la canónica.

### Evidencia (OBSERVED)

```bash
$ cd /var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b/v2/pipeline-application
$ realpath ../docs/v2/status/step-certification.yaml
/var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b/v2/docs/v2/status/step-certification.yaml
$ ls -la /var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b/v2/docs/v2/status/
-rw-r--r-- ... step-certification-XCA2-EVIDENCE.yaml
# step-certification.yaml NO EXISTE en v2/docs/v2/status/

$ realpath ../../docs/v2/status/step-certification.yaml
/var/home/rubentxu/Proyectos/kotlin/pipeline-wu-g5b/docs/v2/status/step-certification.yaml
# Esta SÍ existe — la canónica real
```

La ruta correcta desde `v2/pipeline-application/` es `../../docs/v2/status/step-certification.yaml`
(un `..` adicional), NO `../docs/v2/status/...`.

### Falsificación (DEMOSTRADA EN VIVO)

**Procedimiento**:

1. Mutar `docs/v2/status/step-certification.yaml` (la canónica) para que
   `core.pwd` y `core.pwd.tmp` aparezcan como `certification_state: CERTIFIED`.
2. Correr `XcaCliCanaryTest` con `--rerun-tasks` desde `cycle/wu-g5b`.
3. Observar el resultado.
4. Revertir la mutación (sin commit).

**Resultado OBSERVED**:

```text
Antes de la mutación:  XcaCliCanaryTest = 3/3 PASS (BUILD SUCCESSFUL)
Mutación aplicada:     sed -i '519s/STOPPED_G7/CERTIFIED/' docs/v2/status/step-certification.yaml
                       sed -i '549s/STOPPED_G7/CERTIFIED/' docs/v2/status/step-certification.yaml
Después de la mutación: XcaCliCanaryTest = 3/3 PASS (BUILD SUCCESSFUL) ← FALSE-GREEN
Revert: cp /tmp/canon-backup.yaml docs/v2/status/step-certification.yaml
```

El test **NO falló** con la mutación en su lugar. El `if (Files.exists(ledgerPath))`
se evalúa a FALSE porque el path resuelve a un fichero inexistente, los
`assertFalse` están dentro del bloque, así que se omiten silenciosamente. B.5
es vacuously TRUE.

### Implicación

El comentario del commit 84d3f028 afirma textualmente:

> "XcaCliCanaryTest B.5 now reads docs/v2/status/step-certification.yaml
>  (canonical Step Constitution burn-down authority, single source of truth)"

Esta afirmación es estructuralmente falsa: B.5 lee el camino del ledger
renombrado, que ya no existe, y se queda en silencio. **El rename H1 + el
re-path H8 son ortogonales pero la combinación los anula**: el rename
elimina la autoridad en la ruta vieja; el re-path usa la ruta vieja.

### Fix mínimo (no aplicado — verify reporta, no arregla)

Cambiar la línea 304 de:

```kotlin
val ledgerPath = Path.of("../docs/v2/status/step-certification.yaml")
```

a:

```kotlin
val ledgerPath = Path.of("../../docs/v2/status/step-certification.yaml")
```

(Desde `v2/pipeline-application/`, dos `..` suben a la raíz del repo, donde
vive la canónica.)

---

## H7 — errorClass enrichment: REGEX no captura `EngineInvariantViolation`

### Defecto

El fix añadió `errorClass: String?` a `FixtureRunResult` y lo imprime en el
receipt:

```kotlin
val errNote = r.errorClass?.let { " [$it]" } ?: ""
println("  $status  ${r.fixture.padEnd(30)}  matched=${r.matched}  missing=$missing  extra=$extra$errNote")
```

Pero el regex que extrae la clase:

```kotlin
val errorClass = if (runId == null && stderr.isNotBlank()) {
    Regex("""([A-Z][A-Za-z0-9_]*(?:Exception|Error))""").find(stderr)?.groupValues?.get(1)
} else null
```

requiere que el nombre de la clase termine en `Exception` o `Error`. El
fallo real de 12-error-handling y 21-milestone es:

```text
Exception in thread "main" dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation:
  registry step 'core.milestone' reached execute without declared capabilities
  available: milestone.operations
```

`EngineInvariantViolation` no termina en `Exception` ni en `Error`. El regex
no matchea. `errorClass` queda en `null`. El receipt imprime:

```text
  SKIP      12-error-handling               matched=0  missing=2  extra=0
  SKIP      21-milestone                    matched=0  missing=1  extra=0
```

**Sin `[errorClass]` annotation** — el campo está añadido pero vacío para
los dos únicos fixtures que el comentario documenta como fallidos por
`EngineInvariantViolation`.

### Evidencia (OBSERVED, fresh execution)

```text
$ timeout 600 ./v2/pipeline-application/build/install/pipeline-application/bin/pipeline-application \
    run --db /tmp/xca-12.db --control-root /tmp/xca-12.ctrl \
    v2/compatibility/12-error-handling.pipeline.kts 2>&1 | grep -E "Exception|Error"
Exception in thread "main" dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation:
  registry step 'core.milestone' reached execute without declared capabilities
  available: milestone.operations

$ echo -e "Exception in thread \"main\" dev.rubentxu.pipeline.v2.domain.EngineInvariantViolation: ..." \
    | grep -oE "[A-Z][A-Za-z0-9_]*(Exception|Error)" || echo "no match"
no match
```

### Implicación

El comentario del fix (líneas 204-209 de XcaCorpusRunTest.kt) enuncia
correctamente que la causa real es `EngineInvariantViolation`. Pero el
structured receipt no la muestra — el campo existe pero está vacío. La
mejora es cosmética para los dos casos que el fix intenta documentar.

### Fix mínimo (no aplicado)

Cambiar el regex a algo como:

```kotlin
Regex("""(?:Exception in thread "[^"]+" )?([A-Z][A-Za-z0-9_.]*(?:Exception|Error|Violation))""")
```

---

## H1 — verificación estructural

| Aspecto | Estado | Evidencia |
|---|---|---|
| `docs/v2/status/step-certification.yaml` existe | ✓ | 65641 bytes, canónica |
| `v2/docs/v2/status/step-certification.yaml` NO existe | ✓ (correcto) | rename aplicado |
| `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml` existe | ✓ | 14113 bytes, evidencia |
| `XcaCliCanaryTest.B.5` lee la canónica | ✗ | path mal construido (BUG arriba) |
| `certification_state: STOPPED_G7` para core.pwd | ✓ | línea 519 del canónico |
| `certification_state: STOPPED_G7` para core.pwd.tmp | ✓ | línea 549 del canónico |
| Sin `when(stepKey)` en main sources | ✓ | grep = 0 ocurrencias; único hit = comentario que PROHIBE el patrón |

---

## H7 — verificación de conteo (OBSERVED en vivo)

**Comando**: `timeout 600 ./v2/gradlew -p v2 :pipeline-application:test --tests 'XcaCorpusRunTest' --rerun-tasks`
**XML canario** (timestamp `2026-09-17T21:37:56.552Z`, regenerado tras borrado):

```xml
<testsuite name="dev.rubentxu.pipeline.v2.application.XcaCorpusRunTest"
           tests="1" skipped="0" failures="0" errors="0"
           timestamp="2026-09-17T21:37:56.552Z" time="109.187">
  <testcase name="E — full corpus run with evidence reconciliation()" time="109.187"/>
```

**Receipt OBSERVED**:

```text
Fixtures total:   21    ← corregido de "20" (OBSERVED ✓)
Runs OK:          19
Runs FAIL:        2     (12-error-handling, 21-milestone — SKIP)
Fully covered:    9
Unique steps:     12
Total matched:    15

  22-wait-until        matched=1  missing=0  extra=0  [OK]      ← expectation {core.sh} ✓
  12-error-handling    SKIP  matched=0  missing=2  extra=0         ← errorClass vacío ✗
  21-milestone         SKIP  matched=0  missing=1  extra=0         ← errorClass vacío ✗
```

**Sin `assert()` Kotlin** (grep `^\s*assert(` en el test): 0 ocurrencias ✓.

---

## H8 — verificación de falsificación (DEMOSTRADA)

| Canario | Procedimiento | Resultado |
|---|---|---|
| B.5 (regex) | Mutar `certification_state: STOPPED_G7 → CERTIFIED` para `core.pwd` y `core.pwd.tmp` en canónica | **NO falla** ✗ (path bug: assert skipped) |
| B.5 (regex) | Fix path a `../../docs/...` (no aplicado, solo verificable mentalmente) | fallaría ✓ (regex correcto) |

**Conclusión H8**: la corrección del regex es real y estructuralmente sana
(el anchor de 4+ espacios aisla entradas correctamente), pero el canary
está **muteado por el path bug**. Hasta que el path no se arregle, el
canary es vacuous.

---

## Gate formal: `./gradlew -p v2 check --rerun-tasks --continue`

**Comando**: `timeout 1800 ./v2/gradlew -p v2 check --rerun-tasks --continue`
**Resultado**: `BUILD FAILED in 20m 51s` (109 actionable tasks; presupuesto derivado del baseline 1224s × 1.3 = 1591s, dentro del ceiling 1800s)
**Verdad**: XMLs en `v2/<module>/build/test-results/test/TEST-*.xml` (437 ficheros)

### Per-module stats (XML, fuente de verdad)

```text
module                                       tests    failures    errors
────────────────────────────────────────────────────────────────────────
pipeline-application                          1674          28         0
pipeline-architecture-tests                    287          17         0
pipeline-scripting-api                          39           1         0
pipeline-scripting-kotlin24                     43           7         0
pipeline-step-sdk (consolidated)               261           3         0
────────────────────────────────────────────────────────────────────────
TOTAL                                         3151          56         0
```

**El conteo de 56 failures coincide EXACTAMENTE con el baseline del XCA2_VERIFY_RECEIPT.md
(cf5dda04).** No hay regresiones introducidas por apply-fix en módulos fuera
del alcance XCA. Todos los fallos son pre-existentes documentados:

- `pipeline-application` (28): `CompatibilityCorpusTest`, `CoreMilestoneStepContractSuiteTest`,
  `CanonicalDurableRunCoordinatorTest`, `Lfc2PluginEventExtensibilityFitnessTest` (RED
  deliberado para P3.0.1), `UatCompat001CorpusSmokeRunTest`, `UatLocal005..013`.
- `pipeline-architecture-tests` (17): `Lfc0GlobalStateFitnessTest`,
  `Lfc2BlockStepCompilerBodyExhaustivenessFitnessTest`, `Lfc2DurableAggregateIdentityFitnessTest`,
  `FArchL7BlockStepNestingInvariantTest`, `FArchL7JenkinsVerbatimSignatureReflectionTest`,
  `FArchLfc1CanonicalCoverageTest`.
- `pipeline-scripting-api` (1): `PipelineDslSealedHierarchyTest.sealed_hierarchy_is_exhaustive_with_29_kinds()`
- `pipeline-scripting-kotlin24` (7): `WithCredentialsCompileIntegrationTest`, `ScriptTextEscaperTest`.
- `pipeline-step-sdk:workflow-control` (3): `DirExecutor` DIR-S-004 tests.

---

## H5 / H6 / H9 — confirmación de "declarada, no arreglada"

### H5 — guard débil en `reconcile()`

`v2/pipeline-events/src/test/kotlin/dev/rubentxu/pipeline/v2/events/evidence/JournalRunExecutionEvidenceReaderInMemoryTest.kt:211-229`:

```kotlin
@Test
fun `the shortcut status-isTerminal would drop RUNNING from observed`() {
    // Proves that the shortcut breaks the RUNNING case.
    // This is documentation-of-the-falsification, not a second test assertion —
    // the actual guard is the test above.  Here we explicitly compute what the
    // shortcut would give vs what the law gives, so the regression is visible
    // without needing to mutate the actual source.
    val runningStatus = OperationStatus.RUNNING
    val lawResult = runningStatus != OperationStatus.PENDING   // true
    val shortcutResult = runningStatus.isTerminal              // false
    assertTrue(lawResult, ...)
    assertFalse(shortcutResult, ...)
    ...
}
```

**Sigue ahí**: el test computa `lawResult`/`shortcutResult` localmente — no
invoca producción. Si el código mutara, este test seguiría verde porque
solo compara valores precalculados del propio enum. Comentario lo admite
explícitamente. **NO ARREGLADO** ✓.

### H6 — vocabulario del ledger v2

`v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml`:

```text
# Verification status progression:
# CURRENT_STEP | STATIC_CANDIDATE | EVIDENCE_READY | CERTIFIED | QUARANTINED | RETIRED
...
- step_key: core.echo
  verification_status: IMPLEMENTED_UNCERTIFIED   ← fuera de la progresión declarada
```

`IMPLEMENTED_UNCERTIFIED` sigue sin estar en la progresión declarada por la
cabecera del propio fichero. **NO ARREGLADO** ✓.

### H9 — mutante del brief no como test separado

`grep -rn "eb40d5cb\|7d725aca" v2/`: 0 ocurrencias.

El apply-fix-receipt afirma: *"el KDoc apunta a los SHAs"*. Inspección
directa: el KDoc tiene los comentarios `// ... (RED)` y `// ... (GREEN)`
como etiquetas cualitativas, pero **no contiene los SHAs reales** del par
de commits. La falsificación solo es recuperable mediante `git checkout
eb40d5cb && ./gradlew ... && git checkout 7d725aca && ./gradlew ...`
manual. El apply-fix-receipt incurre en una inexactitud menor al decir
que el KDoc "apunta a los SHAs".

**Mutante del brief sigue sin test separado** ✓ (deuda declarada, no
arreglada; nota: la afirmación "KDoc apunta a los SHAs" del apply-receipt
es inexacta).

---

## Resumen uno-a-uno

| Hallazgo | Estado apply-fix | Estado verify (M3) | Veredicto |
|---|---|---|---|
| H1 | FIX declarado | **PARCIAL: B.5 path bug, canary vacuous** | **BLOQUEANTE** — el camino del canary está mal; el rename del ledger es correcto pero inutilizable |
| H7 | FIX declarado | **CASI: count OK, regex errorClass no captura la clase real** | No bloqueante para count/assert() pero la mejora `errorClass` es cosmética para los 2 fixtures afectados |
| H8 | FIX declarado | **PARCIAL: regex anclado correcto, pero moot por H1 bug** | Subsumido en H1 |
| H5 | DECLARADO | Sigue presente, sin tocar ✓ | Correcto (deuda declarada) |
| H6 | DECLARADO | Sigue presente, sin tocar ✓ | Correcto (deuda declarada) |
| H9 | DECLARADO | Sigue presente, sin tocar ✓; apply-receipt claims KDoc→SHAs son inexactos | Correcto (deuda declarada); apply-receipt inexactitud menor a corregir |

---

## Estado de certificación de steps (canónica)

`docs/v2/status/step-certification.yaml` (34 claves, autoridad única):

```text
core.echo         certification_state: CERTIFIED
core.pwd          certification_state: STOPPED_G7   ← B.5 path bug oculta este
core.pwd.tmp      certification_state: STOPPED_G7   ← B.5 path bug oculta este
core.sh           certification_state: CERTIFIED
... (30 más)
```

**XCA-2 evidence** (`v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml`,
14113 bytes, 14 entradas): conserva la evidencia de reconciliación pero NO
es autoridad.

---

## Cierre de hallazgos (apply-fix 2, 2026-09-17)

### H1-PATH-BUG — CERRADO ✓

**Commit**: `d29b65f6` (`fix(H1-PATH-BUG): resolve ledger path from repo root, not relative from v2/`)

**Fix**: `XcaCliCanaryTest.kt` ahora usa `Path.of("../../")` como `repoRoot` y resuelve
`docs/v2/status/step-certification.yaml` (canonical, 65641 bytes) — no la copia
`v2/docs/v2/status/` renombrada.

**Falsificación OBSERVED**:
- Mutación: `certification_state: STOPPED_G7 → CERTIFIED` para `core.pwd` y `core.pwd.tmp`
- Resultado: B.5 falla en línea 326 (`assertFalse corePwdCertified`) — causa exacta
- Revertido: B.5 pasa

**Verificación**: `timeout 300 ./gradlew ... --tests 'XcaCliCanaryTest.B5*'`
XML: `tests="1" failures="0" timestamp="2026-09-17T22:18:57.828Z"` ✓

### H7-ERRORCLASS-REGEX — CERRADO ✓

**Commit**: `cb011ebd` (`fix(H7-ERRORCLASS-REGEX): extend errorClass regex to capture EngineInvariantViolation`)

**Fix**: regex ampliado de `([A-Z][A-Za-z0-9_]*(?:Exception|Error))`
a `([A-Z][A-Za-z0-9_]*(?:Exception|Error|Violation))` — ahora captura `EngineInvariantViolation`.

**Verificación**: receipt del corpus ahora muestra:
```
  SKIP  12-error-handling  matched=0  missing=2  extra=0 [EngineInvariantViolation]
  SKIP  21-milestone       matched=0  missing=1  extra=0 [EngineInvariantViolation]
```
XML: `tests="1" failures="0" timestamp="2026-09-17T22:20:05.343Z"` ✓

### H9-DOC — CERRADO ✓

**Commit**: `3cf22d59` (`fix(H9-DOC): remove false claim that KDoc points to SHAs`)

**Fix**: la frase *"el KDoc apunta a los SHAs"* se elimina. Reemplazada por:
*"los SHAs reales no están en KDoc (OBSERVED: grep eb40d5cb\|7d725aca = 0 matches)"*
— afirmación honesta respaldada por evidencia.

### H5 / H6 / H9-deuda — DECLARADAS, no tocadas ✓

Sunflower confirmó que H5, H6 y la deuda de H9 siguen intactas. El apply-fix-2
**no las modifica**. La deuda declarada es parte del cierre.

---

## Siguiente acción

Los tres hallazgos de apply-fix-2 están cerrados. Listo para publicación
cuando el orquestador lo autorice.

### Estado anterior (apply-fix, ahora obsoleto para H1/H7/H9)

- **H1**: rename del ledger ✓; B.5 path bug — **CERRADO por apply-fix-2**
- **H7**: conteo 21/19 ✓; errorClass regex — **CERRADO por apply-fix-2**
- **H9**: deuda declarada ✓; afirmación inexacta — **CERRADA por apply-fix-2**

---

## Estado del árbol al cierre

```text
HEAD: 3cf22d59 (cycle/wu-g5b), árbol limpio
Commits de apply-fix-2: d29b65f6 + cb011ebd + 3cf22d59
XCA2_FIX_RECEIPT.md actualizado con la corrección H9
Ninguna mutación persistente
Nada pusheado
```

**XCA2_FIX_VERIFY_RECEIPT.md** (este documento) cierra los hallazgos
H1-PATH-BUG, H7-ERRORCLASS-REGEX y H9-DOC de apply-fix-2.
H5/H6/H9-deuda siguen como deuda declarada — no tocadas.