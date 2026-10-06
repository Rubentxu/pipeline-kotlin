package dev.rubentxu.pipeline.v2.domain.step

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.directive.DirectiveKey
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs

/**
 * S6/C — the machine-readable manifest format, and the ONLY place its spelling is decided.
 *
 * ## Why a codec at all
 *
 * Before this type a plugin declared itself by CONSTRUCTING a [PluginManifest] in memory
 * from `System.getProperty` values, and the runtime read that declaration off the plugin's
 * own objects after its classes had already loaded. That order is the defect ADR-EVO-003
 * names: the runtime cannot refuse a plugin it has already run. A manifest that lives only
 * as Kotlin constructors is not a declaration, it is a report.
 *
 * So the manifest becomes a DOCUMENT with a canonical path, a schema version, and a codec
 * that is total: malformed input yields a typed refusal, never an exception a caller could
 * mistake for a bug in the reader.
 *
 * ## Determinism
 *
 * [encode] emits keys in a fixed order and sorts every collection, so the same
 * [PluginManifest] produces the same bytes every time. That matters because the artifact
 * carries this document and anything non-deterministic here becomes a spurious digest
 * change: a manifest that re-serializes differently on every build teaches every reader that
 * digest equality means nothing.
 *
 * ## The digest is NOT proof
 *
 * The `releaseDigest` field travels inside the document it describes, which makes it a
 * self-declared value, and ADR-EVO-003 explicitly refuses "self-declared digest as proof".
 * This codec therefore carries it but never VERIFIES it — [MeasuredArtifactIdentity] is what
 * separates what the runtime measured from what the plugin claimed. Recording the limit in
 * the codec's own KDoc is deliberate: a codec that looked authoritative would invite exactly
 * the trust this field has not earned.
 */
object PluginManifestCodec {

    /**
     * The single canonical location, decided once and now contractual.
     *
     * Under `META-INF/pipelinek/` rather than beside `META-INF/services/` because it is not
     * a ServiceLoader input: nothing reads it reflectively, and admission reads it through a
     * [ClassLoader] the runtime already holds rather than through a fresh scan.
     */
    const val RESOURCE_PATH: String = "META-INF/pipelinek/plugin-manifest.json"

    /** Codec identity. A reader that does not recognise this must refuse, not best-effort. */
    const val CODEC: String = "pipelinek-manifest-codec/v1"

    /**
     * Encode to the canonical textual form.
     *
     * Hand-written rather than delegated to a JSON library so field ORDER and escaping are
     * properties of this file rather than of whichever serializer happens to be on the
     * classpath. A manifest whose bytes depend on the build's dependency tree is not a
     * contract.
     */
    fun encode(manifest: PluginManifest): String {
        val sb = StringBuilder()
        sb.append("{\n")
        sb.append("  \"codec\": \"").append(escape(CODEC)).append("\",\n")
        sb.append("  \"schemaVersion\": \"").append(escape(manifest.schemaVersion.toString())).append("\",\n")
        sb.append("  \"plugin\": \"").append(escape(manifest.plugin.canonicalText())).append("\",\n")
        sb.append("  \"releaseVersion\": \"").append(escape(manifest.release.version.toString())).append("\",\n")
        sb.append("  \"releaseDigest\": \"").append(escape(manifest.release.digest.value)).append("\",\n")
        sb.append("  \"apiRange\": \"").append(escape(manifest.apiRange.toString())).append("\",\n")
        sb.append("  \"publisher\": \"").append(escape(manifest.publisher)).append("\",\n")
        sb.append("  \"delivery\": \"").append(escape(manifest.delivery.name)).append("\",\n")
        sb.append("  \"trust\": \"").append(escape(trustToken(manifest.trust))).append("\",\n")
        sb.append("  \"families\": [")
        sb.append(manifest.families.sortedBy { it.name }.joinToString(", ") { "\"${escape(it.name)}\"" })
        sb.append("],\n")
        sb.append("  \"steps\": [")
        sb.append(
            manifest.contributions.steps
                .sortedBy { it.stepKey.value }
                .joinToString(", ") { step ->
                    "{\"stepKey\": \"${escape(step.stepKey.value)}\", " +
                        "\"declaredCapabilities\": [" +
                        step.declaredCapabilities.sortedBy { it.key }.joinToString(", ") { "\"${escape(it.key)}\"" } +
                        "]}"
                },
        )
        sb.append("],\n")
        sb.append("  \"directives\": [")
        sb.append(
            manifest.contributions.directives
                .sortedBy { it.directiveKey.value }
                .joinToString(", ") { "\"${escape(it.directiveKey.value)}\"" },
        )
        sb.append("],\n")
        sb.append("  \"events\": [")
        sb.append(
            manifest.contributions.events
                .sortedBy { it.eventKind }
                .joinToString(", ") { "\"${escape(it.eventKind)}\"" },
        )
        sb.append("],\n")
        sb.append("  \"capabilities\": [")
        sb.append(
            manifest.contributions.capabilities
                .sortedBy { it.key }
                .joinToString(", ") { "\"${escape(it.key)}\"" },
        )
        sb.append("]\n")
        sb.append("}\n")
        return sb.toString()
    }

