package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.events.DomainEvent
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText

/**
 * F-ARCH: the console renderer's routing table and its family functions must agree.
 *
 * ## What this prevents
 *
 * `HumanConsoleRenderer.line` is a routing `when`: it is exhaustive over [DomainEvent] and it
 * constructs nothing, it only names, per event, the family function that renders it. The family
 * functions then end in `else -> unrouted(...)`, which throws.
 *
 * Those two properties only hold together if routing and rendering are the same partition. Compile
 * time proves the routing table is exhaustive — a new variant fails the build. It cannot prove the
 * other direction: routing `GitPollChanged` to `controlNoteLine`, which does not render it, is a
 * perfectly well-typed program that throws the first time a run reports a git poll. Nothing in the
 * compiler objects, and the behavioural rows never fire it, because they use a handful of
 * representative events.
 *
 * So this reads the two lists back out of the source and compares them against the sealed hierarchy.
 * That is the only statement available: the disagreement is an *absence* of coverage, which no
 * behavioural assertion can tell apart from the absence of the event altogether.
 *
 * ## Why a source scan
 *
 * The property is structural — two lists agreeing, not any one rendering. A behavioural test can
 * only sample the lists; comparing them exhaustively means reading them. Comments are stripped
 * first, because the renderer documents the very events it routes, and prose must not be counted as
 * a branch.
 *
 * ## Mutation
 *
 * `M-PARTITION` — route `GitPollChanged` to `controlNote`, which does not render it, and leave
 * `ioNote` no longer handling it. This is the mistake the partition row exists for: legal Kotlin,
 * invisible to the compiler, and fatal the first time a run reports a git poll. Measured: it flips
 * `every routed event is rendered by the family it is routed to` and nothing else, naming the event
 * and both families — `controlNote: [GitPollChanged], ioNote: [GitPollChanged]`. The other three rows
 * stay green, which is correct: the event is still routed exactly once, the routing table still
 * constructs nothing, and every family still refuses what it does not render. Restored with hash
 * verified against the pre-mutation file.
 *
 * ## What it does NOT say
 *
 * It says nothing about whether the text each family renders is right. That is the OBS-H rows in
 * `HumanConsoleRendererTest` and `ObserveReplayEncoderEquivalenceTest`. This row covers reachability
 * only: every variant has exactly one route, and that route is handled.
 *
 * It lives here rather than beside the renderer because it needs `kotlin-reflect` to enumerate the
 * sealed hierarchy, and this is the module that already owns the hierarchy-count pin
 * (`FArchL7DomainEventExhaustivityTest`). Two pins of the same sealed hierarchy belong together.
 */
class RendererRoutePartitionTest {

    private val source: String = locate("HumanConsoleRenderer.kt").readText().withoutComments()
    private val families: String = locate("ConsoleFamilyLines.kt").readText().withoutComments()

    @Test
    fun `every DomainEvent variant is routed exactly once`() {
        val routed = routingByFunction().values.flatten()

        val duplicated = routed.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue(
            duplicated.isEmpty(),
            "an event routed twice would render from whichever family the dispatcher picked, and the " +
                "other family's line would silently never appear. Duplicated: $duplicated",
        )

        assertEquals(
            DomainEvent::class.sealedSubclasses.mapNotNull { it.simpleName }.sorted(),
            routed.distinct().sorted(),
            "the routing table must cover the sealed hierarchy exactly: every event has a home, and no " +
                "branch names an event that does not exist. If an event was just added, it belongs in " +
                "exactly one family function AND in the routing branch that calls it.",
        )
    }

    @Test
    fun `every routed event is rendered by the family it is routed to`() {
        val mismatches = routingByFunction().mapNotNull { (function, routedHere) ->
            val handled = handledBy(function)
            val difference = (routedHere - handled) + (handled - routedHere)
            difference.takeIf { it.isNotEmpty() }?.let { "$function: $it" }
        }

        assertTrue(
            mismatches.isEmpty(),
            "a family function that does not render what it was routed would throw at runtime, and " +
                "the compiler cannot see it: both lists are individually legal Kotlin. Each entry is " +
                "the symmetric difference — routed but not handled, and handled but not routed. " +
                "$mismatches",
        )
    }

