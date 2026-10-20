package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument

/**
 * Pure mapping from the closed [YamlDocument] ADT to plain Java objects that
 * SnakeYAML 2.3's default `Representer` can serialise.
 *
 * The output is restricted to the same primitives and containers SnakeYAML's
 * safe constructor accepts (Boolean, Number, String, null, List, Map), which
 * closes the round-trip loop:
 *
 *   safe-constructor output (Any?) --[SnakeYamlAdapter]--> YamlDocument
 *                                                         |
 *                                                         v  (encode/decode via YamlDocumentCodec)
 *                                                       YamlDocument
 *                                                         |
 *                                                         v
 *                                              [YamlToJava.toJava]
 *                                                         |
 *                                                         v
 *                                  SnakeYAML Representer output (Any?) --[Yaml.dump]--> YAML text
 *
 * The exhaustive `when` is intentional: any future addition to the
 * YamlDocument ADT must show up here as a compile-time warning, not as a
 * silent runtime loss.
 */
internal object YamlToJava {

    @Suppress("UNCHECKED_CAST")
    fun toJava(doc: YamlDocument): Any? = when (doc) {
        YamlDocument.Null -> null
        is YamlDocument.Bool -> doc.value
        is YamlDocument.Integer -> doc.value
        is YamlDocument.Real -> doc.value
        is YamlDocument.Str -> doc.value
        is YamlDocument.Seq -> doc.items.map { toJava(it) }
        is YamlDocument.Map -> LinkedHashMap<String, Any?>().also { m ->
            doc.entries.forEach { e -> m[e.key] = toJava(e.value) }
        }
    }
}
