package dev.rubentxu.pipeline.v2.release

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * One entry of the published-contract exception ledger: a deliberate break of
 * a published surface, the SHA that made it, the reason it was allowed, and —
 * when it is decided by machine — the versions it covers, the removals it
 * approves, and the evidence behind it.
 *
 * The last three fields are what make the entry decidable. An entry without
 * them records that a break was accepted; it does not authorize anything,
 * because nothing in it says WHICH break, for WHICH consumers, on WHAT
 * evidence. The gate asks this ledger whether a specific removal was
 * approved, and an entry that cannot answer that question does not answer it.
 */
@Serializable
data class PublishedContractExceptionEntry(
    /** The module whose published surface changed. */
    val module: String,
    /** The commit that made the change. */
    val sha: String,
    /** What changed, in prose. */
    val surface: String,
    /** The nature of the change, in prose. */
    val change: String,
    /** The maturity that permitted the change. */
    @SerialName("permitting_maturity") val permittingMaturity: String,
    /** Why the change was allowed. */
    val reasoning: List<String>,
    /** Where the removal stops. */
    @SerialName("removal_boundary") val removalBoundary: String,
    /** The entry this one replaces, when it replaces one. */
    val supersedes: String? = null,
    /**
     * The published versions whose consumers this entry covers.
     *
     * A version is a set of consumers: the artifacts published under it. An
     * approval therefore names the versions it speaks for, and a comparison
     * against a version this entry does not name is not covered by it.
     */
    @SerialName("applies_to_versions") val appliesToVersions: List<String> = emptyList(),
    /**
     * The removals this entry approves, in the artifact's own spelling: the
     * declaration name for a declaration, `<declaration>#<member>` for a
     * member.
     */
    @SerialName("authorized_removals") val authorizedRemovals: List<String> = emptyList(),
    /**
     * The evidence behind the decision: the artifact and the failure a
     * consumer of the covered versions would hit. An entry without evidence
     * approves nothing.
     */
    val evidence: String = "",
)

/**
 * The ledger file, as it is written on disk.
 *
 * The top-level `_comment` is declared rather than ignored: a file that grew
 * a new top-level key should fail here, where the reader of the ledger is,
 * instead of being silently dropped by a lenient decoder.
 */
@Serializable
data class PublishedContractLedgerFile(
    @SerialName("_comment") val comment: List<String>,
    val entries: List<PublishedContractExceptionEntry>,
)

/**
 * The exception ledger: the single authority for which breaks of a published
 * surface were approved.
 */
data class PublishedContractLedger(
    /** The entries, in the order the file lists them. */
    val entries: List<PublishedContractExceptionEntry>,
) {
    /**
     * The entry that approves [removalKey] for [module] at [version], or null
     * when there is none.
     *
     * Closed on all four conditions, because each of them is the difference
     * between an approval and a wish: the module, the version whose consumers
     * are affected, the exact removal, and the evidence. An entry that names
     * no version, no removal or no evidence authorizes nothing — the gate
     * would otherwise be able to read "a break was accepted once" as "this
     * break is accepted", which is the whole failure this ledger exists to
     * prevent.
     */
    fun approvalFor(
        module: String,
        version: String,
        removalKey: String,
    ): PublishedContractExceptionEntry? =
        entries.firstOrNull { entry ->
            entry.module == module &&
                version in entry.appliesToVersions &&
                removalKey in entry.authorizedRemovals &&
                entry.evidence.isNotBlank()
        }
}

/**
 * Reads the exception ledger.
 *
 * The decoder is strict on unknown keys for the same reason the other codecs
 * in this module are: a ledger that silently drops a field it does not
 * recognise reads as narrower than it is, and the gate acts on what it reads.
 */
object PublishedContractLedgerCodec {

    private val json = Json {
        prettyPrint = true
        prettyPrintIndent = "  "
        encodeDefaults = true
        ignoreUnknownKeys = false
    }

    fun decode(text: String): PublishedContractLedger {
        val file = json.decodeFromString(PublishedContractLedgerFile.serializer(), text)
        return PublishedContractLedger(file.entries)
    }
}
