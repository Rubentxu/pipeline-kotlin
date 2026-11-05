package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths

/**
 * The contract of reception, and what a reader has to refuse.
 *
 * The guarantee under test is that the contract this product ships is a
 * CONTRACT and not a paragraph: it is read by a reader that refuses what it
 * does not understand, and the refusals are not about the shape alone. A
 * contract that named another message, or another file name, or another
 * identity, would send the receiver to look in one place while the publisher
 * wrote in another, and the request would sit there unanswered with both
 * sides certain the other one was at fault.
 *
 * The first witness is the shipped document itself: it decodes. That single
 * fact says the contract agrees with what this build emits, because the
 * reader compares the two and refuses a contract that does not. The rest of
 * the witnesses say the reader has the power to refuse: each one doctors the
 * shipped document in one way and names the refusal.
 *
 * The layer is the unit, and no layer deeper is needed: the guarantee is
 * about a document and about a rule that reads it. There is no process, no
 * port and no state to observe, so an integration test would say the same
 * thing more slowly. Where the message LANDS is a different guarantee, and
 * it has its own instrument.
 */
class ReceptionContractTest {

    /** The digest of a candidate: 64 hex, so the identity is a digest and not a name. */
    private val digest = "a".repeat(64)

    /** The contract this product ships, read from the repository. */
    private val shipped: String = Files.readString(contractPath())

    /** The contract this product ships, decoded. */
    private fun contract(): ReceptionContract =
        assertInstanceOf(
            ReceptionContractDecodeResult.Accepted::class.java,
            ReceptionContractCodec.decode(shipped),
        ).contract

    /** The refusal the reader gives for [text]. */
    private fun rejection(text: String): CertificationRejection =
        assertInstanceOf(
            ReceptionContractDecodeResult.Rejected::class.java,
            ReceptionContractCodec.decode(text),
        ).rejection

    @Test
    fun `the contract this product ships decodes`() {
        val contract = contract()

        assertEquals("pipelinek-release-reception", contract.contractId)
        assertEquals("rc.certification.requested", contract.message.name)
        assertTrue(contract.pendingSweep.required, "the sweep is part of the contract")
        assertTrue(
            contract.pendingSweep.why.isNotBlank(),
            "a rule the receiver cannot see the reason of is a rule it implements on trust",
        )
    }

    @Test
    fun `a contract that carries a field this schema does not define is refused by name`() {
        val doctored = shipped.replaceFirst("{", "{\n  \"unexpected\": \"surprise\",")

        val rejection = rejection(doctored)

        val unknown = assertInstanceOf(CertificationRejection.UnknownField::class.java, rejection)
        assertEquals(listOf("unexpected"), unknown.fields)
    }

