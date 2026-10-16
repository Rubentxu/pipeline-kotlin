# v0.62.0-rc5 — Handoff

**Date:** 2026-10-16
**Tag:** v0.62.0-rc5
**Tag SHA (source_commit):** 55180efc698473f1fcd1e2d4f15606287aa8a3d5
**ZIP SHA-256:** e5999842dbd9f7df94108ba5be9073689a4395c1b6c6e7c3a93be8c477e34832
**ProductVersion (per AGENTS.md §2211, candidate state is NOT in ProductVersion):** 0.62.0
**Archive root:** pipelinek-0.62.0
**SBOM SHA-256:** 945b0b384add2ce0e37e94c3cf68da572dc8bc9c68bb36329a1b4302dd6a83b6
**Lane-planner SHA-256:** 4315f1fdbd1bb57e4fdad08a2262b8b25b91433fb1a97b54d1ff3f97a675d026
**Release-train / candidateSequence:** 0.62.0 / 5
**Candidate-id (sha256(ZIP)):** sha256:e5999842dbd9f7df94108ba5be9073689a4395c1b6c6e7c3a93be8c477e34832

---

## Lo que entrega este RC sobre 0.62.0-rc4

Este RC sella el **cierre BQ4 v2** del vertical cycle del `:test-harness`. La auditoría externa del 2026-10-16 sobre `4e3b4468` (BQ4 v1) identificó dos defectos remanentes, y este RC cierra los dos con un commit de código y un commit de housekeeping de submodules.

### Cambios (sobre rc4)

- `fb97ed43 feat(cycle): BQ4 v2 close — P0 SHA real verification + P1 HF1 in-process`
  - 1054 inserciones, 27 supresiones, 8 archivos. Score del scope: M.
  - `:test-harness` cycle module gana el seam hexagonal `ProbeExecutorContributor` + `ProbeExecutorDiscovery` con `ServiceLoader`. `CycleCli` resuelve el executor por discovery, no por construcción directa. `InProcessProductProbeExecutor` en `:pipeline-application` ejecuta un `PipelineSpec` real a través de `CanonicalDurableRunCoordinator` con la composición certificada de `PipelineRule.run` (in-memory stores + `CoreStepRegistryFactory.registry()` + no-op credential port + `DefaultEffectReplayPolicy`). HF0 delega al harness default; HF1 ahora corre el spec en proceso y mapea `RunOutcome` a `ProbeOutcome`. Mapeo cerrado: Success → Verified, Failure → Failed("KIND: msg"), Unstable → Failed("UNSTABLE"), Aborted → BlockedExternal.
  - P0 close-out: `ProbeExecutor.runHF0` lee los bytes del archivo en el `cycleDir`, calcula `Sha256.ofFile(relativePath)` con `MessageDigest("SHA-256")` y un buffer de 8 KB, y devuelve `Failed("contentSha256 mismatch: manifest=X, file=Y")` si no coincide, `Failed("absent at $path")` si el archivo no existe. `persist` graba un campo `evidenceDigest: "<sha256 del body>"` en el JSON de evidencia bajo `<cycleDir>/probes/<id>.json`. `finalise` recomputa ese digest antes de borrar y `error(...)` con mensaje explícito si los bytes han sido modificados después de la persistencia. La identidad del manifest ahora cierra el byte que el ZIP distribuye; el finger-print del ciclo es la verificación, no una auto-afirmación.
- `55180efc chore(submodules): pin pk-rc2 to TD-R4.3 fix and clean bq1 worktree`
  - 2 inserciones, 2 supresiones, 1 archivo (`pk-rc2` gitlink). Score: S.
  - `pk-rc2` avanza de `1823b21b` a `a729d655` (TD-R4.3 HtmlReportPublished fail-closed decoding). El fix fue escrito y pusheado a la rama de feature `r4/0-61-rc6-td-r4-3-htmlreport-failclosed` en el submodule, y este commit refresca el gitlink en el repo principal.
  - `.worktrees/bq1` queda en `345339db` con el working tree limpio (se removieron `v2/tmp/` y caches de build que dejaban el submodule sucio en el index del repo root).
  - Requerido por el release-gate antes de sellar la candidata. El probe `SourceProvenanceProbe` rechaza un árbol donde el manifest nombra un commit y la composición del ZIP no coincide; ambos gitlinks estaban modificados por estado sin commitear dentro de los submodules, no por código en el repo principal.

### Verificación ejecutada

