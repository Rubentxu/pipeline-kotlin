package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * What one entry of the exception ledger authorizes.
 *
 * The ledger is the single authority for which breaks of a published surface
 * were approved, and it is asked about ONE removal at a time. An entry
 * answers that question only when it names the module, the version whose
 * consumers are affected, the exact removal, and the evidence behind the
 * decision. Any one of the four missing, and the entry authorizes nothing:
 * it records that a break was accepted once, not that THIS break is
 * accepted — and reading it as the latter is the failure this ledger exists
 * to prevent.
 *
 * The fail-closed direction is the whole point, so it is pinned from every
 * side: each test below removes exactly one of the four conditions and
 * asserts that the approval disappears.
 *
 * Pure: entries in, one answer out. No file, no build.
 */
class PublishedContractLedgerApprovalTest {

    private val removalKey = "dev/rubentxu/pipeline/v2/domain/Removed#<init>(Ljava/lang/String;)V"

    private fun entry(
        module: String = "pipeline-domain",
        versions: List<String> = listOf("0.90.0"),
        removals: List<String> = listOf(removalKey),
        evidence: String = "Measured on the published SDK artifacts of v0.90.0.",
    ) = PublishedContractExceptionEntry(
        module = module,
        sha = "de45a74abd0717c9b39e32d6f47bdc7e4676aace",
        surface = "the published surface of $module",
        change = "a declaration and its members were withdrawn",
        permittingMaturity = "published",
        reasoning = listOf("the consumers of the covered versions were measured"),
        removalBoundary = "the next published version",
        appliesToVersions = versions,
        authorizedRemovals = removals,
        evidence = evidence,
    )

    private fun ledgerWith(vararg entries: PublishedContractExceptionEntry) =
        PublishedContractLedger(entries.toList())

    // --- the approval, when all four conditions hold -------------------------------------

    @Test
    fun `an entry that names the module, the version, the removal and the evidence approves it`() {
        val ledger = ledgerWith(entry())

        val approval = ledger.approvalFor("pipeline-domain", "0.90.0", removalKey)

        assertNotNull(approval, "the entry did not approve the removal it names")
    }

    @Test
    fun `the ledger answers with the entry that approves, not with a boolean`() {
        // The caller needs to be able to say WHICH receipt covered the break,
        // because that receipt is what the operator reads.
        val ledger = ledgerWith(entry())

        val approval = ledger.approvalFor("pipeline-domain", "0.90.0", removalKey)

        assertNotNull(approval)
        assertEquals("de45a74abd0717c9b39e32d6f47bdc7e4676aace", approval?.sha)
    }

    // --- each condition removed on its own -------------------------------------------------

    @Test
    fun `an entry that names no version approves nothing`() {
        // A break was accepted once, by whom and for which consumers is not
        // stated. Any version could be covered and none is.
        val ledger = ledgerWith(entry(versions = emptyList()))

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `an entry that covers another version approves nothing`() {
        // The approval speaks for the consumers of the versions it names. A
        // comparison against a version it does not name is not covered by it.
        val ledger = ledgerWith(entry(versions = listOf("0.89.0")))

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `an entry that names no removal approves nothing`() {
        // The entry records that a break was accepted; it does not say which
        // one, and a break nobody named is a break nobody approved.
        val ledger = ledgerWith(entry(removals = emptyList()))

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `an entry that covers another removal approves nothing`() {
        val ledger = ledgerWith(
            entry(removals = listOf("dev/rubentxu/pipeline/v2/domain/Other#toString()I")),
        )

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `an entry with no evidence approves nothing`() {
        // An approval without evidence is a wish. The gate has to be able to
        // say what the evidence was, or it cannot say that the break was
        // measured rather than assumed.
        val ledger = ledgerWith(entry(evidence = "   "))

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `an entry of another module approves nothing`() {
        val ledger = ledgerWith(entry(module = "pipeline-events"))

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    // --- the exactness of the removal key ---------------------------------------------------

    @Test
    fun `a key built from the member NAME alone does not approve the member`() {
        // The trap. Two constructors of one declaration share the name
        // `<init>`, and an approval written for one of them must not
        // authorize the removal of the other. The key carries the erased
        // descriptor precisely so that this cannot happen, and this test is
        // what would fail if a future change shortened the key to the name.
        val ledger = ledgerWith(
            entry(removals = listOf("dev/rubentxu/pipeline/v2/domain/Removed#<init>")),
        )

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }

    @Test
    fun `a key built from the member NAME alone does not approve the other constructor either`() {
        val ledger = ledgerWith(
            entry(removals = listOf("dev/rubentxu/pipeline/v2/domain/Removed#<init>")),
        )
        val otherConstructor = "dev/rubentxu/pipeline/v2/domain/Removed#<init>(Ljava/lang/String;I)V"

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", otherConstructor))
    }

    // --- an empty ledger ------------------------------------------------------------------

    @Test
    fun `an empty ledger approves nothing`() {
        val ledger = PublishedContractLedger(emptyList())

        assertNull(ledger.approvalFor("pipeline-domain", "0.90.0", removalKey))
    }
}
