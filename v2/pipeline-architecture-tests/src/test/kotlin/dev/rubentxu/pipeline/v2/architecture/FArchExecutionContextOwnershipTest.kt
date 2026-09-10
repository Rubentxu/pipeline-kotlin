package dev.rubentxu.pipeline.v2.architecture

import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.isDirectory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * CTX-P fitness: execution-context ownership is explicit, immutable, and
 * scheduler-independent.
 *
 * Mechanical proof:
 *  F1 zero mutable execution-context authority in production (var contextStack,
 *     var currentExecutionContext, AtomicReference<ExecutionContext>, ...)
 *  F2 zero ambient context authorities (ThreadLocal<ExecutionContext>,
 *     global/singleton ExecutionContext holder)
 *  F3 the parallel branch path receives ExecutionContext explicitly
 *     (runParallelStage -> executeBranchSteps -> dispatch) and never reads a
 *     coordinator context field
 *  F4 ContextTransition is pure data: declared in pipeline-domain and
 *     referencing no application/runtime/infrastructure type
 *  F5 no finally-restore idiom over a context authority
 *  F6 ExecutionContext stays an immutable domain value: its module has no
 *     application/journal/registry/event/scope dependency to leak in
 */
class FArchExecutionContextOwnershipTest {

    private val v2Root: Path = FitnessPaths.v2Root()

    private val moduleSrcRoots = listOf(
        "pipeline-application/src/main",
        "pipeline-domain/src/main",
        "pipeline-step-sdk/api/src/main",
        "pipeline-step-sdk/runtime/src/main",
        "pipeline-events/src/main",
        "pipeline-scripting-api/src/main",
    )

    private fun productionKotlinSources(): MutableList<Path> {
        val files = mutableListOf<Path>()
        for (root in moduleSrcRoots) {
            val dir = v2Root.resolve(root)
            if (!dir.isDirectory()) continue
            Files.walk(dir).use { stream ->
                stream.filter { f -> f.toString().endsWith(".kt") }
                    .forEach { f: Path -> files.add(f) }
            }
        }
        return files
    }

    private fun codeOnly(text: String): String =
        text.lineSequence()
            .filter { !it.trimStart().startsWith("//") && !it.trimStart().startsWith("*") }
            .joinToString("\n")

    // F1 — zero mutable context authority.
    @Test
    fun `F1 no mutable execution-context authority field exists in production`() {
        val forbidden = listOf(
            Regex("""var\s+contextStack"""),
            Regex("""var\s+currentExecutionContext"""),
            Regex("""var\s+executionContext"""),
            Regex("""AtomicReference<ExecutionContext>"""),
            Regex("""MutableStateFlow<ExecutionContext>"""),
            Regex("""var\s+.*:\s*ContextStack"""),
        )
        val offenders = mutableListOf<String>()
        for (f in productionKotlinSources()) {
            val code = codeOnly(Files.readString(f))
            for (pattern in forbidden) {
                if (pattern.containsMatchIn(code)) offenders += "${f}: ${pattern.pattern}"
            }
        }
        assertEquals(emptyList<String>(), offenders, "Mutable execution-context authority must stay at zero")
    }

    // F2 — no ambient context authorities.
    @Test
    fun `F2 no ThreadLocal or global ambient ExecutionContext exists`() {
        val forbidden = listOf(
            Regex("""ThreadLocal<"""+"""ExecutionContext>"""),
            Regex("""InheritableThreadLocal<"""+"""ExecutionContext>"""),
            Regex("""object\s+\w*Global\w*Context"""),
            Regex("""CoroutineContext.Key<"""+"""ExecutionContext>"""),
        )
        val offenders = mutableListOf<String>()
        for (f in productionKotlinSources()) {
            val code = codeOnly(Files.readString(f))
            for (pattern in forbidden) {
                if (pattern.containsMatchIn(code)) offenders += "${f}: ${pattern.pattern}"
            }
        }
        assertEquals(emptyList<String>(), offenders, "Context ownership must be explicit, never ambient")
    }

    // F3 — branch explicit-context seam.
    @Test
    fun `F3 branch path threads ExecutionContext explicitly and reads no coordinator context`() {
        val coordinator = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/CanonicalDurableRunCoordinator.kt",
        )
        assertTrue(Files.exists(coordinator))
        val code = codeOnly(Files.readString(coordinator))
        assertTrue(
            """executeBranchSteps\(branch, runId, stageIndex, branchIndex, stageShOptions, executionContext\)""".toRegex()
                .containsMatchIn(code),
            "runParallelStage must pass the context value explicitly to executeBranchSteps",
        )
        val branchFn = code.substringAfter("private suspend fun executeBranchSteps")
            .substringBefore("\n    private suspend fun")
        assertTrue(
            "executionContext: ExecutionContext" in branchFn,
            "executeBranchSteps must declare an explicit ExecutionContext parameter",
        )
        assertTrue(
            "val branchContext = executionContext" in branchFn,
            "branch context must derive from the explicit parameter, never coordinator state",
        )
        assertTrue(
            !Regex("""contextStack|currentExecutionContext""").containsMatchIn(branchFn),
            "branch execution must not read a coordinator context authority",
        )
    }

    // F4 — ContextTransition is pure data in the domain module.
    @Test
    fun `F4 ContextTransition is domain data with no infrastructure reach`() {
        val domainSrc = v2Root.resolve("pipeline-domain/src/main")
        assertTrue(domainSrc.isDirectory())
        val declaration = mutableListOf<Path>()
        Files.walk(domainSrc).use { stream ->
            stream.filter {
                it.toString().endsWith(".kt") &&
                    Files.readString(it).contains("sealed interface ContextTransition")
            }.forEach { f: Path -> declaration.add(f) }
        }
        assertEquals(1, declaration.size, "exactly one ContextTransition definition, in pipeline-domain")
        val text = codeOnly(Files.readString(declaration.single()))
        val forbidden = listOf(
            "CanonicalDurableRunCoordinator", "OperationJournal", "EventSink",
            "CoroutineScope", "StepRegistry", "ProcessBuilder",
        )
        for (symbol in forbidden) {
            assertTrue(symbol !in text, "ContextTransition must not reference $symbol")
        }
    }

    // F5 — no context restore idiom.
    @Test
    fun `F5 no save-set-finally-restore idiom over a context authority remains`() {
        val restore = Regex("""contextStack\s*=\s*parentStack|contextStack\s*=\s*saved|executionContext\s*=\s*parentStack""")
        val offenders = productionKotlinSources()
            .filter { restore.containsMatchIn(codeOnly(Files.readString(it))) }
        assertEquals(emptyList<String>(), offenders, "The finally-restore ownership idiom must stay deleted")
    }

    // F6 — ExecutionContext is an immutable domain value (module boundary + declaration shape).
    @Test
    fun `F6 ExecutionContext stays an immutable scheduler-independent domain value`() {
        val file = v2Root.resolve("pipeline-domain/src/main/kotlin/dev/rubentxu/pipeline/v2/domain/ExecutionContext.kt")
        assertTrue(Files.exists(file))
        val text = codeOnly(Files.readString(file))
        assertTrue("data class ExecutionContext(" in text, "must remain an immutable data value")
        val forbidden = listOf(
            "ShOptions", "OperationJournal", "StepRegistry", "EventSink",
            "CoroutineScope", "ProcessExecutor", "CanonicalDurableRunCoordinator",
        )
        for (symbol in forbidden) {
            assertTrue(symbol !in text, "ExecutionContext must not own or reference $symbol")
        }
    }
}
