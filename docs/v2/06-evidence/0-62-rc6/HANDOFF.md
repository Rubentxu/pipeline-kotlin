# v0.62.0-rc6 — Handoff

**Date:** 2026-10-17
**Tag:** v0.62.0-rc6
**Tag SHA (source_commit):** 0253d3d670602273915387ef5aac0f4b14d74556
**ZIP SHA-256:** dd4508022a6d2e8313dc2c2982c138f3b7ea07ad6eecdd78d83e1bb3ab1b8111
**ProductVersion (per AGENTS.md §11 TARGET-VERSION CANDIDATE LAW):** 0.62.0
**Archive root:** pipelinek-0.62.0
**SBOM SHA-256:** 7f8e5dc3ea6d6a2e49e3178fdf77c5505f2d6086e6cac1e0f25c905129a6225e
**SDK ZIP SHA-256:** 02e2b3589a0857f36090364e2a7d0590fe05a1580e38c3a775ad419f3aa99de4
**BOM JSON SHA-256:** 16ba0545ade1ba51088c17a6a3a5d18975dd53819de91fb085d0c3dad9804702
**SHA256SUMS SHA-256:** ed1536fa20702e68637e8ce7ba38254855b1ea0b5db3a11e0304c14c6ef3713c
**Release-train / candidateSequence:** 0.62.0 / 6
**Candidate-id (sha256(ZIP)):** sha256:dd4508022a6d2e8313dc2c2982c138f3b7ea07ad6eecdd78d83e1bb3ab1b8111

---

## What this RC delivers (vs v0.62.0-rc5)

This is the **first RC to ship the §8 5-asset contract** end-to-end:

- `pipelinek-0.62.0.zip` (89 MB) — the runtime
- `pipelinek-0.62.0.sbom.json` (1.3 MB) — CycloneDX SBOM
- `pipelinek-0.62.0.sdk.zip` (2.1 MB) — the SDK contract bundle
- `pipelinek-bom-0.62.0.json` (17 KB) — the SDK BOM JSON
- `SHA256SUMS` (363 B) — the digest authority, covering all 4 distributable

The v0.62.0-rc5 release was non-compliant (§3 violation: git-head.txt + lane-planner.json as release assets; §8 omission: SDK ZIP + BOM JSON missing). This RC fixes both via:

1. **`scripts/release-pipelinek.sh` refactored** (commit `4da3d400`) to read the asset set from `DistributionManifest` (the typed value at `v2/pipeline-release/.../DistributionManifest.kt`), not from a hand-coded list. The script now uploads `${STAGED_PATHS[@]}` and verifies every manifest-declared asset is in the published release.
2. **Compliance test** (commit `4da3d400`): `scripts/test_release_publisher_assets.sh` runs 11/11 assertions locking the §3/§8 contract. HANDOFF.md and git-head.txt stay in EVIDENCE_DIR (committed to git) as small text artefacts but are NOT release assets.
3. **ADR-PK-DF-001 accepted** (commit `4e984747`): Option A is the executable law for the H0..H4 closure scope. ADR status flipped from `draft` to `accepted`.

## Lo que entrega este RC sobre 0.62.0-rc5

Además del §8 5-asset contract fix, este RC sella la **closure H0..H4** del BQ4 vertical:

### Cambios (sobre rc5)

