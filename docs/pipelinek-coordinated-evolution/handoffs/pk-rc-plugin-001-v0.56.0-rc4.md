# PK_CONTRACT_HANDOFF — PK-RC-PLUGIN-001 (v0.56.0-rc4) — closure

> Emisor: PipelineK (`Rubentxu/pipeline-kotlin`).
> Destinatario: Fabric (`Rubentxu/pipelinek-fabric`).
> Estado: **PREPARED — PK-RC-PLUGIN-001 closure (plugin compatibility drift eliminated)**.
> La candidata `v0.56.0-rc4` cierra el bloque PK-RC-PLUGIN-001 (P0). `v0.56.0-rc3` se
> conserva como **historical-incompatible** (su tag y su ZIP no se reescriben). `v0.56.0-rc4`
> es la candidata canónica final para `CERTIFIED` + `PAIR_CERTIFIED` con Fabric.

---

## Resumen ejecutivo

`v0.56.0-rc4` cierra el bloque **PK-RC-PLUGIN-001** sobre `v0.56.0-rc3`:

1. **Causa raíz demostrada con bytes oficiales de GitHub**: el ZIP `v0.56.0-rc3`
   llevaba 4 manifests con `apiRange=[0.47.0, 0.53.0)` mientras el host era `0.56.0`.
   `PluginAdmissionGate` habría rechazado todos los plugins en su propia
   distribución. La razón del bypass: el guard `BundledPluginCompatibility` +
   `CandidateAdmission` existía pero **nunca fue invocado contra el ZIP publicado**.
2. **Eliminada la doble fuente de verdad para `apiRange`**: una política pura en
   `buildSrc` lee `rootProject.version` y deriva el rango; los 4 `*PluginDeclaration.kt`
   consumen el recurso generado; los 4 `build.gradle.kts` perdieron el literal manual.
3. **Gate de release obligatorio**: nueva task `:pipeline-release:releaseGate` que
   depende de `distZip + cyclonedxBom + candidateAdmission`. Sin un PASS del gate,
   la publicación queda bloqueada.

**Cambio de capability:** ninguno. Las 11 capabilities vigentes mantienen su estado.

**Cambio de artefacto canónico:** `v0.56.0-rc4` reemplaza a `v0.56.0-rc3` como la
candidata canónica final para `CERTIFIED`. `v0.56.0-rc3` queda como
historical-incompatible (no se reescribe ni se re-firma; la
`PK_CONTRACT_HANDOFF CRIC-PK1 rc3` sigue vigente para el resto de superficies
de PK-1 que SÍ están admitidas — sólo la superficie de compatibilidad de
plugins queda bloqueada para rc3).

---

## SHA commit PK / branch / tag

- **Commit de build:** `f6b020e4` (HEAD de `main`).
- **Branch:** `release/pk-rc-plugin-001-v0.56.0-rc4` (merge commit = `f6b020e4`).
- **Tag candidato:** `v0.56.0-rc4` (anotado, peel = `f6b020e4`).
- **Prerelease URL:** https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.56.0-rc4

## Artifact PK inmutable

- **Asset principal:** `pipelinek-0.56.0.zip` (88.3 MB).
- **SHA-256 (asset re-descargado):** `1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.
- **`candidate_id`:** `sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.
- **5 SDK coordinates** a `0.56.0` (idénticas a rc1+rc2+rc3):
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

## Petición a Fabric

1. **Componer la release PK-RC-PLUGIN-001 contra el artefacto `v0.56.0-rc4`**
   (no contra rc3; esta es la candidata canónica final, rc3 es historical-incompatible).
2. **Correr la batería completa** (los 5 casos del consumer PK-1 + la nueva verificación
   de que los 4 manifests en el ZIP admiten 0.56.0) contra el artefacto publicado.
3. **Verificar el `--correlation-id` end-to-end** invocando
   `pipeline run --correlation-id <uuid>` y parseando la línea `pipelinek.launch/1` de
   stdout (regresión de PK-1).
4. **Verificar que `docs/pipelinek-coordinated-evolution/coordination/MILESTONE_MATRIX.md`
   y `verify_pair_gate.py` siguen versionados** (regresión de PK-1 closure).
5. **No dar por superadas pruebas no ejecutadas o condicionadas por infraestructura
   ausente** (regla del plan).
6. **Emitir el `release-receipt` del candidato Fabric** contra el artefacto PK
   `v0.56.0-rc4`. El par se considera `PAIR_CERTIFIED` solo cuando ambos releases
   estén verificados.
7. **Conservar el artefacto publicado y verificado.** La promoción GA es un cambio
   de tabla, no de binario; ocurre tras `CERTIFIED`.

