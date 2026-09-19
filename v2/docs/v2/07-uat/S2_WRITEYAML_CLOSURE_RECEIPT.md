# S2_WRITEYAML — Closure Receipt

Slice 2 / S2.2: `core-utils.writeYaml`. Step referente a Jenkins
`pipeline-utility-steps-plugin::WriteYamlStep`.

## Reference consulted

- `pipeline-utility-steps-plugin::WriteYamlStep.java` (MIT).
- `pipeline-utility-steps-plugin::WriteYamlStepTest.java` (MIT).
- `pipeline-utility-steps-plugin::PipelineUtilityStepsConvention.java`
  (binding contract for `writeYaml` / `writeYaml file: ...`, MIT).

## Behaviour adopted

| Jenkins contract | Adopted form |
| --- | --- |
| `data XOR datas` (exactly one of two payloads) | `WriteYamlPayload.Single(value) XOR Multiple(documents)` (sealed) |
| `file XOR returnText` (exactly one of two destinations) | `WriteYamlDestination.ToFile(path, overwrite) XOR ToText` (sealed) |
| `data XOR returnText` (excludes `returnText` when a file is written) | `WriteYamlOutput.wroteToFile` discriminator with XOR-checked fields |
| `overwrite` defaults to `false` | same default; refuses to overwrite existing files |
| SnakeYAML `DumperOptions` with default flow style | mirrored in `CoreUtilsWriteYamlStepDefinition` |
| Default SnakeYAML dump flag map | mirrored (BLOCK flow, splitLines=false) |
| Refuse to clobber pre-existing file (Jenkins deletes before write) | same: typed `PluginStepException` raise before any side effect |

## Deviations from Jenkins

1. **Closed `YamlDocument` ADT replaces `Object`.** Jenkins accepts any
   Groovy value and serialises via SnakeYAML's `Representer`. We accept only
   `YamlDocument.{Str,Integer,Real,Bool,Null,Seq,Map}` so the durable
   envelope is a closed shape.
2. **Capability-routed handler.** Jenkins reaches for a `FilePath`
   argument. We declare `WORKSPACE_IDENTITY_CAPABILITY` and resolve
   relative paths through the capability; the handler never touches the
   coordinator or the engine.
3. **Sealed XOR invariants at compile time.** Jenkins enforces
   `data XOR datas` and `file XOR returnText` at runtime (validation in
   the DSL `doStart`). We encode both invariants in sealed hierarchies so
   an illegal shape is unrepresentable.
4. **No `yaml-marshal-plugin` bridge.** We use SnakeYAML 2.3 directly,
   with the same `LoaderOptions` reuse pattern as Jenkins (constructor and
   `Yaml` constructed with the same options).

## Security implications reviewed

- **Read side** is governed by the S2.0 SnakeYAML safety probe (sealed
  `YamlDocument`, `SafeConstructor`, tag allow-list, depth limit 64,
  `codePointLimit=8MiB`). `writeYaml` inherits all those guarantees: it
  only writes the same closed `YamlDocument` ADT back to disk.
- **Write side** serialises only primitives and `List` / `Map` shapes
  derived from the closed ADT through `YamlToJava`. No arbitrary-class
  instantiation path exists on the write side either.
- **Overwrite default `false`** prevents a configuration mistake (e.g. a
  user's pipeline writing a YAML file from a template that happens to
  collide with a path someone else relies on) from clobbering existing
  state. A failed overwrite raises a typed `PluginStepException` with
  `kind == USER`, so the step's failure mode is observable through the
  normal error path.

## Contract tests (14 new in this slice)

```
identity — writeYaml Key is core-utils dot writeYaml
contract — writeYaml declares WRITES_WORKSPACE, NEVER, WORKSPACE_IDENTITY_CAPABILITY
codec writeYaml input — roundtrip preserves destination + payload variants
codec writeYaml output — roundtrip preserves file write fields
envelope — writeYaml input codec emits a well-formed JSON object (durable eligible)
success — writeYaml writes a typed YamlDocument tree to disk and returns sha256Hex
success — writeYaml creates missing parent directories
success — writeYaml roundtrip via readYaml preserves typed structure
success — writeYaml multiple documents writes a multi-doc YAML stream
typed failure — writeYaml refuses to overwrite an existing file unless overwrite=true
typed failure — writeYaml with overwrite=true succeeds even if the file exists
typed failure — writeYaml with empty payload list surfaces USER class
replay — writeYaml output is deterministic across runs
observability — writeYaml returns a typed Output with wroteToFile + absolutePath + sha256Hex
```

## Evidence

```
:pipeline-step-sdk:utilities:test
  tests="63" failures="0" errors="0" timestamp="2026-09-19T21:11:05Z"
  YamlSafetyCharacterisationTest tests="6" failures="0" errors="0"
```

## Step state

- `core-utils.writeYaml`: **CERTIFIED**.
- Slice 1 (`core-utils.readJson`/`writeJson`/`sha256`): CERTIFIED,
  untouched.
- `core-utils.readYaml`: CERTIFIED in S2.1, untouched.

## Ledger update

```
Certified Steps:           5  (readJson, writeJson, sha256,
                              readYaml, writeYaml)
Legacy executable Steps:   0
Registry-primary Steps:    5
```

## Commit

`aa5cf444 S2.2: core-utils.writeYaml (Jenkins-reference Step) — sealed destination+payload, 63/63 contract tests green`
