package dev.rubentxu.pipeline.v2.application.observation

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * PK-RC-RENDER-001 — `ConsoleFamilyLines.run(RunCorrelated)` must:
 *
 *  - not throw via `unrouted` (it has a `RunCorrelated` arm in the dispatch),
 *  - render the correlation in a way that names the event without inventing
 *    a new identity or deciding the run's outcome,
 *  - scrub control characters and ANSI escape sequences from the
 *    external-supplied correlationId, so a hostile invoker cannot inject
 *    terminal escapes that rewrite a reader's screen.
 *
 * The runner is the only test that proves the rc4 regression does not
 * return: the renderer threw on the very first event of every `run`. The
 * architecture-level partition test (RendererRoutePartitionTest) catches the
 * missing branch via source scanning; this test catches the runtime path:
 * construct the event, call the family function, observe the Line.
 */
class ConsoleFamilyLinesRunCorrelatedTest {

    private fun correlated(value: String): RunCorrelated = RunCorrelated(
        eventId = "evt-1",
        runId = "run-1",
        sequence = 0L,
        occurredAt = Instant.parse("2026-10-12T00:00:00Z"),
        correlationId = CorrelationId(value),
        ownerId = "tester",
        startedAtMs = 0L,
    )

    @Test
    fun `RunCorrelated is rendered by the RUN family`() {
        // The rc4 regression was: ConsoleFamilyLines.run() fell into
        // `else -> unrouted(...)` and threw `IllegalStateException` for
        // RunCorrelated. The fix adds a `is RunCorrelated` arm. This test
        // is the runtime proof that the arm is there.
        val line = ConsoleFamilyLines.run(correlated("corr-1"))
        assertEquals(
            HumanConsoleRenderer.Family.RUN, line.family,
            "RunCorrelated must route to the RUN family, not the NOTE fallback or the FALLURE family",
        )
    }

    @Test
    fun `RunCorrelated rendering names the correlation without inventing an identity`() {
        val line = ConsoleFamilyLines.run(correlated("corr-abc-123"))
        assertTrue(
            line.text.contains("corr-abc-123"),
            "the rendering must surface the correlationId supplied by the invoker; got: '${line.text}'",
        )
        // The rendering must not say the run succeeded, failed, started, or
        // finished — the event is run-scoped metadata, not a lifecycle
        // bookend. The runner is the runtime witness for the law the
        // dispatcher documents.
        assertFalse(
            line.text.contains("started", ignoreCase = true) ||
                line.text.contains("finished", ignoreCase = true) ||
                line.text.contains("failed", ignoreCase = true),
            "RunCorrelated is observability, not a lifecycle bookend; " +
                "the rendering must not impersonate one. Got: '${line.text}'",
        )
    }

    @Test
    fun `control characters in correlationId are stripped`() {
        // CR without LF would split one log line into two; NUL can truncate
        // downstream tools; BEL rings the bell. None of them belong on a
        // console. The fix's sanitize() helper drops 0x00..0x1F except
        // LF and TAB.
        val line = ConsoleFamilyLines.run(correlated("a\u0000b\u0007c\u000Dd"))
        assertTrue(
            line.text.contains("abcd"),
            "control characters (NUL/BEL/CR) must be stripped from the correlationId; " +
                "got: '${line.text}'",
        )
        assertFalse(
            line.text.contains('\u0000') || line.text.contains('\u0007') || line.text.contains('\r'),
            "no NUL/BEL/CR may survive into the rendered line; got: '${line.text}'",
        )
    }

    @Test
    fun `ANSI CSI escape sequences in correlationId are stripped`() {
        // The classic terminal-clear: ESC [ 2 J  — if it survived, the
        // console would clear the reader's screen on a render.
        val line = ConsoleFamilyLines.run(correlated("a\u001B[2Jb\u001B[31mred\u001B[0mc"))
        assertTrue(
            line.text.contains("abredc"),
            "the literal text between ANSI escapes must survive, but the escapes themselves must not. " +
                "got: '${line.text}'",
        )
        assertFalse(
            line.text.contains('\u001B'),
            "no ESC (0x1B) may survive into the rendered line; got: '${line.text}'",
        )
    }

    @Test
    fun `LF and TAB are preserved`() {
        // A multi-line error message remains readable on the console; a
        // tab keeps a tabular correlation id (e.g. <runId>\t<owner>)
        // aligned. Dropping them would be over-aggressive.
        val line = ConsoleFamilyLines.run(correlated("a\nb\tc"))
        assertTrue(
            line.text.contains("a\nb\tc"),
            "LF and TAB must survive sanitization; got: '${line.text}'",
        )
    }
}
