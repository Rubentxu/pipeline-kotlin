package dev.rubentxu.pipeline.v2.sdk.scm.git

import dev.rubentxu.pipeline.v2.domain.scm.CheckoutSpec
import dev.rubentxu.pipeline.v2.domain.scm.GitCredentials
import dev.rubentxu.pipeline.v2.domain.scm.GitScm
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EventSink
import java.nio.file.Path
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

/**
 * What a checkout does when the run's record refuses the write.
 *
 * ## The guarantee
 * A checkout that cannot record what it did does not carry on. The run's
 * memory of the checkout lives in the event sink, so a sink that refuses the
 * write takes the fact away; a checkout that continued from there would report
 * a success the run cannot prove.
 *
 * ## The failure this covers
 * The write used to be wrapped in a catch that printed a warning on stderr and
 * let the checkout continue. The fact was lost and the run went on as if it had
 * been written: the checkout reported success, and the record of what happened
 * said nothing about it.
 *
 * ## Why the instrument is this cheap
 * The refusal happens on the FIRST record, before any git process starts, so
 * the row needs no repository, no network and no git at all. The temporary
 * directory is there because the credential applier insists on owning one; the
 * checkout never gets far enough to write into it.
 */
class GitCheckoutExecutorRecordTest {

    @TempDir
    lateinit var temp: Path

    @Test
    @DisplayName("a checkout whose record is refused fails instead of carrying on")
    fun `a refused record fails the checkout`() {
        val sink = RefusingSink()
        val executor = GitCheckoutExecutor(
            poll = GitPollExecutor(controlRoot = temp.resolve("poll")),
            changelog = GitChangelogWriter(controlRoot = temp.resolve("changelog")),
            credentialsApplier = GitCredentialsApplier(temp, GitCredentials()),
            clock = FIXED_CLOCK,
            gitControlRoot = temp.resolve("control"),
        )
        val request = GitCheckoutRequest(
            spec = CheckoutSpec(GitScm(url = "file:///nowhere/repo.git", branch = "main")),
            runId = "run-1",
            workspaceRoot = temp.resolve("workspace"),
            eventSink = sink,
            clock = FIXED_CLOCK,
            secretStore = null,
            stepIndex = 0,
            previousRemoteSha = null,
        )

        val result = executor.execute(request)

        assertTrue(result.isFailure, "the checkout reported success after its record was refused")
        val failure = result.exceptionOrNull()!!
        assertTrue(
            failure.message!!.contains("GitCheckoutStarted"),
            "the failure does not name the record that was refused, so an operator cannot " +
                "tell a lost record from a failed checkout; message was: ${failure.message}",
        )
        assertEquals(
            1,
            sink.asked,
            "the checkout carried on after its record was refused, so it went on to do work " +
                "the run cannot account for",
        )
    }

    /**
     * A sink that refuses every write and counts how many it was asked for.
     */
    private class RefusingSink : EventSink {
        var asked = 0
            private set

        override fun append(event: DomainEvent) {
            asked++
            error("the store refused the write")
        }

        override fun eventsFor(runId: String): Sequence<DomainEvent> = emptySequence()
    }

    private companion object {
        val FIXED_CLOCK: Clock = Clock.fixed(Instant.EPOCH, ZoneOffset.UTC)
    }
}