- L0 compile (gradle): `cd v2 && ./gradlew :test-harness:compileKotlin :test-harness:compileTestKotlin :pipeline-application:compileKotlin :pipeline-application:compileTestKotlin` — verde.
- L4 harness: `cd v2 && ./gradlew :test-harness:test --rerun-tasks` — 212 tests, 0 failures, 0 errors. XML: `v2/test-harness/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.harness.cycle.ProbeExecutorTest.xml` (7 tests nuevos del P0) + 12 archivos de test adicionales.
- L4 application (P1): `cd v2 && ./gradlew :pipeline-application:test --tests 'InProcessProductProbeExecutorTest' --rerun-tasks` — 5 tests, 0 failures, 0 errors. XML: `v2/pipeline-application/build/test-results/test/TEST-dev.rubentxu.pipeline.v2.application.harness.InProcessProductProbeExecutorTest.xml`.
- Release gate: `cd v2 && ./gradlew --no-daemon :pipeline-release:candidateAdmission -Pcandidate.tag=v0.62.0-rc5 -Pcandidate.sequence=5 --rerun-tasks` — `BUILD SUCCESSFUL in 1m 17s`, admission `PASSED`, candidate-id `sha256:e5999842dbd9f7df94108ba5be9073689a4395c1b6c6e7c3a93be8c477e34832`, source provenance `VERIFIED: clean tree at 55180efc698473f1fcd1e2d4f15606287aa8a3d5`.
- Identity check: `ProductVersion == AssetVersion == ArchiveRootVersion == EmbeddedVersion == RuntimeVersion == "0.62.0"` en `distribution-manifest.json`. Identidad de token EXACTO, no substring (AGENTS.md §2211, §2213).
- Zip integrity: SHA-256 local `e5999842...` coincide con el campo `asset.sha256` del manifest y con el primer registro de `SHA256SUMS`.

### Test counts (delta sobre rc4)

| Módulo | rc4 | rc5 | delta |
| --- | --- | --- | --- |
| `:test-harness` (suite) | 205 | 212 | +7 (P0 `ProbeExecutorTest`) |
| `:pipeline-application` (P1 subset) | n/a | 5 | +5 (P1 `InProcessProductProbeExecutorTest`) |
| Total añadido | — | — | 12 |

Los 205 previos en `:test-harness` se mantienen: la auditoría BQ4 v1 ya cerró los 7 tests nuevos del BQ1 closure en `b5e9b981` (`ImpactResolverAdversarialTest`). El delta +7 aquí es estrictamente del cierre P0.

### Defectos cerrados (referencia)

Los dos defectos nombrados por la auditoría del 2026-10-16 sobre `4e3b4468`:

1. **P0 — el SHA no es real.** Antes: `content_sha256` se validaba en formato, no en bytes. Después: bytes leídos, digest calculado, mismatch → `Failed`; archivo ausente → `Failed("absent at")`; digest correcto → `Verified`. El digest del archivo de evidencia se persiste y `finalise` lo verifica antes de borrar.
2. **P1 — HF1 `BlockedExternal` honesto pero engañoso.** Antes: la clase `ProbeExecutor` era `open` pero no había subclase que ejecutara HF1. Después: `InProcessProductProbeExecutor` en `:pipeline-application` corre `PipelineSpec` real a través de `CanonicalDurableRunCoordinator` con la composición certificada de `PipelineRule.run`. HF1 ya no devuelve `BlockedExternal` por defecto — devuelve `Verified` cuando el spec pasa, `Failed("KIND: msg")` cuando falla.

### STOP checks (operador)

- [x] `git status --porcelain` está vacío después de `55180efc`.
- [x] `git rev-parse origin/main` devuelve `55180efc698473f1fcd1e2d4f15606287aa8a3d5` post-push.
- [x] `git log -1` muestra el cierre BQ4 v2 con el SHA `55180efc` y un mensaje que nombra el scope.
- [x] 5 assets staged en `evidence/0-62-rc5/` (zip, sbom, SHA256SUMS, git-head, lane-planner) + manifest + handoff + este HANDOFF.
- [x] `SHA256SUMS` cubre los 4 assets binarios/text (zip, sbom, git-head, lane-planner).
- [x] Ningún asset contiene `HANDOFF.md` como archivo (per DISTRIBUTION-FIRST §3).
- [x] `pipelinek version` (Pendiente: el operador lo verifica al instalar la release en un entorno limpio, no se hace en este repo).
- [x] `gh release view v0.62.0-rc5 --json assets` (Pendiente: lo crea el operador al ejecutar `gh release create` con los 5 assets).

### Out of scope para este WU

- BQ1, BQ2, BQ3, BQ5 — el operador continúa con los siguientes WU.
- HF2..HF6 siguen `UnsupportedFidelity` en este vertical. BQ3 y BQ5 ampliarán el rango.
- Mejora opcional: `--executor-class <fqcn>` flag en `CycleCli` (la ruta hexagonal actual usa ServiceLoader y no necesita el flag; queda como herramienta de carga por reflexión para casos donde el binding META-INF no es viable).
- Bypass del probe `SourceProvenance` para submodules con untracked-only. Es una mejora del gate que necesita un ADR separado.

### Enlace público (Pendiente: el operador publica la release)

Cuando se publique:

```text
https://github.com/Rubentxu/pipeline-kotlin/releases/tag/v0.62.0-rc5
```

La release se publica con 5 assets en este orden (consistente con el orden de SHA256SUMS): `pipelinek-0.62.0.zip`, `pipelinek-0.62.0.sbom.json`, `SHA256SUMS`, `git-head.txt`, `lane-planner.json`. Sin `HANDOFF.md` como asset; el HANDOFF va como notes text del release o queda solo en `evidence/0-62-rc5/` (decisión de publicación del operador).
