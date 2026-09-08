package dev.rubentxu.pipeline.v2.application.durable.credentials

import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.CertificateBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.FileBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.SshUserPrivateKeyBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.StringBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePasswordBindingSpec
import dev.rubentxu.pipeline.v2.domain.credentials.ZipBindingSpec
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.put

/**
 * EM-7 / LFC-5.3 — Symmetric JSON codec for credential binding specs across the
 * compiled BlockStepNode payload seam.
 *
 * [CredentialBindingSpec] is a sealed ADT (per ADR-0051) and deliberately NOT
 * `@Serializable` (the IR must never carry secret values; only env-var *names*
 * and the non-secret [CredentialsId] travel in the node payload). This codec is
 * the single symmetric pair (encode in the compiler, decode in the coordinator)
 * so a payload produced by `compile` round-trips through `acquire`'s decode.
 *
 * The JSON shape is `{"bindings":[{"kind": <discriminator>, ...fields}, ...]}`.
 * Only the fields valid for each kind are present (mirror of the sealed subtype).
 * A missing required field or an unknown kind throws [IllegalArgumentException],
 * which the coordinator maps to a typed schema `StepOutcome.Failure` — the body
 * is never dispatched (fail-closed).
 */
object CredentialBindingsPayload {

    /** Payload key under which the binding array is carried on a withCredentials node. */
    const val BINDINGS_KEY: String = "bindings"

    /**
     * Encodes a list of domain [CredentialBindingSpec] into the canonical
     * withCredentials node payload JSON string.
     */
    fun encode(specs: List<CredentialBindingSpec>): String {
        val bindings = JsonArray(
            specs.map { spec ->
                buildJsonObject {
                    put("kind", spec.kind)
                    put("credentialsId", spec.credentialsId.value)
                    when (spec) {
                        is StringBindingSpec -> put("variable", spec.variable)
                        is UsernamePasswordBindingSpec -> {
                            put("usernameVariable", spec.usernameVariable)
                            put("passwordVariable", spec.passwordVariable)
                        }
                        is SshUserPrivateKeyBindingSpec -> {
                            put("keyFileVariable", spec.keyFileVariable)
                            spec.passphraseVariable?.let { put("passphraseVariable", it) }
                            spec.usernameVariable?.let { put("usernameVariable", it) }
                        }
                        is FileBindingSpec -> put("variable", spec.variable)
                        is CertificateBindingSpec -> {
                            put("keystoreVariable", spec.keystoreVariable)
                            spec.aliasVariable?.let { put("aliasVariable", it) }
                            spec.passwordVariable?.let { put("passwordVariable", it) }
                        }
                        is ZipBindingSpec -> put("variable", spec.variable)
                        is UsernameColonPasswordBindingSpec -> put("variable", spec.variable)
                    }
                }
            },
        )
        val root = buildJsonObject { put(BINDINGS_KEY, bindings) }
        return Json.encodeToString(JsonObject.serializer(), root)
    }

    /**
     * Decodes a withCredentials node payload JSON string back into a typed
     * [CredentialBindingSpec] list. Exhaustive over the sealed family.
     *
     * @throws IllegalArgumentException if the payload is malformed, a kind is
     *         unknown, or a required field is missing
     */
    fun decode(payload: String): List<CredentialBindingSpec> {
        val root = Json.parseToJsonElement(payload).jsonObject
        val bindings = root[BINDINGS_KEY]?.jsonArray
            ?: throw IllegalArgumentException("withCredentials payload missing '$BINDINGS_KEY' array")
        return bindings.map { element ->
            val obj = element.jsonObject
            val kind = obj["kind"]?.jsonPrimitive?.contentOrNull
                ?: throw IllegalArgumentException("withCredentials binding missing 'kind'")
            val credentialsId = CredentialsId(
                obj["credentialsId"]?.jsonPrimitive?.contentOrNull
                    ?: throw IllegalArgumentException("withCredentials binding '$kind' missing 'credentialsId'"),
            )
            when (kind) {
                "string" -> StringBindingSpec(
                    credentialsId = credentialsId,
                    variable = required(obj, "variable", kind),
                )
                "usernamePassword" -> UsernamePasswordBindingSpec(
                    credentialsId = credentialsId,
                    usernameVariable = required(obj, "usernameVariable", kind),
                    passwordVariable = required(obj, "passwordVariable", kind),
                )
                "sshUserPrivateKey" -> SshUserPrivateKeyBindingSpec(
                    credentialsId = credentialsId,
                    keyFileVariable = required(obj, "keyFileVariable", kind),
                    passphraseVariable = optional(obj, "passphraseVariable"),
                    usernameVariable = optional(obj, "usernameVariable"),
                )
                "file" -> FileBindingSpec(
                    credentialsId = credentialsId,
                    variable = required(obj, "variable", kind),
                )
                "certificate" -> CertificateBindingSpec(
                    keystoreVariable = required(obj, "keystoreVariable", kind),
                    credentialsId = credentialsId,
                    aliasVariable = optional(obj, "aliasVariable"),
                    passwordVariable = optional(obj, "passwordVariable"),
                )
                "zip" -> ZipBindingSpec(
                    variable = required(obj, "variable", kind),
                    credentialsId = credentialsId,
                )
                "usernameColonPassword" -> UsernameColonPasswordBindingSpec(
                    variable = required(obj, "variable", kind),
                    credentialsId = credentialsId,
                )
                else -> throw IllegalArgumentException("Unknown withCredentials binding kind: '$kind'")
            }
        }
    }

    private fun required(obj: JsonObject, field: String, kind: String): String =
        obj[field]?.jsonPrimitive?.contentOrNull
            ?: throw IllegalArgumentException("withCredentials binding '$kind' missing required field '$field'")

    private fun optional(obj: JsonObject, field: String): String? =
        obj[field]?.jsonPrimitive?.contentOrNull
}
