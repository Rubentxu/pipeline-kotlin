# RP034-H — Installed-distribution UAT for the local-first default

**Status:** PASS — the local-first default is certified on real installed bytes
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**Subject SHA:** `3cd0d0d2de0488307004a3ea4e88d730952fbf09`
**Authority:** ADR-0101 (Accepted 2026-10-01), ADR-0102, ADR-0048 (amended)

## Artefacts under test

| Artefact | SHA-256 |
|---|---|
| `pipelinek-0.44.0.zip` | `d989b6dba6ec6c1d7338aee912770c7b2e8797b7e2dc9cd335c61306f6aa8806` |
| `lib/pipeline-application-0.44.0.jar` | `1245aef1e10fc8c8ee62b33617b378f6f71bd3bd87866d7791ecd29b550199a3` |

Built with `cd v2 && ./gradlew :pipeline-application:installDist :pipeline-application:distZip`
→ `BUILD SUCCESSFUL`, 49 tasks. All runs below execute the **installed binary**,
not the test classpath.

## Fixture — a real Gradle project

`/var/home/rubentxu/Proyectos/kotlin/uat-rp034/gradle-proj` contains
`settings.gradle.kts`, `build.gradle.kts`, `src/main/java/app/App.java`, a
`gradlew` wrapper stand-in that resolves `./gradlew` from the current directory
and fails if `build.gradle.kts` is not reachable, and `pipeline.kts`.

The wrapper stand-in is deliberate: it is the exact thing that could not work
before this WU. A relative executable and a relative project file only resolve
if the shell CWD is the caller's directory.

## Scenarios — all OBSERVED

### A — no flag (the case WU-RP-034 exists for)

```kotlin
stage("build") {
    sh("pwd")
    sh("./gradlew -q assemble --console=plain")
    sh("test -f build.gradle.kts && echo PROJECT_FILES_VISIBLE")
}
```

| Observation | Value |
|---|---|
| `pwd` | `/var/home/rubentxu/Proyectos/kotlin/uat-rp034/gradle-proj` |
| `./gradlew` | `STUB-GRADLE ran in /var/home/.../gradle-proj` |
| project file probe | `PROJECT_FILES_VISIBLE` |
| **exit** | **0**, `RunFinished(success)` |

The caller's project is the workspace, the relative wrapper resolves, and the
relative project file is visible. This is the defect that motivated WU-RP-034,
now closed on installed bytes.

### B — `--isolated` preserves the historical scratch

| Observation | Value |
|---|---|
| `pwd` | `/var/home/rubentxu/Proyectos/kotlin/uat-rp034/ctl-b/workspace/build-0` |

PipelineK-managed, per-stage (`<stageName>-<index>`), under the control root —
and **not** the caller's project. The historical Jenkins-like mode is intact.

### C — `--workspace <dir>` stays authoritative

| Observation | Value |
|---|---|
| `pwd` | `/var/home/rubentxu/Proyectos/kotlin/uat-rp034/explicit-ws` |

An explicit workspace is honoured exactly as before the change.

### D — incompatible modes fail closed

```bash
pipelinek run --workspace <dir> --isolated pipeline.kts
```

```text
exit=1
Invalid CLI arguments: ConflictingWorkspaceModes(workspace=/var/home/.../explicit-ws):
--isolated requests a PipelineK-managed scratch workspace while --workspace attaches
a user directory; choose one (ADR-0101 clause 3.4).
```

Rejected at admission with a typed error naming the contract. No step ran, and
there is no silent precedence rule.

## Regression evidence on the same SHA

| Suite | Result |
|---|---|
| `WorkspaceExecutionLocationCharacterizationTest` | 6/6 |
| `CompatibilityCorpusTest` | 30/30 |
| `UatLocal007SandboxProfileTest` | 14/14 |
| `UatLocal003ReturnStdoutTest` | 2/2 |
| `DestructiveSafetyOwnershipTest` | 11/11 |
| `DirScopeEndToEndTest` | green |
| `WorkspaceIntentTest` | green |
| `:pipeline-application:detekt` | BUILD SUCCESSFUL |

## Method note — harness failures that were not product failures

Three harness defects were diagnosed and corrected rather than worked around,
because each would otherwise have been reported as a product regression:

1. `--db` and `--control-root` were first given the same path, so SQLite could
   not open its database and the run blocked on stdin.
2. The control directory did not exist for scenarios B and C; SQLite does not
   create parent directories.
3. Scenarios A, B and C first ended in `RunFinished(failure)` because the
   fixture's third step asserted a JAR that the wrapper stand-in does not
   produce. The pipeline was corrected to assert a property the fixture really
   provides, and the exit-0 run is the one recorded above.

## Not certified here

Maven and Node real projects, replay/resume under the new default, and the
non-core consumers still on the legacy capability. Those belong to RP034-E and
RP034-I. The full `check` gate has also not been re-run on this SHA: the
regression evidence above is per-suite, measured on this tree.
