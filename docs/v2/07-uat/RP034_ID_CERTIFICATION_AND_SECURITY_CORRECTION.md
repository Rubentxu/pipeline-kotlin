# RP034-I(d) — Certification closure and destructive-safety correction

```yaml
id: WU-RP-034
status: CERTIFIED_AT_SHA
base_sha: 0f45a2018428850cccadb694c6e2de2602b99712   # v0.44.1
head_sha: c06331af94dc5c37abba235484b1a48d37e39fa8
source_tree_sha: 18e95177f6df1313a1ff80bc89c59841b7a4789c
artifact: v2/pipeline-application/build/install/pipelinek  (installDist)
artifact_sha256: NOT_BUILT            # dist/attestation is a release-phase concern
scope: workspace + execution-location semantics; destructive-safety guard
checks:
  - id: GATE-FULL-CHECK
    command: ./gradlew check          # from v2/
    exit_code: 0
    xml: 633 result files aggregated
    evidence: BUILD SUCCESSFUL in 20m 27s; 276 actionable tasks
    result: PASS
  - id: COUNTS
    command: aggregate tests/failures/errors/skipped from XML
    xml: tests=4058 failures=0 errors=0 skipped=130
    evidence: per module application 1920, domain 654, architecture 351,
              step-sdk runtime 201, step-sdk api 8
    result: PASS
  - id: CORPUS-SMOKE
    command: :pipeline-application:test --tests "*UatCompat001CorpusSmokeRunTest*"
    exit_code: 0
    xml: 2 tests, 0 failures
    evidence: 30/30 fixtures green in 6m 4s
    result: PASS
  - id: UAT-LOCAL-011-REFUSAL
    command: :pipeline-application:test --tests "*UatLocal011WorkflowControlTest*"
    exit_code: 0
    xml: tests=14 skipped=1 failures=0 errors=0
    evidence: SC-011-13 asserts exit != 0, StepFailed, no DirDeleted, and
              survival of the caller's file
    result: PASS
  - id: DIST-DELETE-ATTACHED
    command: pipelinek run pipeline.kts   # installed distribution, /tmp/rp034-safety/proj
    exit_code: 1
    xml: NONE
    evidence: refusal raised, important.txt preserved; reproduced RED before fix
    result: PASS
  - id: DIST-DELETE-ISOLATED
    command: pipelinek run --isolated pipeline.kts
    exit_code: 0
    xml: NONE
    evidence: DirDeleted on the per-stage scratch; caller directory untouched
    result: PASS
  - id: DIST-DELETE-SUBPATH
    command: pipelinek run pipeline.kts   # deleteDir("build")
    exit_code: 0
    xml: NONE
    evidence: deletedCount=1, sibling files intact
    result: PASS
  - id: EXIT-GRADLE-MAVEN-NODE
    command: NOT_RUN
    exit_code: NOT_RUN
    xml: NONE
    evidence: RP-034 exit criteria require the installed distribution to drive
              real Gradle, Maven and Node builds and to self-host; this gate
              does not exercise them
    result: NOT_RUN
known_failures: []
coverage: UNKNOWN   # no coverage task in the check gate
security: UNKNOWN   # no SAST or dependency scan in the check gate
performance: UNKNOWN
next_action: run the Gradle/Maven/Node installed-distribution UAT before
             declaring the RP-034 exit criterion satisfied, then release
```

## What this slice found

The RP034-G gate had been recorded as satisfied. It was green in every unit
test and **dead in production**.

`WorkspaceIntent` decided ownership correctly — ADR-0101 makes the default
`pipelinek run` resolve `WorkspaceLease.Attached` — and then dropped it.
`WorkspaceIntent` exposed only a `Path?` base, which cannot express ownership,
so `ShOptionsExecutionLocationAdapter` re-derived `WorkspaceLease.Managed` for
every invocation. `authorizeRootDestruction` therefore always returned
`Permitted` and `protectWorkspaceRoot` was always `false`.

Reproduced against the installed distribution at `HEAD=e3e2140b`: a project
containing `important.txt` and a `deleteDir()`-only pipeline had its file
deleted, exit 0.

The guard was live in the type and dead in the wiring.

## Why every preceding check missed it

Each guard test constructed its own `ExecutionLocation` and asked the adapter a
question it was already designed to answer. The corpus passed for the wrong
reason: `11-workflow-control` runs `deleteDir()` over a disposable workspace that
is legitimately `Managed`, so a guard that never fires and a guard that fires
correctly both yield exit 0. No fixture ever pointed `--workspace` at a real
user-owned root, which is the only input that distinguishes them.

UAT-LOCAL-011 made this explicit: SC-011-04 asserted **the opposite of ADR-0102**
— exit 0 from bare `deleteDir` over an attached root — for the entire life of the
feature, and no scenario covered the refusal at all.

## The correction

`9d2e999a` groups both facts into one typed transport read from a single
resolved lease, rather than adding a second derivation from the flags:

- `WorkspaceIntent.resolveRuntimeTransport` returns
  `RuntimeWorkspaceTransport(base, ownership)`; `runtimeWorkspaceBase` is
  deleted, because a base without an owner *is* the defect.
- `ShOptions` carries `workspaceOwnership`; `CompositionRoot` and both `Main`
  call sites forward it.
- The bridge passes it to the adapter, which derives `Attached` for `USER` and
  keeps `Managed` as the fail-closed default when ownership is unstated.

The new tests were confirmed RED with the fix reverted (3 failures) and then
GREEN, so they demonstrably bind to the defect rather than merely passing.

## Standing lesson

An adapter unit test proves the adapter behaves. It does not prove the value
reaching it was ever populated. Any gate about a *transported* fact needs an
end-to-end witness through the real CLI, not a directly constructed fixture.

## Scope of the "corpus green" claim

The corpus is not uniform about workspace mode, and that is a property of the
fixtures, not a defect:

| fixture | mode | why |
|---|---|---|
| `10-smoke-e2e` | `--isolated` | `git clone .` needs an empty destination |
| `11-workflow-control` | `--isolated` | bare `deleteDir()` needs a managed root |
| `29-mixed-utilities` | attached | writes in one stage, reads in four later ones |

Cross-stage workspace continuity is an **Attached** property; `Managed`
deliberately lacks it because ADR-0101 declares per-stage scratch. No single CLI
flag can serve the whole corpus. An earlier attempt in this slice unified the
runner on `--isolated` and fixture 29 failed immediately, which is the evidence
that the property is real rather than incidental.

**Open product question, deliberately not settled here:** whether `--isolated`
should itself provide stage-to-stage continuity. As ADR-0101 is written it does
not, and fixture 29 shows that breaks cross-stage data flow for multi-stage
pipelines, which is Jenkins semantics. Changing it would contradict an accepted
ADR.