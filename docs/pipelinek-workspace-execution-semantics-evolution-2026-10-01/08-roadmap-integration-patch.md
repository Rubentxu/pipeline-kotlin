# 08 — Patch propuesto para `docs/v2/05-roadmap/ROADMAP.md`

## 1. Regla de integración

No reescribir receipts históricos ni afirmar que RP-3 nunca cerró. Añadir la WU como **remediación post-exit** disparada por evidencia posterior.

También evitar limpiar en esta misma PR texto histórico no relacionado (por ejemplo menciones de GitHub Actions que puedan haber quedado supersedidas por decisiones posteriores). Scope estricto.

## 2. Inserción propuesta al final de RP-3

```markdown
### WU-RP-034 — Workspace & Execution Location Semantic Remediation (post-exit corrective WU)

**Trigger:** el dogfooding posterior a RP-3 demostró que `pipelinek run` sin
`--workspace` ejecuta proyectos locales en un scratch temporal donde no existen
los paths del checkout (`c3026a72`, `46eea758`). La caracterización previa
WU-RP-053R también mostró coexistencia de WORKSPACE_ROOT/CURRENT_DIRECTORY y
consumidores con anchors diferentes.

**Objetivo:** establecer una única semántica tipada para workspace root, current
working directory y control root; hacer local-first el default del CLI sin
perder el modo scratch Jenkins-like.

**Decisiones objetivo:**
- `WorkspaceLease` distingue Attached(user-owned) de Managed(PipelineK-owned).
- `ExecutionLocation = workspace + cwd`; `cwd` es no-null y comienza en root.
- `dir(...)` deriva sólo cwd; no redefine workspace root.
- `controlRoot` nunca resuelve rutas del usuario.
- no-flag `pipelinek run` => attach invocation directory.
- `--workspace <path>` => attach explícito (compatible).
- `--isolated` => scratch gestionado (comportamiento histórico explícito).
- `--workspace` y `--isolated` son mutuamente excluyentes.
- filesystem Steps resuelven mediante PathAnchor + ExecutionLocation.
- workspaces Attached protegen su root contra `deleteDir/cleanWs` por defecto.

**Secuencia obligatoria:**
A characterization-only → B ADTs puros → C runtime capability bridge →
D core cwd Steps → E consumers externos/utilities → F root/differential Steps →
G destructive safety → H CLI default flip → I cleanup/certificación.

**Exit:** distribución instalada demuestra Gradle/Maven/Node + self-hosting sin
`--workspace`, `--workspace .` conserva compatibilidad, `--isolated` conserva
scratch, nested `dir` es coherente entre sh/pwd/files/stash/plugins, attached root
no puede destruirse por default, fresh/replay/concurrency/architecture/full gate
verdes en el mismo SHA.

**Gate:** esta WU reabre únicamente la semántica de workspace necesaria para
certificación futura; no invalida RP3_EXIT_REVIEW en su SHA. No certificar una
nueva candidata local-first hasta cerrar WU-RP-034.
```

## 3. Añadido a RP-4 / dogfooding

Incorporar al criterio de self-hosting:

```markdown
- El canario principal de proyecto local MUST ejecutar el pipeline raíz sin
  `--workspace`; añadir un canario separado con `--isolated`. Usar
  `--workspace .` únicamente como compatibility witness, no como requisito para
  que el self-hosted pipeline encuentre su propio checkout.
```

## 4. Añadido al gate RP-5

```markdown
- Workspace semantics gate: attached-default, explicit-workspace e isolated
  certificados en la misma candidata; ningún root Attached es destructible por
  `deleteDir/cleanWs` sin una policy explícita; no existen autoridades activas
  divergentes para workspace root/cwd/control root.
```

## 5. Trazabilidad

| Roadmap item | Spec/ADR |
|---|---|
| WU-RP-034 model | ADR-0100 |
| CLI modes | ADR-0101 |
| path/security | ADR-0102 |
| Step semantics | `04-step-path-anchor-matrix.md` |
| implementation | `05-migration-plan-wu-rp-034.md` |
| exit | `06-uat-certification-matrix.md` |
