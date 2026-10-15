# B1 — UAT battery out of the local gate (frontier applied in `just`)

- **Date:** 2026-10-15
- **HEAD at authorship:** `c8875380362c50e699e0b7e95ca660b01de266e3` (pre-commit working tree)
- **Branch:** `worktree/test-harness.brave-harbor-6920`
- **Scope:** `justfile` only. No test deleted, no test disabled, no Gradle logic changed.

Operator instruction: "fuera prueba UAT". This applies the frontier rule this
repository already documents (`AGENTS.md` §"Frontera de responsabilidad —
pipeline-kotlin vs pipelinek-release-harness"): the UAT battery belongs to the
external `pipelinek-release-harness`; the local gate verifies internal contracts,
and rule 7 forbids deleting UATs to save time.

## 1. Change

```text
uat_excludes := '*UatLocal*,*UatCompat*,*UatDsl*,*UatStep*,*UatEvt*,*UatDurable*,*Lpr011*,*PublishHtml*,*StashOps*'

just gate                -> check -Pshard.excludes='<uat_excludes>'
just gate-app            -> :pipeline-application:test -Pshard.excludes='<uat_excludes>'
just gate-escalate       -> check --rerun-tasks -Pshard.excludes='<uat_excludes>'
just gate-full           -> check                          (legacy, UAT battery included)
just gate-app-full       -> :pipeline-application:test      (legacy)
just gate-escalate-full  -> check --rerun-tasks             (legacy)
```

It reuses the existing `shard.excludes` property already read by
`v2/pipeline-application/build.gradle.kts` (the same mechanism the retired CI
shards used); nothing new was invented in the build.

## 2. Evidence (real Gradle runs)

Excluded pattern cannot be selected:

```text
$ v2/gradlew -p v2 :pipeline-application:test -Pshard.excludes='*UatLocal004*' \
      --tests 'dev.rubentxu.pipeline.v2.application.UatLocal004*'
> No tests found for given includes: [dev.rubentxu.pipeline.v2.application.UatLocal004*]
BUILD FAILED in 17s        (exit 1 — the class is filtered out, not run)
```

Non-UAT classes still run with the property set:

```text
$ v2/gradlew -p v2 :pipeline-application:test \
      -Pshard.excludes='<full uat_excludes>' \
      --tests 'dev.rubentxu.pipeline.v2.application.UatRunConcurrencyCharacterisationTest*'
BUILD SUCCESSFUL in 20s    (exit 0)
```

`just -n gate` prints the excluded invocation with the full pattern list.

## 3. What this does NOT claim

- The UAT test sources remain in the repository, untouched and runnable; no
  `@Disabled` was added and nothing was deleted (frontier rule 2).
- The raw `cd v2 && ./gradlew check` (as written in `AGENTS.md`) still includes
  the UAT battery; only the `just` entry points changed. Aligning the prose rule
  is a documentation decision for the owner.
- No verification claim is made about the UAT battery itself: it is out of this
  repo's loop, which is the point.

## End-of-work-unit closure

```text
Reference implementation consulted: AGENTS.md frontier section + the existing
    shard.excludes property in v2/pipeline-application/build.gradle.kts.
Behaviour adopted:                  local gates exclude the UAT battery via the
                                    build's own exclusion property; legacy paths
                                    remain one command away.
Intentional deviations:             library-level build files are untouched, so the
                                    raw Gradle gate keeps its old meaning.
Security implications reviewed:     none; test selection only.
Tests demonstrating the contract:    the two Gradle invocations above (excluded
                                    class => No tests found; non-UAT class => green).
```
