package dev.rubentxu.pipeline.v2.application.durable

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * ADR-OBS-002 — no read path may reach an opening that recovers.
 *
 * ## What this prevents
 *
 * The seven OBS-1 rows are behavioural and they all pass through the real store. What none of them can
 * say is whether the shape that made the defect possible is still *available*, because the defect was
 * an availability, not an event:
 *
 * ```kotlin
 * fun storeFor(controlDirRoot: Path): SegmentOutputStore = SegmentOutputStore(root.resolve(OUTPUT_DIR))
 * // ...later, inside read():
 * if (!recovered && recoveryPermitted) recover()
 * ```
 *
 * One opener, used by writers and by `observe`/`console` alike, whose `read` reconciled on the way in.
 * Every reader therefore had the power to truncate a live writer's reservation, and OBS-G measured
 * exactly that: a writer confirmed 8192 bytes and 4096 survived.
 *
 * The fix splits the opening (`storeForReading` / `storeForWriting`) and adds a kernel-held ownership
 * lock. Both halves are only worth anything if they cannot be quietly undone, and the undo is cheap:
 * swap one identifier in one line and every behavioural row stays green, because with the ownership
 * lock in place the reverted call is merely *destructive again*, not visibly wrong to a reader that
 * happens to look. This file is what makes that revert red.
 *
 * ## Why a source scan, and what it deliberately does not claim
 *
 * The question is a reachability question — "can executable code get from a read verb to `recover()`?" —
 * and answering it properly needs whole-program analysis. The scan answers the narrower question that
 * is actually checkable here: **the set of files permitted to name a recovering opening is closed**, and
 * the store has exactly one constructor site. That is a real boundary rather than a convention, and it
 * holds for a future read-side file nobody has thought about yet, which a list of known readers would
 * not.
 *
 * The scan does not claim to prove `SegmentOutputStore` cannot be reached indirectly through a value
 * passed around as a parameter. That is a real limit and it is stated here rather than left to be
 * discovered: the law is "recovering openings are not *named* outside the write side", which is the
 * failure mode that actually occurred. Whichever store a reader is handed, the constructor's
 * `recoveryPermitted` default and the `check(recoveryPermitted)` inside `recover()` remain the second
 * line of defence, and those are pinned behaviourally by `ObsPcReadRecoveryOwnershipUatTest`.
 *
 * Test sources are EXCLUDED. `ObsGInterferenceProducer` legitimately opens a writing store to play the
 * part of a crashed writer; a fitness that failed on it would train people to route around the law
 * rather than obey it.
 *
 * ## The mutations that must kill this
 *
 * - **M-OWN-4** — drop `recoveryPermitted = false` from `storeForReading`. REDS **row 2**, and restores
 *   the single line that made every read verb destructive.
 * - **M-OWN-5** — construct a second `SegmentOutputStore` anywhere outside `OutputPlaneProvider`. REDS
 *   **row 1**: a private opening with recovery enabled re-creates the whole defect off the seam.
 * - **M-OWN-6** — change `MainConsoleCli` to `storeForWriting`. REDS **row 3**: this is the exact
 *   regression OBS-G reproduced, reverted by one identifier, and it is the one no behavioural row in
 *   OBS-1 would notice.
 */
class ObsPcReadRecoverySeamFitnessTest {