    /**
     * Total decode. Every refusal is a typed [PluginManifestDecodeResult.Rejected]; nothing
     * throws, because the caller is a boundary reading bytes it did not author and an
     * exception there would be indistinguishable from a defect in the reader.
     */
    fun decode(text: String): PluginManifestDecodeResult =
        when (val parsed = decodeOrNull(text)) {
            null -> PluginManifestDecodeResult.reject(
                PluginManifestRejection.MalformedDocument("document does not match the pipelinek manifest grammar"),
            )

            is DecodeFailure -> PluginManifestDecodeResult.reject(parsed.rejection)
            is DecodeSuccess -> PluginManifestDecodeResult.Accepted(parsed.manifest)
        }

    /** What a whole document decode produces: exactly a manifest or exactly a rejection. */
    private sealed interface DecodeOutcome

    /**
     * What ONE section of the document produced.
     *
     * A separate closed type rather than more cases of [DecodeOutcome]: a section is not a
     * document, and letting the two share one hierarchy would force `decode`'s `when` to carry
     * three branches that can never occur at the top level. Keeping them apart is what lets
     * that `when` stay exhaustive over its two real cases with no `else` to hide a third.
     */
    private sealed interface SectionOutcome

    private data class DecodeSuccess(val manifest: PluginManifest) : DecodeOutcome

    private data class DecodeFailure(val rejection: PluginManifestRejection) : DecodeOutcome, SectionOutcome

    /** The document discriminator plus everything that identifies WHICH plugin this claims to be. */
    private data class Header(
        val schema: ManifestSchemaVersion,
        val plugin: ResourceRef,
        val release: PluginReleaseRef,
    ) : SectionOutcome

    /** The compatibility contract the plugin publishes for itself. */
    private data class Contract(
        val apiRange: PipelineKApiRange,
        val publisher: String,
        val delivery: Delivery,
    ) : SectionOutcome

    /** What the plugin declares it contributes, and the domain families it belongs to. */
    private data class Contributions(
        val families: Set<PluginFamily>,
        val contributions: PluginContributions,
    ) : SectionOutcome

    private fun decodeOrNull(text: String): DecodeOutcome? {
        val root = JsonCursor(text).readObject() ?: return null

        // Codec before schema, and both before any interpretation: an unrecognised writer or
        // an unknown schema means this document says something we cannot vouch for, and
        // guessing at it is how a v2 manifest gets read as if it were v1.
        val codec = root.str("codec") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'codec' field"),
        )
        if (codec != CODEC) {
            return DecodeFailure(PluginManifestRejection.UnknownCodec(codec))
        }

