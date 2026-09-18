# XCA-2 — Debt-Verify-2 Receipt (2026-09-18, cycle/wu-g5b)

**Rol**: debt-verify (testigo glm-5-turbo, distinto del autor M2.7-highspeed y del verify M3)
**Misión**: auditar deuda y cumplimiento de I1/I2/I3 sobre los 4 commits de apply-fix-2
**Base verificado**: `943e3769..74b309dc`
**HEAD al cerrar**: `74b309dc` (NO incluir handoff `b65ee102` ni envelope verify-2 en este debt-verify; son read-only)
**Árbol al cerrar**: limpio salvo los receipts pendientes de commitear por el orquestador (`XCA2_FIX_VERIFY_2_RECEIPT.md` de M3, y este recibo). Ningún fichero de código o ledger fue modificado.

**Clases de evidencia usadas**:

```text
OBSERVED     — comando ejecutado en vivo en esta sesión, salida capturada
STRUCTURAL   — propiedad estática del árbol/commits (git show, grep, md5)
DERIVED      — inferencia lógica a partir de evidencia OBSERVED/STRUCTURAL
DOCUMENTED   — afirmación que solo existe en un doc, sin evidencia de ejecución
```

---

## Veredicto por hallazgo original (de debt-verify-1 cactus)

### H1 PATH-BUG (apply-fix-2 d29b65f6) — CERRADO CORRECTAMENTE

**¿Aplicado correctamente? SÍ.**

- STRUCTURAL: el diff sustituye `Path.of("../docs/v2/status/step-certification.yaml")` por
  `repoRoot = Path.of("../../")` + `resolve("docs/v2/status/step-certification.yaml")`.
  Desde el cwd de test por defecto de Gradle (`v2/pipeline-application/`), `../../` resuelve
  a la raíz del repo y el path final apunta a la autoridad canónica, no a la copia
  renombrada `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml` (que además ya no
  existe bajo ese pathname: la copia fue renombrada por la apply anterior).
- OBSERVED (verify-2 M3, corroborado): falsificación RED→GREEN del guard con mutación real
  del canónico (`STOPPED_G7 → CERTIFIED` en core.pwd) produce fallo por causa exacta en
  `XcaCliCanaryTest.kt:326`; restauración con md5 idéntico produce GREEN.
- OBSERVED (esta sesión): `md5sum docs/v2/status/step-certification.yaml` =
  `b84fdf3c7ae7e9518701bbd9372bfedf` — coincide con el valor esperado. La mutación fue
  revertida; el canónico está intacto.

**¿Introduce nueva deuda? SÍ, menor, declarada abajo (N1: guard condicionado a
`Files.exists` sin `else fail`).** No bloquea.

### H7 ERRORCLASS-REGEX (apply-fix-2 cb011ebd) — CERRADO CORRECTAMENTE

**¿Aplicado correctamente? SÍ.**

- STRUCTURAL: sufijo ampliado `(?:Exception|Error)` → `(?:Exception|Error|Violation)`.
  `EngineInvariantViolation` ahora es capturable.
- OBSERVED (verify-2 M3): falsificación de la regex por sí sola (shell RED/GREEN) +
  ejecución real del corpus con XML fresco (`XcaCorpusRunTest.xml`,
  `tests="1" failures="0" errors="0" timestamp="2026-09-18T06:30:44.361Z"`,
  re-verificado en vivo en esta sesión) mostrando
  `[EngineInvariantViolation]` en `12-error-handling` y `21-milestone`.
- I1/I2 respetadas: el PASS se apoya en XML leído, no en exit code ni en presencia en fuente.

**¿Introduce nueva deuda? SÍ, menor, declarada abajo (N2: sobre-captura teórica de la
regex).** El patrón puede capturar prefijos de identificadores que contengan
`Error`/`Violation` en medio (p.ej. `MyErrorTracker` → captura `MyError`). Es la misma
debilidad de greedy que debt-verify-1 ya declaró como H8; el campo es solo diagnóstico
(aparece en el receipt cuando `runId == null && stderr.isNotBlank()`), no controla
ningún guard de PASS/FAIL. No bloquea.

### H9-DOC (apply-fix-2 3cf22d59) — CERRADO CORRECTAMENTE

