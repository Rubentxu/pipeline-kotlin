package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Every test that drives a LIVE follow handle must declare a class-level `@Timeout`.
 *
 * ## What this guards, and why it is not a style rule
 *
 * A follow handle whose run has no terminal condition is **alive, not finished**. Its `hasNext()`
 * blocks in the poll loop until the operator closes it — that is the live-follow contract, and
 * `EventFollowerAdapterTest` row 7b asserts exactly that it blocks. So a row that confuses "the
 * tail is drained" with "the follow is over" does not fail. It **blocks**, and it blocks the whole
 * test JVM, taking every other class in the module with it.
 *
 * The incident this file exists for was measured, not imagined:
 *
 * ```text
 * :pipeline-events-store:test   exit=1, ONE XML out of 44 classes
 * the worker stack:  Test worker -> EventFollowerAdapterTest.7 -> hasNext -> idle (parked)
 * elapsed:           >60 s, growing
 * ```
 *
 * `EventFollowerAdapterTest` had no `@Timeout`, so the single mis-stated row wedged the module
 * permanently — which is why an unrelated change in the same module could not measure its own L4.
 *
 * ## Why `@Timeout` is the fix and not a retry
 *
 * A timeout converts a wrong row from a **module-wide wedge** into **one red row with a name**. That
 * is the whole difference between a five-minute diagnosis and a forty-five-minute one. It does not
 * make the row pass — the row was genuinely asserting a contract the product had deliberately
 * removed — and it must not: the row was fixed separately, and this file only removes the
 * amplification.
 *
 * ## Why the predicate is narrow on purpose
 *
 * The predicate requires BOTH a live follow handle type AND a `.hasNext()` call. A file that merely
 * names a follow type is not in the set:
 *
 * ```text
 * CapabilityRegistrationTest   handle only, no hasNext()   -> NOT in the set
 * M1DCrossJvmFollowTest        handle + hasNext()          -> in the set
 * ```
 *
 * Widening it to "mentions follow" would put four more classes under a rule they cannot violate,
 * and the response to a fitness with false positives is to satisfy it mechanically everywhere —
 * which is how a guard stops being a guard.
 *
 * ## The trap this file is written around
 *
 * The patterns below are string literals in this file's own source, and this file's path would
 * otherwise be the first offender it reports. So the exclusion is explicit and the scanned set is
 * asserted NON-EMPTY before anything is asserted about it — a fitness that walks nothing reads as
 * coverage and measures nothing.
 */
class LiveFollowHandleIsBoundedFitnessTest {

    private val v2Root: Path = generateSequence(Path.of("").toAbsolutePath()) { dir -> dir.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?.resolve("v2")
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has a " +
                ".git entry. This fitness would otherwise scan nothing and pass.",
        )

    /** This file names every pattern it searches for; it is never its own subject. */
    private val self: String = "LiveFollowHandleIsBoundedFitnessTest.kt"

    private val followHandle = Regex("""SqliteEventFollower|EventFollowHandle|EventFollowEvent|SegmentOutputFollower""")

    private val consumes = Regex("""\.hasNext\(\)""")

    /**
     * A class-level `@Timeout`, and nothing else.
     *
     * The pattern requires the annotation to be IMMEDIATELY followed by a class declaration, so:
     *
     * ```text
     *   @Timeout(value = 30, unit = TimeUnit.SECONDS)      followed by `class X`  -> counts
     *   @Timeout(value = 30, unit = TimeUnit.SECONDS)      followed by `fun x()`  -> does NOT count
     *   // @Timeout(value = 30, ...) in a KDoc                                    -> does NOT count
     * ```
     *
     * The last two are the point. A bare `@Timeout\s*\(` is satisfied by this very file's own
     * KDoc, which quotes the annotation as a mutation example — and a guard satisfied by its own
     * documentation is a guard that measures nothing. Class-level rather than per-method because
     * per-method is twenty chances to forget one, and forgetting one is the entire failure being
     * guarded.
     */
    private val declaresTimeout = Regex("""@Timeout\s*\((?:[^()]|\([^()]*\))*\)\s*(?:\w+\s+)*class\s""")

    private fun testSources(): List<Path> =
        Files.walk(v2Root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { it.toString().contains("/src/test/") }
                .filter { !it.toString().contains("/build/") }
                .filter { it.fileName.toString() != self }
                .toList()
        }

    /**
     * M-TIMEOUT — strip the `@Timeout` off one class and the row must notice.
     *
     * Applied to `SegmentOutputFollowerTest`, the last class in the set without one.
     *
     * ```text
     *   @Timeout(value = 30, unit = TimeUnit.SECONDS)     <- deleted
     *     class SegmentOutputFollowerTest {
     * ```
     *
     * The green half is a separate claim, and without it this row could be satisfied by "every
     * follow test file in the repo has a `@Timeout` somewhere" — including in a comment. So the
     * positive half below asserts the set is non-empty AND that a class which names a follow handle
     * WITHOUT calling `hasNext()` is still outside it.
     */
    @Test
    fun `every test that drives a live follow handle declares a class-level timeout`() {
        val sources = testSources()
        assertTrue(
            sources.isNotEmpty(),
            "the production scan walked nothing, so this row would be a vacuous green: root=$v2Root",
        )

        val offenders = sources
            .filter { source ->
                val code = source.toFile().readText()
                followHandle.containsMatchIn(code) && consumes.containsMatchIn(code) &&
                    !declaresTimeout.containsMatchIn(code)
            }
            .map { it.toString().removePrefix("$v2Root/") }
            .sorted()

        assertEquals(
            0,
            offenders.size,
            "A live follow handle with no terminal condition keeps hasNext() blocked on purpose — " +
                "row 7b asserts that it does. A row that confuses 'the tail is drained' with 'the " +
                "follow is over' therefore does not fail, it BLOCKS, and with no class-level " +
                "@Timeout it blocks the whole test JVM and every other class in the module. That " +
                "is measured: :pipeline-events-store:test returned exit=1 with one XML out of 44 " +
                "classes, the worker parked in EventFollowerAdapterTest.7 -> hasNext -> idle. Add " +
                "@Timeout(value = 30, unit = TimeUnit.SECONDS) to the class. Offending classes: " +
                "$offenders",
        )
    }

    /**
     * The positive half, in two directions.
     *
     * 1. The set is non-empty, so row 1 is not passing over nothing.
     * 2. A file that names a follow handle but never calls `hasNext()` is OUTSIDE the set. Without
     *    this, a predicate that simply flagged "mentions follow" would look identical to a correct
     *    one on this tree, and would be found out only the day it forced a `@Timeout` onto a class
     *    that cannot possibly block.
     */
    @Test
    fun `the predicate reaches real consumers and spares files that cannot block`() {
        val sources = testSources()
        val blocking = sources.filter { source ->
            val code = source.toFile().readText()
            followHandle.containsMatchIn(code) && consumes.containsMatchIn(code)
        }

        assertTrue(
            blocking.isNotEmpty(),
            "no test drives a live follow handle, so row 1 would be guarding an empty population: " +
                "either the follow API moved or this predicate rotted. root=$v2Root",
        )

        val mentionsWithoutConsuming = sources.filter { source ->
            val code = source.toFile().readText()
            followHandle.containsMatchIn(code) && !consumes.containsMatchIn(code)
        }
        assertTrue(
            mentionsWithoutConsuming.isNotEmpty(),
            "no file names a follow handle without calling hasNext(). If this is empty the narrow " +
                "predicate is indistinguishable from a broad one, and its green half proves nothing.",
        )
    }
}