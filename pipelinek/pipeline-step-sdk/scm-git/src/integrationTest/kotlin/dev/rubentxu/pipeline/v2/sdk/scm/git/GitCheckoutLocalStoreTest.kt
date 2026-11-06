package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.durable.DurableTaskRuntime
import dev.rubentxu.pipeline.v2.domain.durable.ExecutionOutputSink
import dev.rubentxu.pipeline.v2.domain.durable.OutputChunk
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionRequest
import dev.rubentxu.pipeline.v2.domain.durable.TaskExecutionResult
import dev.rubentxu.pipeline.v2.domain.durable.TaskSpec
import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.workspace.WorkspaceLease
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Files
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.Base64
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * The checkout of a private remote, composed with the store that holds its
 * credential.
 *
 * **The guarantee.** The credential the store holds is the credential the
 * checkout hands to git: a user name and a password become the Basic header of
 * the remote the checkout named, a private key and its passphrase become the
 * identity of the SSH transport, and when the checkout ends nothing of it is
 * left on disk or in the record of the run.
 *
 * **Why this is an integration test and not a unit test.** Two real modules
 * meet here: the checkout of scm-git and the store that holds the credential.
 * What they disagree about is what a credential that is more than one secret
 * looks like from the outside, and no fake of the STORE can reproduce that
 * disagreement: the store refuses to project a user name and a password into
 * one secret handle, and a seam that took a handle would fail here while
 * every unit test around it stayed green. The world OUTSIDE the two modules
 * is faked: the process that would run git, and the clock.
 *
 * **Why the credential is a real one.** The bytes come out of a store that
 * encrypts them, through the same accessor the production path uses. A
 * credential handed over as a byte array by the test would certify the
 * checkout against itself.
 */
class GitCheckoutLocalStoreTest {

    @TempDir
    lateinit var temp: Path

    @Test
    fun `the user name and the password the store holds are the ones the remote receives`() {
        val store = store()
        val id = CredentialsId("ci-basic")
        store.add(
            id,
            UsernamePassword(id = id, username = USER, password = PASSWORD.toByteArray()),
        )
        val applier = applier()
        val events = mutableListOf<DomainEvent>()

        val result = executor(applier).execute(request("https://github.com/org/repo.git", id, events))

        assertTrue(
            result.isSuccess,
            "the checkout of a remote whose credential the store holds failed: " +
                "${result.exceptionOrNull()?.message}",
        )
        val config = Path.of(requireNotNull(applier.gitConfigFilePath()) {
            "the checkout applied no credential to a remote it declared one for"
        })
        val header = Files.readString(config)
            .lines()
            .first { it.contains("extraHeader") }
            .substringAfter("Basic ")
            .trim()
        assertEquals(
            "$USER:$PASSWORD",
            String(Base64.getDecoder().decode(header), Charsets.UTF_8),
            "the remote received something other than the credentials the store holds",
        )
    }

    @Test
    fun `the key and the passphrase the store holds are the ones the ssh transport carries`() {
        val store = store()
        val keyId = CredentialsId("ci-ssh-key")
        val passphraseId = CredentialsId("ci-ssh-passphrase")
        store.add(passphraseId, SecretText(id = passphraseId, bytes = PASSPHRASE.toByteArray()))
        store.add(
            keyId,
            SshPrivateKey(
                id = keyId,
                username = "git",
                privateKey = SSH_KEY.toByteArray(),
                passphraseRef = dev.rubentxu.pipeline.v2.domain.credentials.LinkedSecretRef(passphraseId),
            ),
        )
        val applier = applier()
        val events = mutableListOf<DomainEvent>()

        val result = executor(applier).execute(request("ssh://git@github.com/org/repo.git", keyId, events))

        assertTrue(
            result.isSuccess,
            "the checkout of a remote whose key the store holds failed: " +
                "${result.exceptionOrNull()?.message}",
        )
        assertEquals(
            SSH_KEY,
            Files.readString(Path.of(requireNotNull(applier.sshKeyPath()) {
                "the checkout applied no key to a remote it declared one for"
            })),
            "the ssh transport carries something other than the key the store holds",
        )
        val passphraseFile = applierRoot(applier).resolve(".git-passphrase")
        assertTrue(
            Files.readString(passphraseFile).contains(PASSPHRASE),
            "the askpass of the checkout cannot read the passphrase the store holds",
        )
    }

