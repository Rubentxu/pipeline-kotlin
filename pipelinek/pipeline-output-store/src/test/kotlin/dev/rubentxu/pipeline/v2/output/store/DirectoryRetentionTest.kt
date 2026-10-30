package dev.rubentxu.pipeline.v2.output.store

import dev.rubentxu.pipeline.v2.output.OutputPin
import dev.rubentxu.pipeline.v2.output.OutputPinId
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Test

/**
 * The guarantee: a directory somebody holds survives the retention pass.
 *
 * The capacity this serves: a release that respects the pins. The published pin port promises that
 * a pin survives any subsequent prune until it is released or it expires, and the retention side
 * acts on the pins alone — so the question "may this directory be deleted?" has exactly two answers
 * that hold it back, and the decision between them has to fall on the side of NOT deleting.
 *
 * The second answer is the one that is easy to forget: "I could not find out" is not "there is
 * nobody". A retention pass that reads it as "there is nobody" deletes bytes somebody is holding,
 * and it reports the bytes as released.
 *
 * These rows are pure: a consult answer and a decision. No directory, no store, no clock, no file.
 */
class DirectoryRetentionTest {

    @Test
    fun `an answered consult with no pins may be deleted`() {
        val decision = decideDirectoryRetention(DirectoryPinAnswer.Answered(emptyList()))

        assertEquals(
            DirectoryRetention.Deletable,
            decision,
            "nobody holds the directory, so the retention pass may delete it",
        )
    }

    @Test
    fun `an answered consult with a pin retains the directory`() {
        val pin = pinOf(stream = "run-1/op-1/stdout", range = 0L..10L)

        val decision = decideDirectoryRetention(DirectoryPinAnswer.Answered(listOf(pin)))

        val retained = assertInstanceOf(DirectoryRetention.Retained::class.java, decision)
        val hold = assertInstanceOf(RetentionHold.Pinned::class.java, retained.hold)
        assertEquals(
            listOf(pin),
            hold.pins,
            "the hold carries the pins that keep the directory: the refusal names who is holding it",
        )
    }

    @Test
    fun `an unavailable substrate retains the directory`() {
        val decision = decideDirectoryRetention(
            DirectoryPinAnswer.Unavailable("the pin manifest could not be read"),
        )

        val retained = assertInstanceOf(DirectoryRetention.Retained::class.java, decision)
        assertInstanceOf(
            RetentionHold.SubstrateUnavailable::class.java,
            retained.hold,
            "\"I could not find out\" is not \"there is nobody\": the directory survives",
        )
    }

    @Test
    fun `an unavailable substrate keeps its cause`() {
        val decision = decideDirectoryRetention(
            DirectoryPinAnswer.Unavailable("the pin manifest could not be read"),
        )

        val retained = assertInstanceOf(DirectoryRetention.Retained::class.java, decision)
        val hold = assertInstanceOf(RetentionHold.SubstrateUnavailable::class.java, retained.hold)
        assertEquals(
            "the pin manifest could not be read",
            hold.cause,
            "the cause names why nobody could be asked",
        )
    }

    private fun pinOf(stream: String, range: LongRange): OutputPin = OutputPin(
        pinId = OutputPinId("pin-1"),
        stream = OutputStreamId(stream),
        range = range,
        holder = "holder-1",
        reason = "held for a read",
        createdAtMs = 0L,
        expiresAtMs = null,
    )
}
