package dev.rubentxu.pipeline.v2.sdk.utilities.step

import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepException
import dev.rubentxu.pipeline.v2.sdk.utilities.domain.YamlDocument
import java.math.BigDecimal
import java.math.BigInteger

/**
 * Pure mapping from SnakeYAML's safe-constructor output to the closed
 * [YamlDocument] ADT.
 *
 * The safe constructor only emits:
 *   - null
 *   - Boolean
 *   - Integer / Long / Short / Byte / BigInteger → YamlDocument.Integer
 *   - Double / Float / BigDecimal                 → YamlDocument.Real
 *   - String / Character                          → YamlDocument.Str
 *   - java.util.List / arrays of primitives      → YamlDocument.Seq
 *   - java.util.Map with String keys             → YamlDocument.Map
 *
 * Anything outside that set is a [PluginStepException] USER-class failure.
 * The exhaustive `when` is intentional: a future SnakeYAML release that adds
 * a new safe type MUST show up here as a compile-time warning rather than a
 * silent runtime loss.
 */
internal object SnakeYamlAdapter {

    fun toDocument(value: Any?): YamlDocument = when (value) {
        null -> YamlDocument.Null
        is Boolean -> YamlDocument.Bool(value)
        is String -> YamlDocument.Str(value)
        is Char -> YamlDocument.Str(value.toString())
        is Int -> YamlDocument.Integer(value.toLong())
        is Long -> YamlDocument.Integer(value)
        is Short -> YamlDocument.Integer(value.toLong())
        is Byte -> YamlDocument.Integer(value.toLong())
        is BigInteger -> YamlDocument.Integer(value.toLong())
        is Float -> YamlDocument.Real(value.toDouble())
        is Double -> YamlDocument.Real(value)
        is BigDecimal -> YamlDocument.Real(value.toDouble())
        is List<*> -> YamlDocument.Seq(value.map { toDocument(it) })
        is Array<*> -> YamlDocument.Seq(value.map { toDocument(it) })
        is Map<*, *> -> {
            val entries = value.entries.map { (k, v) ->
                val keyStr = when (k) {
                    is String -> k
                    else -> throw PluginStepException(
                        failure = PipelineFailure(
                            kind = FailureKind.USER,
                            message = "core-utils.readYaml: safe constructor returned a non-string map key (${k!!::class}); this should not happen with SafeConstructor + tag allow-list",
                        ),
                    )
                }
                YamlDocument.Map.Entry(keyStr, toDocument(v))
            }
            // Preserve insertion order: SnakeYAML returns LinkedHashMap by default.
            YamlDocument.Map(entries)
        }
        else -> throw PluginStepException(
            failure = PipelineFailure(
                kind = FailureKind.USER,
                message = "core-utils.readYaml: safe constructor returned an unsupported value type (${value!!::class.qualifiedName}); this should not happen with SafeConstructor + tag allow-list",
            ),
        )
    }
}