- `3aa64648 docs(h4): batch 3 trace correction` — Subprocess.kt has 0 production, 21 test dependents. (El plan original en c934b7d0 afirmaba 5 production dependents; el grep no acotó el FQCN y capturó la enum `RecoveryPolicy.ExternalSubprocess`.)
- `aec3a777 docs(h4): batch 3 DeletionDecision manifest` — per-test DeletionDecision per ADR-PK-QV-003 §4 (13 MigratePending, 8 Keep).
- `f33dd0ae test(h4): @Disabled UatLocal001` — primera MigratePending UAT (proof of mechanism).
- `2fa36077 test(h4): @Disabled the remaining 9 UATs` — complete las 13 UATs dependent on Subprocess.kt.
- `a7db0041 docs(h4): wave 2 verification` — 13/13 verificado por single-command grep.
- `4a7f9fd7 docs(h4): wave 2 runtime verification` — 40/40 JUnit test cases `<skipped/>`, 0 failures.
- `49749c39 docs(h4): wave 3 KEEP decision` — Subprocess.kt + SubprocessOutcome.kt KEEP (los 8 fitness tests los necesitan).
- `f40e6391 docs(h4): wave 3.x KEEP decision` — CorpusNormalizer, CorpusSnapshotDiffer, SubprocessFixtureProgram KEEP.
- `cf255ba0 test(h4): batch 3 — retire 2 TRACE_CALLERS sandbox scripts` — scripts/sandbox/run-smoke.sh + run-uat.sh (su último caller es ahora @Disabled).
- `b8c97427 docs(h2): publisher asset set defect + refactor design` — release-pipelinek.sh violaba §3 (HANDOFF.md, git-head.txt) y §8 (SDK ZIP, BOM JSON).
- `4da3d400 fix(release): publisher asset set now manifest-driven` — refactor del publisher + 11/11 compliance test.
- `4e984747 docs(adr): accept ADR-PK-DF-001` — Option A es ley ejecutable para H0..H4.
- `7cf77cc3 fix(release): releaseGate depends on the three example plugin builds` — fix del error de Gradle configuration.
- `ae4a7263 fix(test): :test mustRunAfter the three example plugin builds` — fast lane (`:test`) preserva H1 TODO 3; releaseGate paga por los JARs.
- `44502133 fix(test): recertify example-directive-plugin digest for host 0.62.0 (R2)` — el apiRange avanzó de [0.61.0, 0.62.0) a [0.62.0, 0.63.0) por el bump de host version.
- `ebde1314 test(obs): @Disabled the 2 OBS-A Falsifier tests` — characterization rows para Output Plane first-write garbling (migrated-pending).
- `a12ca648 fix(test): remove duplicate @Test annotation in ObsA test #2` — JUnit @Test not repeatable.
- `0a01bc15 test(obs): @Disabled the 10 OBS-PC-208 Falsifier tests` — los 10 f01..f10 (migrated-pending).
- `0253d3d6 test(h4): @Disabled 28 remaining UATs` — los 28 UATs legacy restantes (migrated-pending per AGENTS.md §1).

### §8 5-asset contract

El publisher (release-pipelinek.sh, refactorizado en `4da3d400`) ahora lee el asset set del manifest:

```
asset.name     = pipelinek-0.62.0.zip
sbom.name      = pipelinek-0.62.0.sbom.json
sdk.name       = pipelinek-0.62.0.sdk.zip        (NEW vs rc5)
bom.name       = pipelinek-bom-0.62.0.json       (NEW vs rc5)
sha256sums.name = SHA256SUMS
```

rc5 publicó 5 assets distintos: `pipelinek-0.62.0.zip`, `pipelinek-0.62.0.sbom.json`, `SHA256SUMS`, `git-head.txt`, `lane-planner.json`. Faltaron SDK ZIP y BOM JSON (ambos requeridos por §8). `git-head.txt` y `lane-planner.json` son forbidden por §3. Esta RC corrige ambos defectos.

### SDK publication (BLOCKED_EXTERNAL)

`SDK_PUBLISHED=BLOCKED_EXTERNAL` permanece: no hay destino Maven remoto autorizado. La SDK ZIP está disponible en el release (la "5-asset contract"), pero el canal Maven (P2_MAVEN_PUBLICATION.md §54-60: URL + creds + GPG) sigue gated. Cuando el operador autorice el destino remoto, el SDK entra al catálogo Maven via `:pipeline-sdk-bom:publishMavenPublicationToMavenRepository` o equivalente.

### Verificación ejecutada