        // The three sections below run in the order their fields are read, so the FIRST
        // malformed field is still the one reported. Reordering them would change which
        // rejection a document with two defects receives, which is part of the contract
        // rather than an incidental detail.
        val header = decodeHeader(root)
        if (header is DecodeFailure) return header
        val contract = decodeContract(root)
        if (contract is DecodeFailure) return contract
        val contributions = decodeContributions(root)
        if (contributions is DecodeFailure) return contributions

        val h = header as Header
        val c = contract as Contract
        val k = contributions as Contributions

        // The PluginManifest constructor enforces the cross-field invariants (identity ==
        // release.plugin, known schema, non-empty families, non-empty contributions). Letting
        // it throw here is correct and not the exception case: those invariants are part of
        // the TYPE, so a document that satisfies the grammar and violates them is a malformed
        // document, not a valid one.
        return runCatching {
            PluginManifest(
                schemaVersion = h.schema,
                plugin = h.plugin,
                release = h.release,
                apiRange = c.apiRange,
                publisher = c.publisher,
                families = k.families,
                delivery = c.delivery,
                trust = TrustMetadata.Unverified,
                contributions = k.contributions,
            )
        }.fold(
            onSuccess = { DecodeSuccess(it) },
            onFailure = { DecodeFailure(PluginManifestRejection.MalformedDocument(it.message ?: "invariant violated")) },
        )
    }

    @Suppress("ReturnCount")
    private fun decodeHeader(root: Map<String, Any>): SectionOutcome {
        val schemaRaw = root.str("schemaVersion") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'schemaVersion' field"),
        )
        val schema = ManifestSchemaVersion.parseOrNull(schemaRaw)
            ?: return DecodeFailure(PluginManifestRejection.UnsupportedSchema(schemaRaw))

        val pluginText = root.str("plugin") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'plugin' field"),
        )
        val plugin = decodeResourceRef(pluginText) ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("plugin identity is not a canonical ResourceRef: $pluginText"),
        )

        val versionRaw = root.str("releaseVersion") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'releaseVersion' field"),
        )
        val version = parseSemVer(versionRaw) ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("releaseVersion is not major.minor.patch: $versionRaw"),
        )

        val digestRaw = root.str("releaseDigest") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'releaseDigest' field"),
        )
        val digest = runCatching { Digest(digestRaw) }.getOrNull() ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("releaseDigest is not a sha256 digest: $digestRaw"),
        )

        return Header(schema, plugin, PluginReleaseRef(plugin = plugin, version = version, digest = digest))
    }

    @Suppress("ReturnCount")
    private fun decodeContract(root: Map<String, Any>): SectionOutcome {
        val apiRangeRaw = root.str("apiRange") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'apiRange' field"),
        )
        val apiRange = parseApiRange(apiRangeRaw) ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("apiRange is not [from, until): $apiRangeRaw"),
        )

        val publisher = root.str("publisher") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'publisher' field"),
        )

        val deliveryRaw = root.str("delivery") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'delivery' field"),
        )
        val delivery = Delivery.entries.firstOrNull { it.name == deliveryRaw } ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("unknown delivery: $deliveryRaw"),
        )

        val trustRaw = root.str("trust") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing 'trust' field"),
        )
        if (trustRaw != TRUST_UNVERIFIED) {
            return DecodeFailure(
                PluginManifestRejection.MalformedDocument("unknown trust token: $trustRaw"),
            )
        }

        return Contract(apiRange, publisher, delivery)
    }

    @Suppress("ReturnCount")
    private fun decodeContributions(root: Map<String, Any>): SectionOutcome {
        val familiesRaw = root.strings("families") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing or malformed 'families'"),
        )
        val families = familiesRaw.map { name ->
            PluginFamily.entries.firstOrNull { it.name == name } ?: return DecodeFailure(
                PluginManifestRejection.MalformedDocument("unknown plugin family: $name"),
            )
        }.toSet()

        val stepsRaw = root.objects("steps") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing or malformed 'steps'"),
        )
        val steps = stepsRaw.map { entry ->
            val key = entry.str("stepKey") ?: return DecodeFailure(
                PluginManifestRejection.MalformedDocument("a step entry has no 'stepKey'"),
            )
            val caps = entry.strings("declaredCapabilities") ?: return DecodeFailure(
                PluginManifestRejection.MalformedDocument("step $key has no 'declaredCapabilities' array"),
            )
            runCatching {
                PluginStepContribution(
                    stepKey = PluginStepId(key),
                    declaredCapabilities = caps.map { StepCapability(it) }.toSet(),
                )
            }.getOrElse {
                return DecodeFailure(
                    PluginManifestRejection.MalformedDocument("step $key is not a legal contribution: ${it.message}"),
                )
            }
        }

        val directivesRaw = root.strings("directives") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing or malformed 'directives'"),
        )
        val directives = directivesRaw.map { raw ->
            runCatching { PluginDirectiveContribution(DirectiveKey(raw)) }.getOrElse {
                return DecodeFailure(
                    PluginManifestRejection.MalformedDocument("directive key is not legal: $raw"),
                )
            }
        }

        val eventsRaw = root.strings("events") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing or malformed 'events'"),
        )
        val events = eventsRaw.map { raw ->
            runCatching { PluginEventContribution(raw) }.getOrElse {
                return DecodeFailure(
                    PluginManifestRejection.MalformedDocument("event kind is not legal: $raw"),
                )
            }
        }

        val capabilitiesRaw = root.strings("capabilities") ?: return DecodeFailure(
            PluginManifestRejection.MalformedDocument("missing or malformed 'capabilities'"),
        )
        val capabilities = capabilitiesRaw.map { StepCapability(it) }.toSet()

        // PluginContributions is constructed HERE rather than left to the top-level
        // PluginManifest, and it still has to be guarded: its constructor refuses a duplicate
        // StepKey, and a document carrying one must come back as a typed rejection rather than
        // as an IllegalArgumentException thrown across the decode boundary. Moving the
        // construction out of the top-level runCatching without re-establishing the guard is
        // exactly how that regression happened once.
        return runCatching {
            Contributions(
                families = families,
                contributions = PluginContributions(
                    steps = steps,
                    directives = directives,
                    events = events,
                    capabilities = capabilities,
                ),
            )
        }.getOrElse {
            DecodeFailure(PluginManifestRejection.MalformedDocument(it.message ?: "invariant violated"))
        }
    }

    private fun trustToken(trust: TrustMetadata): String = when (trust) {
        is TrustMetadata.Unverified -> TRUST_UNVERIFIED
    }

    /**
     * Rebuild a [ResourceRef] from its canonical text.
     *
     * Round-trips [ResourceRef.canonicalText] rather than inventing a plugin-specific
     * spelling: the manifest carries the same textual identity the rest of the domain uses,
     * so there is one way to write a resource and not a second one that only manifests know.
     */
    private fun decodeResourceRef(text: String): ResourceRef? {
        val segments = text.split(':')
        if (segments.size < 2) return null
        val kindRaw = segments[1]
        val rest = segments.drop(2).joinToString(":")
        val pathSegments = rest.split('/').filter { it.isNotEmpty() }
        if (pathSegments.isEmpty()) return null
        val kind = dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.entries
            .firstOrNull { it.name.equals(kindRaw, ignoreCase = true) }
            ?: return null
        return runCatching { ResourceRef(kind, pathSegments) }.getOrNull()
    }

    private fun parseSemVer(raw: String): SemVer? {
        val parts = raw.split('.')
        if (parts.size != 3) return null
        val numbers = parts.map { it.toIntOrNull() ?: return null }
        if (numbers.any { it < 0 }) return null
        return SemVer(numbers[0], numbers[1], numbers[2])
    }

    private fun parseApiRange(raw: String): PipelineKApiRange? {
        // Encoded by PipelineKApiRange.toString() as "[from, until)".
        if (!raw.startsWith('[') || !raw.endsWith(')')) return null
        val inner = raw.substring(1, raw.length - 1)
        val parts = inner.split(',').map { it.trim() }
        if (parts.size != 2) return null
        val from = parseSemVer(parts[0]) ?: return null
        val until = parseSemVer(parts[1]) ?: return null
        return runCatching { PipelineKApiRange(from, until) }.getOrNull()
    }

    private fun escape(raw: String): String =
        raw.replace("\\", "\\\\").replace("\"", "\\\"")

    private const val TRUST_UNVERIFIED = "unverified"
}

