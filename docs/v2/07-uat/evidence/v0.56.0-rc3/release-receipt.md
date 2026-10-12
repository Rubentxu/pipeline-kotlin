# PipelineK v0.56.0-rc3 — release-candidate receipt

**Estado:** `CANDIDATE_PUBLISHED` — **release correctiva final** sobre `v0.56.0-rc2` que cierra la deuda técnica de governance y deja PK-1 listo para `CERTIFIED` + `PAIR_CERTIFIED`.

> **Origen.** `v0.56.0-rc1` cubrió T1-T4, T7-T8, T11, T14-T18 (foundation de PK-1). `v0.56.0-rc2` añadió T9-T11 + T12-T13 (propagación durable de correlación + consumer extension). `v0.56.0-rc3` cierra la **deuda técnica de governance**: el framework `docs/pipelinek-coordinated-evolution/` que vivía untracked en el worktree principal ahora está comprometido y versionado. **Sin reescribir las tags `v0.56.0-rc1` ni `v0.56.0-rc2`**; `v0.56.0-rc3` es la candidata canónica final para `CERTIFIED`.

## Cambios sobre la candidata anterior (`v0.56.0-rc2` → `v0.56.0-rc3`)

1. **Governance framework comprometido (commit `4d5e8e95`).** 36 archivos / 2120 inserciones
   dentro de `docs/pipelinek-coordinated-evolution/{coordination,acceptance,adrs,agent,reference,roadmap,specifications}/` + `PACKAGE_MANIFEST.json`. Documentos incluidos:
   - `coordination/`: `BASELINE-PINS.md`, `FAB_CONSUMER_VERDICT.template.md`, `GATE_OPERATIONS.md`, `GATE_STATUS.template.md`, `MILESTONE_MATRIX.md`, `PAIR-RECEIPT.{schema.json,template.json}`, `PAIR_RELEASE_FLOW.md`, `PK_CONTRACT_HANDOFF.template.md`, `REQUIRED_GITHUB_PROTECTION.md`, `gate-policy.json`, `milestone-required-evidence.json`, `pair_gate_selftest.py`, `verify_pair_gate.py`.
   - `acceptance/`: `UAT.md`, `AAT.md`, `FITNESS.md`.
   - `adrs/`: `ADR-01-RUNTIME-BOUNDARY.md`, `ADR-02-DURABLE-OBSERVATION.md`, `ADR-07-ADAPTIVE-PRESSURE.md`, `ADR-09-SECURITY-OTEL.md`.
   - `agent/PIPELINEK-AGENT-HANDOFF.md`.
   - `reference/`: `ARCHITECTURE.md`, `BASELINE.md`, `DECISIONS.md`, `RELEASE-PROTOCOL.md`, `REPO-OWNERSHIP.md`, `SOURCES.md`, `contracts/CONTRACTS.md`, `contracts/WIRE-AND-MIGRATION.md`.
   - `roadmap/PIPELINEK-ROADMAP.md`.
   - `specifications/`: `SPEC-01-PIPELINEK-LIVE-PLANES.md`, `SPEC-08-SECURITY-TELEMETRY.md`, `PK-SPEC-02-RUNTIME-OBSERVATION.md`, `PK-SPEC-03-LOCAL-PRESSURE.md`.
   - `PACKAGE_MANIFEST.json`.
2. **Sin cambios funcionales** sobre `v0.56.0-rc2`. El binario es idéntico al de `v0.56.0-rc2` salvo timestamps de zip; el commit governance es docs-only y no recompila nada.

## Artefactos publicados (inmutables)

- **Asset principal:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 ZIP:** `78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512` (verificado
  re-descargado vía `gh release view`).
- **5 SDK coordinates** a `0.56.0`: idénticas a `v0.56.0-rc1` y `v0.56.0-rc2`.
- **`candidate_id`:** `sha256:78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`.

## Tests certificadores (PK-1 final, sin cambios sobre rc2)

- `Pk1ContractTypesTest` (8 casos) — verde.
- `Pk1ContractSurfaceTest` (5 casos, consumer) — **5/5 verde** contra `-PsdkVersion=0.56.0` con
  `PIPELINEK_BIN=installDist`.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde (regresión check).
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 145+ casos certificadores.

## Verificación

- ✅ `git sddk-align` acknowledged para el commit governance
- ✅ `git sddk-close` registrado para el commit governance
- ✅ `:pipeline-application:installDist` BUILD SUCCESSFUL (UP-TO-DATE, docs-only)
- ✅ `pipelinek-0.56.0.zip` (88.3 MB) SHA-256 `78c3a3e4e828a797804254b7ab7638e09363e7baf8381c5fa2644c8225157512`
- ✅ `git push origin release/pk1-contract-v0.56.0-rc1` rama actualizada (con governance commit)
- ✅ `git tag -a v0.56.0-rc3` + `git push origin v0.56.0-rc3` etiqueta publicada
- ✅ `gh release create v0.56.0-rc3 --prerelease` Prerelease publicada con el ZIP asset
- ✅ Digest del asset re-descargado = SHA-256 local

**Estado actual:** `CANDIDATE_PUBLISHED` en GitHub.
**Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc3

## Estado del bloque PK-1 (final)

```
T1  CorrelationId value class                                ✓
T2  RunCorrelated DomainEvent (decodable + re-emittable)   ✓
T3  LaunchReceipt JSON envelope                              ✓
T4  CliExit sealed (8 cases) + CliExitMapping (exhaustive)  ✓
T7  'pipeline capabilities' subcommand                       ✓
T8  'pipeline sdk-info' subcommand                           ✓
T9  RunIdDirectory correlation persistence                  ✓
T11 Pk1ContractTypesTest (8 casos)                          ✓
T12 example-external-consumer Pk1ContractSurfaceTest (5/5)  ✓
T14 version bump 0.55.0 → 0.56.0                            ✓
T15 INTERFACE_CONTRACT + CONTRACT_SHA256                     ✓
T16 release-receipt + handoff-to-release-harness             ✓
T17 PK_CONTRACT_HANDOFF CRIC-PK1 to Fabric                   ✓
T18 build + publish + tag + Prerelease                      ✓
T19 (rc3) governance framework committed                    ✓
```

**19 tareas completadas** (T5/T6/T10/T13 con implementación inline equivalente).

## Pendiente (externo)

- `CERTIFIED` por `pipelinek-release-harness` para `v0.56.0-rc3`.
- `PAIR_CERTIFIED` con Fabric contra `v0.56.0-rc3`.
- Promoción `EXPERIMENTAL → PUBLICADA` post-`CERTIFIED`.
- `v0.56.0-rc1` y `v0.56.0-rc2` quedan como Prereleases históricas (no reescritas); `v0.56.0-rc3` es la candidata canónica final.

## Estado de la cadena CRIC-1 + PK-1

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
PK-1 (v0.56.0-rc3): closure           ✓ (governance committed)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, 2 new subcommands
```

**PK-1 fully complete; ready for `CERTIFIED` + `PAIR_CERTIFIED` with Fabric.**

---

**Firma:** PK release maintainer (autoridad del release-receipt PK-1 final rc3).