    @Test
    fun `the Output Plane store is constructed in exactly one file`() {
        val offenders = productionSources().flatMap { file ->
            if (file.fileName.toString() == PROVIDER) return@flatMap emptyList()
            codeLines(file)
                // The declaration `class SegmentOutputStore(` is not a call site. Excluding the whole
                // file instead would have been the lazier fix and the weaker law: a genuine second
                // construction inside it would then pass.
                .filterNot { it.second.startsWith("class ") }
                .filter { it.second.contains("SegmentOutputStore(") }
                .map { "${file.fileName}:${it.first}  ${it.second}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "the store may only be constructed by OutputPlaneProvider: an opening is a CAPABILITY " +
                "decision, and a second constructor call is a second decision made by code that did not " +
                "have to think about recovery. Route new needs through storeForReading/storeForWriting, " +
                "which is what makes this a boundary rather than a convention. Found: $offenders",
        )
    }

    @Test
    fun `the reading opening is constructed without permission to recover`() {
        val provider = locateFile(PROVIDER)
        val text = readTextOrFail(provider)

        assertTrue(
            Regex("""recoveryPermitted\s*=\s*false""").containsMatchIn(text),
            "storeForReading must open the store with recoveryPermitted = false. Without it the " +
                "constructor default is true, read() reconciles on the way in, and every `observe` and " +
                "`console` invocation truncates any live writer's reservation — the OBS-G defect, restored " +
                "by deleting one argument in ${provider.fileName}",
        )
    }

    @Test
    fun `only the write side and retention may name a recovering opening`() {
        // Exhaustive over production sources, with the permitted set named. The scan does not ask "is
        // this file a reader?" — it asks "is this file allowed to reach a recovering opening?", so a
        // read-side file that does not exist yet is covered the day it is written.
        val offenders = productionSources().flatMap { file ->
            val name = file.fileName.toString()
            if (name in RECOVERING_OPENING_ALLOWED) return@flatMap emptyList()

            codeLines(file)
                .filter { RECOVERING_OPENING.containsMatchIn(it.second) }
                .map { "$name:${it.first}  ${it.second}" }
        }

        assertTrue(
            offenders.isEmpty(),
            "only the write side ($WRITE_SIDE) and retention ($RETENTION) may open a recovering store, " +
                "with OutputPlaneProvider defining them. A read verb that names storeForWriting or " +
                "storeFor can truncate a live writer's range, which is the defect ADR-OBS-002 removed " +
                "and OBS-G measured. Use storeForReading. Found: $offenders",
        )
    }

    // ------------------------------------------------------------------ helpers

    /**
     * `storeFor(` or `storeForWriting(` — and deliberately NOT `storeForReading(`.
     *
     * Two mistakes are recorded here because this file made both of them, and the first one is the
     * instructive one.
     *
 * "Reading" must be absent from the alternation: `storeForReading(` is the one opening that is
     * *supposed* to appear outside the write side, and a pattern that matched it reported the three
     * legitimate readers as offenders. This file was written with `Reading` in that group and did exactly
     * that on its first run.
     *
     * The alternation must spell the method as it is actually CALLED — `Writing`, not `Write`. Written as
     * `storeFor(Write|)`, it matched neither `storeFor(` nor `storeForWriting(`, and **M-OWN-6 proved it**:
     * switching `MainConsoleCli` to the recovering opening left all three rows green. A scan that has never
     * been pointed at the real shape of the call it forbids is a scan that has never been tested, and the
     * only thing that catches that is a mutation that is expected to fail and does not.
     */
    private val RECOVERING_OPENING = Regex("""storeFor(Writing|)\s*\(""")

    private val RECOVERING_OPENING_ALLOWED = setOf(PROVIDER, WRITE_SIDE, RETENTION)

    /**
     * Executable lines with their 1-based number, with prose excluded.
     *
     * Comments legitimately name the calls they forbid — that is how each law's reason survives in the
     * file that states it — and a scan that counted prose would report this file's own KDoc as a
     * violation of itself.
     */
    private fun codeLines(file: Path): List<Pair<Int, String>> =
        readTextOrFail(file).lineSequence()
            .withIndex()
            .filterNot { (_, line) ->
                val trimmed = line.trimStart()
                trimmed.startsWith("//") || trimmed.startsWith("*") || trimmed.startsWith("/*")
            }
            .map { (index, line) -> (index + 1) to line.trim() }
            .toList()

    /** Every `src/main/kotlin` tree under the `v2` root, which is the whole production surface. */
    private fun productionSources(): List<Path> {
        val root = v2Root()
        return Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { it.toString().contains("/src/main/") }
                .toList()
        }
    }

    /**
     * The `v2` root, derived by walking up from the module this test runs in.
     *
     * Asserted rather than assumed: a scan rooted at the wrong directory finds no offenders and reports
     * a green law over nothing, which is worse than no law at all.
     */
    private fun v2Root(): Path {
        val moduleMain = locateFile(PROVIDER).resolve("../..").normalize()
        val root = generateSequence(moduleMain) { it.parent }
            .firstOrNull { Files.isDirectory(it.resolve("pipeline-application/src/main/kotlin")) }
        checkNotNull(root) { "no v2 root above $moduleMain: this fitness would scan nothing and pass" }
        return root
    }

    private fun locateFile(fileName: String): Path {
        val moduleRoot = generateSequence(Path.of(System.getProperty("user.dir"))) { it.parent }
            .map { it.resolve("pipeline-application/src/main/kotlin") }
            .firstOrNull { Files.isDirectory(it) }
            ?: error("could not locate the application module from ${System.getProperty("user.dir")}")

        return Files.walk(moduleRoot).use { paths ->
            paths.filter { it.fileName.toString() == fileName }.findFirst()
                .orElseThrow { error("$fileName not found under $moduleRoot") }
        }
    }

    /** An unreadable source must not be silently treated as compliant. */
    private fun readTextOrFail(path: Path): String = try {
        Files.readString(path)
    } catch (e: Exception) {
        throw AssertionError("could not read $path for the scan: the evidence is not ignored", e)
    }

    private companion object {
        const val PROVIDER = "OutputPlaneProvider.kt"
        const val WRITE_SIDE = "ShExecution.kt"
        const val RETENTION = "CompositionRoot.kt"
    }
}