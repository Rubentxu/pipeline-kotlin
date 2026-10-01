# 11 — Prompt operativo para que un agente adopte el evolutivo

Usar después de integrar estos documentos y ADRs en el repo.

```text
Modo: ejecución autónoma, incremental y fail-closed.

Objetivo activo: adoptar WU-RP-034 Workspace & Execution Location Semantic
Remediation según los documentos integrados y la autoridad actual del ROADMAP.

Reglas:
1. Antes de tocar producción, reconcilia SDDK + Git + ROADMAP + ADR-0100..0102
   y ejecuta RP034-A de caracterización. No confíes en receipts antiguos como
   estado actual; úsalos sólo como evidencia histórica.
2. No hagas big-bang. Sigue RP034-A → B → C → D → E → F → G → H → I.
3. Workspace root, cwd y control root son autoridades distintas:
   - workspace.root es estable;
   - cwd es no-null y comienza en root;
   - dir() deriva sólo cwd;
   - controlRoot nunca resuelve inputs de usuario.
4. No introduzcas nuevos usos de System.user.dir, chdir global, mutable cwd,
   WorkspaceResolver dentro de nuevos handlers, ni when(stepKey) central.
5. Mantén `--workspace <path>` compatible. Añade/preserva `--isolated` como el
   comportamiento scratch explícito. No cambies el default del CLI hasta que
   RP034-G (destructive safety) esté GREEN.
6. Attached workspace es user-owned: root deleteDir/cleanWs falla por defecto.
   Managed workspace es PipelineK-owned y puede seguir lifecycle cleanup.
7. Para archiveArtifacts/publishHTML y cualquier Step marcado
   DIFFERENTIAL_REQUIRED, no adivines: ejecuta la caracterización Jenkins
   definida antes de modificar semantics.
8. Cada slice: RED discriminante → mínimo cambio de producción → tests
   quirúrgicos → receipt. Full suite/corpus/distribución sólo en integración/
   release según el protocolo vigente.
9. Commits atómicos Conventional Commits. No mezcles limpieza documental,
   nuevos Steps ni refactors no necesarios con esta WU.
10. Un slice no está DONE por compilar: debe cumplir sus exit criteria y dejar
    evidencia sobre el SHA exacto.
11. Si una decisión contradice un ADR aceptado, detente en ese fork y registra
    la decisión necesaria; no escondas la contradicción en un adapter.
12. Al final, el canario principal debe demostrar:
      cd proyecto && pipelinek run pipeline.kts
    sin `--workspace`, y un canario separado debe demostrar `--isolated`.

Primer trabajo concreto:
- regenerar inventario de consumidores de WorkspaceResolver/workspaceBase/
  ShOptions.workspaceRoot/ShOptions.workingDirectory/WorkspaceIdentity;
- crear los REDs de RP034-A;
- producir el receipt de caracterización;
- sólo después iniciar ADR-0100 apply.
```
