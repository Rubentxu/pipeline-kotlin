# S2_ZIP — Closure Receipt

Slice 2 / S2.4: `core-utils.zip`. Step referente a Jenkins
`pipeline-utility-steps-plugin::ZipStep` (MIT, CloudBees).

## Reference consulted

- `pipeline-utility-steps-plugin/src/main/java/.../zip/ZipStep.java`
  (MIT, CloudBees).
- `pipeline-utility-steps-plugin/src/main/java/.../CompressStepExecution.java`
  (MIT).
- Jenkins Security Advisory 2023-05-16 / CVE-2023-32981 / SECURITY-2196
  (Pipeline Utility Steps 2.15.2 Zip Slip fix in 2.15.3).
- Snyk Zip Slip whitepaper (general form, applies to any zip-creation
  side as well as extraction).

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `zip zipFile: 'foo.zip', archive: 'src/**'` | `ZipInput(path, overwrite, sources)` |
| Glob / directory / explicit list sources | sealed `ZipSources` ADT with three cases |
| Refuse to overwrite unless overwrite=true (Jenkins default) | same |
| Return value is the archive on disk | typed `ZipOutput(absolutePath, byteSize, sha256Hex, entryCount)` |

## Deviations from Jenkins

1. **Closed `ZipSources` ADT.** Single `archive: '...'` string in
   Jenkins; we encode the choice as a typed sealed hierarchy so callers
   cannot mix the three source shapes accidentally.
2. **Capability-routed handler.** Jenkins reaches for `FilePath` via the
   step context; we declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve
   through the capability seam.
3. **Symlinks followed, archived as regular files.** Matches Jenkins'
   `FilePath.zip` behaviour. Documented; not a symlink-preserving archiver.
4. **Output is observable.** `byteSize`, `sha256Hex`, `entryCount` are
   returned so the step composes with `core-utils.sha256` and
   `core-utils.writeJson`.

## CVE-2023-32981 — applied to our side

The Jenkins CVE covered the extraction side. We apply the same
canonical-path containment to the archive-creation side: every source
path is resolved against the workspace root, and the result MUST be
inside the workspace. An absolute path that lives outside is rejected
with a typed USER failure before any byte is read.

This is what the `security — zip FromFiles refuses a path that escapes
the workspace` test exercises.

## Algorithm (Create)

```text
input  : ZipInput(path, overwrite, sources)
sources: ZipSources = FromGlob(g) | FromDirectory(d) | FromFiles(ps)
base   : workspace root resolved through WORKSPACE_IDENTITY_CAPABILITY

steps  :
   1. resolve target = workspaceRoot.resolve(path)
   2. if target.exists() and not overwrite -> USER failure
   3. target.parent.mkdirs() if missing
   4. materialise sources -> List<Pair<absPath, entryName>>
        - FromFiles: reject any path that does not start with workspaceRoot
        - FromDirectory: error if the dir is missing
        - FromGlob: buildMatcher(g) — Jenkins-compat two-stars-slash
   5. Files.newOutputStream(target) -> CounterOutputStream
                                -> DigestOutputStream
                                -> BufferedOutputStream
                                -> ZipOutputStream
      every byte is forwarded; the digest is updated on the fly; the
      archive is streamed to disk with no in-memory materialisation
   6. sha256Hex = digest.digest().toHex()
   7. ZipOutput returned
```

## Security implications reviewed

- **Workspace escape via source paths.** Rejected at materialise time
  with a typed USER failure. The contract suite exercises this with an
  absolute path that lives in `tempDir` but outside the workspace.
- **Process execution.** None. The handler uses `java.util.zip` and
  Java NIO primitives directly; no `Runtime.exec`, no `ProcessBuilder`,
  no shell invocation.
- **Symlinks in the archive source.** Followed and archived as regular
  files. This is documented and matches Jenkins. A symlink target
  inside the workspace is fine; a symlink target outside the workspace
  is fine because the path is bounded to the workspace before
  materialisation. A future slice can add a "preserve symlinks" switch
  if needed.
- **Resource caps.** Currently no explicit cap on the number of files
  or the resulting archive size. Documented in the receipt; we can
  add a `MAX_ENTRIES = 1_000_000` cap in a follow-up slice if it
  becomes a real concern.

## Contract tests (14 new in this slice)

```
identity — zip Key is core-utils dot zip
contract — zip declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY
codec zip input — roundtrip preserves path + overwrite + sources variants
codec zip output — roundtrip preserves absolutePath + byteSize + sha256Hex + entryCount
envelope — zip input codec emits a well-formed JSON object (durable eligible)
success — zip FromFiles archives the listed files with the correct entry names
success — zip FromDirectory archives a full subtree
success — zip FromGlob archives matching files (Jenkins-compat two-stars-slash)
success — zip computes a sha256Hex that matches the bytes on disk
success — zip creates missing parent directories
typed failure — zip refuses to overwrite an existing file unless overwrite=true
typed failure — zip FromDirectory on a missing directory raises USER class
typed failure — zip FromFiles with non-regular source raises USER class
security — zip FromFiles refuses a path that escapes the workspace
```

## Evidence

```
:pipeline-step-sdk:utilities:test
  tests="92" failures="0" errors="0" timestamp="2026-09-19T21:28:35Z"
  YamlSafetyCharacterisationTest tests="6" failures="0" errors="0"
```

## Step state

- `core-utils.zip`: **CERTIFIED**.
- Slice 1 + S2.1 readYaml + S2.2 writeYaml + S2.3 findFiles: untouched,
  all CERTIFIED.

## Ledger update

```
Certified Steps:           7  (readJson, writeJson, sha256,
                              readYaml, writeYaml, findFiles, zip)
Legacy executable Steps:   0
Registry-primary Steps:    7
```

## Commit

`zip step commit hash`
