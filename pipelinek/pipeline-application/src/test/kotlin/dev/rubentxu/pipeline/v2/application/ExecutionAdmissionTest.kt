package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.Admission
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.SandboxProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertInstanceOf

/**
 * The guarantee: the isolation the operator asks for is DECIDED by the pure
 * core, before anything runs, and the refusal says what the request would have
 * required.
 *
 * The capacity this serves: an operator who asks for a boundary this build
 * cannot provide reads an answer about the BOUNDARY, not about the word they
 * typed. The refusal used to name only the word ("not supported in this build"),
 * which is true and useless: it leaves the operator unable to learn whether
 * another word, or another question, would be answerable.
 *
 * ## Why this instrument
 *
 * The rows call the parser and read the refusal it returns. The parser is the
 * surface where the request is resolved, so a refusal that arrives here has
 * arrived before any script is read, before any store is opened and before any
 * child exists. The decision itself is the core's: the reason the refusal
 * carries is the core's own reason, and these rows would go red if the refusal
 * went back to being a comparison of strings.
 *
 * ## What this instrument does not freeze
 *
 * The exact sentence. What is frozen is that the refusal NAMES the demanded
 * capabilities, because that is the fact the operator cannot get any other way.
 */
class ExecutionAdmissionTest {

    @Test
    fun `the legacy profile is admitted`() {
        val admission = ExecutionAdmission.decide(ExecutionAdmission.profileRequestFor("none")!!.request)

        assertInstanceOf<Admission.Allowed>(admission)
    }

    @Test
    fun `the local profile is admitted and does not become the OS one`() {
        val asked = ExecutionAdmission.profileRequestFor("local")!!

        val admission = ExecutionAdmission.decide(asked.request)

        assertInstanceOf<Admission.Allowed>(admission)
        assertEquals(
            SandboxProfile.LOCAL,
            asked.profile,
            "the word 'local' asks for the legacy confinement, not for the OS one",
        )
    }

    @Test
    fun `a request for a real boundary is refused for a missing attestation`() {
        val asked = ExecutionAdmission.profileRequestFor("os")!!

        val admission = ExecutionAdmission.decide(asked.request)

        assertInstanceOf<DenialReason.MissingAttestation>(
            assertInstanceOf<Admission.Denied>(admission).reason,
        )
    }

    @Test
    fun `the refusal names every capability the request would have required`() {
        val exit = refusalExitFor("os")

        val demanded = IsolationCapability.entries.map { it.name }
        val missing = demanded.filterNot { exit.message.contains(it) }
        assertEquals(
            emptyList<String>(),
            missing,
            "the refusal must list the capabilities the request would have required: ${exit.message}",
        )
    }

    @Test
    fun `the refusal keeps the case and the code of the taxonomy`() {
        val exit = refusalExitFor("os")

        assertEquals(4, exit.code, "a request this build cannot serve is the runtime-not-available case")
        assertEquals("RuntimeNotAvailable", exit.name)
    }

    @Test
    fun `the refusal says why, and not only which word was refused`() {
        val exit = refusalExitFor("os")

        assertTrue(
            exit.message.contains("isolation provider"),
            "the refusal names the cause: ${exit.message}",
        )
    }

    @Test
    fun `the request for a boundary is refused before anything is read`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "os", "/nonexistent/pipeline.kts"),
        )

        val rejected = assertInstanceOf<CliParseResult.Rejected>(parsed)
        assertInstanceOf<CliError.IsolationRequestRefused>(
            rejected.error,
            "the request is refused by the admission, before the script path is ever looked at",
        )
    }

    @Test
    fun `the legacy profile is accepted by the parser`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "local", "pipeline.kts"),
        )

        val accepted = assertInstanceOf<CliParseResult.Parsed>(parsed)
        assertEquals(SandboxProfile.LOCAL, accepted.flags.sandboxProfile)
    }

    @Test
    fun `a word that names no profile is still an invalid argument`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "banana", "pipeline.kts"),
        )

        val rejected = assertInstanceOf<CliParseResult.Rejected>(parsed)
        assertInstanceOf<CliError.InvalidSandboxProfile>(rejected.error)
        assertEquals(2, rejected.error.toCliExit().code, "a word nobody knows is an invalid argument")
    }

    /** The exit the refusal for [word] becomes. */
    private fun refusalExitFor(word: String): CliExit {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", word, "pipeline.kts"),
        )

        val rejected = assertInstanceOf<CliParseResult.Rejected>(parsed)
        return rejected.error.toCliExit()
    }
}