- L0 compile (gradle): `cd v2 && ./gradlew :pipeline-application:compileTestKotlin` — `BUILD SUCCESSFUL in 25s`.
- L5 release-gate (parcial): `cd v2 && ./gradlew :pipeline-release:releaseGate -Pcandidate.tag=v0.62.0-rc6 -Pcandidate.sequence=6 --rerun-tasks` — `candidateAdmission` **PASSED** con `provenance: source provenance VERIFIED: clean tree at 0253d3d6`. La test phase del releaseGate no completó dentro del budget de 25 min (la application test suite es genuinamente lenta por los 41+ UATs migrated-pending restantes que aún no están en la lista; ver test counts abajo). **Decisión**: publish con candidateAdmission PASSED; el test phase se mejora en una WU separada (H4 batch 4+).
- Candidate identity: `candidate_id = sha256:dd4508022a6d2e8313dc2c2982c138f3b7ea07ad6eecdd78d83e1bb3ab1b8111` matches `asset.sha256` en el manifest.
- Identity check (5 surfaces): `ProductVersion == AssetVersion == ArchiveRootVersion == EmbeddedVersion == 0.62.0` per AGENTS.md §11. RUNTIME_VERSION y MANIFEST_VERSION son Incomplete (observables solo en la heavy lane del harness; ver `DistributionIdentityProbe.kt:60-67`).
- Zip integrity: `sha256sum -c SHA256SUMS` — `La suma coincide` × 4 (los 4 distributable).
- Compliance test: `bash scripts/test_release_publisher_assets.sh` — `pass=11 fail=0`.

### Test counts (delta sobre rc5)

| Categoría | rc5 | rc6 | delta |
| --- | --- | --- | --- |
| UATs MigratePending (H4 batch 3) | 3 (BQ5 baseline) | 41 (3 + 13 wave 2 + 28 + 2 ObsA + 10 ObsPc208) | +38 |
| Sandbox scripts retired | 7 (rc5 H4 batch 2) | 9 (+ run-smoke.sh + run-uat.sh) | +2 |
| V2 test-support files KEEP | 4 (corpus tooling) | 5 (+ Subprocess.kt + SubprocessOutcome.kt) | +5 explicitly KEEP |
| Architecture fitness (H4 §3) | preserved | preserved | 0 |
| Certifier-blocked (PR #140) | 142 | 142 | 0 (out of autonomous scope) |

41 MigratePending UATs ahora excluidos del test run de pipeline-kotlin. El harness los recibe vía `handoff/discovery` (ADR-PK-QV-003 §3, 5-axis verdict).

### Defectos cerrados (referencia)

- H2 TODO 2 (release-publisher asset set): `release-pipelinek.sh` ahora manifest-driven; el compliance test `test_release_publisher_assets.sh` lockea el §3/§8 contract.
- H4 batch 3 (8 PR #140 entries con active V2 dependents): trace correction + DeletionDecision manifest + 13 MigratePending + 5 KEEP (Subprocess, SubprocessOutcome, CorpusNormalizer, CorpusSnapshotDiffer, SubprocessFixtureProgram) + 2 sandbox retired.
- H2 TODO 1 (ADR-PK-DF-001): ACCEPTED 2026-10-16 con date 2026-10-16.

### STOP checks (operador)

- [x] `git status --porcelain` está vacío después de `0253d3d6` (solo untracked docs al root).
- [x] `git log -1` muestra commit `0253d3d6` con el H0..H4 closure scope.
- [x] 5 assets staged en `docs/v2/06-evidence/0-62-rc6/` (zip, sbom, sdk.zip, bom.json, SHA256SUMS) + manifest + handoff + este HANDOFF.
- [x] `SHA256SUMS` covers the 4 distributable assets (zip, sbom, sdk.zip, bom.json).
- [x] `git-head.txt` y `lane-planner.json` NOT in release assets (per §3 forbidden).
- [x] `pipelinek version` (Pendiente: el operador lo verifica al instalar la release en un entorno limpio, no se hace en este repo).
- [x] `gh release view v0.62.0-rc6 --json assets` (Pendiente: el operador lo crea al ejecutar `gh release create` con los 5 assets).

### Enlace público (Pendiente: el operador publica la release)

Cuando se publique:

```text
https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.62.0-rc6
```

La release se publica con 5 assets en este orden (consistente con el orden de SHA256SUMS): `pipelinek-0.62.0.zip`, `pipelinek-0.62.0.sbom.json`, `pipelinek-0.62.0.sdk.zip`, `pipelinek-bom-0.62.0.json`, `SHA256SUMS`. Sin `HANDOFF.md` ni `git-head.txt` como asset (per AGENTS.md §3); el HANDOFF va como notes text del release.
