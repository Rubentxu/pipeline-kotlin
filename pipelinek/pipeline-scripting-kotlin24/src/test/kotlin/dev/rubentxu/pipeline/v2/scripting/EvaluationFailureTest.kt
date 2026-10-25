package dev.rubentxu.pipeline.v2.scripting

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertSame
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.io.File
import java.lang.reflect.InvocationTargetException

/**
 * The classifier decides whether a failed evaluation may be retried, and
 * the two rows it refuses to blur are the ones the cache's safety depends
 * on: an artifact that never loaded may be recompiled, and a script body
 * that threw may never be run again behind the cache's back.
 *
 * The instrument here is the **real** refusal, not a copy of it: the
 * scripting runtime's own class is asked for the exception it raises, so
 * these rows observe the contract the product depends on instead of
 * asserting a literal both sides happen to share.
 *
 * The class is named **twice, on purpose**. This file names it in
 * [REAL_LOADER_CLASS] because it has to find the class to ask it
 * anything; the product names it in its own constant because it has to
 * compare the frame it sees. The two names are asserted equal, so the
 * product's claim is pinned to a class that was *observed to exist and to
 * raise the refusal* — and if the runtime ever renames the class, this
 * file fails loudly instead of the product quietly ceasing to match.
 */
class EvaluationFailureTest {

    /**
     * The class the scripting runtime really ships for a lazily loaded
     * compiled artifact. This file's own copy: the product's constant is
     * what gets asserted against it, never what is used to find it.
     */
    private val realLoaderClass: String =
        "kotlin.script.experimental.jvmhost.KJvmCompiledScriptLazilyLoadedFromClasspath"

    /**
     * The exception the scripting runtime raises when a cached artifact's
     * compiled entry cannot be loaded, obtained by asking the real class for
     * it. The instance is built with a class name that was never compiled and
     * an empty classpath, so the loader has nothing to load and refuses —
     * the same state a jar truncated to a quarter of its bytes leaves behind.
     */
    private fun realUnloadedArtifactRefusal(): RuntimeException {
        val loader = Class.forName(realLoaderClass)
        val instance = loader.getDeclaredConstructor(String::class.java, List::class.java)
            .apply { isAccessible = true }
            .newInstance("com.example.NeverCompiled", emptyList<File>())
        val refusal = loader.getDeclaredMethod(UNLOADED_ARTIFACT_METHOD)
            .apply { isAccessible = true }
        val thrown = assertThrows(InvocationTargetException::class.java) { refusal.invoke(instance) }
        return thrown.targetException as RuntimeException
    }

    @Test
    fun `the loader's own refusal is an artifact failure, retriable by recompiling`() {
        val refusal = realUnloadedArtifactRefusal()

        // The refusal is a RuntimeException raised from the loader's own
        // method: the frame the exception was constructed in, read back.
        assertEquals("java.lang.RuntimeException", refusal.javaClass.name)
        assertEquals(realLoaderClass, refusal.stackTrace.first().className)
        assertEquals(UNLOADED_ARTIFACT_METHOD, refusal.stackTrace.first().methodName)

        assertEquals(
            EvaluationFailure.UnloadedArtifact,
            classifyEvaluationFailure(refusal),
        )
    }

    @Test
    fun `the same words raised anywhere else are the script's own failure and are never retried`() {
        val refusal = realUnloadedArtifactRefusal()

        // The same message, raised by somebody else: the script's own body.
        // Deciding by the words would recompile and run that body a second
        // time, repeating whatever it had already done.
        val fromTheBody = RuntimeException(refusal.message)
        assertEquals(refusal.message, fromTheBody.message)

        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(fromTheBody),
        )
    }

    @Test
    fun `an Error carrying the loader's own frame is never retriable`() {
        val refusal = realUnloadedArtifactRefusal()

        // The origin is right and the type is wrong: an Error is not a
        // damaged cache. Recompiling would only ask for more of the same.
        val asError = Error(refusal.message).apply { setStackTrace(refusal.stackTrace) }

        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(asError),
        )
    }

    @Test
    fun `any other throwable is the script's own failure and is never retried`() {
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(RuntimeException("boom from the script body")),
        )
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(IllegalStateException("network call already sent")),
        )
        assertEquals(
            EvaluationFailure.ScriptBody,
            classifyEvaluationFailure(RuntimeException("Compiled script is not loaded ye")),
        )
    }

    @Test
    fun `the origin the rule names is the class the scripting runtime really ships`() {
        // The product's claim, resolved: the class it names exists, it is
        // the one this file observed raising the refusal, and it comes from
        // the same artifact as the host that evaluates scripts.
        assertEquals(realLoaderClass, UNLOADED_ARTIFACT_CLASS)

        val loader = Class.forName(UNLOADED_ARTIFACT_CLASS)
        val method = loader.getDeclaredMethod(UNLOADED_ARTIFACT_METHOD)

        assertSame(
            loader.classLoader,
            Class.forName("kotlin.script.experimental.jvmhost.BasicJvmScriptingHost").classLoader,
        )
        // And it is the method that hands out the loaded script — the one
        // that refuses when there is nothing to hand out.
        assertEquals("kotlin.script.experimental.jvm.impl.KJvmCompiledScript", method.returnType.name)
        assertTrue(java.lang.reflect.Modifier.isPublic(method.modifiers))
    }
}
