# WU-RP-053R C0 — Evidence Integrity Receipt

**Cycle:** WU-RP-053R (Execution Context & Workspace Semantics)
**Phase:** C0 — Evidence integrity
**Date:** 2026-09-25T11:14Z
**Authority:** orchestrator-direct
**SHA evidence:** `acc903875d70f939713786d71a6331bb6ccf7dc9` (clean main)

## Resumen ejecutivo

La discrepancia histórica **`./gradlew -p v2 check` reporta 3187 tests / 41 failures / 0 errors / 121 skipped sobre `acc90387`** queda **CERRADA como `EXPLAINED_BY_WORKTREE_CONTAMINATION`** mediante evidencia quirúrgica sobre el árbol restaurado. Las 6 clases del antiguo failure-set (10 tests fallidos históricos según WORK_JOURNAL) pasan **62/62** cuando el árbol está limpio.

No se ha ejecutado L5 completo. La base material para WU-RP-053R C0–C2 queda establecida.

## Material identity snapshot

**BEFORE** de la ejecución C0-bis:

```text
[1] git rev-parse HEAD:
eb604c60a4f92e02c31e0bad75aa5bf0dbaacf76   ← working branch al inicio
acc903875d70f939713786d71a6331bb6ccf7dc9   ← main, donde se ejecutó C0-bis

[2] git status --porcelain=v1 (en acc90387):
 M .agent/SESSION_POINTER.md       ← gitignored, no contamination
 M .agent/TECH_DEBT_BACKLOG.md     ← gitignored, no contamination
 M .agent/WORK_JOURNAL.md          ← gitignored, no contamination

[3] git diff --exit-code: exit 0 (sin tracked differences)

[4] git ls-files -d v2/compatibility:
(output count: 0)                  ← 0 tracked deletions, todos los fixtures presentes

[5] ls v2/compatibility/*.pipeline.kts | wc -l:
31                                 ← 31 fixtures presentes (cumple ≥30)
```

**AFTER** de la ejecución C0-bis (idéntico snapshot; ninguna mutación de tracked files):

```text
git rev-parse HEAD: acc90387
git status --porcelain=v1: idéntico (sólo .agent/* gitignored)
git diff --exit-code: exit 0
git ls-files -d v2/compatibility: 0 deletions
```

El baseline estático es estable. Cualquier fallo durante C0-bis es atribuible al código, no al ambiente.

## C0-bis — 6 clases del antiguo failure-set

Comando ejecutado:

```bash
cd v2 && timeout 1200 ./gradlew :pipeline-application:test \
  --tests 'UatLocal007SandboxProfileTest' \
  --tests 'UatLocal008CredentialsTest' \
  --tests 'UatLocal011WorkflowControlTest' \
  --tests 'UatCompat001CorpusSmokeRunTest' \
  --tests 'UatLocal005CorpusUntouchedTest' \
  --tests 'Lfc2WaitUntilCanonicalReentryFitnessTest' \
  --rerun-tasks
```

Resultado: **BUILD SUCCESSFUL in 9m 4s**, exit 0.

| Clase | Tests | Failures | Errors | Skipped | XML canary SHA-256 |
|-------|-------|----------|--------|---------|--------------------|
| `UatLocal007SandboxProfileTest` | 14 | 0 | 0 | 0 | `026c37d60465fcf5356148c662d19557b1ca9d631e3dc85c8c96db0fc55c3c3c` |
| `UatLocal008CredentialsTest` | 27 | 0 | 0 | 1 | `d1b0404be4cc06e77ec0d66c67aa9b3d2b9bdd2dd1621ca559a5f4d74729a644` |
| `UatLocal011WorkflowControlTest` | 13 | 0 | 0 | 1 | `2b04f1756c91e7b56daa16e7b44d86876c713cf0b3dac4d8b62fd4b925d59a9b` |
| `UatCompat001CorpusSmokeRunTest` | 2 | 0 | 0 | 0 | `ef55d99307660ae4a7c1e6887d0ae88e9a3972cec01e9c955cd539833f97fd6c` |
| `UatLocal005CorpusUntouchedTest` | 2 | 0 | 0 | 0 | `874d678750525579b3974b51e8a8b668533eca1b4330dfd22be47a91f40378fe` |
| `Lfc2WaitUntilCanonicalReentryFitnessTest` | 4 | 0 | 0 | 0 | `842872c2e416486a1aadcdf53a8b567391e69ab8fd9217c916551413a9b0a8a6` |
| **TOTAL** | **62** | **0** | **0** | **2** | 6 canaries |

