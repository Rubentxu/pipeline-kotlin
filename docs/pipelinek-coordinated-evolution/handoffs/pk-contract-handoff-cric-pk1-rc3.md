# PK_CONTRACT_HANDOFF — CRIC-PK1 (v0.56.0-rc3) — closure

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — PK-1 closure (governance committed + binary packaged)**.
> La candidata `v0.56.0-rc3` cierra el bloque PK-1 con la deuda técnica de governance
> liquidada. `v0.56.0-rc1` y `v0.56.0-rc2` se preservan como Prereleases históricas;
> **`v0.56.0-rc3` es la candidata canónica final para `PAIR_CERTIFIED` con Fabric**.

---

## Resumen ejecutivo

`v0.56.0-rc3` cierra el **bloque PK-1** sobre `v0.56.0-rc2`:

1. **Governance framework versionado.** El `docs/pipelinek-coordinated-evolution/`
   que vivía untracked en el worktree principal ahora está comprometido y forma parte
   de la historia del proyecto. Cualquier clon de la release branch ve el toolkit
   completo de coordinación cross-repo.
2. **Binario equivalente a `v0.56.0-rc2`.** El commit governance es docs-only;
   el ZIP rc3 contiene los mismos JARs que rc2 (modulo timestamps de zip). El
   candidate_id (sha256 del ZIP) identifica al artefacto, no al commit fuente.

**Cambio de capability:** ninguno. Las 11 capabilities vigentes mantienen su estado.

**Cambio de artefacto canónico:** `v0.56.0-rc3` reemplaza a `v0.56.0-rc2` como la
candidata canónica final para `PAIR_CERTIFIED`.

---

## SHA commit PK / branch / tag

- **Commit de build:** `4d5e8e95` (HEAD de `release/pk1-contract-v0.56.0-rc1` al construir el ZIP rc3).
- **Branch:** `release/pk1-contract-v0.56.0-rc1`.
- **Tag candidato:** `v0.56.0-rc3` (anotado, peel = `4d5e8e95`).
- **Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc3.

## Artifact PK inmutable

- **Asset principal:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 (asset re-descargado):** `78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.
- **`candidate_id`:** `sha256:78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.
- **5 SDK coordinates** a `0.56.0` (idénticas a rc1+rc2):
  - `pipeline-domain-0.56.0.{jar,pom,module}`
  - `pipeline-events-0.56.0.{jar,pom,module}`
  - `pipeline-output-0.56.0.{jar,pom,module}`
  - `pipeline-runtime-api-0.56.0.{jar,pom,module}` (M6+M7 surface)
  - `pipeline-scripting-api-0.56.0.{jar,pom,module}`
- **SDK BOM:** `pipeline-sdk-bom-0.56.0.{pom}`.

## Capacidades vigentes (11) — sin cambios

| Capability ID | Publicada en | Estado |
|---|---|---|
| `output.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** |
| `events.follow.v1` | `v0.49.0-rc1` | **PUBLICADA** |
| `runtime.inspect.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.cancel.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `runtime.recover.v1` | `v0.50.0-rc1` | **EXPERIMENTAL** |
| `output.read.digested.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.pin.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `output.refusal.retention.v1` | `v0.51.0-rc1` | **EXPERIMENTAL** |
| `runtime.structure.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.operation.v1` | `v0.53.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |
| `runtime.eventshape.v1` | `v0.54.0-rc1` / re-affirmed `v0.55.0-rc1` | **EXPERIMENTAL** |

## Governance versionado (delta sobre rc2)

`v0.56.0-rc3` lleva en el ZIP indirectamente (vía release branch) la
disponibilidad de los siguientes documentos versionados que NO estaban en rc1+rc2:

- `docs/pipelinek-coordinated-evolution/PACKAGE_MANIFEST.json`
- `docs/pipelinek-coordinated-evolution/coordination/{BASELINE-PINS,FAB_CONSUMER_VERDICT.template,GATE_OPERATIONS,GATE_STATUS.template,MILESTONE_MATRIX,PAIR-RECEIPT.schema.json,PAIR-RECEIPT.template.json,PAIR_RELEASE_FLOW,PK_CONTRACT_HANDOFF.template,REQUIRED_GITHUB_PROTECTION}.md/json` + `{gate-policy,milestone-required-evidence}.json` + `{pair_gate_selftest,verify_pair_gate}.py`
- `docs/pipelinek-coordinated-evolution/acceptance/{UAT,AAT,FITNESS}.md`
- `docs/pipelinek-coordinated-evolution/adrs/ADR-{01-RUNTIME-BOUNDARY,02-DURABLE-OBSERVATION,07-ADAPTIVE-PRESSURE,09-SECURITY-OTEL}.md`
- `docs/pipelinek-coordinated-evolution/agent/PIPELINEK-AGENT-HANDOFF.md`
- `docs/pipelinek-coordinated-evolution/reference/{ARCHITECTURE,BASELINE,DECISIONS,RELEASE-PROTOCOL,REPO-OWNERSHIP,SOURCES}.md` + `contracts/{CONTRACTS,WIRE-AND-MIGRATION}.md`
- `docs/pipelinek-coordinated-evolution/roadmap/PIPELINEK-ROADMAP.md`
- `docs/pipelinek-coordinated-evolution/specifications/{SPEC-01-PIPELINEK-LIVE-PLANES,SPEC-08-SECURITY-TELEMETRY,PK-SPEC-02-RUNTIME-OBSERVATION,PK-SPEC-03-LOCAL-PRESSURE}.md`