/** Typed accessors over the parsed manifest, so the decoder reads as field names not casts. */
private fun Map<String, Any>.str(key: String): String? = this[key] as? String

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.strings(key: String): List<String>? =
    (this[key] as? List<*>)?.map { it as? String ?: return null }

@Suppress("UNCHECKED_CAST")
private fun Map<String, Any>.objects(key: String): List<Map<String, Any>>? =
    (this[key] as? List<*>)?.map { it as? Map<String, Any> ?: return null }

/**
 * Cursor over the fixed manifest grammar [PluginManifestCodec.encode] writes.
 *
 * Deliberately NOT a general JSON parser and it does not pretend to be one: objects, arrays
 * of strings, arrays of flat objects, and strings. Anything else yields null, which decode
 * turns into a refusal. Growing a general parser here would create a second authority for
 * what a manifest is allowed to say.
 */
private class JsonCursor(private val source: String) {
    private var pos = 0

    fun readObject(): Map<String, Any>? {
        skipWs()
        if (!consume('{')) return null
        val out = LinkedHashMap<String, Any>()
        skipWs()
        if (consume('}')) return out
        while (true) {
            skipWs()
            val key = readString() ?: return null
            skipWs()
            if (!consume(':')) return null
            skipWs()
            val value = readValue() ?: return null
            out[key] = value
            skipWs()
            if (consume(',')) continue
            if (consume('}')) return out
            return null
        }
    }

