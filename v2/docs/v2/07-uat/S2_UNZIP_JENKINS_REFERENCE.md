# S2_UNZIP — Jenkins reference & adaptation decisions

Slice 2 / S2.5: `core-utils.unzip`. Step referente a Jenkins
`pipeline-utility-steps-plugin::UnZipStep` (MIT, CloudBees).

## Reference

- `pipeline-utility-steps-plugin/src/main/java/.../zip/UnZipStep.java`
  (MIT, CloudBees).
- `pipeline-utility-steps-plugin/src/main/java/.../zip/UnZipStepExecution.java`
  (MIT).
- Jenkins Security Advisory 2023-05-16 / CVE-2023-32981 / SECURITY-2196
  (Pipeline Utility Steps 2.15.3 fixed by adding `isDescendantOfDestination`
  check on every entry path).
- Snyk Zip Slip whitepaper (general Zip Slip form).

## CVE-2023-32981 — what happened and what we adopt

> Pipeline Utility Steps Plugin 2.15.2 and earlier does not validate or
> limit file paths of files contained within these [tar / zip] archives.
> This allows attackers able to provide crafted archives as parameters
> to create or replace arbitrary files on the agent file system with
> attacker-specified content.

The Jenkins fix (in `DecompressStepExecution`) computes, for every
archive entry, the destination path (`getDestination().child(name)`),
then calls `isDescendantOfDestination(path)` which is a
`getCanonicalPath().startsWith(destination.getCanonicalPath())` check.
If the entry is not a descendant, it throws `FileNotFoundException`
("out of bounds") and refuses the whole archive.

We adopt the same containment check, using `java.nio.file.Path`
semantics: the normalised target path MUST start with the normalised
destination root. Anything else raises a typed USER failure before
any byte is written.

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `unzip zipFile: 'foo.zip'` -> extract everything | `UnzipInput(path)` defaults `glob = null`, `read = false` |
| `unzip zipFile: 'foo.zip', glob: '...'` -> filter by glob | `UnzipInput(path, glob=...)` uses `findFiles`-compatible matcher |
| `unzip zipFile: 'foo.zip', dir: 'dest'` -> extract into a sub-directory | `UnzipInput(path, destination="dest")` creates `dest` under the workspace |
| `unzip zipFile: 'foo.zip', read: true` -> return file contents instead of writing | `UnzipInput(path, read=true)` returns `Map<String,String>` |
| `unzip zipFile: 'foo.zip', test: true` -> CRC check without writing | `UnzipInput(path, test=true)` returns `UnzipTestReport(ok, entryCount, badEntries)` |
| Per-entry Java NIO Path containment check | `materialise()` returns only the entries whose resolved target is inside the destination root; otherwise the whole archive is rejected |

## Deviations from Jenkins

1. **Capability-routed handler.** Jenkins reaches for `FilePath` via the
   step context; we declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve
   through the capability seam.
2. **No `ch:` / charset parameter.** All entries are decoded as UTF-8.
   The Jenkins charset parameter accepts any `Charset.forName(...)`
   string, which is a footgun (custom charsets can decode byte
   sequences the user did not expect). Our default is UTF-8; we do not
   expose a charset override in this slice.
3. **No `quiet` parameter.** Jenkins' `quiet` flag only suppresses
   `Extracting: %s -> %s` log lines. We do not log per-entry extraction
   by default — the returned `UnzipOutput` is observable.
4. **Symlinks inside the archive are NEVER followed.** A crafted zip
   entry with mode 0xA1ED (symlink) is treated as an unhandled entry
   and rejected. We do not honour symlinks during extraction: this is a
   defence against a related class of attacks where the attacker drops
   a symlink that points outside the workspace and then a subsequent
   step reads through it.

## Algorithm (Extract)

```text
input   : UnzipInput(path, glob?, destination?, read, test)
base    : workspace root resolved through WORKSPACE_IDENTITY_CAPABILITY

steps   :
   1. resolve archive = workspaceRoot.resolve(path)
   2. if archive missing -> USER failure
   3. destRoot = workspaceRoot.resolve(destination) (or workspaceRoot)
   4. normalisedDestRoot = destRoot.toAbsolutePath().normalize()
   5. open java.util.zip.ZipFile(archive)
   6. for each entry:
        entryPath = destRoot.resolve(entry.name)
        normalisedEntryPath = entryPath.normalize()
        if !normalisedEntryPath.startsWith(normalisedDestRoot) -> USER failure
                                                       (CVE-2023-32981)
        if entry.name contains backslash, drive letter, or absolute prefix
                                                       -> USER failure
        if entry.isDirectory -> mkdirs(entryPath)
        else:
           if test mode:
              compute CRC32 while reading; compare with entry.getCrc()
              if mismatch -> record bad entry, return ok=false at end
           elif read mode:
              read entry into String, put name -> content in result map
           else:
              Files.createDirectories(entryPath.parent)
              copy inputStream -> entryPath
              update digest + counter
   7. emit UnzipOutput (extractedCount, files: List<ExtractedFile>)
        or Map<String, String> when read=true
        or UnzipTestReport when test=true
```

## Security implications reviewed

- **Zip Slip.** The canonical containment check refuses any entry whose
  resolved target is outside the destination root. This is the
  CVE-2023-32981 mitigation. Tested by 4 contract cases (entry with
  `../`, entry with absolute path, entry with backslash, entry that
  resolves through a parent symlink — the last requires no follow).
- **Zip Slip via symlink.** A symlink entry whose link target points
  outside the workspace is rejected because its target path resolves
  outside `destRoot`. We refuse to extract symlink entries altogether
  in this slice.
- **Resource exhaustion.** A zip bomb (a tiny compressed file that
  expands to many GiB) is bounded by the entry-count cap
  (`MAX_ENTRIES = 1_000_000`) and by the per-entry size cap
  (`MAX_ENTRY_BYTES = 4 GiB`). Exceeding either cap raises a typed USER
  failure before writing.
- **CRC mismatch (test mode).** CRC32 is not a security primitive, but
  combined with the test-mode contract it gives the caller a fast way
  to verify a zip before extracting.

## Step state

- `core-utils.unzip`: **CERTIFIED** (after this slice closes).
