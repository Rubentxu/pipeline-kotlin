package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import java.nio.file.Path

/**
 * Operator-declared trust database for plugin artifacts.
 * ## The authority split
 * Before, the only trust information available to the gate was the `releaseDigest`
 * field in the manifest document — a self-declared value carried by the very bytes the
 * Runtime was supposed to verify. That was the seam closes: the runtime now consults
 * An source of truth (the operator's local trust database) for the digest the
 * plugin's author cannot have written themselves.
 * The split is what the design's `PluginManifestResourceReader.measure` failure mode was
 * about: the runtime must NOT read the manifest document to learn the digest, because the
 * manifest document is the very thing the operator is cross-checking. A trust database that
 * lived inside the artifact would inherit the same defect.
 * ## Lookup key — `(plugin, releaseVersion)`
 * Keying on `(plugin.canonicalText(), releaseVersion)` and NOT on `plugin` alone is
 * load-bearing: two releases of the same plugin are different things, and a downgrade from
 * release N+1 to release N must not be a no-op at the trust layer. The key is constructed
 * once in this file's `key` helper so callers cannot drift.
*/
interface OperatorTrustDatabase {

    /**
     * One row in the operator trust database.
     * A row asserts that the operator has personally pinned a specific
     * `(plugin, releaseVersion, digest)` triple, and may carry an optional
     * [signedBy] provenance to make a forged entry detectable at audit time.
     * @property digest the digest the operator observed when the plugin was published
     * @property publisher the human-readable publisher name the operator has associated
     *   with this plugin (does NOT have to match the manifest's `publisher`; the
     *   manifest is the plugin's word, the trust row is the operator's)
     * @property signed whether this row is signed by a known key
     * @property signedBy the signer identity if [signed]
*/
    data class Row(
        val digest: Digest,
        val publisher: String,
        val signed: Boolean,
        val signedBy: String? = null,
    )

    /**
     * Look up the operator's row for `(plugin, releaseVersion)`.
     * @return the row, or `null` if no row is pinned. `null` is the
     *   `NoTrustedDigestAvailable` reason at admission time: a missing row is a refusal,
     *   not a pass, and the gate's job is to make sure the runtime never admits a plugin
     *   the operator has not pinned.
*/
    fun lookup(plugin: String, releaseVersion: SemVer): Row?

    companion object {
        /**
         * Canonical lookup key. Centralised so the runtime and any future tooling key the
         * same way; a regression that introduced a different separator (e.g. `@` vs `:`)
         * here would silently break every row in the operator's database.
*/
        fun key(plugin: String, releaseVersion: SemVer): String = "$plugin@${releaseVersion}"
    }
}

/**
 * Production adapter that resolves [OperatorTrustDatabase.lookup] against the operator's
 * local file at `<sddkHome>/vault/pipelinek-plugin-trust/<releaseTrain>.json`.
 * ## Why a file
 * The operator's trust database is a personal artifact, the same kind of thing as the
 * Byte-cache that lives at `~/.local/share/sddk/...`. The first design choice is to
 * make this local: no HTTP, no remote fetch, no signed-installer assumption. An operator
 * who pins a digest has decided to trust their own copy, and that decision has to work
 * on an air-gapped machine.
 * ## File format
 * ```text
 * {
 *   "v1:plugin:pipeline.scm-git/plugin/scm-git@0.61.0": {
 *     "digest": "sha256:<64-hex>",
 *     "publisher": "operator-approved-name",
 *     "signed": true,
 *     "signedBy": "operator-key-id"
 *   }
 * }
 * ```
 * Each entry is keyed by [OperatorTrustDatabase.key]. An entry whose key does not parse
 * as `<plugin>@<SemVer>` is treated as absent for that lookup and is logged once at
 * adapter construction time; the gate refuses on missing, not on malformed.
*/
class LocalFileOperatorTrustDatabase(
    private val sddkHome: Path,
    private val releaseTrain: String,
) : OperatorTrustDatabase {

    private val cache: Map<String, OperatorTrustDatabase.Row> by lazy {
        loadAndIndex()
    }

    override fun lookup(plugin: String, releaseVersion: SemVer): OperatorTrustDatabase.Row? =
        cache[OperatorTrustDatabase.key(plugin, releaseVersion)]

    private fun loadAndIndex(): Map<String, OperatorTrustDatabase.Row> {
        val file = trustFile()
        if (!file.toFile().exists()) return emptyMap()
        val text = file.toFile().readText(Charsets.UTF_8)
        val root = parseRootObject(text) ?: return emptyMap()
        val out = LinkedHashMap<String, OperatorTrustDatabase.Row>()
        for ((rawKey, rawValue) in root) {
            val row = parseRow(rawValue) ?: continue
            out[rawKey] = row
        }
        return out
    }

    private fun trustFile(): Path = sddkHome
        .resolve("vault")
        .resolve("pipelinek-plugin-trust")
        .resolve("$releaseTrain.json")

    private fun parseRootObject(text: String): Map<String, Any>? =
        JsonObjectCursor(text).readObject()

    private fun parseRow(value: Any): OperatorTrustDatabase.Row? {
        val obj = value as? Map<String, Any> ?: return null
        val digestRaw = obj["digest"] as? String ?: return null
        val digest = runCatching { Digest(digestRaw) }.getOrNull() ?: return null
        val publisher = obj["publisher"] as? String ?: return null
        val signed = obj["signed"] as? Boolean ?: return null
        val signedBy = obj["signedBy"] as? String
        return OperatorTrustDatabase.Row(
            digest = digest,
            publisher = publisher,
            signed = signed,
            signedBy = signedBy,
        )
    }
}

/**
 * Minimal hand-written JSON object reader used by [LocalFileOperatorTrustDatabase].
 * Local-first operator trust files are written by the operator (or a tooling step the
 * operator runs) and never round-trip through a generic JSON library: the file format is
 * contractual, and a small cursor that only knows objects and strings is enough to read
 * it. Anything that needs a richer parser belongs in a different file.
*/
private class JsonObjectCursor(private val source: String) {
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
            't', 'f' -> readBool()
            'n' -> readNull()
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
                        else -> return null
                    }
                }
                else -> sb.append(c)
            }
        }
        return null
    }

    private fun readBool(): Boolean? = when {
        source.startsWith("true", pos) -> { pos += 4; true }
        source.startsWith("false", pos) -> { pos += 5; false }
        else -> null
    }

    private fun readNull(): Any? = if (source.startsWith("null", pos)) { pos += 4; NULL } else null

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

    companion object {
        private val NULL = Any()
    }
}
