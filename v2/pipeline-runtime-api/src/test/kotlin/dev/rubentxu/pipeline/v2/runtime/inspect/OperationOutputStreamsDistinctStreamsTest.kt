package dev.rubentxu.pipeline.v2.runtime.inspect

import dev.rubentxu.pipeline.v2.output.OutputStreamId
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The distinct-streams invariant of [OperationOutputStreams] (runtime-api), and the three states
 * nullability is genuinely there to express.
 *
 * ## What this row certifies
 *
 * That "both channels declared" means TWO streams. Its sibling in `:pipeline-output` accepted the
 * same stream twice; closing that instance alone would have left the shape broken in two places.
 *
 * ## The authority it crosses
 *
 * [OperationOutputStreams]'s constructor `init`. The rows ask the constructor and read what
 * happened — nothing here re-implements the check. HF0 pure contract: the invariant is over two
 * immutable values, with no store, no filesystem and no process.
 *
 * ## Why the three states below are NOT flag-bag cleanup
 *
 * `AGENTS.md` forbids nullable sentinels, and it is right about a boolean paired with a nullable.
 * Here none/one/both are the three real states of "what did this operation declare", and a single
 * value would have to invent an "all of the above" channel that does not exist — the same argument
 * `dev.rubentxu.pipeline.v2.output.OutputChannel` makes about why `merge` is not a third channel.
 * So the nullability stays and the invariant guards the one combination that must never occur.
 *
 * ## The row that earned its place
 *
 * [the three real states are all constructible] went RED against the first version of the guard,
 * which was the shape the name suggests: `require(stdoutStream != stderrStream)`. `null != null` is
 * `false`, so it refused the *none* state — a legitimate value, produced by an operation that
 * declares no stream. Measured 2026-10-18: `tests=4 failures=1`, the death at
 * `OperationOutputStreamsDistinctStreamsTest.kt:40`, the message naming `null`.
 *
 * Both halves were needed and neither alone would have done. The two refusal rows say the invariant
 * has teeth; the `none` row says it does not have too many. A guard that only proves it can refuse
 * something is satisfied by a guard that refuses everything.
 */
class OperationOutputStreamsDistinctStreamsTest {

    private val stdout = OutputStreamId("run-7/run-7-s0-0/stdout")
    private val stderr = OutputStreamId("run-7/run-7-s0-0/stderr")

    @Test
    fun `the three real states are all constructible`() {
        // none: a pure computation declares no streams.
        assertNull(OperationOutputStreams(null, null).stdoutStream)
        // one: a step that only writes stderr.
        assertTrue(OperationOutputStreams(null, stderr).stderrStream == stderr)
        // both: the ordinary case.
        val both = OperationOutputStreams(stdout, stderr)
        assertTrue(both.stdoutStream == stdout && both.stderrStream == stderr)
    }

    @Test
    fun `the same stream in both fields is refused`() {
        // The construction goes INSIDE the lambda. `init` refuses at construction time, so hoisting
        // the call out lets the correct refusal escape the assertion and the row reports a product
        // bug that is not there.
        val failure = assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(stdoutStream = stdout, stderrStream = stdout)
        }
        assertTrue(
            failure.message!!.contains("two different streams"),
            "the refusal must say which invariant failed. Message was: ${failure.message}",
        )
    }

    @Test
    fun `the reversed duplicate is refused too`() {
        assertThrows(IllegalArgumentException::class.java) {
            OperationOutputStreams(stdoutStream = stderr, stderrStream = stderr)
        }
    }

    @Test
    fun `one null and one present is not a duplicate`() {
        // The `!=` form must not over-reach into the two states that declare one channel: a null
        // never equals a value, so both of these are accepted, and a stricter-looking guard that
        // demanded "exactly one null" would have made the ordinary `both` case illegal.
        assertTrue(OperationOutputStreams(stdout, null).stdoutStream == stdout)
        assertTrue(OperationOutputStreams(null, stderr).stderrStream == stderr)
    }
}