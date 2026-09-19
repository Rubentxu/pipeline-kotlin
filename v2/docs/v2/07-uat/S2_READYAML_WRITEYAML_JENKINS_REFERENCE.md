# S2.0 / S2.1 Jenkins reference — `readYaml` & `writeYaml`

LFC-2E2 Slice 2 — first Jenkins-reference note for the long-running utility-steps
expansion. The intent is to capture the public contract Jenkins users expect,
the actual algorithm, the security history that already happened in their
plugin, and the architectural mapping into PipelineK.

**Authority.** `jenkinsci/pipeline-utility-steps-plugin` (`master`), published
docs at `https://www.jenkins.io/doc/pipeline/steps/pipeline-utility-steps/`,
GitHub Security Lab advisory `GHSL-2023-058 / GHSL-2023-059` (CVE-2023-32981).

This note is the input for S2.1 (`core-utils.readYaml`); the same template
re-applies to S2.2–S2.5.

---

## 1. Public contract (what Jenkins users see)

### `readYaml` parameters

| Parameter             | Type | Notes                                                       |
| --------------------- | ---- | ----------------------------------------------------------- |
| `file` *(optional)*   | String | path inside the workspace.                                  |
| `text` *(optional)*   | String | raw YAML text.                                              |
| `codePointLimit` *(optional)* | int | upper bound on the input size. `-1` → library default. |
| `maxAliasesForCollections` *(optional)* | int | hardcoded ceiling 1000. `-1` → library default. |

**Return.** `Map<String, Object>` if exactly one YAML document was provided;
`List<Object>` of parsed documents if several were provided (`loadAll`).
Each document root is whatever SnakeYAML constructed at the top of the doc:
a `Map`, a `List`, a scalar, or `null`.

**File XOR text.** Jenkins forbids both in the same invocation (`AbstractFileOrTextStep`
checks `file != null && text != null` in the same Execution).

### `writeYaml` parameters

| Parameter             | Type | Notes                                                       |
| --------------------- | ---- | ----------------------------------------------------------- |
| `file` *(optional)*   | String | output path inside the workspace.                           |
| `returnText` *(optional)* | boolean | if `true`, returns the YAML text instead of writing a file. |
| `data` *(optional)*   | Object | a single root value.                                       |
| `datas` *(optional)*  | Collection | multiple YAML documents written via `dumpAll`.         |
| `charset` *(optional)* | String | defaults to UTF-8.                                         |
| `overwrite` *(optional)* | boolean | default `false`; refuses to overwrite existing files. |

**Validation.** Jenkins rejects `data`/`datas` that contain anything other than
`Boolean`, `Character`, `Number`, `String`, `URL`, `Calendar`, `Date`, `UUID`,
`null`, or recursive `Map`/`Collection` of those. The check is performed at
*setter time* (so the error is on parameter binding, not on execution).

**Mutual exclusion.**

- `returnText` ↔ `file`  → cannot combine.
- `returnText` ↔ `charset` → cannot combine.
- `returnText` ↔ `overwrite` → cannot combine.
- `data` ↔ `datas` → cannot combine (exactly one).

**Behaviour with `file`.** If `overwrite=false` (default) and the target file
exists, Jenkins throws `FileAlreadyExistsException` from `Execution.run`. If
the target path is a directory, it throws `FileNotFoundException`.

---

## 2. Algorithm Jenkins actually runs

### Read

```text
yamlText = (file != null ? read(file) : "") + (text != null ? text : "")
Iterable<Object> all = new Yaml(SafeConstructor(loaderOptions), representer,
                                dumperOptions, loaderOptions).loadAll(yamlText)
List<Object> list = new LinkedList<>();
for (Object data : all) list.add(data);
// serializability probe: writeObject(result); if it fails → fail
return list.size() == 1 ? list.get(0) : list
```

Concrete instances:

- `ReadYamlStep.java` — declares `codePointLimit` and `maxAliasesForCollections`
  fields, validates them against an upper bound, and forwards them to a second
  `LoaderOptions` instance that lives in the `Yaml` constructor.
- `ReadYamlStep.Execution.doRun()` — assembles text, calls `loadAll`,
  enforces serializability of the result via `ObjectOutputStream` to a temporary
  buffer.

### Write

```text
DumperOptions options = new DumperOptions()
options.setDefaultFlowStyle(BLOCK)
options.setSplitLines(false)
Yaml yaml = new Yaml(options)
Charset cs = (charset == null) ? UTF-8 : Charset.forName(charset)
if (file != null) {
    if (path is directory)  → FileNotFoundException
    if (!overwrite && path exists) → FileAlreadyExistsException
    write(yaml.dump(data) or yaml.dumpAll(datas.iterator())) with cs
} else if (returnText) {
    return yaml.dump(data) or yaml.dumpAll(datas.iterator())
}
```