## Pruebas cruzadas que esta release tiene

- `Pk1ContractTypesTest` (8 casos) — verde in-monorepo.
- `Pk1ContractSurfaceTest` (5 casos) — **5/5 verde** en consumer extension.
- `PluginCompatibilityPolicyTest` (5 casos) — verde.
- `PluginCompatibilityResourceTest` (6 casos) — verde.
- `DistributionMandatoryPluginsTest` (3 casos) — **3/3 verde** contra rc4 +
  reproducer rc3 INCOMPATIBLE.
- `BundledPluginCompatibilityTest` (9 casos sintéticos) — verde.
- `BuiltPluginManifestArtifactTest` (6 casos, con `RuntimeApiVersion.current()`) — verde.
- `DomainEventExhaustiveRoundTripTest` (72 variantes) — verde.
- `DomainEventRoundTripTest` (72-variant seal) — verde.
- `CapabilityRegistrationTest` (7/7) — verde.
- Baterías M1+M2+M3+M6+M7 (132+ casos) — sin regresiones.

**Total:** 161+ casos certificadores, todos verdes.

## Estado del chain CRIC-1 + PK-1 + PK-RC-PLUGIN-001

```
M1 (v0.49.0-rc1):   2 PUBLICADA        ✓
M2 (v0.50.0-rc1):   +3 EXPERIMENTAL   ✓
M3 (v0.51.0-rc1):   +3 EXPERIMENTAL   ✓
M4 (handoff only):  no new surface    ✓
M5 (handoff only):  no new surface    ✓
M6 (v0.53.0-rc1):   +2 EXPERIMENTAL   ✓ (placement fixed in M6.4)
M7 (v0.54.0-rc1):   +1 EXPERIMENTAL   ✓ (placement fixed by association)
M6.4 (v0.55.0-rc1): corrective         ✓
PK-1 (v0.56.0-rc1): foundation         ✓ (historical)
PK-1 (v0.56.0-rc2): completion         ✓ (historical)
PK-1 (v0.56.0-rc3): closure           ✓ (historical-incompatible)
PK-RC-PLUGIN-001 (v0.56.0-rc4):        ✓ (canonical final)
TOTAL: 11 capabilities, 0 PARTIAL rows, 3 PK-1 contract surfaces, drift eliminated
```

## Próximo paso (post-PK-RC-PLUGIN-001)

Tras `CERTIFIED` por `pipelinek-release-harness` y la aceptación por Fabric de su
release contra el artefacto `v0.56.0-rc4`, el Integrator emite el
`PAIR_CERTIFIED` final. La cadena CRIC-1 + PK-1 + PK-RC-PLUGIN-001 queda entonces
**completa**:

- 11 capabilities publicadas (2 PUBLICADA + 9 EXPERIMENTAL)
- 0 PARTIAL rows
- N/N-1 compatibility documentada
- Negative-scope (F.4 / F.5) preservada
- Cross-repo seams endurecidas vía B.1 / B.2 / B.3
- M6 surface correctamente colocada en `:pipeline-runtime-api`
- 3 nuevas superficies de contrato (PK-1): `CorrelationId`, `LaunchReceipt`, `CliExit`
- 2 nuevas superficies machine-verifiable (PK-1): `pipeline capabilities`, `pipeline sdk-info`
- `--correlation-id` propagado al durable state + emitido en `RunCorrelated` + visible en `LaunchReceipt`
- Governance framework versionado y verificable
- **Plugin compatibility drift eliminated** (PK-RC-PLUGIN-001): los 4 plugins
  oficiales admiten el host por construcción; un bump de versión no requiere
  editar sus declaraciones; el guard `releaseGate` rechaza por construcción
  cualquier candidato que sus propios plugins excluyan.

La promoción `EXPERIMENTAL → PUBLICADA` ocurre en un release posterior, una vez
que el certifier haya revisado la batería completa. Esa promoción es un cambio
de tabla, no de binario.

---

## Auditoría y firma

- **PK-RC-PLUGIN-001 implementación:** 3 commits sobre `release/pk-rc-plugin-001-v0.56.0-rc4`
  (B2 single source, B3 tests + gate, B4 release-receipt + handoff).
- **Handoff emitido por:** PK release maintainer (autoridad del release-receipt `v0.56.0-rc4`).
- **Candidate ID:** `sha256:1a423a7876ae13fec9122743187fa7c8a577f1ab8248493982a1f3dcfdeb7696`.
- **Candidatas previas:** `v0.56.0-rc1`, `v0.56.0-rc2`, `v0.56.0-rc3` preservadas (rc3
  marcada historical-incompatible pero inmutable). `v0.56.0-rc4` es la canónica final.
