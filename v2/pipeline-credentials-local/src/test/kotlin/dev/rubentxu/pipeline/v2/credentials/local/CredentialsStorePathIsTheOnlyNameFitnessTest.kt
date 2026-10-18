package dev.rubentxu.pipeline.v2.credentials.local

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout

/**
 * The credentials store file has exactly ONE name in this module.
 *
 * ## What this pins
 *
 * `credentials.bin` used to be spelled out in three places across two modules,
 * and two of them derived it from the run's control root. The CLI wrote to
 * `~/.pipeline/credentials.bin`; `pipelinek run` with no `--control-root` read
 * `/tmp/credentials.bin`, because the default control root is a fresh temporary
 * directory. The documented "add a credential, then run" flow therefore found
 * nothing, and said nothing, because "store absent" returns null by design.
 *
 * The rule is now one function ([CredentialsStorePath]) with the file name in
 * it. This fitness says so at the source level, because the signature a caller
 * reads is exactly where the defect came back from: adding a `controlRoot`
 * parameter would compile, pass every unit row, and restore the bug.
 *
 * ## Why it scans rather than calls
 *
 * A row that calls the function proves the function agrees with itself. The
 * thing that broke was the set of CALLERS. Source is where the callers are, and
 * the cross-module half lives in `pipeline-application`, which has its own copy
 * of this fitness over its own `src/main` — so neither module reads the other's
 * sources, and neither gains a hidden Gradle input.
 */
@DisplayName("credentials store file is named in exactly one place")
@Timeout(30)
class CredentialsStorePathIsTheOnlyNameFitnessTest {

    private val mainRoot: Path = Paths.get("src/main/kotlin")

    private fun kotlinSources(): List<Path> =
        Files.walk(mainRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .sorted()
                .toList()
        }

    /** Comments are stripped so a KDoc mention cannot fail the scan, nor satisfy it. */
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL))
            .replace(Regex("//[^\\n]*").replace(source, ""), "")

    @Test
    fun `the authority exists, so this fitness is not vacuous`() {
        // Without this row the scan below would also pass if the file it is
        // protecting were deleted — a guard whose subject can vanish.
        assertTrue(
            Files.isRegularFile(
                mainRoot.resolve(
                    "dev/rubentxu/pipeline/v2/credentials/local/CredentialsStorePath.kt",
                ),
            ),
            "CredentialsStorePath.kt is the ONE authority; if it is gone the rule it holds has gone with it",
        )
    }

    @Test
    fun `no other source in this module spells the store file name`() {
        val offenders = kotlinSources()
            .filterNot { it.fileName.toString() == "CredentialsStorePath.kt" }
            .filter { codeOnly(Files.readString(it)).contains("credentials.bin") }
            .map { mainRoot.relativize(it).toString() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "these sources spell the credentials store file themselves, which is how the CLI " +
                "and the runtime came to name different files. Use CredentialsStorePath.resolve()",
        )
    }

    @Test
    fun `the CLI reader asks the authority instead of reading the environment`() {
        val cli = codeOnly(
            Files.readString(
                mainRoot.resolve(
                    "dev/rubentxu/pipeline/v2/credentials/local/MainCredentialsCli.kt",
                ),
            ),
        )
        assertTrue(
            cli.contains("CredentialsStorePath.fromEnvironment(System.getenv())"),
            "MainCredentialsCli.resolveStoreFile must delegate; a local System.getenv is the second authority",
        )
        assertTrue(
            !cli.contains("System.getenv(\""),
            "MainCredentialsCli must not name an env var directly; CredentialsStorePath owns the name",
        )
    }
}