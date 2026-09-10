package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PAR-D fitness: parallel reconciliation authorities == 1.
 *
 * Mechanical proof (ADR-0076):
 * 1. the legacy `BranchReconciler` source file does not exist (burned down
 *    2026-09-10 after a production-reachability proof of zero call sites,
 *    zero constructors, zero wiring);
 * 2. no production source references the `BranchReconciler` symbol
 *    (comments excluded);
 * 3. the ONLY parallel reconciliation decision authority is the pure
 *    `ParallelReconciler` in pipeline-domain, and the ONLY production site
 *    consuming it is `CanonicalDurableRunCoordinator` (the single effect
 *    executor: durable facts -> pure reconciliation -> typed decision ->
 *    coordinator executes).
 */
class FArchParallelReconciliationAuthorityTest {

    private val v2Root: Path = FitnessPaths.v2Root()

    private val moduleSrcRoots = listOf(
        "pipeline-application/src/main",
        "pipeline-domain/src/main",
        "pipeline-step-sdk/api/src/main",
        "pipeline-step-sdk/runtime/src/main",
        "pipeline-events/src/main",
        "pipeline-scripting-api/src/main",
    )

    @Test
    fun `legacy BranchReconciler source is absent`() {
        val legacy = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/BranchReconciler.kt",
        )
        assertFalse(Files.exists(legacy), "BranchReconciler.kt must stay deleted (ADR-0076 burn-down)")
    }

    @Test
    fun `no production source references the BranchReconciler symbol`() {
        val offenders = mutableListOf<String>()
        for (root in moduleSrcRoots) {
            val dir = v2Root.resolve(root)
            if (!dir.isDirectory()) continue
            Files.walk(dir).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.forEach { f ->
                    val text = Files.readString(f)
                    val codeOnly = text.lineSequence()
                        .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
                        .joinToString("\n")
                    if ("BranchReconciler" in codeOnly) offenders += f.toString()
                }
            }
        }
        assertEquals(emptyList<String>(), offenders, "BranchReconciler must not be resurrected in production sources")
    }

    @Test
    fun `ParallelReconciler is defined once and consumed only by the canonical coordinator`() {
        val definitions = mutableListOf<String>()
        val consumers = mutableListOf<String>()
        for (root in moduleSrcRoots) {
            val dir = v2Root.resolve(root)
            if (!dir.isDirectory()) continue
            Files.walk(dir).use { stream ->
                stream.filter { it.toString().endsWith(".kt") }.forEach { f ->
                    val text = Files.readString(f)
                    val codeOnly = text.lineSequence()
                        .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
                        .joinToString("\n")
                    if (Regex("\\bobject ParallelReconciler\\b").containsMatchIn(codeOnly)) {
                        definitions += f.toString()
                    }
                    if ("ParallelReconciler.reconcile" in codeOnly && "ParallelReconciler.kt" !in f.toString()) {
                        consumers += f.toString()
                    }
                }
            }
        }
        assertEquals(1, definitions.size, "exactly one ParallelReconciler definition allowed; got $definitions")
        assertEquals(
            listOf("CanonicalDurableRunCoordinator.kt"),
            consumers.map { it.substringAfterLast('/') },
            "the canonical coordinator must be the only effect executor of the parallel decision",
        )
    }

    @Test
    fun `the orphan unstructured CoroutineScope is absent from the parallel path`() {
        val coord = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        val text = Files.readString(coord)
        assertFalse(
            "CoroutineScope(Dispatchers" in text,
            "runParallelStage must not create an orphan unstructured CoroutineScope (ADR-0076 §4)",
        )
        assertTrue(
            "supervisorScope" in text,
            "the parallel branch join must run inside a structured supervisorScope",
        )
    }
}
