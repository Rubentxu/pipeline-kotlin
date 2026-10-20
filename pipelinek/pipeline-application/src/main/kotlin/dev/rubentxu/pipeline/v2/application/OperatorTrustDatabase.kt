package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.step.Digest
import dev.rubentxu.pipeline.v2.domain.step.SemVer
import java.nio.file.Files
import java.nio.file.Path

/**
 * The operator's own record of which plugin artifacts they approve.
 *
 * ## The authority split
 * The only other place a plugin's digest is written down is the plugin's own manifest, and a
 * plugin that can write its own digest can write any digest. A trust database that lived inside
 * the artifact would inherit that defect. This one lives on the operator's machine, and it is the
 * only source the runtime consults about whether an artifact is approved.
 *
 * ## What a row pins, and what that does and does not buy
 * A row pins **the digest of the artifact's bytes** — the digest the runtime measures over the
 * file it is about to load — together with the publisher the operator approved for it and the key
 * that publisher signs with.
 *
 * The manifest's own `releaseDigest` is a DIFFERENT magnitude: it is a digest of the plugin's
 * payload, computed by the plugin's build over the classes and resources it ships. Comparing the
 * two is comparing a container with its contents, and it accuses every plugin in existence of
 * lying about itself. So the runtime never does that comparison, and this row is not about the
 * manifest's claim: it is about the file.
 *
 * Pinning the bytes is a stronger policy than pinning a declaration, and that is the point: a
 * declaration can be copied into another artifact, but bytes cannot.
 *
 * ## Lookup key — `(plugin, releaseVersion)`
 * Keying on `(plugin.canonicalText(), releaseVersion)` and NOT on `plugin` alone is load-bearing:
 * two releases of the same plugin are different things, and a downgrade from release N+1 to
 * release N must not be a no-op at the trust layer. The key is constructed once in this file's
 * [key] helper so callers cannot drift.
 */
interface OperatorTrustDatabase {

    /**
     * One row in the operator trust database.
     * A row asserts that the operator has personally pinned a specific
     * `(plugin, releaseVersion, digest)` triple, and names the publisher and the key they expect
     * that release to be signed with.
     *
     * @property digest the digest of the ARTIFACT'S BYTES as the runtime measures them: the
     *   SHA-256 over the plugin file the operator vetted. NOT the digest the manifest declares
     *   about the plugin's payload — that is a different magnitude, and comparing the two would
     *   report every plugin as lying.
     * @property publisher the publisher the operator approved for this release. The manifest's
     *   own publisher is the plugin's word; this one is the operator's, and they have to agree.
     * @property signed whether this release carries the publisher's signature. A row that says
     *   false names a publisher the operator could not verify, and an artifact is refused under an
     *   enforced policy.
     * @property signedBy the id of the key that signed the release. It names a key in the
     *   operator's keyring, so a row cannot smuggle in a key of its own.
     */
    data class Row(
        val digest: Digest,
        val publisher: String,
        val signed: Boolean,
        val signedBy: String? = null,
    )

    /**
     * Look up the operator's row for `(plugin, releaseVersion)`.
     *
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
 * The production adapter: the operator's trust file, read from the path they named.
 *
 * ## Why a file
 * The operator's trust database is a personal artifact. The first design choice is to make this
 * local: no HTTP, no remote fetch, no signed-installer assumption. An operator who pins a digest
 * has decided to trust their own copy, and that decision has to work on an air-gapped machine.
 *
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
 * Each entry is keyed by [OperatorTrustDatabase.key]. An entry whose key does not parse as
 * `<plugin>@<SemVer>`, or whose fields are missing or malformed, is treated as ABSENT for that
 * lookup: the gate refuses on missing, not on malformed, and a row nobody can read is not a row
 * anybody can rely on.
 *
 * ## A document that cannot be read is an empty database
 * An unreadable or malformed trust file yields no rows. That refuses every artifact, which is
 * the correct reading: an operator who asked for a trust policy and got a file this runtime
 * cannot read must not be left believing the policy is in force when it is not.
 */
class LocalFileOperatorTrustDatabase(
    private val trustFile: Path,
) : OperatorTrustDatabase {

    private val rows: Map<String, OperatorTrustDatabase.Row> by lazy { loadAndIndex() }

    override fun lookup(plugin: String, releaseVersion: SemVer): OperatorTrustDatabase.Row? =
        rows[OperatorTrustDatabase.key(plugin, releaseVersion)]

    /** The path this database reads. Diagnostics: a refusal names the policy it was judged against. */
    fun source(): Path = trustFile

    private fun loadAndIndex(): Map<String, OperatorTrustDatabase.Row> {
        if (!Files.exists(trustFile)) return emptyMap()
        val text = runCatching { Files.readString(trustFile, Charsets.UTF_8) }.getOrNull() ?: return emptyMap()
        return parseOperatorTrustRows(text)
    }
}

/**
 * Read the operator's trust rows out of the document they wrote.
 *
 * Pure: the text goes in, the rows come out, and nothing here touches a disk. That is what lets
 * the format be pinned down by a test that starts no process and reads no file.
 *
 * @return the rows, keyed exactly as they were written. Entries that cannot be read are absent.
 */
internal fun parseOperatorTrustRows(text: String): Map<String, OperatorTrustDatabase.Row> {
    val root = MinimalJson.parseObject(text) ?: return emptyMap()
    val out = LinkedHashMap<String, OperatorTrustDatabase.Row>()
    for ((rawKey, rawValue) in root) {
        val row = parseTrustRow(rawValue) ?: continue
        out[rawKey] = row
    }
    return out
}

private fun parseTrustRow(value: Any?): OperatorTrustDatabase.Row? {
    val obj = value as? Map<*, *> ?: return null
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
