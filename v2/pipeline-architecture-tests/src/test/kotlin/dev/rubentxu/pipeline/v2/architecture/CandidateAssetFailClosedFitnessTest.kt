package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * R-BUILD-07 — `candidateAdmission` may not observe its own dependencies optionally.
 *
 * ## The defect, measured 2026-10-18
 *
 * The task declared `dependsOn(":pipeline-application:cyclonedxBom")` and
 * `dependsOn("sdkZip")`, then observed what they produced like this:
 *
 * ```kotlin
 * val sbom = listOf(...).firstOrNull { it.isFile }
 * val sdkZip = dist.resolve("pipelinek-$version.sdk.zip").takeIf { it.isFile }
 * val bomJson = dist.resolve("pipelinek-bom-$version.json").takeIf { it.isFile }
 * ```
 *
 * An absence became `null`, `null` was written into argv as `"-"`, and
 * `CandidateAdmissionMain` mapped `"-"` back to `null`. So the handoff recorded a
 * candidate carrying none of the three, while the task itself went green.
 *
 * Nothing downstream noticed. `covered` shrank with them, and
 * `CandidateMaterializer`'s only completeness invariant is
 * `covered.isNotEmpty()` — so a `SHA256SUMS` covering the runtime ZIP alone
 * satisfied it. That is the same shape as the `locateSbom` null the SBOM probe
 * returned under a green test: a detector that reports honestly and a consumer
 * that discards the report, which is zero coverage in both directions rather
 * than in one.
 *
 * §8 of the DISTRIBUTION-FIRST RELEASE POLICY fixes the asset set exactly, and
 * §5 makes an incomplete one a `PUBLICATION_REFUSED`. A candidate that silently
 * omitted three of five assets was neither refused nor complete.
 *
 * ## Fidelity
 *
 * SOURCE-LEVEL, declared as such. The property is "the task cannot admit a
 * candidate whose required asset is absent", which is decided by parsing and
 * fully legible here. What the task then DOES with the assets is behavioural and
 * is covered by the release lane itself; proving a text property by rebuilding a
 * distribution would cost a full build to learn nothing this row cannot see.
 *
 * ## Why the rows are positive first
 *
 * A row asserting only "this file contains no `takeIf { it.isFile }`" passes on a
 * file where the whole task had been deleted, and passes on a task rewritten to
 * assert nothing at all. Both are vacuous greens, and this repository has a
 * receipt for each. So the FIRST row demands that each asset is bound to its
 * guard, and the SECOND row only removes the known silent shapes. Deleting the
 * task fails the first row outright; the negatives cannot carry it alone.
 *
 * ## Why the guard for each asset is named individually
 *
 * The three assets are not guarded by one construct, and pretending otherwise
 * would make this row assert a shape nobody wrote. The SBOM is copied from a
 * `dependsOn` task's output directory and carries its own `if (!bomSource.isFile)`
 * guard; the SDK ZIP and the BOM manifest are both produced by `sdkZip` and both
 * go through `requiredAsset(`. The row below therefore asserts each asset
 * against the guard that actually protects it, so a future edit that keeps the
 * name in the file but drops it from the guard is still a RED.
 */
class CandidateAssetFailClosedFitnessTest {

    /**
     * The `v2` build root, resolved the same way as
     * [SingleVersionAuthorityFitnessTest]: walk up for the directory that CONTAINS
     * the module, not for the first `settings.gradle.kts`, which lands on `v2`
     * itself and then gets prefixed a second time.
     */
    private val buildRoot: File = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
        .firstOrNull { File(it, "pipeline-architecture-tests").isDirectory }
        ?: error("could not locate the v2 build root from ${System.getProperty("user.dir")}")

    private val buildScript: File = File(buildRoot, "pipeline-release/build.gradle.kts")

    /**
     * The body of the `candidateAdmission` task, from its registration to the
     * closing brace that returns the indentation to column zero.
     *
     * Scoping to the task rather than to the file is deliberate in both
     * directions. A `takeIf { it.isFile }` somewhere else in the script would
     * otherwise turn a correct build file red, and a row that fires on unrelated
     * text is a row operators learn to disable.
     */
    private fun candidateAdmissionBlock(): String {
        val lines = buildScript.readLines()
        val start = lines.indexOfFirst { it.contains("""tasks.register<JavaExec>("candidateAdmission")""") }
        assertTrue(
            start >= 0,
            "no `candidateAdmission` registration in ${buildScript.path}. If the task was renamed " +
                "or removed, this row protects nothing and must be updated or retired " +
                "deliberately — not deleted, which would leave the module green by default.",
        )
        val end = (start + 1 until lines.size).firstOrNull { lines[it] == "}" }
        assertTrue(
            end != null,
            "the `candidateAdmission` registration in ${buildScript.path} has no closing brace at " +
                "column zero, so this row cannot delimit the task body. Without a delimited body the " +
                "assertions below would scan an unknown region and could pass on anything in it.",
        )
        return lines.subList(start, end!! + 1).joinToString("\n")
    }

