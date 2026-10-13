# PipelineK — Roadmap R0–R4 (operador 2026-10-13)

**Baseline:** `origin/main@b4400b58` (T1 audit corregido)
**Última estable:** `v0.56.0` · **Última candidata:** `v0.57.0-rc1` (con integridad rota — F5)
**WorkItem SDDK:** `93158a45-2997-49ff-9a85-7906c56344c6`

## Principio

- Reutilizar código existente.
- Resolver primero las propiedades incorrectas (no añadir las correctas
  encima).
- Entregar exclusivamente RC verificadas.
- Ramas `par/cli-observation` y `feat/cric-m1-output-events-live` están
  completamente contenidas en `main`; no reabrir ni re-fusionar.

## R0 — Recuperar certificación y release íntegra (PRIORIDAD INMEDIATA)

**Problema:** `v0.57.0-rc1` se publicó por excepción al full gate
(operator override). El body del release contiene 6/9 SHA-256
discrepantes con los assets; `git-head.txt` apunta al evidence commit
(`f6eb1ebb`), no al tag commit (`51f654e3`); 2 assets extra
(`pipelinek-0.57.0.sdk.zip`, `pipelinek-bom-0.57.0.json`) no están en
`SHA256SUMS`.

**Tareas:**
- [ ] **R0.1** Diagnosticar el cuelgue de `:pipeline-application:test`
  bajo carga. Reproducir en entorno controlado (load < 5 o
  workaround explícito). Si el diagnóstico revela defecto de
  producción, reparar; si es ambiental, documentar y proceder.
- [ ] **R0.2** Contrastar ZIP/SBOM/SHA256SUMS con los assets de
  GitHub. Reemplazar el body del release con los SHA-256 reales;
  eliminar los 2 assets extra o añadirlos a `SHA256SUMS`;
  reescribir `git-head.txt` con el commit del tag.
- [ ] **R0.3** UAT del binario instalado contra `v0.57.0-rc1` extraído
  del ZIP. Confirmar identidad, manifest, ABI, SHA, BOM, módulos,
  plugins. Suite UAT-PK existente donde aplique.
- [ ] **R0.4** Ejecutar el certifier independiente sobre
  exactamente el `candidate_id`. Reflejar `NOT_RUN` / `FAIL` como
  bloqueo. Si el certifier falla, NO promover.
- [ ] **R0.5** Si la corrección de integridad invalida el tag
  `v0.57.0-rc1`, abrir **`v0.57.0-rc2`** con artefactos y trazabilidad
  verificada. No re-tagear `v0.57.0-rc1`; añadir nota de release
  con la corrección.
- [ ] **R0.6** PR, merge autorizado a `main`, tag inmutable,
  GitHub prerelease verificada.

**Entrega:** `v0.57.0-rc2` (o el tag que decida el operador) con
certificación externa firmada.

**Refs:** `odd/tasks/pk-57A.md`, `evidence/0-57-rc1/STATUS.md`,
`docs/v2/07-uat/CERTIFICATION_PROTOCOL.md`,
`docs/v2/08-production-readiness/PRODUCTION_READY_GATE.md`,
`docs/v2/07-uat/PK-58A-ports-audit.md` (matriz F1–F6).

## R1 — Live Follow real, recuperable y coherente

**Problema:** `SqliteEventFollowHandle.handlePage` emite `Completed`
con `hasMore=false` aunque no exista `RunFinished`. El cross-JVM test
funciona porque escribe eventos proactivamente.

**Tareas:**
- [ ] **R1.1** Fix `handlePage`: reservar `Completed` para
  `UntilSequence` satisfecho o `RunFinished` observado. El estado
  natural-tail sin `RunFinished` debe ser distinguible del terminal.
- [ ] **R1.2** Falsificadores:
  - open follow → reach tail → producer publica después, NO `Completed` terminal
  - stream `Open` vs `Sealed`
  - run terminado con output todavía pendiente de sellado
  - lectura desde otra JVM después de un poll vacío
  - reabrir con `after = nextCursor` tras un `Completed` espurio
- [ ] **R1.3** Extender `M1DCrossJvmFollowTest` con esos casos (sin
  duplicar los existentes).
- [ ] **R1.4** Diferenciar snapshot, follow vivo, `Sealed`, EOF,
  cancelación en el contrato del handle.
- [ ] **R1.5** Reconciliar IDs UAT/AAT con la matriz existente; no
  duplicar puertos.
- [ ] **R1.6** ReleaseGate end-to-end verde, ABI/conformance SDK
  documentada, handoff a Fabric.

**Entrega:** `v0.58.0-rc1`.