## Petición a Fabric

1. **Componer la release PK-1 contra el artefacto `v0.56.0-rc3`** (no contra rc1 ni rc2; esta es la candidata canónica final).
2. **Correr la batería completa** (los 5 casos del consumer + cualquier test PK-1 propio de Fabric) contra el artefacto publicado.
3. **Verificar el `--correlation-id` end-to-end** invocando `pipeline run --correlation-id <uuid>` y parseando la línea `pipelinek.launch/1` de stdout.
4. **Verificar que `docs/pipelinek-coordinated-evolution/coordination/MILESTONE_MATRIX.md` y `verify_pair_gate.py` están versionados** (distinguen rc3 de rc1/rc2; gating de governance).
5. **No dar por superadas pruebas no ejecutadas o condicionadas por infraestructura ausente** (regla del plan).
6. **Emitir el `release-receipt` del candidato Fabric PK-1** contra el artefacto PK `v0.56.0-rc3`. El par se considera `PAIR_CERTIFIED` solo cuando ambos releases estén verificados.
7. **Conservar el artefacto publicado y verificado.** La promoción GA es un cambio de tabla, no de binario; ocurre tras `CERTIFIED`.

## Pruebas cruzadas que esta release tiene

- `Pk1ContractTypesTest` (8 casos) — verde in-monorepo.
- `Pk1ContractSurfaceTest` (5 casos) — **5/5 verde** en consumer extension.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos certificadores, todos verdes en CI sobre la imagen reconstruida desde el ZIP `v0.56.0-rc3`.

## Estado del chain CRIC-1 + PK-1

```
M1 (v0.49.0-rc1):   2 PUBLICADA        ✓
M2 (v0.50.0-rc1):   +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):   +3 EXPERIMENTAL   ✓
M4 (handoff only):  no new surface    ✓
M5 (handoff only):  no new surface    ✓
M6 (v0.53.0-rc1):   +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):   +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective         ✓
PK-1 (v0.56.0-rc1): foundation         ✓
PK-1 (v0.56.0-rc2): completion         ✓
PK-1 (v0.56.0-rc3): closure           ✓ (governance committed + binary packaged)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, 2 new subcommands
```

## Próximo paso (post-PK-1)

Tras `CERTIFIED` por `pipelinek-release-harness` y la aceptación por Fabric de su
PK-1 release contra el artefacto `v0.56.0-rc3`, el Integrator emite el
`PAIR_CERTIFIED` final. La cadena CRIC-1 + PK-1 queda entonces **completa**:

- 11 capabilities publicadas (2 PUBLICADA + 9 EXPERIMENTAL)
- 0 PARTIAL rows
- N/N-1 compatibility documentada
- Negative-scope (F.4 / F.5) preservada
- Cross-repo seams endurecidas vía B.1 / B.2 / B.3
- M6 surface correctamente colocada en `:pipeline-runtime-api`
- 3 nuevas superficies de contrato: `CorrelationId`, `LaunchReceipt`, `CliExit`
- 2 nuevas superficies machine-verifiable: `pipeline capabilities`, `pipeline sdk-info`
- `--correlation-id` propagado al durable state + emitido en `RunCorrelated` + visible en `LaunchReceipt`
- Governance framework versionado y verificable

La promoción `EXPERIMENTAL → PUBLICADA` ocurre en un release posterior, una vez que el certifier haya revisado la batería completa. Esa promoción es un cambio de tabla, no de binario.

---

## Auditoría y firma

- **ADR PK-1:** `docs/pipelinek-coordinated-evolution/pk1-design/ADR-PK1-IDENTITY.md`.
- **PK-1-Impl (rc3):** 8 commits sobre `release/pk1-contract-v0.56.0-rc1` (T1-T4, T7-T8, T9-T11, T11, T12-T13, T14-T18, T19 governance).
- **Handoff emitido por:** PK release maintainer (autoridad del release-receipt `v0.56.0-rc3`).
- **Candidate ID:** `sha256:78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.
- **Candidatas previas:** `v0.56.0-rc1` (foundation) y `v0.56.0-rc2` (completion) preservadas; `v0.56.0-rc3` (closure) es la canónica final.