    @Test
    fun `a contract that declares another schema version is refused`() {
        val doctored = shipped.replace(
            ReceptionContract.SCHEMA_VERSION,
            "pipelinek-reception-contract-2",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains("pipelinek-reception-contract-1"),
            "the refusal must name the schema this build reads, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that does not require the sweep of the pending messages is refused`() {
        val doctored = shipped.replace("\"required\": true", "\"required\": false")

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains("sweep"),
            "the refusal must name what is missing, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that requires the sweep and does not say why is refused`() {
        val doctored = Regex("\"why\": \"[^\"]*\"").replace(shipped) { "\"why\": \"\"" }

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
    }

    @Test
    fun `a contract that declares another format of the message is refused`() {
        val doctored = shipped.replace(
            CertificationRequestedEvent.SCHEMA_VERSION,
            "pipelinek-certification-requested-2",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains(CertificationRequestedEvent.SCHEMA_VERSION),
            "the refusal must name the format this build emits, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that declares another protocol version is refused`() {
        val doctored = shipped.replace(
            "\"protocol_version\": \"${CertificationRequestedEvent.PROTOCOL_VERSION}\"",
            "\"protocol_version\": \"99\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
    }

    @Test
    fun `a contract that names another message is refused`() {
        val doctored = shipped.replace(
            "\"name\": \"${CertificationRequestedEvent.NAME}\"",
            "\"name\": \"rc.other.requested\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains(CertificationRequestedEvent.NAME),
            "the refusal must name the message this build emits, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that names another file for the message is refused`() {
        val doctored = shipped.replace(
            "\"file_name\": \"${CertificationRequestedEvent.FILE_NAME_TEMPLATE}\"",
            "\"file_name\": \"request.json\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
    }

    @Test
    fun `a contract that declares another pattern for the file name is refused`() {
        // The pattern travels in the contract as a JSON string, so the doctored
        // value has to be one: a backslash inside a JSON string is written
        // twice.
        val doctored = Regex("\"file_name_pattern\": \"[^\"]*\"")
            .replace(shipped) { "\"file_name_pattern\": \"^request-[0-9]+\\\\.json$\"" }

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains("pattern"),
            "the refusal must name what disagrees, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that declares another identity is refused`() {
        val doctored = shipped.replace(
            "\"rule\": \"$RECEPTION_IDENTITY_RULE\"",
            "\"rule\": \"modification_time\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
        assertTrue(
            rejection.describe().contains(RECEPTION_IDENTITY_RULE),
            "the refusal must name the identity this build carries, got: ${rejection.describe()}",
        )
    }

    @Test
    fun `a contract that carries the identity by something else is refused`() {
        val doctored = shipped.replace(
            "      \"file_name\",\n      \"document\"",
            "      \"file_name\",\n      \"mtime\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
    }

    @Test
    fun `a contract that declares a state it does not explain is refused`() {
        val doctored = shipped.replace(
            "\"pending\": \"a message whose identity the receiver has not processed yet\"",
            "\"pending\": \"\"",
        )

        val rejection = rejection(doctored)

        assertInstanceOf(CertificationRejection.MalformedDocument::class.java, rejection)
    }

    @Test
    fun `the inbox is the directory the environment names, when it names one`() {
        val contract = contract()

        val directory = contract.inboxDirectory(
            mapOf(
                "PIPELINEK_INBOX_DIRECTORY" to "/srv/inbox",
                "XDG_STATE_HOME" to "/state",
                "HOME" to "/home/receiver",
            ),
        )

        assertEquals("/srv/inbox", directory)
    }

    @Test
    fun `the inbox hangs from the state directory when nothing names one`() {
        val contract = contract()

        val directory = contract.inboxDirectory(
            mapOf("XDG_STATE_HOME" to "/state", "HOME" to "/home/receiver"),
        )

        assertEquals("/state/pipelinek/inbox", directory)
    }

    @Test
    fun `the inbox falls back to the home directory when the state directory says nothing`() {
        val contract = contract()

        val directory = contract.inboxDirectory(mapOf("HOME" to "/home/receiver"))

        assertEquals("/home/receiver/.local/state/pipelinek/inbox", directory)
    }

    @Test
    fun `an inbox that nothing can resolve is no inbox`() {
        val contract = contract()

        assertNull(contract.inboxDirectory(emptyMap()))
    }

    @Test
    fun `the file that carries the message is named by the candidate`() {
        val contract = contract()

        val name = contract.messageFileName(CandidateId.fromDigest(digest))

        assertEquals("rc.certification.requested.$digest.json", name)
        assertTrue(
            Regex(contract.message.fileNamePattern).matches(name),
            "the name the contract declares must match the pattern it declares",
        )
    }

    @Test
    fun `the path of the message is the inbox and the name of the message`() {
        val contract = contract()

        val path = contract.messagePath(
            CandidateId.fromDigest(digest),
            mapOf("PIPELINEK_INBOX_DIRECTORY" to "/srv/inbox"),
        )

        assertEquals(
            Paths.get("/srv/inbox/rc.certification.requested.$digest.json"),
            path,
        )
    }

    /** The contract of this repository, read from where this repository keeps it. */
    private fun contractPath(): Path {
        val repoRoot = System.getProperty("pipeline.repoRoot")
            ?: error(
                "the test needs the repository root to find the contract, and the build did not " +
                    "hand it over. A witness that cannot find the document it is about proves " +
                    "nothing.",
            )
        return Paths.get(repoRoot, "pipelinek", "contract", "reception-contract.json")
    }
}
