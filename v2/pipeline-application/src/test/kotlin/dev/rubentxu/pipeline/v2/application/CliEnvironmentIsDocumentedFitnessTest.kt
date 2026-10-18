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
 * Every environment variable the product reads is reachable from `--help`.
 *
 * ## What this pins
 *
 * Measured 2026-10-18 before `CLI_ENVIRONMENT` existed: six `PIPELINE*` literals
 * in product code, **zero** of them reachable from `--help`.
 * `PIPELINE_STORE_PASSPHRASE` appeared only in two error hints, so it became
 * discoverable exactly when something had already gone wrong.
 * `PIPELINE_CREDENTIALS_STORE` appeared only in a comment; the sandbox pair only
 * in SDK KDoc.
 *
 * That failure is quieter than a missing verb, because a missing verb is a
 * rejection an operator can read, while an env var that is not documented looks
 * identical to a program that ignored it. This is the same drift `CLI_VERBS`
 * already prevents for verbs, on the other surface.
 *
 * ## Why there is no exclusion
 *
 * The obvious implementation skips `Main.kt`, because that is where the list
 * lives. That exclusion is a hole: a fourth variable added to `Main.kt` outside
 * the list would be skipped by filename and the row would stay green. Instead
 * every literal is checked against the lists, including the lists' own — which
 * is self-consistent rather than circular, and leaves nowhere to hide.
 *
 * `PIPELINE_OP_COOKIE` is product-internal (the durable executor writes it as a
 * sentinel, the wrapper overwrites it with the real operation id, and the
 * process killer scans `/proc/<pid>/environ` for it). It belongs in
 * `CLI_ENVIRONMENT_INTERNAL`, **not** in a skip: an exemption that is a name
 * somebody chose can be audited, whereas an exemption expressed as a pattern or
 * a filename can hide a real operator variable behind it. The comparison is
 * therefore against the union of both lists, so a seventh variable still fails.
 *
 * ## What this fitness has already caught
 *
 * Its first run failed with `PIPELINE_OP_COOKIE`, `PIPELINE_SANDBOX_ALLOW_EXTRA`
 * and `PIPELINE_SANDBOX_PATH_KEEP` — names a sweep in this session had reported
 * as absent. The sweep had used a one-level glob, `v2/<module>/src/main/`,
 * which misses `v2/pipeline-step-sdk/runtime/src/main/`. Three of the six
 * variables live two module levels deep. A census that reads as complete
 * because it found
 * *something* is not a census; `Files.walk` is here so that the denominator is
 * the tree and not a shell pattern the author happened to write.
 *
 * ## Why the cross-module scan needs no build change
 *
 * This reads every module's `src/main/kotlin` under `v2`, outside its own source
 * set. That is already a declared Gradle input of this module's `test` task
 * (`crossModuleProductionSources`, `build.gradle.kts`), added when BQ2 found the
 * same class of undeclared input serving a stale green. Reading files the task
 * already watches adds no new staleness.
 */
@DisplayName("every environment variable the product reads is listed by --help")
@Timeout(30)
class CliEnvironmentIsDocumentedFitnessTest {

    /** One module above this one is `v2`, the root that holds every module's sources. */
    private val modulesRoot: Path = Paths.get("..")

    private fun productionSources(): List<Path> =
        Files.walk(modulesRoot).use { stream ->
            stream.filter { Files.isRegularFile(it) && it.toString().endsWith(".kt") }
                .filter { it.toString().contains("/src/main/kotlin/") }
                .sorted()
                .toList()
        }

    /** Comments are stripped: a name mentioned in prose is not a name the program reads. */
    private fun codeOnly(source: String): String =
        Regex("/\\*.*?\\*/", setOf(RegexOption.DOT_MATCHES_ALL))
            .replace(Regex("//[^\\n]*").replace(source, ""), "")

    private fun declaredEnvironmentNames(): Set<String> =
        productionSources()
            .flatMap { file ->
                Regex("\"(PIPELINE[A-Z0-9_]*)\"").findAll(codeOnly(Files.readString(file)))
                    .map { it.groupValues[1] }
                    .toList()
            }
            .toSet()

    @Test
    fun `the list exists and printUsage renders it`() {
        // Without this row, deleting the list would leave the scan below comparing
        // real variables against an empty set — which fails loudly, but for the
        // wrong reason. This pins the claim itself: the names are ADVERTISED.
        val main = codeOnly(Files.readString(mainKt()))
        assertTrue(
            main.contains("CLI_ENVIRONMENT"),
            "CLI_ENVIRONMENT is the one place a behaviour-changing variable is advertised",
        )
        assertTrue(
            Regex("out\\.append\\(\"\\\\nEnvironment:\\\\n\"\\)").containsMatchIn(main),
            "printUsage must render the environment section; a constant nothing prints is not discoverable",
        )
        assertTrue(
            Regex("out\\.append\\(\"\\\\nEnvironment \\(internal").containsMatchIn(main),
            "the internal names must be rendered too; an operator who finds one in /proc cannot " +
                "tell whether setting it means anything",
        )
    }

    @Test
    fun `the scan actually finds variables`() {
        // The denominator. Without it, a regex that stops matching would make the
        // row below pass with nothing to check, and "no undocumented variables"
        // would be indistinguishable from "no variables".
        val found = declaredEnvironmentNames()
        assertTrue(
            found.isNotEmpty(),
            "the scan found no PIPELINE* literals at all; either the regex or the sources moved",
        )
    }

    @Test
    fun `every variable the product reads is listed`() {
        val advertised =
            (CLI_ENVIRONMENT.map { it.first } + CLI_ENVIRONMENT_INTERNAL.map { it.first }).toSet()
        val undocumented = (declaredEnvironmentNames() - advertised).sorted()

        assertEquals(
            emptyList<String>(),
            undocumented,
            "these environment variables appear in product code and are reachable from neither " +
                "`--help` list. An operator cannot set what they cannot find; add each to " +
                "CLI_ENVIRONMENT, or to CLI_ENVIRONMENT_INTERNAL with a reason if the product " +
                "sets it for itself.",
        )
    }

    private fun mainKt(): Path =
        modulesRoot.resolve(
            "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/Main.kt",
        )
}