# UAT strategy

## Purpose

UAT validates product behavior from a user/project perspective. Unit tests prove implementation details; UAT proves the pipeline system actually behaves as promised.

## Principles

- black-box CLI first where possible;
- use real subprocesses/files/projects, not mocks for final acceptance;
- deterministic fixtures committed to the repo;
- each UAT declares platform assumptions;
- every failure prints paths to durable logs/events for diagnosis;
- retries are forbidden as a mechanism to hide flaky tests;
- performance UAT records machine metadata and thresholds.

## Fixture families

1. **DSL/compiler fixtures** — known source -> canonical IR/error.
2. **runtime fixtures** — shell/files/cancellation/retry/parallel.
3. **security fixtures** — credential scope/redaction/missing-provider.
4. **plugin fixtures** — external plugin compile/load/version mismatch.
5. **Jenkins migration corpus** — supported Jenkinsfile -> pipeline.kts -> equivalent IR/behavior.
6. **distribution fixtures** — clean install/upgrade/uninstall.
7. **real project fixtures** — Gradle, Maven, Node/npm; later Python if demanded.

## Evidence

A UAT result is valid only when it records command, version/commit, OS/arch, result and artifacts/logs required to reproduce a failure.
