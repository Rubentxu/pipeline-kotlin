package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * D3 — the event store has ONE admission authority, and this is what stops a sixth verb from
 * quietly becoming a second one.
 *
 * ## The defect was not the stack trace
 *
 * The stack trace was the symptom. The cause is the shape AGENTS.md calls a second authority under
 * DR-1: **the decision already existed, twice, and three other verbs simply never asked.**
 *
 * ```text
 * MainEventsCli.kt:221         if (!Files.exists(db)) { println("Error: db not found: $db"); return 2 }
 * MainEventsVerifyCli.kt:41    the same rule, its own copy, its own literal
 * Main.kt / MainInspectCli.kt / MainObserveCli.kt / MainConsoleCli.kt   never asked
 * ```
 *
 * Six `SqliteEventStore(` construction sites, four different reactions to one condition. A fix that
 * patches the three leaking verbs leaves the fourth site free to reintroduce the leak, and nothing in
 * the build would notice — which is why the fix is [EventStoreAdmission] plus THIS test, and why the
 * test is the part that matters.
 *
 * ## Why these rows are mechanical rather than a review note
 *
 * Each row reads the real production sources. A sixth verb adding `SqliteEventStore(path)` is a
 * build failure, not a thing a reviewer has to notice. And each row carries a mutation that must
 * kill it; the measured results are recorded in the two KDoc blocks below, because a guard whose
 * mutation SURVIVES is a guard that reads as coverage and measures nothing.
 *
 * ## The two traps this file is written around
 *
 * 1. **The scan must not report its own subject.** The KDoc here names `SqliteEventStore(` and
 *    `db not found`, and the admission's own KDoc carries the measured six-site table. Scanning raw
 *    text would report the explanation as the violation — and, worse, would make the honest way to
 *    write this file (naming what was removed) a build failure. Hence: [isCode] drops comment lines,
 *    and the scan is scoped to `src/main` so this test's own source is never in the set at all.
 * 2. **The guard must be able to see its subject.** A path-scoped scan anchored on the wrong root
 *    walks nothing and passes. That is why the root is found by walking to `.git` rather than from a
 *    relative default, and why the row asserts the set it scanned was NON-EMPTY before asserting
 *    anything about it.
 */
class EventStoreSingleAdmissionAuthorityFitnessTest {

    /**
     * The `v2` root, found by walking up to the checkout that owns `.git`.
     *
     * This module's tests run with the module directory as their working directory, so a relative
     * `Path.of("v2")` resolves to nothing and every scan below would pass over an empty set — a
     * fitness that measures nothing is worse than no fitness, because it reads as coverage.
     */
    private val v2Root: Path = generateSequence(Path.of("").toAbsolutePath()) { dir -> dir.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?.resolve("v2")
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has a " +
                ".git entry. This fitness would otherwise scan nothing and pass.",
        )

    /** Production Kotlin, across every module. Never `build/` — generated code is not the subject. */
    private fun productionSources(): List<Path> =
        Files.walk(v2Root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { it.toString().contains("/src/main/kotlin/") }
                .filter { !it.toString().contains("/build/") }
                .toList()
        }

    /**
     * True when [line] is CODE rather than documentation.
     *
     * KDoc and line comments are dropped rather than stripped in place, because an in-place strip has
     * to find the terminator that ends each block, and silently mis-handles the ones without a
     * closing form on the same line. A line whose first token is a comment marker carries no code by
     * construction, which is the whole rule.
     */
    private fun isCode(line: String): Boolean {
        val trimmed = line.trimStart()
        return trimmed.isNotEmpty() &&
            !trimmed.startsWith("*") &&
            !trimmed.startsWith("//") &&
            !trimmed.startsWith("/*")
    }

    private fun Path.relativeToV2(): String = toString().removePrefix("$v2Root/").removePrefix("$v2Root\\")

    /** The one production file allowed to construct the store. */
    private val admission = "pipeline-events-store/src/main/kotlin/dev/rubentxu/pipeline/v2/events/durable/EventStoreAdmission.kt"

    /** The one production file allowed to name the operator-facing refusal text. */
    private val renderer = "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/EventStoreRefusalRendering.kt"

