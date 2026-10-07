package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * P1 — one admission authority per StepKey, and one authority over process transcript.
 *
 * ## What this pins, and why it is not a comment
 *
 * The SDK used to declare a **second** `core.sh`:
 *
 * ```kotlin
 * @Step(id = "core.sh", effects = [Effect.EXECUTES_SUBPROCESS], …)
 * suspend fun sh(context: StepContext, argv: List<String>, sink: EventSink, stepIndex: Int): Int {
 *     … stdoutBuilder.append(…); stderrBuilder.append(…)
 *     sink.append(EchoOutputCaptured(… content = output …))   // process bytes in a semantic event
 *     return result.exitCode
 * }
 * ```
 *
 * Two independent things were wrong with it, and the second is the one that bit:
 *
 * 1. It re-declared a StepKey the canonical [dev.rubentxu.pipeline.v2.application.CoreShellStep]
 *    already owns, and KSP turned it into a descriptor with `requiredCapabilities = emptyList()`
 *    where the canonical descriptor demands `SHELL_OPERATIONS_CAPABILITY`. Two declarations of one
 *    StepKey, one of them claiming a shell step needs no shell capability. That is a second
 *    admission authority, and admission is exactly where that is not allowed to be ambiguous.
 * 2. It published process stdout/stderr into `EchoOutputCaptured` — the second authority over the
 *    very bytes ADR-M1 D2 gave to the Output Plane, and the reason the release-scale corpus went
 *    RED with an empty observed string in B2e.
 *
 * It had **zero call-sites** (the only hit in the repository was an unused import) and its
 * generated descriptor had **zero consumers**, so it was removed rather than shimmed. A shim would
 * have kept the collision alive behind a dead door.
 *
 * ## Why these assertions are mechanical
 *
 * Each row reads the real sources, so a regression is a build failure rather than a reviewer
 * noticing. They are deliberately not satisfied by a comment: deleting a guard would leave the
 * invariants unenforced, and re-adding a second declaration trips the scan.
 */
class CoreShSingleAdmissionAuthorityTest {

    /**
     * The `v2` root, found by walking up to the checkout that owns `.git`.
     *
     * Walking beats a relative default here: this module's tests run with the module directory as
     * their working directory, so `Path.of("v2")` would resolve to a directory that does not
     * exist and the scan would silently pass over an empty set — a fitness that measures nothing
     * is worse than no fitness, because it reads as coverage. Anchoring on `.git` is the same
     * approach `A4_8LegacyRegistrySemanticParityTest` already uses, so there is one way to find
     * the root in this module rather than two.
     */
    private val v2Root: Path = generateSequence(Path.of("").toAbsolutePath()) { dir -> dir.parent }
        .firstOrNull { dir -> Files.isDirectory(dir.resolve(".git")) || Files.isRegularFile(dir.resolve(".git")) }
        ?.resolve("v2")
        ?: error(
            "Cannot locate the checkout root: no ancestor of ${Path.of("").toAbsolutePath()} has " +
                "a .git entry. This fitness would otherwise scan nothing and pass.",
        )

    /** Kotlin sources, never anything under `build/` — generated code is not the subject. */
    private fun kotlinSources(root: Path): List<Path> =
        Files.walk(root).use { stream ->
            stream
                .filter { Files.isRegularFile(it) && it.fileName.toString().endsWith(".kt") }
                .filter { !it.toString().contains("/build/") }
                .toList()
        }

    /**
     * This very file, excluded from the text scans below.
     *
     * It names the removed symbol in its own KDoc so a reader can recognise what was deleted. A
     * scan that reported the scanner would be reporting itself, and worse, it would make the
     * honest way to write the explanation — naming the thing — a build failure.
     */
    private fun isThisTest(source: Path): Boolean =
        source.fileName.toString() == "CoreShSingleAdmissionAuthorityTest.kt"

    /** Matches a top-level function or `suspend fun` declaration, capturing its name. */
    private val functionDeclaration = Regex("""^(?:@\w+\s+)*(?:public |internal |private )?(?:suspend )?fun\s+(\w+)""")

    private fun Path.relativeToV2(): String = toString().removePrefix("$v2Root/").removePrefix("$v2Root\\")

    private fun Path.text(): String = Files.readString(this)