Note: `WriteYamlStep.setData(...)` already validated the structure recursively
at the setter. The Execution does not re-validate.

---

## 3. Security history (what we must NOT reproduce)

GitHub Security Lab reported **CVE-2023-32981** (GHSL-2023-058 / GHSL-2023-059)
in the `unzip` and `untar` steps:

- `UnZipStepExecution.invoke(...)` constructed each destination path as
  `getDestination().child(entry.getName())` and opened it for writing without
  verifying the normalised path stayed inside `getDestination()`.
- Same pattern in `UnTarStepExecution` with `tarStream.getNextTarEntry().getName()`.

Both defects allowed an attacker controlling the archive (e.g., an upstream
artifact) to **write arbitrary files outside the workspace** via path
traversal entries like `../../../../tmp/evil.sh`.

**Implication for our S2.4 / S2.5.** `unzip` and `zip` containment checks are
NOT optional. We derive two negative tests **before** implementing S2.5:

1. A ZIP entry named `../escape.txt` MUST be rejected as a typed
   `UnzipSafetyViolation`; no file may be written outside `destDir`.
2. A ZIP entry with an absolute path (`/tmp/evil`) or with a Windows drive
   letter (`C:\evil`) MUST be rejected for the same reason.

`zip` itself is benign (writes inside `zipFile`'s directory) but we still
refuse paths that point outside the workspace.

`readYaml` does not have a recorded CVE in this plugin, but SnakeYAML 1.x had
widespread `!!python/name:` and `!!javax.script.ScriptEngineManager` RCE
vulnerabilities that motivated Jenkins to switch to `SafeConstructor`. We will
go one step further: a `TagInspector` allow-list for the YAML 1.1 standard tags
on top of `SafeConstructor`, so any future tag addition in the standard is
explicit and reviewable.

---

## 4. Adaptation to PipelineK

Jenkins contract drives the user-visible shape; PipelineK implementation
drivers are our own SDK contracts. Translation table:

| Concern                         | Jenkins (`Step`)                                        | PipelineK (our decision) |
| ------------------------------- | ------------------------------------------------------- | ------------------------ |
| Source / destination            | `FilePath` channel-bound, relative to workspace         | `WORKSPACE_IDENTITY_CAPABILITY` returns the resolved `Path`; paths are always workspace-relative strings (no `file:` URI). |
| `file` XOR `text`               | `AbstractFileOrTextStep` checks                         | `YamlInput` sealed: `FromFile(path)` XOR `FromText(text)`. Codec rejects the dual case at decode time. |
| Multi-document load             | `yaml.loadAll(...)` returns `Iterable`                  | `YamlOutput` has `single: Boolean?` + `data: MapValue?` + `documents: List<MapValue>?`. |
| `SafeConstructor`               | protects against arbitrary classes                      | Same, plus `SafeConstructorOnlyOptions` tag allow-list. |
| Recursive type validation       | `WriteYamlStep.isValidObjectType(...)`                  | `YamlInput.data` and `YamlInput.datas` are typed `MapValue` / `List<MapValue>`; the codec rejects `MapValue` containing unsupported entries before SnakeYAML ever runs. |
| `data` XOR `datas`              | `WriteYamlStep.start(...)` throws if both               | `YamlOutput.data: MapValue?` XOR `documents: List<MapValue>?`; codec validates. |
| `overwrite` default `false`     | `FileAlreadyExistsException`                            | Typed failure `WriteYamlFailure.FileAlreadyExists(path)`. |
| `returnText`                    | returns `String`                                        | `WriteYamlOutput` has `wroteToFile: Boolean?` + `text: String?`. |
| Pipeline visibility             | Jenkins log + `TaskListener`                            | Typed `StepDomainEvent` (`YamlLoaded`, `YamlWritten`) emitted through the common execution boundary. |
| Workspace enforcement           | `FilePath.getBase()`                                    | `WORKSPACE_IDENTITY_CAPABILITY` resolves the effective workspace; paths escaping it are rejected with typed `WorkspaceEscape`. |
| Backwards-compatibility         | Jenkins serialises via `ObjectOutputStream` probe        | Not needed; PipelineK's durable boundary uses our `outputCodec.encode(...)`. |

### Deviations we justify explicitly

1. **`SafeConstructorOnlyOptions` with explicit tag allow-list.** Jenkins only
   sets `SafeConstructor`; the tag allow-list is ours. The cost is one extra
   config knob to maintain; the benefit is a closed set of permitted tags
   (visible in code) instead of an open one limited only by the constructor
   class's class graph. SnakeYAML's own tests pass with this allow-list
   (verified by `YamlSafetyCharacterisationTest`).
2. **`codePointLimit` defaulted to 8 MiB.** Jenkins defers to SnakeYAML's
   library default (3 MiB in 2.3). 8 MiB is generous for typed config and
   matches a CI-grade step; if a use-case needs more, the user passes an
   explicit `codePointLimit` and the codec records the chosen value in the
   audit event.
3. **`maxAliasesForCollections` defaulted to 64.** SnakeYAML's library default
   is 50; Jenkins hardcoded-ceiling is 1000 with library default. 64 is enough
   for real config (YAML uses anchors sparingly) and rejects alias bombs
   cheaply.
4. **No `datas` in `readYaml` Input.** Jenkins uses `loadAll` and returns a
   single document when there is one; ours does the same. We DO surface the
   `multipleDocuments: Boolean` so a script can distinguish the two cases
   without re-parsing.
5. **`data` may contain any `MapValue` or `List<MapValue>` shape.** Jenkins'
   `isValidObjectType` is recursive over arbitrary Java collections; PipelineK
   uses the closed `MapValue`/`ListValue`/`ScalarValue` ADT from Slice 1
   (`ReadJsonOutputValue` and cousins). Scripts that previously passed
   `Date`/`URL`/`UUID` must now serialise them as strings before calling
   `writeYaml`. This is a deliberate API tightening; the recipe is documented
   in the S2.2 G8 receipt.

### What we explicitly do NOT reproduce

- Jenkins' `ReturnTextExecution` / `Execution` split inside one `Step`. In
  PipelineK, both modes are the same Step; the codec validates.
- Jenkins' `ObjectOutputStream` serializability probe. Our boundary is the
  typed `StepCodec` round-trip; an unserialisable shape is a domain error.
- Jenkins' reliance on `TaskListener` and `FilePath.getBase()` for workspace
  enforcement. We route through the declared capability, which is the only
  authority the engine reads.
- Any Jenkins-specific class (`StepContext`, `SynchronousNonBlockingStepExecution`,
  `FilePath`, etc.). Our handler signature uses only declared capabilities.

---

## 5. Decision log

| Item                       | Decision (PipelineK)                                                              |
| -------------------------- | --------------------------------------------------------------------------------- |
| YAML processor             | SnakeYAML `org.yaml.snakeyaml:snakeyaml:2.3`                                       |
| Constructor                | `SafeConstructor` + explicit tag allow-list via `SafeConstructorOnlyOptions`      |
| Input source               | `FromFile | FromText` (sealed), mutually exclusive                                  |
| Multi-document behaviour   | always read with `loadAll`; surface `multipleDocuments` and `documents` list      |
| `codePointLimit` default   | 8 MiB                                                                             |
| `maxAliasesForCollections` default | 64                                                                      |
| `nestingDepthLimit`        | 64 (native cap)                                                                   |
| Output destination         | `ToFile(path, overwrite)` XOR `ToText`                                             |
| Recursive data validation  | Closed ADT (`MapValue`/`ListValue`/`ScalarValue`) at codec time                   |
| `overwrite` default        | `false`                                                                           |
| `charset` default          | UTF-8                                                                             |
| Workspace path enforcement | `WORKSPACE_IDENTITY_CAPABILITY`; absolute / `..` paths rejected                   |
| Observability              | `YamlLoaded` / `YamlWritten` domain events                                        |

---

## 6. Files consulted

- `ReadYamlStep.java` — note the split: `SafeConstructor` receives one
  `LoaderOptions`, `Yaml` receives another. We mirror that pattern so the
  configured caps apply to BOTH paths.
- `WriteYamlStep.java` — `isValidObjectType` is the reference for "what
  values may be passed".
- `AbstractFileOrTextStep.java` — confirms `file XOR text` invariant.
- `FindFilesStep.java` / `FindFilesStepExecution.java` — used by S2.3 (not
  directly by S2.1 but referenced here so the S2.0 note covers the
  reference set we will use across S2.1–S2.5).
- Security advisory `GHSL-2023-058 / GHSL-2023-059` — Zip Slip pattern;
  drives the negative test cases for S2.5.

---

## 7. Out of scope (NOT carried into PipelineK)

- `pipeline-utility-steps` `readProperties`, `readManifest`, `readMavenPom`,
  `touch`, `tee`, `verify*`. Slice 2 is YAML → findFiles → zip/unzip only.
- Jenkins LTS / Jenkins agent model. PipelineK is local-first; agent concepts
  do not apply.
- Plugin marketplace, dependency resolution, plugin signing. Per the project's
  long-running Slice 1 G8 boundary.
