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

---

# XCA-1C.1 — EXECUTED

## Fixtures created

```text
examples/utilities/02-yaml-properties.pipeline.kts   readYaml readProperties
examples/utilities/03-filesystem.pipeline.kts        findFiles touch
examples/utilities/04-checksums.pipeline.kts         md5 sha1 sha512
examples/utilities/05-zip.pipeline.kts               zip unzip
examples/utilities/06-tar.pipeline.kts               tarCreate tarExtract
examples/utilities/02-input.yaml                     input for readYaml
examples/utilities/02-input.properties               input for readProperties
```

Every signature was read from plugin source before writing, and not guessed:

```text
readYaml(path) readProperties(path) findFiles(root, glob, maxDepth)
touch(path, lastModifiedMillis, createDirs) md5/sha1/sha512(path)
zip(sourceDir, targetZip, overwrite) unzip(sourceZip, targetDir, overwrite)
tarCreate(sourceDir, targetTar, overwrite) tarExtract(sourceTar, targetDir, overwrite)
```

## Verification performed

Import resolution was checked mechanically, not assumed — all 14 imports across the five
fixtures resolve to a real `fun` in the declared package of a plugin source file:

```text
unresolved imports: 0
```

```text
31 CERTIFIED
  EXERCISED                          27
  NOT_EXERCISED                       2   writeYaml, writeProperties (blocked)
  STRUCTURAL_SYNTH                    1   core.emit.event
A_IN_EXAMPLES                        17   (was 4)
B_PROMOTE_COMPATIBILITY              11
C_CREATE_OR_EXPAND                    2   (was 13)
D_REWRITE_CONTRACT                    1
LEDGER-MAPPING FALSE CLAIMS           0
```

## Honest limit of this evidence

These fixtures are verified **statically**: imports resolve and the DSL symbol is present.
They have **not** been executed through the installed CLI. The ledger's `real_fixtures`
currently means "symbol present", which is the standard the other rows are held to; XCA-2
upgrades that meaning to "executed, with the StepKey observed in the canonical journal".
Until then these rows are claims of the same strength as the existing ones, no stronger.

## Two self-inflicted defects caught during this step

1. The first ledger update used `txt.index(" - step_key: utilities.readYaml")`, which also
   matches the **nested 6-space** entry. Since that entry appears earlier in the file, the
   fixture was written into **`core.load`'s** record. Detected by the auditor showing
   `readYaml` with zero fixtures while `readProperties` updated, then confirmed by diff
   (`registry_file: null` on the corrupted record = `core.load`). Reverted with
   `git checkout` and redone line-anchored on `^  - step_key: `.
2. The redo cached record line indices once, then edited the file top-down, so the first
   splice invalidated every later index and the script aborted on `readProperties` before
   writing anything. Fixed by processing in **descending** line order.

Neither wrote a wrong value to the ledger; the abort happened before `write_text` in case
2, and case 1 was caught and reverted. Both are the same family as the earlier false
greens: an ambiguous pattern treated as if it were an identity.
