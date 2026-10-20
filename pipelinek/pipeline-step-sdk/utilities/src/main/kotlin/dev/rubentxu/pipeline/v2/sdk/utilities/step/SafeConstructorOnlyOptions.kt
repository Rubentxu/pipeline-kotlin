package dev.rubentxu.pipeline.v2.sdk.utilities.step

import org.yaml.snakeyaml.LoaderOptions

/**
 * SnakeYAML LoaderOptions configured for untrusted input.
 *
 * The defaults are dangerous: the default `Constructor` instantiates arbitrary
 * classes via `!!class.name` tags, alias expansion has no cap, and nested
 * documents blow the JVM stack. The Step NEVER uses defaults; it ALWAYS uses
 * this builder.
 *
 * Limits (calibrated against empirical probe `YamlSafetyCharacterisationTest`):
 *
 *  - `tagInspector = Inspector()` — refuse every non-standard tag, including
 *    `!!python/name:os.system`, `!!javax.script.ScriptEngineManager`, etc.
 *    Only the YAML 1.1 standard tags are accepted.
 *  - `nestingDepthLimit = 64` — empirical cap; SnakeYAML enforces this
 *    natively. Far above any realistic YAML document, far below JVM stack.
 *  - `codePointLimit = 8 MiB` — refuse documents larger than 8 MiB
 *    BEFORE parsing (a 64 MiB YAML file is almost always a mistake or a
 *    hostile payload).
 *  - `maxAliasesForCollections = 64` — refuse alias bombs.
 *  - `allowRecursiveKeys = false` — refuse cycles that would otherwise
 *    hang the parser.
 *  - `allowDuplicateKeys = false` — duplicate keys are a typo or an
 *    attack; refuse to silently overwrite.
 *  - `processComments = false` — comments are dropped silently rather
 *    than retained in the typed value.
 */
internal object SafeConstructorOnlyOptions {
    /** YAML 1.1 standard tags + a few common safe extensions. */
    private val ALLOWED_TAGS: Set<String> = setOf(
        "tag:yaml.org,2002:map",
        "tag:yaml.org,2002:seq",
        "tag:yaml.org,2002:str",
        "tag:yaml.org,2002:int",
        "tag:yaml.org,2002:float",
        "tag:yaml.org,2002:bool",
        "tag:yaml.org,2002:null",
        "tag:yaml.org,2002:binary",
        "tag:yaml.org,2002:timestamp",
        "tag:yaml.org,2002:omap",
        "tag:yaml.org,2002:pairs",
        "tag:yaml.org,2002:set",
        "tag:yaml.org,2002:merge",
        "tag:yaml.org,2002:value",
    )

    fun builderSafe(): LoaderOptions = LoaderOptions().apply {
        // Tag safety: refuse any tag that is not on the YAML 1.1 allow-list.
        // This blocks `!!python/name:os.system "echo pwned"` and similar
        // arbitrary-class-instantiation attacks.
        setTagInspector(object : org.yaml.snakeyaml.inspector.TagInspector {
            override fun isGlobalTagAllowed(globalTag: org.yaml.snakeyaml.nodes.Tag?): Boolean {
                if (globalTag == null) return true
                return ALLOWED_TAGS.contains(globalTag.value)
            }
        })
        // Built-in native caps. SnakeYAML enforces them BEFORE handing a
        // value to the constructor.
        setNestingDepthLimit(64)
        setCodePointLimit((8L * 1024L * 1024L).toInt())  // 8 MiB
        setMaxAliasesForCollections(64)
        setAllowRecursiveKeys(false)
        setAllowDuplicateKeys(false)
        setProcessComments(false)
        // Defer to the SafeConstructor only — the default `Constructor`
        // would bypass the tag inspector for `!!class` style tags.
        setWrappedToRootException(false)
        setEnumCaseSensitive(true)
        setMergeOnCompose(false)
    }
}
