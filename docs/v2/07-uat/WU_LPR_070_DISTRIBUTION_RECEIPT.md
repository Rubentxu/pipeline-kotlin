# WU-LPR-070 Receipt — Distribution: `pipelinek` distZip, checksum, reproducibility

## Change
- `applicationName = "pipelinek"` in `v2/pipeline-application/build.gradle.kts`
  (installed distribution: `pipelinek-<version>.zip` with `bin/pipelinek`).
- Reproducible archives at the v2 build root: all `AbstractArchiveTask` get
  `isPreserveFileTimestamps = false` + `isReproducibleFileOrder = true`.

## Defect found and fixed (nondeterministic dist)
Two `distZip --rerun-tasks` cycles produced different SHA256. Root cause:
nested project jars inside the dist carry file timestamps → CRC drift in 15
embedded jars. Fixed by the archive flags above.

## Evidence (OBSERVED)
- Reproducibility: two full `distZip --rerun-tasks` cycles → identical SHA256:
  `8bd288f4ccb8db0ea2a7665860e53715590465ea62a5a830f6e4e2076a341dd4`
  (pipelinek-0.1.0-SNAPSHOT.zip, 2026-09-19).
- Clean-install smoke: unzip → `bin/pipelinek version` → `pipeline 0.1.0-SNAPSHOT`;
  E2E gradle-demo from the clean install → exit 0, GRADLE-DEMO-OK.
- Prior failure evidence: 3 distinct SHAs across earlier cycles
  (3c7391…, 733aa0…, 590ca3…) — repro attached in session log.

## Open (deferred by WU scope)
- SBOM generation and non-SNAPSHOT versioning land with WU-LPR-071 release flow.

## Counters
Certified Steps unchanged: 15 SUPPORTED_CERTIFIED (+1 EXPERIMENTAL).
