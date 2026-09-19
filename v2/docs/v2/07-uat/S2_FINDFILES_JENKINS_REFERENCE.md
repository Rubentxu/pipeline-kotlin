# S2_FINDFILES — Jenkins reference & adaptation decisions

Slice 2 / S2.3: `core-utils.findFiles`. Step referente a Jenkins
`pipeline-utility-steps-plugin::FindFilesStep` (CloudBees, MIT).

## Reference

- `pipeline-utility-steps-plugin/src/main/java/.../fs/FindFilesStep.java` (MIT).
- `pipeline-utility-steps-plugin/src/main/java/.../fs/FindFilesStepExecution.java` (MIT).
- `pipeline-utility-steps-plugin/src/main/java/.../fs/FileWrapper.java` (MIT).
- `pipeline-utility-steps-plugin/src/test/java/.../fs/FindFilesStepTest.java` (MIT).

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `findFiles()` (no `glob`) → list **direct descendants** of cwd only | sealed `FindFilesInput` with explicit `Pattern.None(...)` vs `Pattern.Glob(glob, excludes)`; no glob = direct-only scan |
| `findFiles(glob: '...')` → recursive glob scan | Java NIO `PathMatcher` with `glob:` syntax (`**` recursive, `*` single-segment, `?` single char) |
| `findFiles(glob: '...', excludes: '...')` → exclude patterns | second optional glob pattern, applied as a post-filter on the included matches |
| `findFiles` returns an array of `FileWrapper` (name, path, directory, length, lastModified) | closed ADT `FileEntry` carrying all five fields; sealed so every shape is exhaustive |
| Paths are **relative to the workspace root**, forward-slash normalised | `path` field always uses forward slashes and is workspace-relative when the workspace is the base |

## Deviations from Jenkins

1. **Closed `FileEntry` ADT replaces `FileWrapper`.** Same five fields but typed as a data class. Jenkins' `FileWrapper` is a Groovy runtime class — we make the shape part of the contract.
2. **Capability-routed handler.** Jenkins reaches for `FilePath` via the step context. We declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve the workspace root through the capability; the handler never touches the coordinator or the engine.
3. **Java NIO glob instead of Ant-glob via `FilePath.list()`.** Jenkins' `FilePath.list(String glob, String excludes)` is a Remoting-aware version that uses Ant-style globs evaluated on the agent. Our handler runs inside the worker JVM with no remote transport, so `java.nio.file.FileSystems.getDefault().getPathMatcher("glob:...")` is the right primitive. Behaviour matches Jenkins for the supported syntax (`*`, `**`, `?`).
4. **Symlinks are never followed.** `Files.walk` with default options does not follow symlinks. This matches Jenkins' `FilePath.list` and is the right default for a workspace scanner; a malicious tarball that drops a symlink to `/etc/passwd` cannot be exploited by an enumeration step.
5. **No `FileWrapper.equals/hashCode`.** Jenkins implements equality by `path`. Our `FileEntry` is a data class so equality is total over all five fields — matches by content, not just path. This is the right behaviour for a typed result.

## Algorithm

```text
input   : FindFilesInput (base + Pattern)
base    : workspace root resolved through WORKSPACE_IDENTITY_CAPABILITY
pattern : None          -> children of base only (Files.list)
           Glob(g,e)    -> Files.walk(base), PathMatcher("glob:$g") keep,
                          then PathMatcher("glob:$e") drop if excludes != null
output  : FindFilesOutput(
             files     : sorted, root-relative, forward-slash, FileEntry per match,
             basePath  : absolute workspace root,
             pattern   : echo of the input (ForReplays),
           )
```

The walker never recurses into a directory the matcher cannot match
(`Files.walk` is BFS by default; we filter after, which is the
behaviour-matched Jenkins semantics). When `excludes` is provided, the
filter applies to the relative path of the candidate.

## Security implications reviewed

- **Symlink traversal.** Default `Files.walk` does NOT follow symlinks
  (`FOLLOW_LINKS` is not set). A pipeline that writes a symlink under
  the workspace pointing outside it cannot be exploited by `findFiles`
  to enumerate or read external files; the walker stops at the link
  boundary. This matches Jenkins' `FilePath.list` semantics.
- **Path traversal in the glob.** Glob syntax is interpreted by
  `PathMatcher` only; the matching is done against the workspace root,
  so a glob like `../../etc/passwd` cannot escape the workspace
  (resolving outside `base` simply produces no matches, because the
  walker is rooted at `base`).
- **Resource limits.** A pathological workspace can have millions of
  files. We document a soft limit (`maxResults = 100_000`) and raise a
  typed `PluginStepException` with `kind = USER` if exceeded. This is
  new relative to Jenkins (Jenkins relies on the agent JVM OOM-ing), but
  it is fail-closed behaviour that the user can override by passing a
  more specific glob.
- **ReDoS in the glob.** NIO's `PathMatcher` compiles the pattern once,
  not per-file. The supported glob syntax is a small finite DSL and
  cannot suffer catastrophic backtracking the way a generic regex can.

## Step state

- `core-utils.findFiles`: **CERTIFIED** (after this slice closes).
