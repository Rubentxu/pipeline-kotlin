package dev.rubentxu.pipeline.v2.application.scripted

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.stream.Collectors

/**
 * S4-A1 — the scripted shell must reach the durable engine through the REGISTRY.
 *
 * ## The law
 *
 * STEP CONSTITUTION §5: "Core Steps are a standard bundled plugin set; core and
 * external plugins run the exact same path (`Invoke → Registry → erased adapter
 * → StepHandler → declared capabilities → durable engine → typed result/events`).
 * **No privileged core path.**"
 *
 * ## What this file replaced
 *
 * `7107f749` added a canary that asserted the OPPOSITE, on purpose: that
 * `ScriptedFrontendRunner` wired a privileged runtime calling
 * `ShExecution.invokeShell` directly, skipping the registry preparation step
 * where `core.sh`'s declared `SHELL_OPERATIONS_CAPABILITY` is admitted.
 *
 * `c62d6ff5` then measured reachability and corrected the severity: the
 * privileged path was BUILT but UNREACHABLE, because the lowering never
 * rewrites `ScriptedCallKind.Shell` and the host rejects the script at compile
 * time. So the bypass was latent, not live — and one small fix away from
 * going live, because fixing the lowering would bind the surviving bare `sh`
 * straight into whatever `ScriptedOperationRuntime` is wired here.
 *
 * This is the flip side of that canary. It now pins the fixed law, and it pins
 * it STRUCTURALLY — by scanning every production source for the call — rather
 * than by reading one file.
 */
class S4A1ScriptedShellPathPrivilegeCanaryTest {

    private fun source(relative: String): String {
        val direct = File(relative)
        if (direct.exists()) return direct.readText()
        return generateSequence(File(".").absoluteFile) { it.parentFile }
            .map { File(it, relative) }
            .firstOrNull { it.exists() }
            ?.readText()
            ?: error("source not found: $relative")
    }

    private fun codeOnly(text: String): String = text
        .split('\n')
        .filterNot { it.trimStart().startsWith("//") || it.trimStart().startsWith("*") || it.trimStart().startsWith("/*") }
        .joinToString("\n")

    private val applicationMain: Path = generateSequence(File(".").absoluteFile) { it.parentFile }
        .map { it.toPath().resolve("src/main/kotlin/dev/rubentxu/pipeline/v2/application") }
        .firstOrNull { Files.isDirectory(it) }
        ?: error("application main source root not found")

    private val applicationSources: List<Pair<String, String>> =
        Files.walk(applicationMain).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .map { it.fileName.toString() to codeOnly(Files.readString(it)) }
                .collect(Collectors.toList())
        }

    @Test
    fun `the scripted frontend must not reference the shell substrate at all`() {
        val runner = codeOnly(
            source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/ScriptedFrontendRunner.kt"),
        )
        assertFalse(
            "ShExecution" in runner,
            "ScriptedFrontendRunner must not name the shell substrate. A direct call here " +
                "bypasses RegistryExecutionPreparation, which is where a Step's declared " +
                "capabilities are admitted — that was the S4-A1 defect.",
        )
        assertTrue(
            "RegistryScriptedShellRuntime" in runner,
            "ScriptedFrontendRunner must wire the registry-routed shell runtime, so the only " +
                "scripted path to a subprocess is one that admits SHELL_OPERATIONS_CAPABILITY",
        )
    }

    @Test
    fun `the shell substrate is reachable from exactly one production place - the capability adapter`() {
        // The strong form of the law. Counting call sites across ALL production
        // sources catches a new privileged caller anywhere, not just a regression
        // in the one file the previous canary read.
        val callers = applicationSources
            .filter { (_, code) -> "ShExecution.invokeShell(" in code }
            .map { (name, _) -> name }
            .distinct()
            .sorted()

        assertEquals(
            listOf("ShOperationsAdapter.kt"),
            callers,
            "ShExecution.invokeShell must be called from exactly one production place: the " +
                "adapter bound to SHELL_OPERATIONS_CAPABILITY, which is what certified `core.sh` " +
                "reaches. Any other caller is a privileged path that skips capability admission.",
        )
    }

    @Test
    fun `the scripted shell runtime routes through the registry and owns no second codec`() {
        val runtime = codeOnly(
            source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/RegistryScriptedShellRuntime.kt"),
        )
        assertTrue(
            "invokeTyped(" in runtime && "CoreShellStep.definition" in runtime,
            "the scripted shell must invoke core.sh through ScriptedRegistryInvoker.invokeTyped",
        )
        // A second journal writer or a hand-rolled wire codec is the same defect in
        // a different costume, so the retired runtime's distinctive machinery must
        // not reappear here.
        //
        // Checked at TYPE level, not call-syntax level. An earlier version of this
        // list matched `Fingerprint.compute` and a mutation that merely referenced
        // the `Fingerprint` type did not trip it — a test that can be satisfied by
        // naming a type without calling it is weaker than it looks.
        listOf(
            "Fingerprint",
            "MemoizedOperation",
            "OperationJournal",
            "OperationStatus",
            "toWire",
            "toShellResult",
            "scripted.core.sh",
        ).forEach { machinery ->
            assertFalse(
                machinery in runtime,
                "the scripted shell runtime must not re-implement durable machinery " +
                    "(`$machinery`): the registry invoker owns journaling, identity and codecs",
            )
        }
    }

    @Test
    fun `invokeTyped derives the StepKey from the contract and decodes with the declared codec`() {
        val invoker = codeOnly(
            source("src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/ScriptedRegistryInvoker.kt"),
        )
        assertTrue(
            "stepKey = definition.contract.key" in invoker,
            "the durable identity must take its StepKey from the StepContract handed in, so a " +
                "caller cannot pass a key that disagrees with the definition whose codecs " +
                "encode and decode the payload",
        )
        assertTrue(
            "definition.contract.outputCodec.decode" in invoker,
            "one output contract in both directions: the codec that encoded the payload must " +
                "decode it, so REUSE can read a persisted value with an empty registry",
        )
        // The invoker must stay step-agnostic, or the seam is not actually open.
        listOf("CoreShellStep", "CorePwdStep", "CoreIsUnixStep", "CoreReadFileStep", "CoreFileExistsStep")
            .forEach { step ->
                assertFalse(
                    step in invoker,
                    "the invoker must never name a concrete Step (`$step`); it is the generic " +
                        "seam, and naming a Step here would make core the only thing that can " +
                        "use it",
                )
            }
    }
}