    /**
     * M-1 — revert one verb to constructing the store directly.
     *
     * ```text
     *     val admission = admitEventStore(compiled.dbPath, EventStorePurpose.Read)
     *     ...
     *     (admission as EventStoreAdmission.Admitted).store.use { ... }
     * ```
     * replaced by the pre-fix line, at ONE of the six sites:
     *
     * ```text
     *     SqliteEventStore(compiled.dbPath).use { ... }
     * ```
     *
     * Measured, with the mutant's file SHA-256 verified before the result was read:
     *
     * ```text
     *   fitness  row 1 RED at MainObserveCli.kt:94        rows 2,3 GREEN
     *   fitness  row 1 RED at MainInspectCli.kt:184       rows 2,3 GREEN
     *   EventStoreAdmissionTest  7/7 GREEN (unaffected — the admission is untouched)
     *   EventStoreAdmissionCliTest  ADMIT_2 RED (observe), then ADMIT_1 RED (inspect)
     * ```
     *
     * The second application is the one that matters for THIS row: the fitness notices a construction
     * site at a verb that was never leaking, which is what makes it a class guard rather than a
     * restatement of the two verbs that happened to be broken today.
     */
    @Test
    fun `no production source constructs an event store outside the admission`() {
        val sources = productionSources()
        assertTrue(
            sources.isNotEmpty(),
            "the production scan walked nothing, so this row would be a vacuous green: root=$v2Root",
        )

        val offenders = sources
            .flatMap { source ->
                source.toFile().readLines()
                    .withIndex()
                    .filter { (_, line) -> isCode(line) }
                    .filter { (_, line) ->
                        line.contains("SqliteEventStore(") &&
                            // The DECLARATION names the type; only a construction calls it.
                            !Regex("""\b(class|interface|object|fun)\s+SqliteEventStore\b""").containsMatchIn(line)
                    }
                    .map { (index, _) -> "${source.relativeToV2()}:${index + 1}" }
            }
            .filterNot { it.startsWith(admission) }

        assertEquals(
            0,
            offenders.size,
            "EventStoreAdmission is the ONE decision on whether a store may be opened. A direct " +
                "SqliteEventStore( elsewhere is a second authority: the pre-fix defect had six " +
                "construction sites and four different reactions to one condition, and three of them " +
                "answered a mistyped --db with a java.sql.SQLException stack trace and exit 1 — the " +
                "code this CLI reserves for a run that executed and failed. Construct through " +
                "admitEventStore(path, EventStorePurpose). Offending sites: $offenders",
        )
    }

    /**
     * M-2 — duplicate the refusal text into a second production file.
     *
     * ```text
     *     is EventStoreRefusal.Absent -> "Error: db not found: $path"
     * ```
     * copied verbatim into a second verb, as `MainEventsCli.kt` and `MainEventsVerifyCli.kt` each
     * carried their own copy.
     *
     * Measured, with the mutant's file SHA-256 verified before the result was read:
     *
     * ```text
     *   fitness  row 2 RED (the literal re-added to MainEventsCli.kt)
     *            rows 1,3 GREEN       <- the separate-claim proof
     *   EventStoreAdmissionCliTest  3/3 GREEN (unaffected)
     * ```
     *
     * Row 1 surviving M-2 is what makes row 2 worth its own build: the two invariants are not the
     * same invariant. One is about WHO decides, the other about WHAT the operator is told, and a
     * duplicate literal is invisible to a scan for duplicate constructors.
     */
    @Test
    fun `the operator-facing refusal text is defined once`() {
        val sources = productionSources()
        assertTrue(
            sources.isNotEmpty(),
            "the production scan walked nothing, so this row would be a vacuous green: root=$v2Root",
        )

        val offenders = sources
            .filter { source ->
                source.toFile().readLines().filter { isCode(it) }.any { it.contains("db not found") }
            }
            .map { it.relativeToV2() }
            .filterNot { it == renderer }

        assertEquals(
            0,
            offenders.size,
            "The refusal text is a public contract: operators and scripts grep `db not found`, and " +
                "`events` has printed it since it first read a run. Rendering it in five places is " +
                "how five places drift, and it is exactly how the correct rule ended up duplicated " +
                "twice while three verbs said nothing. Render through EventStoreRefusal.render(). " +
                "Offending files: $offenders",
        )
    }

