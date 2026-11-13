package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.OpaqueStepNode
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.ShellInvocationResult
import dev.rubentxu.pipeline.v2.domain.StageBody
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.dsl.StageScope
import dev.rubentxu.pipeline.v2.dsl.StageSpec
import dev.rubentxu.pipeline.v2.dsl.StepSpec
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsOutput
import dev.rubentxu.pipeline.v2.runtime.CoreFileExistsStep
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileOutput
import dev.rubentxu.pipeline.v2.runtime.CoreReadFileStep
import dev.rubentxu.pipeline.v2.runtime.CoreShellOutput
import dev.rubentxu.pipeline.v2.runtime.CoreShellStep
import dev.rubentxu.pipeline.v2.runtime.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.sdk.scm.git.step.registerScmGit
import java.util.concurrent.TimeUnit
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The forms whose value Jenkins hands back, and what the eager form does with it.
 *
 * ## The guarantee under trial
 *
 * That a form of this product which PUBLISHES a Jenkins equivalent declares the
 * difference when the eager authoring of that form does not hand the value back:
 * the call declares an operation, and the value arrives in the run.
 *
 * ## Why the guarantee is about the PUBLICATION
 *
 * Because the code is already honest: the eager form returns nothing, so a
 * missing result is never presented as an available value. What is not honest is
 * the catalogue: an entry that publishes `jenkinsSurface = "readFile"` and no
 * divergence tells a reader who comes from Jenkins that the call is the
 * `readFile` of Jenkins, and the reader expects the content back.
 *
 * ## Why the facts are DERIVED and not written down here
 *
 * Every row takes the key from the PLAN the form produces and the divergence
 * from the DESCRIPTOR that key carries. Writing the keys here would freeze the
 * truth in this file, and the file would keep passing the day the thing it
 * watches changed.
 *
 * ## What this file does NOT affirm
 *
 * It does not affirm that the value is USABLE in the eager form: it is not, and
 * that is the difference the divergence declares. It affirms that the
 * difference is DECLARED.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class EagerValueFormsTest {

    /**
     * The eager form of `readFile` declares the read and returns nothing.
     */
    @Test
    @DisplayName("the eager form of readFile declares the read and returns nothing")
    fun theEagerFormOfReadFileDeclaresTheReadAndReturnsNothing() {
        declaresTheOperationAndReturnsNothing(formOf("readFile"))
    }

    /**
     * The eager form of `fileExists` declares the check and returns nothing.
     */
    @Test
    @DisplayName("the eager form of fileExists declares the check and returns nothing")
    fun theEagerFormOfFileExistsDeclaresTheCheckAndReturnsNothing() {
        declaresTheOperationAndReturnsNothing(formOf("fileExists"))
    }

    /**
     * The eager form of `param` declares the read of the parameter and returns
     * nothing.
     */
    @Test
    @DisplayName("the eager form of param declares the read and returns nothing")
    fun theEagerFormOfParamDeclaresTheReadAndReturnsNothing() {
        declaresTheOperationAndReturnsNothing(formOf("param"))
    }

    /**
     * The eager form of `sh` with `returnStdout` declares the shell and returns
     * nothing.
     */
    @Test
    @DisplayName("the eager form of sh with returnStdout declares the shell and returns nothing")
    fun theEagerFormOfShWithReturnStdoutDeclaresTheShellAndReturnsNothing() {
        declaresTheOperationAndReturnsNothing(formOf("sh"))
    }

    /**
     * The entry `readFile` enqueues declares the difference.
     */
    @Test
    @DisplayName("the entry readFile enqueues declares the difference")
    fun theEntryReadFileEnqueuesDeclaresTheDifference() {
        declaresItsDifference(formOf("readFile"))
    }

    /**
     * The entry `fileExists` enqueues declares the difference.
     */
    @Test
    @DisplayName("the entry fileExists enqueues declares the difference")
    fun theEntryFileExistsEnqueuesDeclaresTheDifference() {
        declaresItsDifference(formOf("fileExists"))
    }

    /**
     * The entry `param` enqueues declares the difference.
     */
    @Test
    @DisplayName("the entry param enqueues declares the difference")
    fun theEntryParamEnqueuesDeclaresTheDifference() {
        declaresItsDifference(formOf("param"))
    }

    /**
     * The entry `sh` enqueues declares the difference.
     */
    @Test
    @DisplayName("the entry sh enqueues declares the difference")
    fun theEntryShEnqueuesDeclaresTheDifference() {
        declaresItsDifference(formOf("sh"))
    }

    /**
     * The value the step produces travels in its typed output, and the codec
     * keeps it.
     *
     * The side of the comparison that is not a returned value: the scripted form
     * decodes the typed output back to a `String` or a `Boolean`, and it can
     * only do that because the value is IN the output and the codec carries it.
     */
    @Test
    @DisplayName("the value of the step travels in its typed output and its codec keeps it")
    fun theValueOfTheStepTravelsInItsTypedOutputAndItsCodecKeepsIt() {
        val readFile = CoreReadFileStep.definition.contract.outputCodec
        val decodedRead = readFile.decode(
            readFile.encode(CoreReadFileOutput(content = "hola", exists = true)),
        )
        assertEquals(
            "hola",
            decodedRead.content,
            "the content of the file does not travel in the typed output of the step: the " +
                "scripted form has nothing to decode back to a String",
        )

        val fileExists = CoreFileExistsStep.definition.contract.outputCodec
        val decodedExists = fileExists.decode(
            fileExists.encode(CoreFileExistsOutput(exists = true)),
        )
        assertTrue(
            decodedExists.exists,
            "the answer of the existence check does not travel in the typed output of the step",
        )

        val shell = CoreShellStep.definition.contract.outputCodec
        val decodedShell = shell.decode(
            shell.encode(
                CoreShellOutput(
                    result = ShellInvocationResult.Stdout("hola"),
                    outcome = StepOutcome.Success,
                ),
            ),
        )
        assertEquals(
            "hola",
            (decodedShell.result as ShellInvocationResult.Stdout).value,
            "the captured stdout does not travel in the typed output of the step: the scripted " +
                "form has nothing to decode back to a String",
        )
    }

    /**
     * Requires that the form enqueues exactly one step and that the call
     * returns nothing at all.
     */
    private fun declaresTheOperationAndReturnsNothing(form: Form) {
        val scope = StageScope("stage")
        form.write(scope)

        assertEquals(
            1,
            scope.steps().size,
            "the eager form of `${form.name}` does not enqueue exactly one step: a form that " +
                "declares more than one operation is not the operation this row is about",
        )
        assertEquals(
            Void.TYPE,
            form.returnType(),
            "the eager form of `${form.name}` returns a value: the eager form DECLARES an " +
                "operation, and a value it returned would be a value the run never observed",
        )
    }

    /**
     * Requires that the entry the form enqueues declares its difference with the
     * equivalent of Jenkins, AND that the difference names the EAGER form.
     *
     * A difference that does not name the form it is about leaves the reader
     * guessing which of the two ways of writing the step he is reading about: the
     * eager one declares the operation and hands nothing back, and the scripted
     * one hands the value back. The row requires the vocabulary the catalogue
     * uses for those two facts, and NOT a frozen sentence: the text around them
     * is free, and what it has to say is fixed.
     */
    private fun declaresItsDifference(form: Form) {
        val key = keyOf(form)
        val descriptor = productionRegistry().definition(key)?.contract?.descriptor
            ?: fail("the tree has no entry for the key `$key`, the one the eager form of " +
                "`${form.name}` enqueues: there is nothing to declare a difference on")

        assertEquals(
            form.jenkinsSurface,
            descriptor.jenkinsSurface,
            "the entry `$key` publishes the equivalent ${form.equivalent()} of Jenkins, and the " +
                "tree publishes something else: the reader is pointed at a step that is not the " +
                "one this row is about",
        )
        val divergence = descriptor.jenkinsDivergence
        assertTrue(
            divergence.contains(EAGER_FORM),
            "the entry `$key` declares NO difference with the equivalent of Jenkins, or one " +
                "that does not name the EAGER form: a reader who comes from Jenkins has to know " +
                "that the difference is about the form that DECLARES the operation. The text it " +
                "reads is: `$divergence`",
        )
        assertTrue(
            divergence.contains(HANDS_NOTHING_BACK),
            "the entry `$key` declares a difference that does not say the eager form hands " +
                "nothing back: the reader expects the value of the equivalent of Jenkins, and " +
                "the text has to say that the eager form of `${form.name}` gives him none. The " +
                "text it reads is: `$divergence`",
        )
    }

    /**
     * The key the form enqueues, read from the plan it produces.
     */
    private fun keyOf(form: Form): PluginStepId {
        val scope = StageScope("stage")
        form.write(scope)
        return compileNode(scope.steps().single()).pluginStepId
    }

    /**
     * Compiles one step of the DSL and returns the node it produces.
     */
    private fun compileNode(step: StepSpec): OpaqueStepNode {
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
     * The form of the given name, or a failure that names the ones there are.
     */
    private fun formOf(name: String): Form =
        FORMS.firstOrNull { form -> form.name == name }
            ?: fail("this file knows no form named `$name`; the forms it knows are " +
                FORMS.joinToString(", ") { form -> form.name })

    /**
     * One eager form, and the way the author writes it.
     */
    private class Form(
        val name: String,
        val jenkinsSurface: String,
        val parameterCount: Int,
        val write: (StageScope) -> Unit,
    ) {
        /**
         * The equivalent in Jenkins the entry publishes, said for a message.
         *
         * Empty when Jenkins has no step of that name: a parameter of Jenkins is
         * a variable, not a step, and the entry that reads it publishes no
         * equivalent for that reason.
         */
        fun equivalent(): String =
            if (jenkinsSurface.isEmpty()) "NONE" else "`$jenkinsSurface`"

        /**
         * The declared return type of the method the author calls.
         *
         * The arity tells the overloads apart: `sh` has one that takes the
         * command alone and one that takes the script, the flag of a script block
         * and the flag of a captured stdout, and the author who writes
         * `returnStdout` calls the second one.
         */
        fun returnType(): Class<*> {
            val candidates = StageScope::class.java.methods.filter { method ->
                method.name == name && method.parameterCount == parameterCount
            }
            check(candidates.size == 1) {
                "the eager DSL declares ${candidates.size} methods named `$name` with " +
                    "$parameterCount arguments, and the row needs exactly one"
            }
            return candidates.single().returnType
        }
    }

    private companion object {

        /**
         * The vocabulary the catalogue uses to name the EAGER form.
         *
         * A difference that does not name the form it talks about tells the reader
         * nothing about WHICH of the two ways of writing the step he is reading
         * about. The catalogue already names the eager form this way —the entry of
         * `core.pwd` says it in the same words— and a row that lets the name drift
         * would let the two entries of the catalogue answer the same reader in two
         * vocabularies.
         */
        private const val EAGER_FORM = "eager DSL"

        /**
         * The vocabulary the catalogue uses to say that the eager form hands
         * NOTHING back.
         *
         * This is the fact the row is about: the value Jenkins hands back is not
         * handed back here, and a difference that leaves that out would name the
         * eager form and say nothing about the value the reader is waiting for.
         */
        private const val HANDS_NOTHING_BACK = "returns nothing"

        /**
         * The four forms this card is about.
         *
         * They are the forms whose equivalent in Jenkins hands a value back, and
         * they are the ones the card names.
         */
        val FORMS = listOf(
            Form(
                name = "readFile",
                jenkinsSurface = "readFile",
                parameterCount = 2,
                write = { scope -> scope.readFile("f.txt") },
            ),
            Form(
                name = "fileExists",
                jenkinsSurface = "fileExists",
                parameterCount = 1,
                write = { scope -> scope.fileExists("f.txt") },
            ),
            Form(
                name = "param",
                jenkinsSurface = "",
                parameterCount = 1,
                write = { scope -> scope.param("P") },
            ),
            Form(
                name = "sh",
                jenkinsSurface = "sh",
                parameterCount = 3,
                write = { scope -> scope.sh("echo hola", returnStdout = true) },
            ),
        )
    }
}