**Refs:** `SPEC-01-PIPELINEK-LIVE-PLANES.md` (PK-L1/L2/L3/L6),
`m1-design/M1_FOLLOW_DESIGN.md` (§2, §4, §5, §10),
`PK-SPEC-02-RUNTIME-OBSERVATION.md`.

## R2 — OBS-2: supervivencia real de la ejecución

**Problema:** `ObsPc2IngestAgent` solo en prototipo de test (no en
producción). `OBS-PC-208` con `SIGKILL` real aún no ejecutado.

**Tareas:**
- [ ] **R2.1** Decidir §4 de `ADR-OBS-003-child-output-descriptor-ownership.md`
  (contrato OBS-2 Nivel B: descriptores del hijo, redacción en memoria
  antes de persistir, lifecycle/recovery, sin secretos crudos en disco).
- [ ] **R2.2** Mover `ObsPc2IngestAgent` (o sucesor) a
  `v2/pipeline-application/src/main/`.
- [ ] **R2.3** Implementar y medir con SIGKILL real (pérdida de JVM,
  agente y lector). Falsificador reproducible.
- [ ] **R2.4** ReleaseGate verde.

**Entrega:** `v0.59.0-rc1` con garantías de continuidad demostradas.

**Refs:** `ADR-OBS-003-child-output-descriptor-ownership.md` (§2 y §4),
`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-2), `SPEC-01` (PK-L5/L6).

## R3 — OBS-3: índice de frames y rendimiento escalable

**Problema:** `SegmentFrameIndex.framesOfRun` y `append/sealTornTail`
hacen O(n) read del log completo. Sin benchmarks, sin presupuesto.

**Tareas:**
- [ ] **R3.1** Measurement harness: 200 MiB – 1 GiB, lectores
  lentos, heap/RSS, CPU, FD, latencia p95; preservar recovery y
  ordinals entre JVM.
- [ ] **R3.2** Decidir si reescribir el índice (B-tree durable,
  checkpoints/seek) o aceptar O(n) actual con presupuesto explícito.
- [ ] **R3.3** Si la medición revela regresión, reescribir; si no,
  documentar el presupuesto.
- [ ] **R3.4** Decisión sobre wakeups **solo después** de medir el
  polling ya optimizado. NO abrir `CommitWakeupPort` por defecto.
- [ ] **R3.5** ReleaseGate verde.

**Entrega:** `v0.60.0-rc1`.

**Refs:** `SPEC-01` (PK-L3/L4), `PERFORMANCE_BUDGETS.md`,
`OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-3/PC-04/PC-05).

## R4 — OBS-4/5/6/7: CLI completa e integración de consumidor

**Tareas:**
- [ ] **R4.1** Consolidar `observe`, `console`, `events` e `inspect`
  con filtros run/stage/step, JSONL puro, contexto acotado, UTF-8
  incremental y refusals tipados.
- [ ] **R4.2** Sin inventar orden total Event/Output.
- [ ] **R4.3** Certificar CLI, SDK y Fabric contra los mismos
  artefactos.
- [ ] **R4.4** Ejecutar el gate de pareja (`PAIR_RECEIPT`).
- [ ] **R4.5** ReleaseGate verde.

**Entrega:** `v0.61.0-rc1`, PR integrado, `PK_CONTRACT_HANDOFF`,
veredicto de Fabric y `PAIR_RECEIPT` independiente.

**Refs:** `CLI_OBSERVABILITY_SPEC.md`, `ADR-0088-cli-observation-contract.md`,
`SPEC-01` (PK-L7), `OBS_PROGRESSIVE_CONSOLE_ROADMAP.md` (OBS-4..7),
`coordination/PAIR_RELEASE_FLOW.md`.

## Gate obligatorio (todos los bloques)

- Commits atómicos Conventional Commits.
- Pruebas focales durante desarrollo.
- Suite integral/arquitectura/UAT/AAT ejecutadas en el mismo candidato.
- Comprobación de ABI.
- ZIP + SDK + SBOM + SHA256SUMS.
- PR, merge autorizado a `origin/main`.
- Tag inmutable y GitHub prerelease verificada.

**Gate rojo, hang no diagnosticado, test obligatorio omitido o
evidencia incompleta → BLOCKED, sin publicar la RC como aprobada.**

## No abrir

- `CommitWakeupPort`: no hay beneficio medido y el polling sigue
  trabajando sobre un índice costoso (R3 decidirá).
- Nuevos readers, stores o DSL de observación: ya existen.
- RP-8/RP-9, Jenkins, Kubernetes, gRPC o control plane dentro de
  PipelineK: responsabilidad de Fabric.
- RP7-ASX, nuevos Steps y expansión de plugins: subordinados a sus
  propias dependencias y gates.