    @Test
    fun `the routing table constructs nothing`() {
        // The lookbehind is load-bearing: every family is called with a name ENDING in "Line"
        // (`runLine(`, `structureLine(`), so a plain `"Line(" in table` search reports every branch
        // as a violation and the row is red for a file that constructs nothing.
        val constructed = CONSTRUCTOR.findAll(routingTable())
            .map { it.range.first }
            .toList()

        assertTrue(
            constructed.isEmpty(),
            "the routing `when` must only name a family, never build a line: the list of what exists " +
                "and the list of what each case looks like are two different facts, and merging them " +
                "is what made the original function unreadable. Constructors at offsets $constructed.",
        )
    }

    @Test
    fun `every family function refuses an event it does not render`() {
        val silent = routingByFunction().keys.filterNot { function ->
            SILENT_ELSE.containsMatchIn(bodyOf(function))
        }

        assertTrue(
            silent.isEmpty(),
            "a family function whose `else` returned something would render a plausible wrong line for " +
                "an event it does not know, and the console would lie instead of stopping. Functions " +
                "without the throwing fallback: $silent",
        )
    }

    /** The body of the routing `when`: from `line(...)` to the end of the object. */
    private fun routingTable(): String =
        source
            .substringAfter("internal fun line(event: DomainEvent, scope: Scope): Line = when (event) {")
            .substringBefore("\n}")

    /**
     * Which events the routing table sends to which function.
     *
     * Split on the branch arrow rather than parsing the type lists: the table contains no string
     * literals, so `->` can only ever be a branch.
     *
     * The two halves of one branch live in DIFFERENT segments, which is the whole subtlety here.
     * Segment `i` holds the type tests written before arrow `i`; segment `i + 1` holds the call
     * written after it. Reading a branch's names and its call from the same segment pairs every event
     * with the function of the branch after it — a whole-partition shift that still looks like a
     * clean one-to-one mapping, and that still passes a "did every branch name a function" check.
     * That off-by-one was written here first and only showed up when the same parse was run against
     * the pre-refactor file and reported the wrong text for events that had not changed at all.
     */
    private fun routingByFunction(): Map<String, List<String>> {
        val segments = routingTable().split("->")

        return segments.indices
            .drop(1)
            .mapNotNull { index ->
                val target = CALL.find(segments[index])?.groupValues?.get(1) ?: return@mapNotNull null
                val types = IS.findAll(segments[index - 1]).map { it.groupValues[1] }.toList()
                if (types.isEmpty()) null else target to types
            }
            .groupBy({ it.first }, { it.second })
            .mapValues { (_, entries) -> entries.flatten() }
    }

    /** The event types a family function's own `when` renders. */
    private fun handledBy(function: String): List<String> =
        IS.findAll(bodyOf(function)).map { it.groupValues[1] }.toList()

    /** The source of one family function, up to the next family function. */
    private fun bodyOf(function: String): String =
        families.substringAfter("fun $function(")
            .substringBefore("\n    fun ")

    /**
     * Strips line comments and KDoc.
     *
     * The renderer's own prose names every event it routes, so a scan that counted comments would
     * read the explanation of the law as a violation of it.
     */
    private fun String.withoutComments(): String = lineSequence()
        .filterNot { line ->
            val trimmed = line.trimStart()
            trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
        }
        .joinToString("\n")

    private fun locate(fileName: String): Path {
        val moduleRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("pipeline-application/src/main/kotlin") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("could not locate pipeline-application sources from ${System.getProperty("user.dir")}")

        return Files.walk(moduleRoot).use { paths ->
            paths.filter { it.fileName.toString() == fileName }
                .findFirst()
                .orElseThrow { error("$fileName not found under $moduleRoot") }
        }
    }

    private companion object {
        val IS = Regex("""\bis\s+([A-Z]\w*)""")
        val CALL = Regex("""(\w+)\(""")
        val CONSTRUCTOR = Regex("""(?<![\w])Line\(""")
        val SILENT_ELSE = Regex("""else\s*->\s*unrouted\(""")
    }
}
