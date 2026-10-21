package dev.rubentxu.pipeline.v2.credentials.local

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import java.nio.file.Path
import java.nio.file.Paths

/**
 * [CredentialsStorePath] is the single place that decides where the local store lives. Three
 * readers used to answer that question and they did not agree: the documented default
 * (`~/.pipeline/credentials.bin`) and two copies of `<controlRoot>/../credentials.bin`. A user
 * who followed the documented flow — add a credential, then run a pipeline — saw no
 * credentials. The fix is to take the env var or the home default and nothing else.
 *
 * The tests pin the three rules: env value present -> use it, env blank -> treat as absent,
 * env absent -> use the home default. They are written so an edit that restores the old
 * `<controlRoot>/../credentials.bin` default would have to also add a control-root parameter
 * to keep the tests passing.
 */
class CredentialsStorePathTest {

    @Test
    fun `env var name is PIPELINE_CREDENTIALS_STORE and nothing else`() {
        // The contract for automation. The constant is the only thing the store checks; a
        // typo here means nobody can override the path, and the operator has no way to
        // know the typo except by reading the source.
        assertEquals("PIPELINE_CREDENTIALS_STORE", CredentialsStorePath.ENV_VAR)
    }

    @Test
    fun `env var present uses the env value verbatim`() {
        val home = Paths.get("/home/anyone")
        val envValue = "/srv/custom/credentials.bin"

        val resolved = CredentialsStorePath.resolve(envValue = envValue, userHome = home)

        assertEquals(Paths.get(envValue), resolved, "the env value overrides the home default")
    }

    @Test
    fun `env var absent uses the home pipeline directory`() {
        val home = Paths.get("/home/anyone")

        val resolved = CredentialsStorePath.resolve(envValue = null, userHome = home)

        assertEquals(Paths.get("/home/anyone/.pipeline/credentials.bin"), resolved)
    }

    @Test
    fun `env var blank is treated as absent, not as a path`() {
        // `PIPELINE_CREDENTIALS_STORE=` in a CI job must not silently redirect the store
        // to `credentials.bin` relative to the runner's cwd. The source KDoc declares this
        // explicitly.
        val home = Paths.get("/home/anyone")

        val resolved = CredentialsStorePath.resolve(envValue = "  ", userHome = home)

        assertEquals(
            Paths.get("/home/anyone/.pipeline/credentials.bin"),
            resolved,
            "a blank env value must not produce a path inside the runner's cwd",
        )
    }

    @Test
    fun `the env value is used even when it points to a relative path`() {
        // The store trusts the operator's env value: it is not the resolver's job to
        // second-guess the location. A path the operator sets is used as-is, so the
        // property to pin is that no normalization or homing happens on the env path.
        val home = Paths.get("/home/anyone")
        val envValue = "relative/path/credentials.bin"

        val resolved = CredentialsStorePath.resolve(envValue = envValue, userHome = home)

        assertEquals(Path.of(envValue), resolved)
        assertNotEquals(Paths.get("/home/anyone/relative/path/credentials.bin"), resolved)
    }

    @Test
    fun `fromEnvironment reads the env var key the constant names`() {
        val home = "/home/fromenv"

        val resolved = CredentialsStorePath.fromEnvironment(
            mapOf(
                "PIPELINE_CREDENTIALS_STORE" to "/tmp/store",
                "HOME" to home,
            ),
        )

        assertEquals(Paths.get("/tmp/store"), resolved)
    }

    @Test
    fun `fromEnvironment without the env var uses HOME`() {
        val resolved = CredentialsStorePath.fromEnvironment(
            mapOf("HOME" to "/home/withouthEnv"),
        )

        assertEquals(Paths.get("/home/withouthEnv/.pipeline/credentials.bin"), resolved)
    }

    @Test
    fun `fromEnvironment with a blank env var falls back to HOME`() {
        val resolved = CredentialsStorePath.fromEnvironment(
            mapOf("PIPELINE_CREDENTIALS_STORE" to "", "HOME" to "/home/blankEnv"),
        )

        assertEquals(Paths.get("/home/blankEnv/.pipeline/credentials.bin"), resolved)
    }

    @Test
    fun `fromEnvironment without HOME uses the JVM's user home`() {
        // The fallback is System.getProperty("user.home"). A HOME-less container still gets a
        // usable path; the test pins that fallback by reading the JVM property and asserting
        // equality, not by depending on its value.
        val resolved = CredentialsStorePath.fromEnvironment(emptyMap())

        assertEquals(
            Paths.get(System.getProperty("user.home"), ".pipeline", "credentials.bin"),
            resolved,
        )
    }
}