    @Test
    fun `the checkout leaves no credential behind and no secret in the record`() {
        val store = store()
        val id = CredentialsId("ci-cleanup")
        store.add(id, UsernamePassword(id = id, username = USER, password = PASSWORD.toByteArray()))
        val applier = applier()
        val events = mutableListOf<DomainEvent>()
        val checkout = executor(applier)

        val result = checkout.execute(request("https://github.com/org/repo.git", id, events))

        assertTrue(
            result.isSuccess,
            "the checkout failed, so nothing was left behind either: " +
                "${result.exceptionOrNull()?.message}",
        )
        // The continent, first: the checkout DID produce a record. An absence
        // of a secret in an empty record is a green that says nothing.
        assertTrue(
            events.isNotEmpty(),
            "the checkout produced no record, so the absence of a secret in it would prove nothing",
        )
        val record = events.joinToString("\n") { it.toString() }
        assertFalse(
            record.contains(PASSWORD),
            "the record of the checkout carries the password the store holds",
        )

        checkout.close()

        assertFalse(
            Files.exists(applierRoot(applier)),
            "the credential directory is still on disk after the checkout ended, and it holds " +
                "the password and the configuration that carries it",
        )
    }

    /** A store of its own, in a directory of its own. */
    private fun store(): LocalSecretStore =
        LocalSecretStore(
            file = temp.resolve("credentials.store"),
            passphrase = "a passphrase that is not a passphrase".toCharArray(),
        )

    /** An applier over a directory of its own. */
    private fun applier(name: String = "applier"): GitCredentialsApplier {
        val root = Files.createDirectories(temp.resolve(name))
        return GitCredentialsApplier(tempDir = root)
    }

    /** The directory an applier of this test owns, so the test can look inside it. */
    private fun applierRoot(applier: GitCredentialsApplier): Path =
        Path.of(applier.answerFilePath()).parent

    /** The executor under test, with the process and the clock faked. */
    private fun executor(applier: GitCredentialsApplier): GitCheckoutExecutor =
        GitCheckoutExecutor(
            // The poll executor runs `git ls-remote`, so it is the one that
            // needs the faked process: a checkout that reached the network
            // would certify nothing about the credential.
            poll = GitPollExecutor(
                taskRuntime = AnsweringRuntime(),
                controlRoot = temp.resolve("poll"),
            ),
            changelog = GitChangelogWriter(controlRoot = temp.resolve("changelog")),
            credentialsApplier = applier,
            taskRuntime = AnsweringRuntime(),
            gitControlRoot = temp.resolve("control"),
        )

    private fun request(
        url: String,
        credentialsId: CredentialsId,
        events: MutableList<DomainEvent>,
    ) = GitCheckoutRequest(
        spec = CheckoutSpec(GitScm(url = url, branch = "main", credentialsId = credentialsId)),
        runId = "run-1",
        workspaceRoot = temp.resolve("workspace"),
        authorizedTarget = AuthorizedCheckoutTarget.resolve(
            ExecutionLocation(WorkspaceLease.Attached(temp.resolve("workspace")), temp.resolve("workspace")),
            "scm-git.checkout",
            ".",
        ),
        eventSink = object : EventSink {
            override fun append(event: DomainEvent) {
                events.add(event)
            }

            override fun eventsFor(runId: String): Sequence<DomainEvent> = events.asSequence()
        },
        clock = FIXED_CLOCK,
        secretStore = store(),
        stepIndex = 0,
        previousRemoteSha = null,
    )

    /**
     * A runtime that answers with the output of `git ls-remote` and spawns
     * nothing: the process is the effect that the world OUTSIDE the two
     * modules under test would have.
     */
    private class AnsweringRuntime : DurableTaskRuntime {
        override suspend fun execute(
            request: TaskExecutionRequest,
            outputSink: ExecutionOutputSink,
        ): TaskExecutionResult {
            outputSink.append(
                OutputChunk(
                    stream = dev.rubentxu.pipeline.v2.domain.durable.TaskStream.STDOUT,
                    data = "deadbeef\trefs/heads/main\n".toByteArray(),
                ),
            )
            return TaskExecutionResult(
                exitCode = 0,
                startedAtEpochMs = 0,
                endedAtEpochMs = 0,
            )
        }
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)

        /** A user name that is not a user name: it never leaves this machine. */
        const val USER = "ci-user"

        /** A password that is not a password: it never leaves this machine. */
        const val PASSWORD = "ci-password"

        /** A passphrase that is not a passphrase: it never leaves this machine. */
        const val PASSPHRASE = "ci-secret-42"

        /** A key that is not a key: the transport only carries its bytes. */
        const val SSH_KEY = "ci-private-key"
    }
}
