package dev.rubentxu.pipeline.v2.application.durable

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * S4 retention — one authority for the retention DECISION, and nobody else.
 *
 * ## What is pinned, and why it is not a comment
 *
 * `OutputRetentionPort` shipped with a complete vocabulary and no caller. Wiring it from the run's
 * terminal closed the leak, and it closed a second thing that mattered more: **who is allowed to say
 * a run has ended**. A store that re-derived terminality from a directory scan, a journal row or an
 * outcome string would be a second authority on run lifecycle, and two authorities on that fact drift
 * without any compiler noticing.
 *
 * So the laws are stated as properties of the CODE — one construction site, one lifecycle mention,
 * one dependency direction — and a regression is a build failure rather than a reviewer noticing.
 * Two of them are enforced a second time by the module graph on purpose: a text scan proves intent,
 * a missing dependency makes the intent impossible.
 *
 * ## What is deliberately NOT asserted here
 *
 * That a run's output is eventually released. Whether anything is released at all is the
 * **policy** the composition root declares, and `RunOutputRetentionTest` measures both policy values
 * against a real store. A fitness that demanded a deletion would be demanding a product decision,
 * and it would fail the moment an operator configures a hold.
 */
class RetentionAuthorityFitnessTest {

    private val v2Root: Path = generateSequence(Path.of("").toAbsolutePath()) { dir -> dir.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?.resolve("v2")
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has a " +
                ".git entry. This fitness would otherwise scan nothing and pass.",
        )

    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { !it.toString().contains("/build/") }
                .toList()
        }

    /** Production sources: `src/main` only, so a test fixture cannot satisfy a production law. */
    private fun productionSources(module: String): List<Path> =
        kotlinSources(v2Root.resolve(module).resolve("src/main/kotlin"))

    /**
     * Every `src/main/kotlin` in the tree, at any depth.
     *
     * Depth matters: the build has nested modules (`pipeline-step-sdk/runtime/src/main/kotlin`), and
     * a scan that only looked one level down would report a clean result while reading a fraction of
     * the production code — a fitness that measures part of the subject is worse than none, because
     * it reads as coverage.
     */
    private fun allProductionSources(): List<Path> {
        val sourceRoots = Files.walk(v2Root).use { stream ->
            stream
                .filter { Files.isDirectory(it) && it.fileName.toString() == "kotlin" }
                .filter { it.parent?.fileName?.toString() == "main" }
                .filter { it.parent?.parent?.fileName?.toString() == "src" }
                .filter { !it.toString().contains("/build/") }
                .toList()
        }
        return sourceRoots.flatMap { kotlinSources(it) }
    }

    private fun isThisTest(source: Path): Boolean =
        source.fileName.toString() == "RetentionAuthorityFitnessTest.kt"

    /** The one file that is allowed to know the vocabulary itself: the port's own declaration. */
    private val vocabulary = "pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/OutputRetention.kt"

    private fun Path.relativeToV2(): String = toString().removePrefix("$v2Root/").removePrefix("$v2Root\\")

    private fun Path.text(): String = Files.readString(this)

    /** The body of a Gradle `dependencies { … }` block, so a `project.version` elsewhere is not a dependency. */
    private fun dependenciesOf(module: String): String {
        val text = v2Root.resolve(module).resolve("build.gradle.kts").text()
        val start = text.indexOf("dependencies {")
        if (start < 0) return ""
        var depth = 0
        for (index in start until text.length) {
            when (text[index]) {
                '{' -> depth++
                '}' -> {
                    depth--
                    if (depth == 0) return text.substring(start, index)
                }
            }
        }
        return text.substring(start)
    }

    // ------------------------------------------------------------ one decision site

    @Test
    fun `only the run terminal may claim a run reached its terminal state`() {
        // `OutputPruneIntent.RunReachedTerminalState` is a PERMISSION, and a permission is worth
        // exactly what its issuer is worth. One issuer, and the issuer is the runtime.
        val offenders = allProductionSources()
            .filterNot { isThisTest(it) }
            .filter { it.relativeToV2() != vocabulary }
            .filter { it.text().contains("OutputPruneIntent.RunReachedTerminalState") }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "RunReachedTerminalState may be constructed only by RunOutputRetention, which is fed the " +
                "run's terminal state by the coordinator. Any other site is re-deriving terminality — " +
                "a second authority on run lifecycle. Offending files: $offenders",
        )
    }

    @Test
    fun `only the run terminal asks the store to delete`() {
        // The port is a capability, and a capability with several callers is several chances to
        // invent a reason. There is exactly one lawful caller — the retention seam — and the
        // operator release, which does not exist yet, will be the second and will name
        // `OperatorReleased`; this row names it too, so adding it means editing this fitness.
        //
        // The positive half matters as much as the negative one: a scan that only forbade `prune(`
        // would also pass on a build where nothing called it, which is the leak this wiring closed.
        val seam = "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunOutputRetention.kt"
        val offenders = allProductionSources()
            .filterNot { isThisTest(it) }
            .filter { it.relativeToV2() != vocabulary && it.relativeToV2() != seam }
            .filter { it.text().contains(".prune(") }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "prune() is reached from the run's terminal state only. Offending files: $offenders",
        )
        assertTrue(
            v2Root.resolve(seam).text().contains(".prune("),
            "the retention seam must actually reach the port; a fitness that forbade prune() everywhere " +
                "would pass on a build where the plane is never pruned at all — the leak this wiring closed",
        )
    }

    @Test
    fun `no live run can be expressed as a reason to delete`() {
        // The law is a CLOSED ADT, and the runtime seam is what makes it hold at the call site: a
        // caller cannot pass `StillRunning`, because no method accepts a `RunLifecycle` at all.
        // Mechanically: outside the vocabulary's own file, `RunLifecycle.Terminal` is the only case
        // any production source may name, and only from the retention seam.
        val outsideVocabulary = allProductionSources()
            .filterNot { isThisTest(it) }
            .filter { it.relativeToV2() != vocabulary }
            .map { it.relativeToV2() to it.text() }

        val namingStillRunning = outsideVocabulary
            .filter { (_, text) -> text.contains("RunLifecycle.StillRunning") }
            .map { (path, _) -> path }
        assertTrue(
            namingStillRunning.isEmpty(),
            "a caller that can name a LIVE run is a caller that can express deleting its output: " +
                namingStillRunning,
        )

        val namingTerminal = outsideVocabulary
            .filter { (_, text) -> text.contains("RunLifecycle.Terminal") }
            .map { (path, _) -> path }
        assertEquals(
            listOf("pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunOutputRetention.kt"),
            namingTerminal,
            "the run's terminal state is named in exactly one production file: the retention seam",
        )
    }

    @Test
    fun `the retention decision is not spelled as an outcome string`() {
        // `RunOutcome` already has one owner. A prune authorised by comparing `"unstable"` against a
        // string would be a second spelling of a fact the engine owns, and the two would drift with
        // no compiler and no fitness able to see it.
        val seam = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/RunOutputRetention.kt",
        )
        val outcomes = listOf("\"success\"", "\"unstable\"", "\"failure\"", "\"aborted\"", "RunOutcome")

        assertTrue(
            outcomes.none { seam.text().contains(it) },
            "the retention seam must decide from the lifecycle, never from an outcome spelling: $outcomes",
        )
    }

    @Test
    fun `the stage lifecycle does not decide retention`() {
        // `finalizeStage` runs for a stage that is ABORTING the whole run. If a release hung off it,
        // an aborting stage would discard output the run's own failure message still needs, and a
        // two-stage run would release twice.
        val stageFiles = listOf(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/StageExecutionEngine.kt",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ParallelStageEngine.kt",
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/PostPlan.kt",
        )

        val offenders = stageFiles
            .map { v2Root.resolve(it) }
            .filter { Files.isRegularFile(it) }
            .filter { source ->
                val text = source.text()
                text.contains("RunOutputRetention") || text.contains("OutputPruneIntent") ||
                    text.contains("OutputRetentionPort") || text.contains("RetainUntil")
            }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "retention belongs to the RUN's terminal, never to a stage's. Offending files: $offenders",
        )
    }

    // ---------------------------------------------- the store cannot know the lifecycle

    @Test
    fun `the output module cannot know whether a run is alive`() {
        // A store that learned "is this run finished" would be a second authority on that fact, which
        // is exactly what `OutputRetention.kt` is written to prevent. The module has NO dependency on
        // `:pipeline-domain`, so `RunOutcome` is not even on its compile classpath.
        //
        // `OutputRetention.kt` is excluded on purpose: it has to NAME `RunLifecycle`, because the
        // whole point of the vocabulary is that a live run has no case to be deleted under. Excluding
        // it is not a loophole — the store itself is pinned by the next row, and the vocabulary is
        // the one file whose job is to speak both languages.
        val offenders = productionSources("pipeline-output")
            .filterNot { it.relativeToV2() == vocabulary }
            .filter { source ->
                val text = source.text()
                text.contains("RunLifecycle") || text.contains("RunOutcome") ||
                    text.contains("StageOutcome") || text.contains("StepOutcome")
            }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "outside the retention vocabulary, pipeline-output names no lifecycle or outcome type. " +
                "Offending files: $offenders",
        )
    }

    @Test
    fun `the store itself names no lifecycle and can only delete on an intent`() {
        // The narrowest form of the law, on the one file that deletes bytes. It also pins the shape of
        // the only thing the store accepts: a closed intent, never a boolean and never a run id — an
        // id would be enough to delete a run nobody authorised deleting.
        val store = v2Root.resolve(
            "pipeline-output/src/main/kotlin/dev/rubentxu/pipeline/v2/output/SegmentOutputStore.kt",
        )
        val text = store.text()

        for (forbidden in listOf("RunLifecycle", "RunOutcome", "StageOutcome", "StepOutcome", "RetainUntil")) {
            assertTrue(
                !text.contains(forbidden),
                "the store must not name '$forbidden': it deletes on an intent and learns nothing else",
            )
        }
        assertTrue(
            text.contains("override fun prune(intent: OutputPruneIntent)"),
            "the store's delete takes a named intent, so the reason for a deletion is in the type",
        )
    }

    @Test
    fun `no intent can carry a run outcome because the module cannot see one`() {
        // Enforced by the graph, not by a scan: if `:pipeline-output` never depends on
        // `:pipeline-domain`, a prune intent is structurally incapable of naming how a run ended.
        val dependencies = dependenciesOf("pipeline-output")

        assertTrue(
            dependencies.isNotEmpty(),
            "expected a dependencies block in pipeline-output/build.gradle.kts; the row would otherwise " +
                "pass over an unread file",
        )
        assertTrue(
            !dependencies.contains("project("),
            "pipeline-output must stay a leaf module: it deletes bytes and names no domain type. " +
                "Found: $dependencies",
        )
    }

    @Test
    fun `the journal can reference output but cannot delete it`() {
        // The journal is the component that resolves a run and an operation to a stream, so it is
        // the natural place for a delete to grow. `:pipeline-events` therefore does not depend on
        // `:pipeline-output` at all: it cannot name the port, and a component that can only cite
        // output cannot prune it.
        val dependencies = dependenciesOf("pipeline-events")

        assertTrue(
            dependencies.isNotEmpty(),
            "expected a dependencies block in pipeline-events/build.gradle.kts; the row would otherwise " +
                "pass over an unread file",
        )
        assertTrue(
            !dependencies.contains(""":pipeline-output""""),
            "the event/journal module must not depend on the output module. Found: $dependencies",
        )
    }
}
