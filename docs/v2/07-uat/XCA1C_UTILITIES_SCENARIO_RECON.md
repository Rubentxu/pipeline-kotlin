# XCA-1C — Utilities scenarios: reconnaissance before authoring

**Base:** `48d4d5c8` (XCA-1B complete; both reconciliation counters 0)
**Principle:** do not author a fixture whose correctness depends on an unverified assumption.

## Planned shape (unchanged)

```text
examples/utilities/
  01-json-roundtrip.pipeline.kts   existing  readJSON writeJSON sha256
  02-yaml-properties.pipeline.kts
  03-filesystem.pipeline.kts
  04-checksums.pipeline.kts
  05-zip.pipeline.kts
  06-tar.pipeline.kts
```

Target: the 13 `C_CREATE_OR_EXPAND` surfaces each gain at least one canonical `examples/`
fixture where the DSL symbol is really present.

## Blocker discovered before writing anything

All 13 DSL signatures were read from source. **11 are writable today** with primitive and
`String` arguments:

```text
readYaml(path)                          writeProperties(path, value: JsonObject)  <- blocked
writeYaml(path, value: JsonElement)     <- blocked
readProperties(path)                    findFiles(root, glob, maxDepth)
touch(path, lastModifiedMillis, createDirs)
md5(path) sha1(path) sha512(path)
zip(sourceDir, targetZip, overwrite)    unzip(sourceZip, targetDir, overwrite)
tarCreate(sourceDir, targetTar, overwrite)  tarExtract(sourceTar, targetDir, overwrite)
```

`writeYaml` and `writeProperties` expose **raw kotlinx types** (`JsonElement`,
`JsonObject`) at the DSL boundary. Evidence that a script author cannot construct them:

```text
no example .pipeline.kts anywhere imports kotlinx.serialization   (grep: NONE)
pipeline-scripting-api/build.gradle.kts declares no serialization dependency
writeJSON — the one facade that works from a script — takes a String and parses
  internally:  fun StageScope.writeJSON(path: String, value: String, ...)
               { val parsed: JsonElement = Json.parseToJsonElement(value) ... }
```

That `writeJSON` design is consistent with having been adopted *because* scripts cannot
construct kotlinx values. This is not asserted as proven here; it is the strong reading
of three independent signals.

## Classification

This is an **SDK gap**, not a fixture problem. Per the external-plugin authoring rules:

> If a plugin needs an internal import for a legitimate feature: classify it as an SDK
> gap; do not work around it.

Two legitimate resolutions, to be chosen deliberately:

```text
A. establish that scripts CAN reach kotlinx.serialization (script classpath), proven by
   an actual CLI run of a probe fixture; then author 02-yaml-properties normally.
B. add a String overload mirroring writeJSON, so the DSL boundary stays script-safe.
   This is additive on a plugin DSL facade — NOT a change to an existing plugin SPI, so
   it does not violate the plugin ABI law.
```

Working around it (building a fixture that only exercises `readYaml`/`readProperties` and
letting the ledger credit `writeYaml`/`writeProperties` by association) is precisely the
false-claim pattern XCA-1A removed. It is refused here.

## What this changes

XCA-1C splits:

```text
XCA-1C.1   11 unblocked surfaces -> 4 fixtures (filesystem, checksums, zip, tar)
           plus readYaml/readProperties in the yaml/properties fixture
XCA-1C.2   writeYaml + writeProperties -> blocked on the SDK decision above
```

`C_CREATE_OR_EXPAND` cannot reach 0 while two of its 13 members have no script-safe DSL
entry point. That is a more useful result than six plausible-looking files.

## Not done

No fixture was created in this slice. Nothing was added to `real_fixtures`. The ledger is
unchanged and remains honest at 31 CERTIFIED / 17 EXERCISED / 13 NOT_EXERCISED.