**¿El cambio es honesto y completo? SÍ.**

- OBSERVED (esta sesión, re-ejecutado):
  `grep -n "apunta a los SHAs\|KDoc apunta" docs/v2/07-uat/XCA2_FIX_RECEIPT.md` → 0
  matches. La afirmación inexacta fue ELIMINADA.
- OBSERVED (esta sesión, re-ejecutado): `grep -rn "eb40d5cb\|7d725aca" v2/` → 0 matches
  (los SHAs solo aparecen en receipts bajo `docs/`, nunca en código bajo `v2/`).
  La nueva afirmación ("los SHAs reales no están en KDoc") es VERDADERA.
- No introduce ninguna afirmación nueva inexacta: el texto añadido reporta el grep
  OBSERVED con resultado 0, que esta sesión ha re-verificado.

**Nota**: H9 tiene dos caras en debt-verify-1 — la afirmación inexacta del doc (cerrada
aquí) y la ausencia del test separado del mutante (H9-deuda, sigue declarada abajo).

### 74b309dc (doc closure) — COHERENTE

- STRUCTURAL: docs-only, 62+/25− en `XCA2_FIX_VERIFY_RECEIPT.md` únicamente.
  Declaraciones del doc ("H1/H7/H9 CERRADO", "H5/H6/H9-deuda DECLARADAS, no tocadas")
  coinciden con la evidencia estructural de esta auditoría
  (`git diff --name-only 943e3769..74b309dc` no toca los ficheros de H5/H6).

---

## H2-H4 y H5/H6/H9-deuda (no tocadas por apply-fix-2)

### H2, H3, H4 (resueltas/OK en debt-verify-1)

Estado: sin cambios. El rango auditado no toca `pipeline-domain`, ni los canaries B.5/B.6
más allá del path de B.5, ni la democión de core.echo.

### H5 — guard débil en `reconcile()` sin `else` : SIGUE DECLARADA, intacta ✓

STRUCTURAL: el diff del rango no toca
`v2/pipeline-events/.../JournalRunExecutionEvidenceReaderInMemoryTest.kt`
(`git diff --name-only 943e3769..74b309dc` — verificado, exit sin match).

### H6 — vocabulario del ledger v2 : SIGUE DECLARADA, intacta ✓

STRUCTURAL: `v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml` no aparece en el
diff del rango. Su md5 actual es `db7360038a1b12a130cc0e1b5bc3b0ed`; la copia renombrada
mantiene su condición de NO autoridad.

### H9-deuda — mutante sin test separado : SIGUE DECLARADA, intacta ✓

STRUCTURAL: la falsificación del mutante sigue existiendo solo como par de commits git;
no se añadió ningún test dedicado en el rango. El receipt de apply ahora lo declara
honestamente en lugar de afirmar que el KDoc lo referencia.

**Apply-fix-2 respeta que NO se tocan: SÍ.**

---

## Búsqueda de nueva deuda

### Comentarios

OBSERVED:
```bash
grep -nE "TODO|FIXME|XXX|HACK" v2/pipeline-application/src/test/.../XcaCliCanaryTest.kt
grep -nE "TODO|FIXME|XXX|HACK" v2/pipeline-application/src/test/.../XcaCorpusRunTest.kt
```
0 matches en ambos. Los comentarios añadidos por los fixes son descriptivos del fix y de
su falsificación, no marcadores de deuda. Sin deuda nueva por comentarios.

### Tests sin assertion

OBSERVED: `grep -c "assert\|Assert" XcaCliCanaryTest.kt` = 19. Los guards B.5 contienen
`assertFalse` con mensajes de falsificación que identifican causa y path. B.5 es
ejecutable y falsificable (probado RED→GREEN por verify-2 con causa exacta). Sin deuda.

### Patrones que violan I1/I2/I3

- I1 (exit code ≠ evidencia): RESPECTADA. Los receipts de apply-fix-2 y verify-2 citan
  XML canary con timestamp; esta sesión re-verificó el XML del corpus
  (`tests="1" failures="0" errors="0" timestamp="2026-09-18T06:30:44.361Z"`, fresco).
