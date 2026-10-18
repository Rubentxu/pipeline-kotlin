package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import dev.rubentxu.pipeline.v2.domain.SecretHandle
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * `PATH+=` is Jenkins verbatim syntax: `PATH+=<dir>` prepends the **directory** to
 * PATH and then removes the entry, leaving no `PATH+<dir>` key behind.
 *
 * `EnvModel` carries two overloads — one over `Map<String, String>` ("legacy") and
 * one over `Map<String, SecretHandle>` ("typed"). The typed one is the live one:
 * `ShOptions.env` is declared as `Map<String, SecretHandle>`, and
 * `DurableShellExecutor` materialises its result immediately afterwards
 * (`transformedEnv.mapValues { it.value.materialize() }`). So the typed overload
 * is the one whose output actually reaches `ProcessBuilder`.
 *
 * Every input here supplies PATH explicitly. `EnvModel.apply` falls back to
 * `System.getenv("PATH")` when the input carries none, which would make the
 * expected value depend on the machine running the test.
 */
class EnvModelPathPrependTest {

    private fun pathOf(env: Map<String, SecretHandle>): String? =
        env["PATH"]?.materialize()

    // The specific claim goes first: the value of the handle is what belongs in
    // PATH. The variable name is the *label* of the entry, not its content.
    @Test
    fun `PATH plus prepends the handle value and not the variable name`() {
        val out = EnvModel.apply(
            mapOf(
                "PATH" to SecretHandle.plain("/usr/bin"),
                "PATH+MAVEN" to SecretHandle.plain("/opt/maven/bin"),
            ),
        )

        assertEquals(
            "/opt/maven/bin:/usr/bin",
            pathOf(out),
            "PATH+=MAVEN must prepend the handle's value (/opt/maven/bin); " +
                "prepending the variable name would inject a directory called MAVEN",
        )
    }

    @Test
    fun `the PATH plus key is consumed and does not reach the process environment`() {
        val out = EnvModel.apply(
            mapOf(
                "PATH" to SecretHandle.plain("/usr/bin"),
                "PATH+MAVEN" to SecretHandle.plain("/opt/maven/bin"),
            ),
        )

        assertFalse(
            out.keys.any { it.startsWith("PATH+") },
            "PATH+= entries are consumed, not passed through: a literal " +
                "PATH+MAVEN variable would leak into the spawned process",
        )
    }

    @Test
    fun `multiple PATH plus entries prepend in declaration order, first outermost`() {
        val out = EnvModel.apply(
            mapOf(
                "PATH" to SecretHandle.plain("/usr/bin"),
                "PATH+FIRST" to SecretHandle.plain("/opt/first"),
                "PATH+SECOND" to SecretHandle.plain("/opt/second"),
            ),
        )

        assertEquals(
            "/opt/first:/opt/second:/usr/bin",
            pathOf(out),
            "PATH+= is verbatim: the first declared entry is the outermost one",
        )
    }

    @Test
    fun `an empty input inherits nothing, not even the controller PATH`() {
        assertEquals(
            emptyMap<String, SecretHandle>(),
            EnvModel.apply(emptyMap<String, SecretHandle>()),
            "an empty step environment must stay empty; inheriting the controller's " +
                "PATH would make a hermetic run depend on the machine",
        )
    }

    @Test
    fun `a masked PATH plus entry is consumed too, never leaked as a literal variable`() {
        val out = EnvModel.apply(
            mapOf(
                "PATH" to SecretHandle.plain("/usr/bin"),
                "PATH+SECRET_DIR" to SecretHandle.masked("/opt/private"),
            ),
        )

        assertEquals(
            "/opt/private:/usr/bin",
            pathOf(out),
            "a mask marks the value as non-secret; it does not exempt the entry from PATH+= " +
                "syntax, whose value is a directory rather than a credential",
        )
        assertFalse(
            out.keys.any { it.startsWith("PATH+") },
            "a PATH+ key reaching the child environment is meaningless to every process",
        )
    }

    @Test
    fun `ordinary entries are carried through untouched`() {
        val out = EnvModel.apply(
            mapOf(
                "PATH" to SecretHandle.plain("/usr/bin"),
                "LANG" to SecretHandle.plain("C"),
                "notPATHPlus" to SecretHandle.plain("/x"),
            ),
        )

        assertEquals("C", out["LANG"]?.materialize())
        assertEquals("/x", out["notPATHPlus"]?.materialize())
        assertTrue(
            out.keys.none { it.startsWith("PATH+") },
            "a variable that merely starts with the letters PATH is not PATH+ syntax",
        )
    }
}