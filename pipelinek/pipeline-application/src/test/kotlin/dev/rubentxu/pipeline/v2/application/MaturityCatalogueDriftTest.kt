package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.dsl.PipelineScope
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StageScopeCore
import dev.rubentxu.pipeline.v2.dsl.StageScopeTopSteps
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.registerScmGit
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.concurrent.TimeUnit
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The maturity manifest, and the product it describes.
 *
 * ## The guarantee under trial
 *
 * That the manifest does not classify as SELF-REFUSING anything this product
 * offers or executes: a form the language publishes is a form an author can
 * write, and a key the production registry owns is a step a run can execute.
 * A manifest that says otherwise is a catalogue that contradicts the code, and
 * a reader who trusts it is misled about what the product does.
 *
 * ## Why this instrument reads BOTH the manifest and the product
 *
 * Because the drift is a fact about two artifacts at once, and neither one can
 * see the other: a manifest that names a key the registry owns is perfectly
 * well formed, and a registry that owns that key says nothing about what the
 * manifest claims. Only the two texts can.
 *
 * ## Why the facts are DERIVED and not written down here
 *
 * Every row takes the fact from the product —the registry the composition
 * builds, the methods the DSL publishes, the key the `git` shortcut compiles
 * to— and then requires the manifest to agree. Writing the names here would
 * freeze the truth in this file, and the file would keep passing the day the
 * thing it watches changed.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that a covered key is refused at admission: that is the
 * admission's decision, measured where the admission decides it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class MaturityCatalogueDriftTest {

    /**
     * The classification names no FORM the language publishes.
     *
     * The classification is about KEYS that no registered definition owns. A
     * name the language publishes is a form an author can write, and the
     * product's rule is that the language does not offer a form the run
     * always refuses: the refusal has to happen where the mistake is, in the
     * script that writes the form.
     */
    @Test
    @DisplayName("the classification names no form the language publishes")
    fun theClassificationNamesNoFormTheLanguagePublishes() {
        val forms = publishedForms()
        val offending = covered().filter { name -> name in forms }

        assertTrue(
            covered().isNotEmpty(),
            "the manifest classifies nothing, so this rule proves nothing: it would pass with " +
                "an empty classification",
        )
        assertTrue(
            offending.isEmpty(),
            "the manifest classifies as self-refusing a form the language publishes: " +
                "$offending. A form the language publishes is a form an author can write, and " +
                "the product does not offer forms the run always refuses",
        )
    }

    /**
     * The classification names no KEY the production registry owns.
     *
     * A key the registry owns is a step a run executes. Calling it
     * self-refusing would tell a reader that the product refuses a step it
     * executes, and the reader who believes it would not use it.
     */
    @Test
    @DisplayName("the classification names no key the production registry owns")
    fun theClassificationNamesNoKeyTheProductionRegistryOwns() {
        val owned = productionRegistry().keys().map { key -> key.value }.toSet()
        val offending = covered().filter { name -> name in owned }

        assertTrue(
            offending.isEmpty(),
            "the manifest classifies as self-refusing a key the production registry owns: " +
                "$offending. Those steps are executed, not refused",
        )
    }

    /**
     * The `git` shortcut is executed, and the manifest does not say it fails.
     *
     * The row that names the defect this file was written for: the manifest
     * used to list `git` among the constructs that always fail closed, and the
     * product executes it. The row measures the execution and asks the
     * manifest about it, so the two can never say different things.
     */
    @Test
    @DisplayName("the git shortcut is executed, and the manifest does not say it fails")
    fun theGitShortcutIsExecutedAndTheManifestDoesNotSayItFails() {
        val scope = StageScope("stage")
        scope.git(url = "https://example.org/repo.git")
        val key = (compileNode(scope.steps().single())).pluginStepId.value

        assertTrue(
            productionRegistry().keys().any { owned -> owned.value == key },
            "the key the git shortcut compiles to is not owned by the production registry: the " +
                "shortcut would not be executable, and the row that measures the execution " +
                "proves nothing",
        )
        assertFalse(
            GIT_SHORTCUT in covered(),
            "the manifest classifies the `git` shortcut as self-refusing: the catalogue says " +
                "the product refuses a step it executes",
        )
    }

    /**
     * The names the manifest classifies as self-refusing.
     */
    private fun covered(): List<String> {
        val document = Json.parseToJsonElement(
            Files.readString(manifestPath()),
        ).jsonObject
        val surfaces = document["surfaces"]?.jsonObject ?: error("the manifest declares no surfaces")
        val scriptingApi = surfaces["pipeline-scripting-api"]?.jsonObject
            ?: error("the manifest declares no surface for pipeline-scripting-api")
        val entry = scriptingApi["self-refusing-constructs"]?.jsonObject
            ?: error("the manifest declares no self-refusing surface for pipeline-scripting-api")
        return entry["covers"]?.jsonArray?.map { element -> element.jsonPrimitive.content } ?: emptyList()
    }

    /**
     * The names the language publishes, at the level an author writes them.
     *
     * The ABI mangles a function whose parameter is an inline class —`git`
     * travels as `git--CS5l-g`— and the compiler adds a synthetic bridge for
     * every default argument. The mangling and the bridge are erased here:
     * the name an author writes is the name the manifest would name.
     */
    private fun publishedForms(): Set<String> =
        listOf(
            StageScope::class.java,
            StageScopeCore::class.java,
            StageScopeTopSteps::class.java,
            PipelineScope::class.java,
        ).flatMap { type -> type.methods.toList() }
            .map { method -> method.name.substringBefore('-').substringBefore('$') }
            .toSet()

    /**
     * The registry the composition builds in production: the core's own Steps
     * plus the SCM/Git plugin's.
     */
    private fun productionRegistry() =
        CoreStepRegistryFactory.builder()
            .apply {
                registerScmGit(
                    publisher = "pipeline-kotlin",
                    digestSha256 = "sha256:" + "0".repeat(64),
                )
            }
            .build()

    /**
     * Compiles one step of the DSL and returns the node it produces.
     */
    private fun compileNode(step: dev.rubentxu.pipeline.v2.dsl.StepSpec): OpaqueStepNode {
        val compiled = DslCompiledPipelineCompiler.compile(
            spec = PipelineSpec(stages = listOf(StageSpec(name = "s", steps = listOf(step)))),
            sourcePath = "pipeline.kts",
            sourceContent = "pipeline { }",
            pluginLockDigest = Digest("lock"),
        )
        val body = compiled.stages.single().body as StageBody.Steps
        return body.steps.single() as OpaqueStepNode
    }

    /**
     * The maturity manifest, found by walking up until the directory that
     * carries it appears.
     */
    private fun manifestPath(): Path {
        var candidate: Path? = Paths.get("").toAbsolutePath()
        while (candidate != null) {
            val manifest = candidate.resolve("contract/published-contract-maturity.json")
            if (Files.isRegularFile(manifest)) return manifest
            candidate = candidate.parent
        }
        error("the maturity manifest was not found from '${Paths.get("").toAbsolutePath()}'")
    }

    private companion object {

        /** The name the DSL publishes the SCM shortcut under. */
        const val GIT_SHORTCUT = "git"
    }
}