    /**
     * M-5 — build a refusal by hand instead of asking the admission's own `refusalFor`.
     *
     * ```text
     *     if (!Files.exists(db)) {
     *         return EventStoreAdmission.Refused(EventStoreRefusal.Absent(db))
     *     }
     * ```
     *
     * This is the hole row 1 leaves open, and it is worth naming precisely: **row 1 guards the
     * construction of the STORE, not the construction of the VERDICT.** A verb that writes the above
     * passes row 1 (it never constructs a store), passes row 2 (it hands the refusal to the shared
     * renderer rather than re-spelling the text), and gets the right exit code with the right words
     * — while re-implementing the entire observation. That is the original defect with a nicer error
     * message, and it is the reason the guards here have to cover the decision and not only its
     * result.
     *
     * The predicate distinguishes the three shapes that share the `EventStoreRefusal.` prefix:
     *
     * ```text
     *     EventStoreRefusal.Absent(path)     a CONSTRUCTION — followed by "("
     *     is EventStoreRefusal.Absent ->     a MATCH     — followed by " ->", consumes, allow it
     *     fun EventStoreRefusal.render()     a RECEIVER  — not a case name, consumes, allow it
     * ```
     *
     * Measured, with the mutant's file SHA-256 verified before the result was read:
     *
     * ```text
     *   fitness  row 3 RED (the hand-built Absent added to MainObserveCli.kt)
     *            rows 1,2,4 GREEN
     *   EventStoreAdmissionCliTest  3/3 GREEN  <- the defect is INVISIBLE at the behavioural level
     * ```
     *
     * That GREEN is the finding, not a reprieve: the mutant produces the correct message on the
     * correct exit code, so only a structural guard can see it. Rows 1,2,4 staying green is what
     * proves row 3 is a claim of its own and not a restatement of any of them.
     */
    @Test
    fun `a refusal verdict is derived by the admission and never assembled at a call site`() {
        val sources = productionSources()
        assertTrue(
            sources.isNotEmpty(),
            "the production scan walked nothing, so this row would be a vacuous green: root=$v2Root",
        )

        // A case is constructed only when the case NAME is followed by "(" — which is what separates
        // `EventStoreRefusal.Absent(path)` from `is EventStoreRefusal.Absent ->` and from an
        // extension receiver declared as `fun EventStoreRefusal.render()`.
        val caseConstruction = Regex("""\bEventStoreRefusal\.(Absent|NoDirectory|NotReadable)\s*\(""")

        val offenders = sources
            .flatMap { source ->
                source.toFile().readLines()
                    .withIndex()
                    .filter { (_, line) -> isCode(line) }
                    .filter { (_, line) -> caseConstruction.containsMatchIn(line) }
                    .map { (index, _) -> "${source.relativeToV2()}:${index + 1}" }
            }
            .filterNot { it.startsWith(admission) }

        assertEquals(
            0,
            offenders.size,
            "The VERDICT is the decision; the store is only what you build after deciding it. " +
                "Assembling a refusal at a call site re-implements the whole observation — which " +
                "existence check, which absence matters for this purpose — and gets a correct " +
                "message and exit code while doing it. That is the pre-fix defect wearing a nicer " +
                "error message, and row 1 cannot see it because no store is constructed. Derive " +
                "through admitEventStore(path, EventStorePurpose). Offending sites: $offenders",
        )
    }

    /**
     * The positive half: the admission still exists and still constructs the store.
     *
     * A regression that "fixed" the duplication by deleting the authority — leaving the six sites to
     * find their own way back — would pass both scans above and fail here. This row is what stops the
     * guard from being satisfiable by absence.
     */
    @Test
    fun `the shared admission still exists and still owns the construction`() {
        val source = v2Root.resolve(admission)
        assertTrue(Files.isRegularFile(source), "the admission must exist at $admission; it was deleted")

        val code = source.toFile().readLines().filter { isCode(it) }.joinToString("\n")
        assertTrue(
            code.contains("SqliteEventStore(path)"),
            "the admission must be the file that actually constructs the store",
        )
        assertTrue(
            code.contains("EventStorePurpose.Read") && code.contains("EventStorePurpose.Write"),
            "both purposes must be stated: a WRITE admits an absent file, a READ does not, and " +
                "collapsing them either breaks every first run or admits every unreadable path",
        )
    }
}