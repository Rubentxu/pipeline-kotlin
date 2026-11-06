package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.isolation.Admission
import dev.rubentxu.pipeline.v2.domain.isolation.AdmissionDecider
import dev.rubentxu.pipeline.v2.domain.isolation.DenialReason
import dev.rubentxu.pipeline.v2.domain.isolation.ExecutionRequirements
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationAttestation
import dev.rubentxu.pipeline.v2.domain.isolation.IsolationCapability
import dev.rubentxu.pipeline.v2.domain.isolation.ProvenCapability
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
 * ## Why the provider is a fixture here
 *
 * The rows must not depend on the host: a test that passed on a machine with a
 * confinement tool and failed on one without would be a test about the machine.
 * The provider is therefore a fixture that answers from a constant, and the rows
 * that care about the HOST — whether it is measured at all — count the questions
 * it was asked.
 *
 * ## What this instrument does not freeze
 *
 * The exact sentence. What is frozen is that the refusal NAMES the demanded
 * capabilities, because that is the fact the operator cannot get any other way.
 */
class ExecutionAdmissionTest {

    @Test
    fun `the legacy profile is admitted`() {
        val admission = decideFor("none", IsolationAttestation.TrustedProfileOnly)

        assertInstanceOf<Admission.Allowed>(admission)
    }

    @Test
    fun `the local profile is admitted and does not become the OS one`() {
        val asked = ExecutionAdmission.profileRequestFor("local")!!

        val admission = decideFor("local", IsolationAttestation.TrustedProfileOnly)
        assertInstanceOf<Admission.Allowed>(admission)
        assertEquals(
            SandboxProfile.LOCAL,
            asked.profile,
            "the word 'local' asks for the legacy confinement, not for the OS one",
        )
    }

    @Test
    fun `a request for a real boundary is refused for a missing attestation`() {
        val admission = decideFor("os", IsolationAttestation.TrustedProfileOnly)

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
            exit.message.contains("legacy trusted posture"),
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

    @Test
    fun `a request that demands nothing is answered by the legacy posture`() {
        val attestation = BwrapIsolationProvider.attest(ExecutionRequirements())

        assertInstanceOf<IsolationAttestation.TrustedProfileOnly>(
            attestation,
            "a request that asks for no capability has nothing to measure",
        )
    }

    @Test
    fun `the confined word asks for a confined filesystem and an isolated network`() {
        val asked = ExecutionAdmission.profileRequestFor("confined")!!

        assertEquals(
            setOf(
                IsolationCapability.FilesystemConfinement,
                IsolationCapability.NetworkConfinement,
            ),
            AdmissionDecider.requiredCapabilities(asked.request),
            "the word 'confined' states the boundary it asks for",
        )
        assertEquals(SandboxProfile.CONFINED, asked.profile)
    }

    @Test
    fun `the confined word is admitted when the provider proves the boundary`() {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "confined", "pipeline.kts"),
            provider = CountingProvider(IsolationAttestation.Provider(PROVEN_FILESYSTEM_AND_NETWORK)),
        )

        val accepted = assertInstanceOf<CliParseResult.Parsed>(parsed)
        assertEquals(SandboxProfile.CONFINED, accepted.flags.sandboxProfile)
    }

    @Test
    fun `the confined word is refused naming the capability the host could not prove`() {
        val exit = refusalExitFor("confined", CountingProvider(IsolationAttestation.Provider(PROVEN_FILESYSTEM_ONLY)))

        assertTrue(
            exit.message.contains("NetworkConfinement"),
            "the refusal names what this host does not prove: ${exit.message}",
        )
        assertEquals(
            4,
            exit.code,
            "a boundary this host cannot serve is the runtime-not-available case",
        )
    }

    @Test
    fun `the default profile asks for nothing about the network of the child process`() {
        val asked = ExecutionAdmission.profileRequestFor("none")!!

        assertEquals(
            emptySet<IsolationCapability>(),
            AdmissionDecider.requiredCapabilities(asked.request),
            "the default profile asks for no capability, so it promises nothing about the " +
                "network of its child process: the only posture that takes that network away " +
                "is the one that asks for it",
        )
    }

    @Test
    fun `the refusal says what the child process would keep without the confinement`() {
        val exit = refusalExitFor("confined", CountingProvider(IsolationAttestation.Provider(PROVEN_FILESYSTEM_ONLY)))

        assertTrue(
            exit.message.contains("keeps the host's network"),
            "the refusal names the capability but not its consequence, so the operator " +
                "cannot tell what the run would have kept: ${exit.message}",
        )
    }

    @Test
    fun `the legacy words do not measure the host`() {
        val provider = CountingProvider(IsolationAttestation.Provider(PROVEN_FILESYSTEM_AND_NETWORK))

        CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "local", "pipeline.kts"),
            provider = provider,
        )

        assertEquals(
            0,
            provider.asked,
            "a request that demands nothing is answered by the legacy posture: nothing is measured",
        )
    }

    @Test
    fun `a word with nowhere to run does not measure the host`() {
        val provider = CountingProvider(IsolationAttestation.Provider(PROVEN_FILESYSTEM_AND_NETWORK))

        CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", "os", "pipeline.kts"),
            provider = provider,
        )

        assertEquals(
            0,
            provider.asked,
            "a request this build cannot run is refused without starting a child to ask about it",
        )
    }

    /** The verdict for the request the word [word] expresses. */
    private fun decideFor(word: String, attestation: IsolationAttestation): Admission {
        val asked = ExecutionAdmission.profileRequestFor(word)!!
        return ExecutionAdmission.decide(asked.request, attestation)
    }

    /** The exit the refusal for [word] becomes. */
    private fun refusalExitFor(
        word: String,
        provider: IsolationProvider = CountingProvider(IsolationAttestation.TrustedProfileOnly),
    ): CliExit {
        val parsed = CliExecutionParser.parse(
            arrayOf("run", "--sandbox-profile", word, "pipeline.kts"),
            provider = provider,
        )

        val rejected = assertInstanceOf<CliParseResult.Rejected>(parsed)
        return rejected.error.toCliExit()
    }

    /**
     * A provider that answers from a fixture and counts how many times it was asked.
     *
     * The count is what the rows about measurement read: a provider that was asked
     * is a provider that may have started a child.
     */
    private class CountingProvider(private val attestation: IsolationAttestation) : IsolationProvider {
        var asked: Int = 0
            private set

        override fun attest(request: ExecutionRequirements): IsolationAttestation {
            asked++
            return attestation
        }
    }

    private companion object {
        val PROVEN_FILESYSTEM_AND_NETWORK: List<ProvenCapability> = listOf(
            ProvenCapability(IsolationCapability.FilesystemConfinement, "read-only rootfs"),
            ProvenCapability(IsolationCapability.NetworkConfinement, "no egress"),
        )

        val PROVEN_FILESYSTEM_ONLY: List<ProvenCapability> = listOf(
            ProvenCapability(IsolationCapability.FilesystemConfinement, "read-only rootfs"),
        )
    }
}
