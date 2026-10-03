package dev.rubentxu.pipeline.v2.application.scripted

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File

/**
 * S4-A1 canary — the scripted SHELL path is privileged, and this pins that.
 *
 * ## What the law says
 *
 * STEP CONSTITUTION §5: "Core Steps are a standard bundled plugin set; core and
 * external plugins run the exact same path (`Invoke → Registry → erased adapter
 * → StepHandler → declared capabilities → durable engine → typed result/events`).
 * **No privileged core path.**"
 *
 * ## What the code does
 *
 * Two façades in `CompiledScriptedEntryPoint` reach the durable engine, by two
 * different mechanisms:
 *
 * ```
 * pwd / pwd(tmp) / isUnix / readFile / fileExists
 *     -> ScriptedRegistryInvoker.invoke
 *     -> RegistryExecutionPreparation.prepare   (admission, fail-closed)
 *     -> RegistryExecutionBoundary.coexecute    (declared input/output codecs)
 *     -> the Step's own definition, resolved from the registry
 *
 * sh(...)  and  sh(..., returnStatus = true)
 *     -> JournaledScriptedOperationRuntime.invoke
 *     -> ShExecution.invokeShell(...)           called DIRECTLY
 *     -> never touches StepRegistry
 * ```
 *
 * `ScriptedFrontendRunner` builds the second one by handing the journal runtime a
 * lambda that calls `ShExecution.invokeShell` — the registry preparation step is
 * simply not on the path.
 *
 * ## Why that is a boundary defect and not a style preference
 *
 * `core.sh` declares:
 *
 * ```
 * requiredCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY)
 * effects              = listOf(Effect.EXECUTES_SUBPROCESS)
 * ```
 *
 * and `SHELL_OPERATIONS_CAPABILITY` is checked in `RegistryExecutionPreparation`,
 * which the scripted shell path skips. `ShExecution.invokeShell` contains **zero**
 * references to capabilities or descriptors. So a scripted `sh(...)` launches a
 * subprocess without ever establishing that the run holds the capability the
 * step declares, while a declarative `sh(...)` in the same product does check.
 *
 * It is production-reachable: `Main.kt` dispatches to the scripted frontend when a
 * scripted entry point is present.
 *
 * ## Why the inversion is the finding
 *
 * The one façade that DOES use the correct spine is `shReturnStdout` — it routes
 * through `ScriptedRegistryInvoker` with `CoreShellStep.KEY`, the declared input
 * codec, the declared output codec and typed `REPLAY_COMPATIBILITY` failures. And
 * S4-A0 proved it is **unreachable**: the mapper's eager `sh` arm is first in its
 * `when` and matches on the callee name alone, so no spelling of `sh` is ever
 * classified `ShellReturnStdout`.
 *
 * So the correct path exists and cannot be taken, while the reachable paths
 * include a privileged one. That is the strongest possible argument that S4-A1
 * is not a refactor for tidiness: unifying the spine is what removes the bypass.
 *
 * These tests assert the CURRENT wiring on purpose, so the defect cannot change
 * silently. They go red the day the shell path is unified, at which point they
 * are rewritten to assert the registry spine.
 */
class S4A1ScriptedShellPathPrivilegeCanaryTest {

    private fun source(relative: String): String {
        val candidates = listOf(relative)
        val direct = candidates.map(::File).firstOrNull { it.exists() }
        if (direct != null) return direct.readText()
        return generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("source not found: $relative")
    }

    private val frontendRunner: String by lazy {
        source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/ScriptedFrontendRunner.kt")
    }

    private val shExecution: String by lazy {
        source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/durable/ShExecution.kt")
    }

    private val coreShellStep: String by lazy {
        source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt")
    }

    private fun codeOnly(text: String): String = text
        .split('\n')
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    @Test
    fun `CHARACTERIZED DEFECT - the scripted shell runtime calls ShExecution directly, bypassing the registry`() {
        val code = codeOnly(frontendRunner)
        assertTrue(
            code.contains("ShExecution.invokeShell"),
            "MEASURED DEFECT: ScriptedFrontendRunner hands JournaledScriptedOperationRuntime a " +
                "lambda that calls ShExecution.invokeShell directly. The registry preparation " +
                "step — where a Step's declared capabilities are admitted — is not on this " +
                "path, so the scripted sh runs on a different durable spine from the scripted " +
                "pwd/isUnix/readFile/fileExists, which all go through ScriptedRegistryInvoker. " +
                "Fails the day the shell path is unified onto the registry (S4-A1).",
        )
    }

    @Test
    fun `CHARACTERIZED DEFECT - the capability the scripted shell path skips declaring and admitting`() {
        // The control for the test above: without this, "no capability check" could be
        // read as "nothing was being checked anyway".
        assertTrue(
            coreShellStep.contains("requiredCapabilities = setOf(SHELL_OPERATIONS_CAPABILITY)"),
            "control: core.sh DOES declare a required capability, so the admission the " +
                "scripted shell path skips is a real check and not a formality",
        )
        val shCode = codeOnly(shExecution)
        assertTrue(
            !shCode.contains("Capability") && !shCode.contains("capability"),
            "MEASURED DEFECT: ShExecution.invokeShell contains no capability reference at " +
                "all, confirming that nothing on the scripted shell path can admit one. " +
                "Fails the day admission moves onto this path (S4-A1).",
        )
    }

    @Test
    fun `CHARACTERIZED - the journal namespace for scripted shell is a hardcoded string, not a registry key`() {
        val code = codeOnly(source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/JournaledScriptedOperationRuntime.kt"))
        assertTrue(
            code.contains("SCRIPTED_SHELL_STEP_ID = \"scripted.core.sh\""),
            "MEASURED: the scripted shell journals under a hardcoded namespace string rather " +
                "than resolving a StepDefinition from the registry, which is the structural " +
                "reason the two scripted spines cannot be the same code path.",
        )
    }

    @Test
    fun `CHARACTERIZED - the correct spine exists but is unreachable`() {
        // The inversion, pinned from the other side so the two facts cannot drift
        // apart: a facade that routes through the registry, and a mapper that
        // classifies every sh as eager so that facade is never called.
        val entryPoint = codeOnly(
            source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/CompiledScriptedEntryPoint.kt"),
        )
        assertTrue(
            entryPoint.contains("invoker.invoke(") && entryPoint.contains("CoreShellStep.KEY"),
            "MEASURED: shReturnStdout DOES route through ScriptedRegistryInvoker with " +
                "CoreShellStep.KEY and the declared codecs — the correct spine exists.",
        )
        assertTrue(
            !entryPoint.contains("SHELL_OPERATIONS_CAPABILITY"),
            "control: the correct spine is not itself doing capability checks inline; it " +
                "delegates admission to RegistryExecutionPreparation via the invoker",
        )
    }
}
