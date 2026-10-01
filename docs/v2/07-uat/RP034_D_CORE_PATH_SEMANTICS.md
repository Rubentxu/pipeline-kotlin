# RP034-D — Core path semantics: contract freeze and RED

**Status:** Contract frozen; vertical migration in progress
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

## The divergence being removed

`ShellFilesystemCwdDivergenceTest` (RP034-D RED) is green and discriminating.

`WorkspaceOperationsAdapter` rebuilds `WorkspaceResolver(controlDirRoot,
workspaceBase)` on **every** operation (`WorkspaceOperations.kt:87,121,145`),
so the file vertical never receives the `dir` scope that the shell vertical gets
through `ShOptions.workingDirectory`. Inside a `dir` block the two resolve the
same relative path against different bases.

This is a *recording*, not a target: it asserts the divergence exists. It will
fail once the file Steps are migrated onto the shared execution location, and at
that point its assertion inverts into the agreement law. A green run before that
migration would mean the RED had stopped discriminating, which its
`assertNotEquals` precondition makes explicit.

## Method note

One test defect was corrected rather than the code: the confinement cases
initially cast the resolver outcome to `Resolved` inside a shared helper, so a
correct refusal surfaced as a `ClassCastException` that hid the reason. A
separate `outcome(...)` accessor now observes refusals as refusals. A test that
means to verify confinement must not fail in a way that conceals what it
verified.

## Remaining

Migration of the file Steps onto the shared execution location, then the same
for `deleteDir`. RP034-E covers the non-core consumers, RP034-G the ownership
guard, RP034-H the CLI default flip.