    /**
     * Drops whole-line comments before scanning.
     *
     * This is not cosmetic. The block's own KDoc explains the defect and therefore
     * NAMES `takeIf { it.isFile }` and `?: "-"` verbatim — three times between them.
     * A scan that read comments would see the defect described in prose and report
     * it as present, which is the "scanner blind to its own subject" failure: the
     * guard becomes unfalsifiable precisely because it documents what it forbids.
     *
     * Whole lines are dropped, NOT truncated at the first `//`: truncation would
     * also cut inside a string literal containing a URL, silently deleting the
     * code that follows it on that line.
     */
    private fun codeOnly(block: String): String =
        block.lines()
            .filterNot { it.trimStart().startsWith("//") }
            .joinToString("\n")

    /** Collapse to one line so a multi-line call can be matched by adjacency. */
    private fun flattened(code: String): String =
        code.replace(Regex("\\s+"), " ")

    @Test
    fun `each section 8 asset is bound to the guard that refuses it when absent`() {
        val flat = flattened(codeOnly(candidateAdmissionBlock()))

        // asset name -> the guard shape that actually protects it in this task.
        val guardedBy = mapOf(
            "pipelinek-\$version.sbom.json" to "if (!bomSource.isFile)",
            "pipelinek-\$version.sdk.zip" to "requiredAsset(",
            "pipelinek-bom-\$version.json" to "requiredAsset(",
        )

        val unbound = guardedBy.filterNot { (asset, guard) ->
            when (guard) {
                // The SBOM guard names the variable it checks; the asset is the
                // thing that variable carries, so both must be in the block.
                "if (!bomSource.isFile)" -> flat.contains(guard) && flat.contains(asset)
                // For the other two, ADJACENCY is the property: the asset must be
                // the argument of the guard, not merely present somewhere in the
                // same task. `flat.contains("$guard \"$asset\"")` is what stops a
                // keep-the-name / drop-the-guard edit from staying green.
                else -> flat.contains("$guard \"$asset\"")
            }
        }.map { it.key }

        assertTrue(
            unbound.isEmpty(),
            "these §8 assets are named in ${buildScript.path} but no longer bound to a " +
                "fail-closed guard: ${unbound.joinToString("; ")}. Every one is backed by a " +
                "`dependsOn`, so an absent file after the dependency ran is a dependency that did " +
                "not deliver — not an optional state. Keep the guard the row names here, or " +
                "deliberately re-open the release policy with a recorded decision.",
        )
    }

    @Test
    fun `the admission task carries no absence sentinel into argv`() {
        val code = codeOnly(candidateAdmissionBlock())

        // Three routes to the same silent absence, all of which have existed here.
        val forbidden = listOf(
            "takeIf { it.isFile }" to
                "turns an absent required asset into null, and null into argv `\"-\"`",
            "?: \"-\"" to
                "writes a sentinel that `CandidateAdmissionMain` maps back to null with " +
                    "`takeIf { it != \"-\" }`, so the slot is indistinguishable from one " +
                    "that was never populated",
            "listOfNotNull(" to
                "re-admits the nulls through a second door: without a sentinel it still " +
                    "shortens `covered`, and `CandidateMaterializer`'s only completeness " +
                    "invariant is `covered.isNotEmpty()`",
        )

        val present = forbidden.filter { (shape, _) -> code.contains(shape) }

        assertTrue(
            present.isEmpty(),
            "${buildScript.path} still carries a silent-absence shape inside " +
                "`candidateAdmission`. " +
                present.joinToString(" ") { (shape, why) -> "`$shape` $why." },
        )

        // The argv is what the handoff is built from, so a NULLABLE projection there is a
        // candidate recorded without an asset. The plain `.absolutePath` calls are the point —
        // they are only callable because each guard above returned a non-null `File` — so the row
        // forbids the safe call specifically and not the projection itself.
        assertTrue(
            !code.contains("?.absolutePath"),
            "${buildScript.path} projects an argv slot with `?.absolutePath` inside " +
                "`candidateAdmission`. Each asset's guard returns a non-null `File`, so a safe " +
                "call here can only mean the binding was made nullable again — which is the defect " +
                "reintroduced through the back door, with no diagnostic.",
        )
    }
}