- I2 (presencia en fuente ≠ evidencia): RESPECTADA. Los tres fixes van acompañados de
  ejecución observada, no solo de diff.
- I3 (expectativa del ledger ≠ evidencia): RESPECTADA. La falsificación de H1 mutó el
  ledger real y observó el rojo; el md5 restaurado coincide con la cadena de custodia.

### Nueva deuda declarada (menor, NO bloqueante)

```text
N1  XcaCliCanaryTest.kt (B.5): el guard sigue condicionado a `if (Files.exists(ledgerPath))`
    sin rama `else fail(...)`. Si en el futuro el path volviera a romperse o el fichero
    desapareciera, el guard sería de nuevo vacuously TRUE — exactamente la clase de falso
    verde que H1 destapó, ahora en su forma "fichero ausente". La falsificación actual es
    válida (el fichero existe y la mutación de contenido produce rojo), pero ML pide que
    el guard también detecte su propia imposibilidad de ejecutarse.
    Clase: misma familia que H5 (guard débil). Recomendación: FAIL si !Files.exists.
    Cuándo: en un ciclo posterior; no bloquea el cierre de XCA-2 porque la falsificación
    RED→GREEN observada demuestra que hoy el guard está vivo.

N2  XcaCorpusRunTest.kt (errorClass regex): sobre-captura teórica. El patrón
    `[A-Z][A-Za-z0-9_]*(?:Exception|Error|Violation)` captura prefijos de identificadores
    que contienen el sufijo en posición no final de palabra compuesta. Impacto: solo
    cosmético (anotación del receipt de corpus), no afecta guards ni PASS/FAIL.
    Misma familia que H8 ya declarada. Anotar, no bloquea.
```

---

## Cadena de custodia

### Autoridad canónica

OBSERVED:
```text
md5sum docs/v2/status/step-certification.yaml
b84fdf3c7ae7e9518701bbd9372bfedf  docs/v2/status/step-certification.yaml
```
Coincide con el digest esperado `b84fdf3c7ae7e9518701bbd9372bfedf`. **CANON_MATCH = true.**
La mutación de la falsificación H1 fue revertida correctamente.

### Copia renombrada (NO autoridad)

OBSERVED:
```text
md5sum v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml
db7360038a1b12a130cc0e1b5bc3b0ed  v2/docs/v2/status/step-certification-XCA2-EVIDENCE.yaml
```
No es referenciada por ningún test del rango (B.5 ahora resuelve al canónico). Mantiene
condición de NO autoridad, solo evidencia. **COPIA_NO_AUTORIDAD = true.**

### Envelope verify-2

STRUCTURAL: `docs/v2/07-uat/XCA2_FIX_VERIFY_2_RECEIPT.md` está bien formado: declara rol,
modelo testigo, base/HEAD, clases de evidencia por hallazgo, RED→GREEN con causa exacta,
md5 de restauración, y veredicto CONFIRMA. Pendiente de commitear por el orquestador
(untracked), lo cual está fuera del alcance de este debt-verify.

---

## Resumen ejecutivo

| Categoría | Estado |
|---|---|
| H1 cerrado correctamente | SÍ (falsificación RED→GREEN con causa exacta; md5 canónico verificado) |
| H7 cerrado correctamente | SÍ (regex RED/GREEN + corpus real con XML fresco) |
| H9-DOC cerrado correctamente | SÍ (afirmación inexacta eliminada; nueva afirmación re-verificada) |
| H5/H6/H9-deuda intactas | SÍ (no aparecen en el diff del rango) |
| Nueva deuda introducida | SÍ, menor: N1 (guard B.5 sin else-fail sobre Files.exists), N2 (sobre-captura teórica regex, solo cosmético) |
| I1/I2/I3 respetadas | SÍ (XML canary citado y re-verificado; falsificaciones reales; custodia intacta) |
| Cadena de custodia | INTACTA (canon md5 match; copia NO autoridad) |

**Veredicto: PASS** (con N1/N2 declaradas como deuda menor para un ciclo posterior; ninguna
afecta la decisión de release del operador).

---

## Próximo paso

PASS → release (push + tag; preguntar nombre del tag al operador).
La deuda N1/N2 se incorpora al ledger de deuda declarada en el cierre del ciclo.
