package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.credentials.executor.SecretMaskingStrictness
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKindUnsupportedException
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.durable.Clock
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.events.NullEventSink
import kotlinx.coroutines.runBlocking
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.assertThrows
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.concurrent.TimeUnit

/**
 * The credential seam of a run that was given a broker, with no broker to answer.
 *
 * ## The guarantees under trial
 *
 *  - a run that was given a broker takes the broker's seam, whatever the machine holds: the seam
 *    is not the empty one a machine without a local store gets, so the run is not left to run
 *    without the credentials it declared;
 *  - a run that binds nothing holds no provider and no executor, and still takes the token and
 *    deletes its file, so a token nobody uses does not wait to be read;
 *  - when the broker cannot be reached the scope fails by name and with fixed words: no path of
 *    the machine, and no answer from anywhere else;
 *  - the materialization of a brokered run projects no file.
 *
 * ## Why the broker does not answer here
 *
 * The rows are about WHICH way a run gets its credentials and what it says when that way is
 * closed. The rows where a broker really answers, with the secret in the scope and masked in the
 * output, are in the integration lane, next to the socket they need.
 *
 * ## What this does not freeze
 *
 * The name of the directory and the words of the exception beyond what a run may disclose.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class BrokeredCredentialSeamTest {

    @Test
    fun `a run that binds nothing holds neither provider nor executor and takes the token`(@TempDir broker: Path) {
        val token = Files.writeString(broker.resolve(RunBrokerLease.TOKEN_FILE_NAME), "session-token")

        val seam = seamOf(specOf(StepSpec.Echo("hola")), broker)

        assertNull(seam.provider)
        assertNull(seam.executor)
        assertFalse(Files.exists(token), "the token of a run that asks for nothing is not left to be read")
    }

    @Test
    fun `a run that binds a credential gets the provider of the broker and an executor`(@TempDir broker: Path) {
        val token = Files.writeString(broker.resolve(RunBrokerLease.TOKEN_FILE_NAME), "session-token")

        val seam = seamOf(specOf(binding("deploy-key")), broker)

        assertEquals(SocketCredentialProvider.PROVIDER_ID, seam.provider?.providerId)
        assertNotNull(seam.executor)
        assertFalse(Files.exists(token), "the token was read once and its file is gone")
    }

    @Test
    fun `a broker that nobody answers fails the scope by name and with no path and no other provider answers`(
        @TempDir broker: Path,
    ) {
        Files.writeString(broker.resolve(RunBrokerLease.TOKEN_FILE_NAME), "session-token")
        val seam = seamOf(specOf(binding("deploy-key")), broker)

        val failure = assertThrows<CredentialBrokerRefusedException> {
            runBlocking {
                seam.executor!!.bind(
                    listOf(StepSpec.CredentialsBinding.string("deploy-key", "API_KEY")),
                    "run-1",
                    NullEventSink,
                )
            }
        }

        assertEquals(CredentialBrokerRefusal.BROKER_UNREACHABLE, failure.refusal)
        assertEquals("deploy-key", failure.credentialId)
        assertFalse(
            failure.message.orEmpty().contains(broker.toString()),
            "nothing the machine holds is in the words",
        )
    }

    @Test
    fun `a broker directory that does not exist still gives the seam of the broker and not the empty one`(
        @TempDir root: Path,
    ) {
        val seam = seamOf(specOf(binding("deploy-key")), root.resolve("gone"))

        assertEquals(SocketCredentialProvider.PROVIDER_ID, seam.provider?.providerId)
        assertNotNull(seam.executor, "a run is not left without the credentials it declared")
    }

    @Test
    fun `the materialization of a brokered run refuses every file projection by name`() {
        val credential = SecretText(CredentialsId("deploy-key"), bytes = "s3cr3t".toByteArray())
        val kinds = listOf(
            MaterializationKind.SecretFile to "SecretFile",
            MaterializationKind.SshPrivateKey to "SshPrivateKey",
            MaterializationKind.Certificate to "Certificate",
            MaterializationKind.Zip to "Zip",
        )

        for ((kind, name) in kinds) {
            val failure = assertThrows<MaterializationKindUnsupportedException>(name) {
                NoFileMaterialization.materialize(credential, kind)
            }

            assertEquals(CredentialsId("deploy-key"), failure.credentialsId, name)
            assertEquals(name, failure.actualKind)
            assertTrue(failure.supportedKinds.isEmpty(), "$name: the brokered run supports no file")
            assertFalse(failure.message.orEmpty().contains("s3cr3t"), "$name: no secret in the words")
        }
    }

    private fun seamOf(spec: PipelineSpec, broker: Path): CredentialSeam =
        composeCredentialSeam(null, FIXED_CLOCK, spec, SecretMaskingStrictness.STRICT, broker)

    private fun specOf(vararg steps: StepSpec): PipelineSpec =
        PipelineSpec(stages = listOf(StageSpec(name = "stage", steps = steps.toList())))

    private fun binding(id: String): StepSpec.WithCredentialsBlock =
        StepSpec.WithCredentialsBlock(
            credentialsId = CredentialsId(id),
            purpose = "a secret the step reads",
            bindings = listOf(StepSpec.CredentialsBinding.string(id, "API_KEY")),
            steps = emptyList(),
        )

    private companion object {
        val FIXED_CLOCK: Clock = object : Clock {
            override fun now(): Instant = Instant.EPOCH
        }
    }
}
