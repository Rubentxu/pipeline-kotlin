# S2_ZIP — Jenkins reference & adaptation decisions

Slice 2 / S2.4: `core-utils.zip`. Step referente a Jenkins
`pipeline-utility-steps-plugin::ZipStep` (MIT, CloudBees).

## Reference

- `pipeline-utility-steps-plugin/src/main/java/.../zip/ZipStep.java`
  (MIT, CloudBees).
- `pipeline-utility-steps-plugin/src/main/java/.../zip/CompressStepExecution.java`
  (MIT).
- Jenkins Security Advisory 2023-05-16 / CVE-2023-32981 / SECURITY-2196
  (Pipeline Utility Steps 2.15.2 Zip Slip fix in 2.15.3).

## CVE-2023-32981 — what happened

> Pipeline Utility Steps Plugin 2.15.2 and earlier does not validate or
> limit file paths of files contained within these [tar / zip] archives.
> This allows attackers able to provide crafted archives as parameters
> to create or replace arbitrary files on the agent file system with
> attacker-specified content. Pipeline Utility Steps Plugin 2.15.3
> rejects extraction of files in `tar` and `zip` archives that would
> be placed outside the expected destination directory.

The fix uses canonical-path containment: every archive entry is
resolved against the destination root, the result is normalised, and
the normalised path MUST start with the canonicalised destination.
Anything else is rejected with an `IOException` (which becomes a typed
USER failure in our handler).

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `zip zipFile: 'foo.zip', archive: 'src/**'` — create a zip at a path from a glob | `ZipInput(path, glob, archiveName?)` sealed ADT (file destination XOR archive contents) |
| Glob sources are scoped to the workspace | capability-routed handler resolves the workspace via `WORKSPACE_IDENTITY_CAPABILITY`; relative paths resolve to the workspace root; absolute paths are honoured verbatim |
| Output: the zip is written to disk; no return value | typed `ZipOutput(absolutePath, byteSize, sha256Hex, entryCount)` so downstream steps can verify content |
| Refuse to overwrite an existing archive unless `overwrite=true` (Jenkins default) | same: `overwrite=false` raises typed USER failure |

## Deviations from Jenkins

1. **Sealed `ZipSources` ADT replaces the `archive:` string.** A caller
   picks exactly one of: `FromGlob(glob)`, `FromDirectory(path)`,
   `FromFiles(paths)`. Mixing them is unrepresentable at compile time.
2. **Closed `ZipEntryInfo` ADT replaces `ZipEntry`.** The handler
   produces a sealed summary of what was archived (regular file, dir,
   symlink-followed-as-regular, ignored-outside-base) so the durable
   envelope is a typed shape rather than a Groovy class list.
3. **Capability-routed handler.** Jenkins reaches for `FilePath` via the
   step context; we declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve
   through the capability seam. The handler never imports
   `ProcessBuilder`, `Runtime.exec`, `bash -c`, etc.
4. **Symlinks are followed for archival.** A symlink under the workspace
   is archived as a regular file containing the link target's bytes.
   This matches Jenkins' `FilePath.zip` behaviour. We document the
   expectation in the receipt so users do not assume symlinks are
   preserved as links in the resulting archive.
5. **Result envelope is observable.** Jenkins' `zip` step returns no
   value; we return a `ZipOutput` with the absolute path, byte size,
   SHA-256 of the archive, and the entry count. This makes the step
   composition-friendly with `core-utils.sha256` and
   `core-utils.writeJson`.

## Algorithm (Create)

```text
input  : ZipInput(path, overwrite, sources)
sources: Sources = FromGlob(g)        -> walk the workspace
                    | FromDirectory(d) -> walk the directory
                    | FromFiles(ps)    -> include the literal list
base   : workspace root resolved through WORKSPACE_IDENTITY_CAPABILITY
output : ZipOutput(absolutePath, byteSize, sha256Hex, entryCount)

steps  :
   1. resolve target = workspaceRoot.resolve(path) (or absolute)
   2. if target.exists() and not overwrite -> USER failure
   3. target.parent.mkdirs() if missing
   4. ZipOutputStream(target) wrapped in a try-with-resources
   5. for every source file (relative path = source.relativeTo(baseRoot))
          if source is outside the source base, skip with a typed
          ignorable entry (NEVER raise: caller asked for the glob)
       zipPath = relative.toString().replace(File.separatorChar, '/')
       if zipPath is blank or starts with "/" -> skip
       putNextEntry(zipPath); write bytes; closeEntry
   6. sha256 = SHA-256 of the resulting archive bytes
   7. ZipOutput returned
```

We never call a process; we never use `Runtime.exec`; we use the
NIO + `java.util.zip.ZipOutputStream` primitives directly.

## Security implications reviewed

- **Symlink inside the archive source.** A symlink under the source
  root is followed; its TARGET bytes are written into the archive.
  This is the Jenkins behaviour. Callers who care about preserving
  symlinks as links should use a dedicated step (out of scope for
  this slice).
- **Glob source escaping the workspace.** The handler resolves the
  glob against the source base, not the workspace root. If the source
  is `FromGlob("../../etc/passwd")` and the glob happens to match
  (because the base is itself a path that allows traversal), we
  archive the result. The Step enforces that the resolved source base
  is contained inside the workspace root — otherwise we raise a typed
  USER failure before writing anything. This is the CVE-2023-32981
  mitigation applied to our own step.
- **Files larger than the archive cap.** We refuse archives > 4 GiB
  (Java `ZipOutputStream` limitation is 4 GiB for the legacy format)
  with a typed USER failure before writing.
- **Result envelope cannot leak paths.** The returned
  `ZipOutput.absolutePath` is the workspace-relative resolved path
  resolved by the engine, not a caller-supplied string. The byte size
  and digest are computed from the file on disk; they cannot be
  poisoned by the durable envelope.

## Step state

- `core-utils.zip`: **CERTIFIED** (after this slice closes).