    @Test
    fun `no production source re-declares the core sh StepKey outside the canonical owner`() {
        // The canonical owner is the application module. Anywhere else, a second declaration of
        // the `core.sh` StepKey is a second admission authority by definition, whatever mechanism
        // produced it.
        val offenders = kotlinSources(v2Root)
            .filterNot { it.fileName.toString() == "CoreShellStep.kt" }
            .filterNot { it.relativeToV2().startsWith("pipeline-application/src/test/") }
            .filter { it.text().contains("""id = "core.sh"""") }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "core.sh is declared once, in CoreShellStep. A second declaration of the key produces " +
                "a second admission authority whose requiredCapabilities can disagree with the " +
                "canonical one. Offending files: $offenders",
        )
    }

    @Test
    fun `the canonical core sh descriptor still demands the shell capability`() {
        // The positive half of the invariant: removing the duplicate must not have removed the
        // real requirement. A regression that "fixed" the collision by dropping the capability
        // from the canonical descriptor would pass the scan above and fail here.
        val canonical = v2Root.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/CoreShellStep.kt",
        )
        val text = canonical.text()

        assertTrue(
            text.contains("""stepId = "core.sh""""),
            "the canonical core.sh descriptor must still exist in CoreShellStep",
        )
        assertTrue(
            text.contains("SHELL_OPERATIONS_CAPABILITY"),
            "the canonical core.sh descriptor must still require SHELL_OPERATIONS_CAPABILITY; a " +
                "shell step that declares no shell capability is the exact defect P1 removed in " +
                "its duplicate form",
        )
    }

    @Test
    fun `the SDK sh executor file no longer hosts a process executor`() {
        // Only the file that used to declare the duplicate. The SDK runtime module legitimately
        // CONTAINS the task runtime itself (ProcessDurableTaskRuntime and friends) — that is the
        // authority, not a violation — so scanning the whole module would report the very runtime
        // that exists to make the scan pass.
        val offenders = kotlinSources(v2Root.resolve("pipeline-step-sdk/runtime/src/main/kotlin"))
            .filter { source ->
                val text = source.text()
                text.contains("ProcessDurableTaskRuntime") || text.contains("ProcessBuilder(")
            }
            .filterNot { it.relativeToV2().startsWith("pipeline-step-sdk/runtime/src/main/kotlin/dev/rubentxu/pipeline/v2/sdk/runtime/durable/") }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "StepExecutors.kt survives as the typed echo/error/sleep surface, but it must not " +
                "execute processes: the durable shell runtime and the Output Plane own that. " +
                "Files still executing processes: $offenders",
        )
    }

    @Test
    fun `the removal is real rather than a compatibility shim`() {
        // A shim is what this block exists to prevent: a `fun sh(` that still answers but does
        // nothing, or forwards to a deprecated path. The check is on the observable — no `sh`
        // over StepContext may survive in the SDK runtime at all.
        val survivors = kotlinSources(v2Root.resolve("pipeline-step-sdk/runtime"))
            .flatMap { source -> source.text().lines().withIndex() }
            .filter { (_, line) ->
                line.trimStart().startsWith("fun sh(") || line.trimStart().startsWith("suspend fun sh(")
            }
            .map { (index, _) -> index + 1 }

        assertEquals(
            0,
            survivors.size,
            "no `fun sh(` may survive in the SDK runtime: it would be a second process executor " +
                "and a second admission authority. Found at lines $survivors",
        )
    }

    @Test
    fun `no source imports the removed SDK sh executor`() {
        // The dead door, closed. An import of the deleted symbol would not compile, so this
        // asserts the absence at source level too — including in tests, which compile against
        // the same classpath and would be the first place a re-introduction tried to hide.
        val offenders = kotlinSources(v2Root)
            .filterNot { isThisTest(it) }
            .filter { it.text().contains("sdk.runtime.sh") }
            .map { it.relativeToV2() }

        assertTrue(
            offenders.isEmpty(),
            "the SDK-level sh executor is gone; nothing may import it. Offenders: $offenders",
        )
    }

    @Test
    fun `process transcript is published to an event only from a declared non-durable fallback`() {
        // ADR-M1 D2: for ONE execution, the transcript bytes exist in exactly one place. On the
        // durable path that place is the Output Plane. The one lawful exception is the
        // non-durable fallback, where no control directory exists and therefore no plane can —
        // the event is not a second authority there, it is the only rendering.
        //
        // The rule below is stated as a property of the CODE rather than a list of exempted files,
        // because a path-based exemption rots: a new emitter in a new file would slip past it.
        // Instead, if a file both executes child processes and constructs EchoOutputCaptured, then
        // every function in that file that constructs the event must name the non-durable path.
        // A second emitter anywhere in the same file fails, and so does a differently-named
        // shim of the fallback.
        val offenders = kotlinSources(v2Root)
            .filterNot { it.relativeToV2().contains("/src/test/") }
            .filterNot { isThisTest(it) }
            .mapNotNull { source ->
                val text = source.text()
                val runsProcesses = text.contains("ProcessDurableTaskRuntime") ||
                    text.contains("ProcessBuilder(") ||
                    text.contains("TaskStream.")
                if (!runsProcesses) return@mapNotNull null

                val illegalEmitters = enclosingFunctionNames(text)
                    .filterValues { it.contains("EchoOutputCaptured(") }
                    .filterKeys { !it.contains("NonDurable", ignoreCase = true) }
                    .keys
                    .toList()
                if (illegalEmitters.isEmpty()) null else source.relativeToV2() to illegalEmitters
            }

        assertTrue(
            offenders.isEmpty(),
            "A module that executes child processes must write their transcript to the Output " +
                "Plane. The only lawful place for an EchoOutputCaptured carrying process bytes is " +
                "a function that declares the NON-DURABLE fallback, where no plane can exist. " +
                "Offending files and functions: $offenders",
        )
    }

    /**
     * Maps each top-level function name in [text] to the body it spans, so an emission can be
     * attributed to the function that contains it rather than to the file that merely holds it.
     */
    private fun enclosingFunctionNames(text: String): Map<String, String> {
        val result = mutableMapOf<String, String>()
        var current: String? = null
        val body = StringBuilder()
        for (line in text.lines()) {
            val declaration = functionDeclaration.find(line.trim())
            if (declaration != null) {
                current?.let { result[it] = body.toString() }
                current = declaration.groupValues[1]
                body.setLength(0)
            }
            if (current != null) body.append(line).append('\n')
        }
        current?.let { result[it] = body.toString() }
        return result
    }
}