    private fun readValue(): Any? {
        skipWs()
        return when (peek()) {
            '{' -> readObject()
            '[' -> readArray()
            '"' -> readString()
            else -> null
        }
    }

    private fun readArray(): List<Any>? {
        if (!consume('[')) return null
        val out = mutableListOf<Any>()
        skipWs()
        if (consume(']')) return out
        while (true) {
            skipWs()
            val v = readValue() ?: return null
            out.add(v)
            skipWs()
            if (consume(',')) continue
            if (consume(']')) return out
            return null
        }
    }

    private fun readString(): String? {
        if (!consume('"')) return null
        val sb = StringBuilder()
        while (pos < source.length) {
            when (val c = source[pos++]) {
                '"' -> return sb.toString()
                '\\' -> {
                    if (pos >= source.length) return null
                    when (val esc = source[pos++]) {
                        '"' -> sb.append('"')
                        '\\' -> sb.append('\\')
                        '/' -> sb.append('/')
                        'n' -> sb.append('\n')
                        'r' -> sb.append('\r')
                        't' -> sb.append('\t')
                        'b' -> sb.append('\b')
                        'f' -> sb.append('')
                        'u' -> {
                            if (pos + 4 > source.length) return null
                            val hex = source.substring(pos, pos + 4)
                            val code = hex.toIntOrNull(16) ?: return null
                            sb.append(code.toChar())
                            pos += 4
                        }

                        else -> return null
                    }
                }

                else -> sb.append(c)
            }
        }
        return null
    }

    private fun peek(): Char? = if (pos < source.length) source[pos] else null

    private fun consume(c: Char): Boolean {
        if (pos < source.length && source[pos] == c) {
            pos++
            return true
        }
        return false
    }

    private fun skipWs() {
        while (pos < source.length && source[pos].isWhitespace()) pos++
    }
}
