package dev.rubentxu.pipeline.v2.application

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * This module does not decide where the credentials store is.
 *
 * ## What this pins
 *
 * `pipeline-application` had TWO readers of the credentials store path — the
 * durable branch (`composeWithCredentialsExecutor`) and the in-memory branch
 * (inline in `Main.kt`) — and the second was a verbatim copy of the first. Both
 * derived the default from the run's control root, while `pipelinek credentials
 * add`, which has no control root in scope, wrote to `~/.pipeline/credentials.bin`.
 * With no `--control-root` the runtime therefore read `/tmp/credentials.bin`,
 * the store it had just been told about was invisible, and the absence was
 * silent by design.
 *
 * `CredentialsStorePath` (in `pipeline-credentials-local`) is now the ONE
 * authority. A copy here could not drift from it without someone editing two
 * places, which is the whole failure mode.
 *
 * ## Why the scan is over this module's own sources
 *
 * It reads only `pipeline-application`'s `src/main`, so it adds no cross-module
 * file dependency and no hidden Gradle input: the module's own test task already
 * depends on everything it compiles. The credentials-local half of the same rule
 * has its own fitness there.
 */
@DisplayName("the credentials store location is not recomputed in this module")
@Timeout(30)
class CredentialsStorePathIsNotRecomputedFitnessTest {

    private val mainRoot: Path = Paths.get("src/main/kotlin")

    private fun kotlinSources(): List<Path> =
        Files.walk(mainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }

    /** Comments are stripped so a KDoc mention can neither fail the scan nor satisfy it. */
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL))
            .replace(Regex("//[^\\n]*").replace(source, ""), "")

    @Test
    fun `no source in this module spells the store file name`() {
        val offenders = kotlinSources()
            .filter { codeOnly(Files.readString(it)).contains("credentials.bin") }
            .map { mainRoot.relativize(it).toString() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "these sources spell the credentials store file themselves, which is how the two " +
                "readers here came to disagree with `credentials add`. Call CredentialsStorePath.resolve()",
        )
    }

    @Test
    fun `both readers ask the single authority`() {
        val main = codeOnly(
            Files.readString(
                mainRoot.resolve("dev/rubentxu/pipeline/v2/application/Main.kt"),
            ),
        )
        val support = codeOnly(
            Files.readString(
                mainRoot.resolve("dev/rubentxu/pipeline/v2/application/MainCredentialsSupport.kt"),
            ),
        )

        assertTrue(
            main.contains("CredentialsStorePath.fromEnvironment(System.getenv())"),
            "the in-memory reader in Main.kt must delegate to CredentialsStorePath",
        )
        assertTrue(
            support.contains("CredentialsStorePath.fromEnvironment(System.getenv())"),
            "the durable reader in MainCredentialsSupport must delegate to CredentialsStorePath",
        )
        assertTrue(
            !support.contains("System.getenv(\""),
            "no module may name an env var directly; CredentialsStorePath owns the name",
        )
    }

    @Test
    fun `the durable reader takes no control root`() {
        // The parameter is what made the defect expressible: with it in scope,
        // `controlDirRoot.parent.resolve(...)` is a one-line change that compiles,
        // passes every unit row, and restores the bug.
        val support = codeOnly(
            Files.readString(
                mainRoot.resolve("dev/rubentxu/pipeline/v2/application/MainCredentialsSupport.kt"),
            ),
        )
        val signature = Regex("internal fun composeWithCredentialsExecutor\\(([\\s\\S]*?)\\)")
            .find(support)?.groupValues?.get(1)
            ?: error("composeWithCredentialsExecutor signature not found; this fitness is blind")

        assertTrue(
            !signature.contains("ontrolRoot") && !signature.contains("controlDirRoot"),
            "composeWithCredentialsExecutor must not receive a control root: the store location " +
                "is decided by the environment, not by where a run keeps its data. Found: $signature",
        )
    }
}