**Worktree reference:** `v2/pipeline-application/build/test-results/test/TEST-*.xml` (XML recién generados 2026-09-25T11:13Z, timestamp interno confirma `--rerun-tasks` efectivo).

## Clasificación del antiguo L5

Reclasificamos el L5 histórico:

```text
L5_2026-09-24 (subsecuentes runs sobre acc90387):
  SHA: acc90387
  working_tree: CONTAMINATED
  deleted compatibility fixtures: 33 (según WORK_JOURNAL histórico)
  suitable_as_clean-main-baseline: NO
```

**No se reescribe el recibo histórico.** Se añade esta corrección como anexo retroactivo, preservando la trazabilidad original.

## Estado de la discrepancia 41-vs-0

```text
ESTADO: CERRADA
CLASIFICACIÓN: EXPLAINED_BY_WORKTREE_CONTAMINATION
EVIDENCIA: 62/62 tests verdes sobre acc90387 limpio (XML canaries arriba)
GASTO COMPUTACIONAL: 9m 4s (vs 977s del L5 completo evitado)
```

Hipótesis explícita: **mismo Git SHA ≠ mismos inputs materiales.** El L5 histórico tenía `v2/compatibility/` parcialmente borrado en working tree, lo que producía errores en clases que dependían de fixtures para inicializar (`UatCompat001`, `UatLocal005`, etc.). Estos fixtures se restauraron tras el run; el árbol actual es representativo.

**Lección durable para futuras ejecuciones L5:** capturar siempre la material identity completa antes del run:

```text
commit SHA
git status --porcelain=v1
git diff --exit-code
git ls-files -d <paths críticos>
fixture inventory / hash
argv
XML hash por clase
```

Esta identidad material es ahora un requisito C0 de cualquier futura caracterización (reflejado en SESSION_POINTER y WORK_JOURNAL).

## Estado del ciclo WU-RP-053R

- **C0 evidence integrity:** CERRADO (este recibo).
- **C1 reference semantics:** PENDIENTE.
- **C2 REDs discriminantes:** PENDIENTE.
- **C3 context model:** BLOQUEADO hasta C1+C2.
- **C4 filesystem vertical:** BLOQUEADO.
- **C5 block/parallel/durable:** BLOQUEADO.
- **C6 binary:** BLOQUEADO.
- **C7 harness:** BLOQUEADO.
- **C8 L5 final:** BLOQUEADO.

## Decisiones pendientes del operador (no auto-go)

- **D-001 cherry-pick:** NO GO. Hexagonal sigue en duda hasta próxima revisión.
- **D-002 cherry-pick:** HOLD. Espera a que se cierre la caracterización base.
- **Apertura C1:** GO del operador implícito en este turno (autorización fue "GO caracterización/diseño, no producción").
- **Apertura C3+:** requiere cierre formal de C1+C2 con recibo.

## Trabajo NO ejecutado este turno

- L5 completo (descartado por gasto computacional injustificado dada la explicación material).
- Cherry-pick de cualquier rama.
- Modificación a código de producción (regla C0–C2 = read-only).
- Caracterización C1/C2 (será próximo paso si el operador lo confirma).

## Próximo paso propuesto

Avanzar a **C1 reference semantics** (sin tocar producción):

1. Construir tabla `Step → PathAnchor` (CURRENT_DIRECTORY / WORKSPACE_ROOT / CONTROL_ROOT / por caracterizar).
2. Para Steps documentados en Jenkins (`sh`, `pwd`, `readFile`, `writeFile`, `fileExists`, `stash`, `unstash`, `cleanWs`), congelar la fila con referencia.
3. Para Steps ambiguos (`archiveArtifacts`, `publishHTML`, `deleteDir`, `checkout`), abrir issue de caracterización diferencial Jenkins-vs-código actual antes de asignar fila.
4. No escribir código nuevo. Sólo tabla + referencias.

Comando de inicio C1 (sin Gradle, sólo lectura):

```bash
# Inventario de Steps que tocan filesystem:
grep -rln "Files\." v2/pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/ \
  --include="*Step.kt" --include="*OperationsAdapter.kt" \
  | xargs grep -l "PosixFile\|Path\|cwd\|workspaceRoot"
```
