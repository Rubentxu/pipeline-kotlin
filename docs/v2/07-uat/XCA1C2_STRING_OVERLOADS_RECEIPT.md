# XCA-1C.2 — script-safe String overloads for writeYaml / writeProperties

**Base:** `a1e35345`

## Contract checked BEFORE defining `String` (as required)

```kotlin
data class WriteJsonInput(      val path: String, val value: JsonElement, val prettyPrint: Boolean = true)
data class WriteYamlInput(      val path: String, val value: JsonElement)
data class WritePropertiesInput(val path: String, val value: JsonObject)
```

**All three canonical inputs take structured values.** `writeJSON`'s existing
`value: String` is parsed at the facade:

```kotlin
fun StageScope.writeJSON(path: String, value: String, prettyPrint: Boolean = true) {
    val parsed: JsonElement = Json.parseToJsonElement(value)
    registryStep(..., WriteJsonCodec.encode(WriteJsonInput(path, parsed, prettyPrint)))
}
```

So the established semantics is: **the String is the target format's own text, parsed at
the facade**. The new overloads adopt exactly that, rather than inventing a second
reading. Three names never mean three different things:

```text
writeJSON(value: String)       = JSON text
writeYaml(content: String)     = YAML text      (org.yaml.snakeyaml)
writeProperties(content: String) = .properties text (java.util.Properties)
```

Both parsers were **already dependencies** of the plugin (snakeyaml imported for
`readYaml`, `java.util.Properties` for `readProperties`). No new dependency, no new
capability, no new StepKey.

## Law frozen

```text
String DSL overload
  -> parse at the DSL facade (typed failure, not an escaping parser error)
  -> EXISTING typed input (WriteYamlInput / WritePropertiesInput)
  -> same StepKey
  -> same StepDefinition
  -> same handler
  -> same capability
```

Zero new StepKeys, zero new handlers, zero new capabilities, zero alternate execution
paths. The `JsonElement` / `JsonObject` overloads are **retained**: strictly additive, no
source or binary break.

Converters are reused, not reimplemented: the YAML overload calls the existing private
`jsonElementFrom(...)` that `readYaml` uses; the properties overload produces the same
`JsonObject` string->string projection `readProperties` produces.

Parse failures are typed (`UtilitiesYamlError.YamlParseFailure`,
`UtilitiesPropertiesError.PropertiesIoFailure`), consistent with the read steps.

## Verification

```text
examples/utilities-plugin : ./gradlew compileKotlin   BUILD SUCCESSFUL in 18s
ledger YAML still valid                                PASS
```

`02-yaml-properties.pipeline.kts` extended from 2 reads to a full write->read round-trip
over all four surfaces (writeYaml, readYaml, writeProperties, readProperties).

## Counters

```text
CERTIFIED            31
  EXERCISED          30
  NOT_EXERCISED       0
  STRUCTURAL_SYNTH    1
A_IN_EXAMPLES        19
B_PROMOTE_COMPATIBILITY 11
D_REWRITE_CONTRACT    1
C_CREATE_OR_EXPAND    0
```

`NOT_EXERCISED` and `C_CREATE_OR_EXPAND` both reach 0. Every certified surface now has a
fixture that genuinely invokes it.

## Honest limit (unchanged discipline)

`EXERCISED` here still means **static DSL invocation**, NOT certified execution. The
plugin compiles, but the fixtures have not been run through the installed CLI. XCA-2 is
what converts static candidate evidence into runtime product evidence
(installed CLI -> pipeline.kts -> canonical journal -> observed StepKey). The
`candidate_fixtures` / `real_fixtures` schema split belongs there, not here.

## Debt registered

```text
plugin DSL public facade
must not unnecessarily expose codec implementation types
```

`writeJSON` takes a `String` while its siblings exposed raw `JsonElement`/`JsonObject`.
That inconsistency is what forced this slice. Coverage, TOML and manifest facades could
repeat it. Not fixed with a broad abstraction now; registered.
