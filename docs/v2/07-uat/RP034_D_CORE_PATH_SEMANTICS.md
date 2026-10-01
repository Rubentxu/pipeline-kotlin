# RP034-D — Core path semantics: contract freeze and RED

**Status:** Migration applied to the file vertical; `deleteDir` ownership work belongs to RP034-G
**WU:** WU-RP-034 · **WorkItem:** `3f55b43f-a080-4f46-9eea-01aa230913cf`
**SDDK cycle:** `p-1f3622e11c093341/train-s2-directive-plugin`
**Base:** `9726a01dc80d523f8b77d0414ac9a775b66a37e5`

## Scope

State the law for the core vertical (`dir`, `sh`, `pwd`, `writeFile`,
`readFile`, `fileExists`, `deleteDir`) as executable tests, and record the
current divergence as a discriminating RED.

## The law

```text
inside dir("backend"):
    sh("pwd")            -> <root>/backend
    writeFile("x.txt")   -> <root>/backend/x.txt
    readFile("x.txt")    -> <root>/backend/x.txt
    fileExists("x.txt")  -> true
    pwd()                -> <root>/backend
    workspace root       -> <root>          (unchanged)
```

`CorePathSemanticsTest` — **9/9 green**, asserting against the domain authority
that the adapters are being moved onto:

| Property | Status |
|---|---|
| file Steps resolve under the `dir` scope, like the shell | green |
| shell cwd and filesystem base never diverge | green |
| workspace root invariant across the scope (INV-WS-001) | green |
| nested `dir` composes from the outermost root | green |
| leaving a scope returns the parent with no global state | green |
| traversal and absolute escapes refused, incl. from a nested scope | green |
| a `WORKSPACE_ROOT` anchor ignores the `dir` scope | green |
| a real non-VCS directory is still protected from root destruction | green |

## Migration applied

`WorkspaceOperationsAdapter` now takes the shared `ExecutionLocationCapability`
and resolves `writeFile`, `readFile` and `fileExists` through one
`effectiveWorkspaceRoot(...)` method that prefers the location's
`CURRENT_DIRECTORY` and falls back to the per-stage `WorkspaceResolver` when no
location is supplied. Routing all three through a single method is what stops
any of them drifting back to a private reconstruction.

`DirScopeEndToEndTest` — **green**, proving it end to end through the real CLI:

```kotlin
dir("backend") {
    writeFile("marker.txt", "written-inside-dir")
    echo(pwd())
}
```

The file lands at `<workspace>/backend/marker.txt` with the expected content,
and `pwd()` reports the scope. Before this change the same pipeline wrote to the
stage workspace while `sh` ran inside the scope.

### The RED was rewritten, not deleted

The original `ShellFilesystemCwdDivergenceTest` compared `WorkspaceResolver`
directly, so after the migration it still passed — while no longer exercising the
code path that changed. A RED that stops discriminating is worse than none: it
reports success while proving nothing. It was rewritten to assert the law through
the adapter's own resolution, and now fails if the verticals ever diverge again.

### No regression

`CompatibilityCorpusTest` **30/30 green** after the migration, and
`:pipeline-application:detekt` clean. The `LongMethod` finding introduced by the
wiring was resolved by extracting `workspaceOperationsFor`, not by suppressing
the rule.

## Method note

One test defect was corrected rather than the code: the confinement cases
initially cast the resolver outcome to `Resolved` inside a shared helper, so a
correct refusal surfaced as a `ClassCastException` that hid the reason. A
separate `outcome(...)` accessor now observes refusals as refusals. A test that
means to verify confinement must not fail in a way that conceals what it
verified.

## Remaining

`deleteDir` still resolves through its own `WorkspaceResolver` and still uses the
`ProjectCheckoutDetector` heuristic; both are RP034-G, which must land before any
CLI default flip. RP034-E covers the non-core consumers, RP034-H the default.
