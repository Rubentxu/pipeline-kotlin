package dev.rubentxu.pipeline.v2.harness.codec

import dev.rubentxu.pipeline.v2.harness.model.*
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException

/**
 * YAML is deserialization of the typed ADT — never arbitrary interpretation.
 * Unknown version, unknown constraint form or unknown fields fail CLOSED with
 * a useful diagnostic. Wire form (v1):
 *
 * version: 1
 * name: 07-catch-error
 * expect: { runOutcome: UNSTABLE }
 * constraints:
 *   - exactly: { event: CatchErrorTriggered, where: { buildResult: FAILURE }, count: 1 }
 *   - never: { event: StepFailed }
 *   - before:
 *       first: { event: RetryAttemptFinished, where: { outcome: failed } }
 *       second: { event: RetryAttemptFinished, where: { outcome: succeeded } }
 */
object YamlEventContractCodec {

    class ContractDecodeException(message: String, cause: Throwable? = null) :
        IllegalStateException(message, cause) // corrupt/incompatible contract — NOT a verification failure

    fun decode(yamlText: String): EventContract {
        val root = try {
            @Suppress("UNCHECKED_CAST")
            Yaml().load(yamlText) as? Map<String, Any>
                ?: throw ContractDecodeException("contract root must be a YAML mapping")
        } catch (e: YAMLException) {
            throw ContractDecodeException("invalid YAML: ${e.message}", e)
        }
        val version = (root["version"] as? Number)?.toInt()
            ?: throw ContractDecodeException("missing or non-integer 'version'")
        if (version != EventContract.CURRENT_VERSION)
            throw ContractDecodeException("unsupported contract version $version (supported: ${EventContract.CURRENT_VERSION})")
        val name = root["name"] as? String ?: "unnamed"
        @Suppress("UNCHECKED_CAST")
        val expect = root["expect"] as? Map<String, Any>
            ?: throw ContractDecodeException("missing 'expect' mapping")
        val runOutcome = expect["runOutcome"] as? String
            ?: throw ContractDecodeException("missing expect.runOutcome")
        val expected = ExpectedRunOutcome.fromWire(runOutcome)
            ?: throw ContractDecodeException("unknown expect.runOutcome '$runOutcome' (valid: SUCCESS, UNSTABLE, FAILURE)")
        @Suppress("UNCHECKED_CAST")
        val rawConstraints = root["constraints"] as? List<Any>
            ?: throw ContractDecodeException("missing 'constraints' list")
        val constraints = rawConstraints.mapIndexed { i, c -> decodeConstraint(c, i) }
        return EventContract(version, name, expected, constraints)
    }

    private fun decodeConstraint(raw: Any, index: Int): EventConstraint {
        @Suppress("UNCHECKED_CAST")
        val m = raw as? Map<String, Any>
            ?: throw ContractDecodeException("constraint#$index must be a mapping")
        if (m.size != 1)
            throw ContractDecodeException("constraint#$index must have exactly one form key, got: ${m.keys}")
        val (form, body) = m.entries.first()
        return when (form) {
            "exactly" -> {
                @Suppress("UNCHECKED_CAST")
                val b = body as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: exactly requires a mapping")
                val count = (b["count"] as? Number)?.toInt()
                    ?: throw ContractDecodeException("constraint#$index: exactly requires integer 'count'")
                EventConstraint.Exactly(decodeSelector(b - "count", index), count)
            }
            "never" -> EventConstraint.Never(decodeSelector(body, index))
            "before" -> {
                @Suppress("UNCHECKED_CAST")
                val b = body as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: before requires a mapping")
                @Suppress("UNCHECKED_CAST")
                val first = b["first"] as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: before requires 'first'")
                @Suppress("UNCHECKED_CAST")
                val second = b["second"] as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: before requires 'second'")
                val scope = when (val s = b["scope"]) {
                    null, "global" -> RelationScope.Global
                    "same-subject" -> RelationScope.SameSubject
                    is String -> RelationScope.SameKey(
                        KeyKind.entries.firstOrNull { it.wire.equals(s, true) }
                            ?: throw ContractDecodeException("constraint#$index: unknown scope '$s'")
                    )
                    else -> throw ContractDecodeException("constraint#$index: invalid scope")
                }
                EventConstraint.Before(decodeSelector(first, index), decodeSelector(second, index), scope)
            }
            "outcome" -> {
                @Suppress("UNCHECKED_CAST")
                val b = body as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: outcome requires a mapping")
                val o = b["expected"] as? String ?: throw ContractDecodeException("constraint#$index: outcome requires 'expected'")
                val e = ExpectedRunOutcome.fromWire(o)
                    ?: throw ContractDecodeException("constraint#$index: unknown outcome '$o'")
                EventConstraint.TerminalOutcome(e)
            }
            else -> throw ContractDecodeException("constraint#$index: unknown constraint form '$form' (valid: exactly, never, before, outcome)")
        }
    }

    private fun decodeSelector(raw: Any, index: Int): EventSelector {
        @Suppress("UNCHECKED_CAST")
        val b = raw as? Map<String, Any> ?: throw ContractDecodeException("constraint#$index: selector must be a mapping")
        val kind = b["event"] as? String ?: throw ContractDecodeException("constraint#$index: selector requires 'event'")
        @Suppress("UNCHECKED_CAST")
        val whereRaw = b["where"] as? Map<String, Any> ?: emptyMap()
        val known = setOf("event", "where")
        val unknown = b.keys - known
        if (unknown.isNotEmpty())
            throw ContractDecodeException("constraint#$index: unknown selector fields $unknown (valid: $known)")
        return EventSelector(kind, whereRaw.map { (k, v) -> decodeFieldMatch(k, v, index) })
    }

    private fun decodeFieldMatch(key: String, value: Any, index: Int): FieldMatch = when (key) {
        "buildResult" -> FieldMatch.CatchBuildResult(value as? String ?: err(index, key))
        "retryOutcome", "outcome" -> FieldMatch.RetryOutcome(value as? String ?: err(index, key))
        "attempt" -> FieldMatch.AttemptNumber((value as? Number)?.toInt() ?: err(index, key))
        "branch" -> FieldMatch.BranchIndex((value as? Number)?.toInt() ?: err(index, key))
        "messageContains" -> FieldMatch.MessageContains(value as? String ?: err(index, key))
        "stage" -> FieldMatch.StageIndex((value as? Number)?.toInt() ?: err(index, key))
        "step" -> FieldMatch.StepIndex((value as? Number)?.toInt() ?: err(index, key))
        else -> throw ContractDecodeException("constraint#$index: unknown where field '$key' (valid: buildResult, retryOutcome, outcome, attempt, branch, messageContains, stage, step)")
    }

    private fun err(index: Int, key: String): Nothing =
        throw ContractDecodeException("constraint#$index: field '$key' has wrong type")

    private val KeyKind.wire: String get() = when (this) {
        KeyKind.BRANCH -> "branch"
        KeyKind.STAGE -> "stage"
        KeyKind.STEP -> "step"
    }
}
