# S2_FINDFILES — Closure Receipt

Slice 2 / S2.3: `core-utils.findFiles`. Step referente a Jenkins
`pipeline-utility-steps-plugin::FindFilesStep` (MIT, CloudBees).

## Reference consulted

- `pipeline-utility-steps-plugin/src/main/java/.../fs/FindFilesStep.java`
  (MIT, CloudBees).
- `pipeline-utility-steps-plugin/src/main/java/.../fs/FindFilesStepExecution.java`
  (MIT).
- `pipeline-utility-steps-plugin/src/main/java/.../fs/FileWrapper.java`
  (MIT).
- `pipeline-utility-steps-plugin/src/test/java/.../fs/FindFilesStepTest.java`
  (MIT).

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `findFiles()` (no glob) -> direct children only | `FindFilesPattern.None` -> `Files.list(base)` |
| `findFiles(glob: ...)` -> recursive scan | `FindFilesPattern.Glob(g, e)` -> `Files.walk(base)` + `PathMatcher("glob:g")` |
| `findFiles(glob, excludes)` -> exclude matches | second optional glob pattern applied as post-filter |
| Returns `FileWrapper[]` (name, path, dir, length, lastModified) | `FileEntry` data class with the same five fields |
| Paths are workspace-relative, forward-slash | same: `path` always uses `/`, never `\` |

## Deviations from Jenkins

1. **Closed `FindFilesPattern` ADT replaces runtime `glob`/`excludes`
   setters.** Mixing the "no glob" and "with glob" shapes is
   unrepresentable at compile time.
2. **Capability-routed handler.** Jenkins reaches for `FilePath` via the
   step context; we declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve
   through the capability seam.
3. **Java NIO glob instead of Ant-glob via `FilePath.list`.** Ant-glob
   is Remoting-aware and agent-side; we are in the worker JVM with no
   remote transport, so NIO's `PathMatcher("glob:...")` is the right
   primitive. We also wrap it with a Jenkins-compat two-stars-slash
   fallback so `**/star.txt` matches `star.txt` at the root too (NIO's
   default requires `**` to consume at least one segment).
4. **Symlinks never followed.** `Files.walk` without `FOLLOW_LINKS`
   matches Jenkins' `FilePath.list` semantics; a malicious tarball that
   drops a symlink pointing at `/etc/passwd` cannot be enumerated by
   `findFiles`. Security probe is in the contract suite.
5. **Soft result cap.** `MAX_RESULTS = 100_000`; overflow raises a typed
   `PluginStepException` with `kind = USER`. Jenkins relies on the agent
   JVM OOM-ing; we fail closed with a clear message instead.
6. **`FileEntry` equality is total.** Jenkins' `FileWrapper` equals only
   on `path`; we compare all five fields. This is the right behaviour
   for a typed result.

## Security implications reviewed

- **Symlink traversal outside the workspace.** Confirmed in the
  contract suite: a symlink inside the workspace pointing at a directory
  outside it is NOT traversed. `Files.walk` does not follow symlinks by
  default.
- **Path traversal via glob.** `PathMatcher` matches only paths inside
  the workspace root; a glob like `../etc/passwd` resolves outside `base`
  and produces no matches because the walker is rooted at `base`.
- **ReDoS in the glob.** NIO compiles the pattern once, not per-file;
  the supported glob syntax is a small finite DSL that cannot suffer
  catastrophic backtracking.
- **Pathological workspaces.** Capped at 100_000 matches with a typed
  USER failure.

## Contract tests (15 new in this slice)

```
identity — findFiles Key is core-utils dot findFiles
contract — findFiles declares READ_ONLY, MEMOIZED, WORKSPACE_IDENTITY_CAPABILITY
codec findFiles input — roundtrip preserves base + pattern variants
codec findFiles output — roundtrip preserves basePath + patternEcho + files
envelope — findFiles input codec emits a well-formed JSON object (durable eligible)
success — findFiles no glob returns direct children only (Jenkins simpleList)
success — findFiles recursive glob returns all matching files (Jenkins listAll)
success — findFiles sub-tree glob matches only nested files (Jenkins listSome)
success — findFiles excludes drops matches (Jenkins listSomeWithExclusions)
success — findFiles returns FileEntry with all five fields populated
success — findFiles directories appear with trailing slash and directory=true
typed failure — findFiles on missing base raises USER class
typed failure — findFiles on file (not directory) raises USER class
replay — findFiles output is deterministic for identical workspace contents
security — findFiles does NOT follow symlinks (symlink target outside base is not enumerated)
```

## Evidence

```
:pipeline-step-sdk:utilities:test
  tests="78" failures="0" errors="0" timestamp="2026-09-19T21:21:31Z"
  YamlSafetyCharacterisationTest tests="6" failures="0" errors="0"
```

## Step state

- `core-utils.findFiles`: **CERTIFIED**.
- Slice 1 + S2.1 readYaml + S2.2 writeYaml: untouched, all CERTIFIED.

## Ledger update

```
Certified Steps:           6  (readJson, writeJson, sha256,
                              readYaml, writeYaml, findFiles)
Legacy executable Steps:   0
Registry-primary Steps:    6
```

## Commit

`ed0c43a0 S2.3: core-utils.findFiles (Jenkins-reference Step) — sealed Pattern.None/Glob, 78/78 contract tests green`
