# RP034-G — Ownership and destructive safety (GATE for RP034-H)

**Status:** CLOSED — typed ownership is now the authority for root destruction
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin`
**Base:** `481834eb155970a0caa62d877dc30919faa2dab7`
**Authority:** ADR-0102 (Accepted 2026-10-01)

## Why this gate precedes the CLI default flip

RP034-H makes `pipelinek run` attach the user's own checkout. If it landed
before this slice, the user's project would be reachable with destructive
protection still keyed on a VCS marker and still disengaged on the no-flag
path. This slice removes that possibility by making ownership a typed state
rather than an inference.

## The defect being removed

Both destructive adapters decided protection with:

```kotlin
protectWorkspaceRoot = workspaceBase != null &&
    ProjectCheckoutDetector.isProjectCheckout(workspaceBase)
```

Two distinct failures:

1. **Disengaged on the no-flag path.** The guard only engaged when `--workspace`
   was passed explicitly. Combined with the premature local-first flip this was
   the concrete state WU-RP-034 forbids: the user's checkout reachable with
   `deleteDir()`/`cleanWs()` unprotected.
2. **A bare non-VCS project read as disposable scratch.** The adapter's own
   KDoc recorded this as known limitation; ownership was inferred from the
   *absence* of a marker.

## The change

`DeleteDirOperationsAdapter` and `CleanWsOperationsAdapter` now take the shared
`ExecutionLocationCapability` and derive the guard from the lease:

```kotlin
protectWorkspaceRoot = executionLocation?.let {
    WorkspacePathResolver.authorizeRootDestruction(it.location.workspace, "deleteDir")
        !is DestructiveAuthorization.Permitted
} ?: (legacyProjectCheckoutHeuristic)
```

The typed lease is authoritative whenever it is present. The legacy heuristic
remains only as a fallback for direct construction, and is retired in RP034-I.

## Safety matrix demonstrated

`DestructiveSafetyOwnershipTest` — **11/11 green**, covering every shape the
slice must handle:

| Case | Attached (USER) | Managed (PIPELINEK) |
|---|---|---|
| plain non-VCS project | **refused** | permitted |
| git repository | **refused** | permitted |
| `.git` as a *file* (worktree/submodule) | **refused** | permitted |
| `.hg` and `.svn` checkouts | **refused** | permitted |
| empty named directory | **refused** | permitted |
| nested dir inside a repository | **refused** | permitted |
| subdirectory cleanup | governed by confinement, not the root guard | permitted |

The two cases the old heuristic got wrong are pinned explicitly: the bare
non-VCS project and the `.git`-file worktree. Ownership is identical whatever is
on disk, in both directions — adding a marker to an attached root changes
nothing, and adding one to a managed root does not revoke its cleanup.

## No regression

| Suite | Result |
|---|---|
| `CompatibilityCorpusTest` | **30/30** |
| `DestructiveSafetyOwnershipTest` | **11/11** |
| `UatLocal007SandboxProfileTest` | **14/14** |
| `:pipeline-application:detekt` | BUILD SUCCESSFUL |

The corpus case that matters most here is `11-workflow-control`, which calls
`deleteDir()` with `--workspace` over a disposable temp directory. It stays
green because a disposable temp directory reached through the bridge is
allocated as `Managed`, so the wipe contract is preserved for the right reason —
ownership, not the absence of a marker.

## Gate G

- [x] `ProjectCheckoutDetector` is no longer the authority when a lease exists
- [x] Attached root refuses `deleteDir`/`cleanWs` regardless of VCS type
- [x] Managed scratch keeps lifecycle cleanup
- [x] No safety decision depends on `.git`/`.hg`/`.svn`
- [x] Corpus, sandbox and safety suites green with no expectation edited

## Remaining

The legacy fallback path still exists in source and is retired in RP034-I.
RP034-E (non-core consumers), RP034-F (differentials) and RP034-H (CLI default
flip) remain; RP034-H may now proceed, this gate being satisfied.
