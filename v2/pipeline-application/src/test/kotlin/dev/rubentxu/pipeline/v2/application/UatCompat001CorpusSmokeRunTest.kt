package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * UAT-COMPAT-001: Compatibility corpus smoke-run.
 * Verifies that all corpus fixtures compile and run successfully.
 * Closes E2-06 + M2 exit criterion.
 *
 * Each fixture exercises the public compatibility DSL and produces events.
 */
@Timeout(120)
class UatCompat001CorpusSmokeRunTest {

    // Fixtures that currently fail at runtime (exit non-zero).
    // After v0.33.1 (corpus-closure cycle): fixtures 02 (withEnv canonical), 13 (pwd/isUnix/timestamps
    // synchronous + canonical), 14 (withCredentials pluginId fix) now PASS. Fixture 10 exercises
    // archiveArtifacts with no real build output — it intentionally fails with exit 1 and emits
    // ArtifactArchiveFailed, which is correct canonical behaviour. Fixture 15 exercises the
    // canonical `error()` step which by design fails the run with exit 1. All other fixtures
    // exit 0.
    // WU-LPR-071: fixture 10 now creates real build output (build/libs/smoke.jar) before
    // archiveArtifacts, so the archive succeeds and the fixture exits 0 — the historical
    // "no files matched" failure no longer applies. Only the deliberate-failure fixture
    // remains in the broken set.
    private val brokenFixtures = setOf(
        "15-error.pipeline.kts",    // `error("test")` step is a deliberate failure path
    )

    /**
     * WU-LPR-071 (CR corpus closure): fixture 14 exercises withCredentials with seven
     * binding kinds, which requires a provisioned credentials store. The corpus runner
     * seeds an ephemeral store once and exports the env contract
     * (PIPELINE_CREDENTIALS_STORE / PIPELINE_STORE_PASSPHRASE) to every fixture process.
     */
    private val corpusPassphrase = "corpus-passphrase-0.36.0"

    private fun seedCorpusCredentialsStore(controlRoot: java.nio.file.Path): java.nio.file.Path {
        val storePath = controlRoot.resolve("credentials.store")
        dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore(
            storePath, corpusPassphrase.toCharArray()
        ).use { store ->
            fun bytes(s: String) = s.toByteArray()
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("string-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.SecretText(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("string-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, bytes("corpus-api-key")))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("userpass-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("userpass-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, "corpus-user", bytes("corpus-pass")))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("ssh-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("ssh-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, "corpus@local", bytes("corpus-ssh-key")))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("file-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.SecretFile(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("file-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, bytes("corpus-file")))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("cert-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.Certificate(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("cert-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, bytes("corpus-keystore")))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("zip-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.Zip(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("zip-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL,
                    mapOf("corpus.txt" to bytes("corpus-zip"))))
            store.add(dev.rubentxu.pipeline.v2.domain.CredentialsId("ucp-creds"),
                dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword(
                    dev.rubentxu.pipeline.v2.domain.CredentialsId("ucp-creds"),
                    dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope.GLOBAL, "corpus-user", bytes("corpus-pass")))
        }
        return storePath
    }

    private fun discoverFixtures(): List<Path> {
        val userDir = File(System.getProperty("user.dir"))
        val candidate = generateSequence(userDir) { it.parentFile }
            .map { File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk from $userDir")
        return candidate.listFiles { f -> f.name.endsWith(".pipeline.kts") }?.toList().orEmpty().sortedBy { it.name }.map { it.toPath() }
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `corpus smoke-runs green and satisfies M2 exit criterion`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(22, fixtures.size, "Corpus must have 22 valid fixtures (17 as of v0.33.1; 0.36.0 release cycle added 19-isunix, 20-pwd-tmp, 21-milestone, 22-wait-until, 23-readfile — WU-LPR-104/06x receipts)")

        val appBin = AppBinSupport.discover()
        val failures = mutableListOf<String>()
        val controlRoot = java.nio.file.Files.createTempDirectory("compat-corpus-ctrl")
        val storePath = seedCorpusCredentialsStore(controlRoot)

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .apply {
                    environment()["PIPELINE_CREDENTIALS_STORE"] = storePath.toString()
                    environment()["PIPELINE_STORE_PASSPHRASE"] = corpusPassphrase
                }

            val process = pb.start()
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            val isBroken = brokenFixtures.contains(fixture.fileName.toString())

            if (isBroken) {
                if (exitCode == 0) {
                    failures.add("${fixture.fileName}: expected non-zero exit but got 0")
                }
            } else {
                // Other fixtures must exit 0
                if (exitCode != 0) {
                    val stderr = process.errorStream.bufferedReader().readText()
                    failures.add("${fixture.fileName}: exit $exitCode, stderr: $stderr")
                } else {
                    val events = JsonEventLog.decode(stdout)
                    if (events.isEmpty()) {
                        failures.add("${fixture.fileName}: no events produced")
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(), "Corpus must have zero failures: $failures")
    }

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    fun `each corpus fixture produces non-empty event stream`() {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(22, fixtures.size, "Corpus must have 22 valid fixtures (17 as of v0.33.1; 0.36.0 release cycle added 19-isunix, 20-pwd-tmp, 21-milestone, 22-wait-until, 23-readfile — WU-LPR-104/06x receipts)")
        val appBin = AppBinSupport.discover()
        val controlRoot = java.nio.file.Files.createTempDirectory("compat-corpus-ctrl")
        val storePath = seedCorpusCredentialsStore(controlRoot)

        fixtures.forEach { fixture ->
            val pb = ProcessBuilder(appBin.toString(), "run", fixture.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
                .apply {
                    environment()["PIPELINE_CREDENTIALS_STORE"] = storePath.toString()
                    environment()["PIPELINE_STORE_PASSPHRASE"] = corpusPassphrase
                }

            val process = pb.start()
            process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            val isBroken = brokenFixtures.contains(fixture.fileName.toString())

            if (isBroken) {
            } else {
                val events = JsonEventLog.decode(stdout)
                assertTrue(events.isNotEmpty(), "${fixture.fileName} must produce events")
            }
        }
    }
}
