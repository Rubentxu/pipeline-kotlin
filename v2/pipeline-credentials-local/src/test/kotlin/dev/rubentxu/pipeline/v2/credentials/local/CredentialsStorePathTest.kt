package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.file.Path
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The credentials store location, which used to have three answers.
 *
 * The property under test is deliberately narrow: the store is located by the
 * ENVIRONMENT and by nothing else. `CredentialsStorePath`'s KDoc carries the
 * defect it removes; these rows pin the rule that replaced two of them.
 */
@DisplayName("credentials store location")
@Timeout(30)
class CredentialsStorePathTest {

    private val home = Path.of("/home/someone")

    @Test
    fun `the env var decides the location when it is set`() {
        assertEquals(
            Path.of("/srv/secrets/creds.bin"),
            CredentialsStorePath.resolve("/srv/secrets/creds.bin", home),
            "an explicit PIPELINE_CREDENTIALS_STORE must win over the default",
        )
    }

    @Test
    fun `a blank env var is absent rather than the working directory`() {
        // `PIPELINE_CREDENTIALS_STORE=` is what an unset-but-declared shell
        // variable or a CI job with an empty value produces. Resolving it as a
        // path would silently point the store at `credentials.bin` relative to
        // whatever the runner's working directory happens to be.
        assertEquals(
            CredentialsStorePath.resolve(null, home),
            CredentialsStorePath.resolve("", home),
            "a blank value must resolve exactly as an absent one",
        )
        assertEquals(
            CredentialsStorePath.resolve(null, home),
            CredentialsStorePath.resolve("   ", home),
            "whitespace is not a path either",
        )
    }

    @Test
    fun `with no env var the store is the documented user home file`() {
        assertEquals(
            home.resolve(".pipeline").resolve("credentials.bin"),
            CredentialsStorePath.resolve(null, home),
            "the default the CLI names in its own contract",
        )
    }

    @Test
    fun `fromEnvironment reads the env var by the name every reader already used`() {
        // If ENV_VAR and the string a reader passes to System.getenv ever differ,
        // the override silently stops applying and the default wins — the exact
        // shape of the divergence this object removed. Both sides are named here.
        assertEquals(
            "PIPELINE_CREDENTIALS_STORE",
            CredentialsStorePath.ENV_VAR,
            "the env var name is part of the contract, not a private detail",
        )
        assertEquals(
            Path.of("/tmp/elsewhere.bin"),
            CredentialsStorePath.fromEnvironment(
                mapOf("HOME" to "/home/someone", CredentialsStorePath.ENV_VAR to "/tmp/elsewhere.bin"),
            ),
            "fromEnvironment must apply the override for the real var name",
        )
    }

    @Test
    fun `fromEnvironment falls back to the real user home when HOME is absent`() {
        // The JVM property is the last resort, not the first: a container that
        // sets HOME must not have its home ignored.
        assertEquals(
            CredentialsStorePath.resolve(null, Path.of(System.getProperty("user.home"))),
            CredentialsStorePath.fromEnvironment(emptyMap()),
            "with no HOME the JVM's user.home is the home",
        )
    }
}