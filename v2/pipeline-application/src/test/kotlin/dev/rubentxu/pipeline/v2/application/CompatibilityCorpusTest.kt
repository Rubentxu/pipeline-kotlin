package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.credentials.local.LocalSecretStore
import dev.rubentxu.pipeline.v2.domain.CredentialsId
import dev.rubentxu.pipeline.v2.domain.credentials.Certificate
import dev.rubentxu.pipeline.v2.domain.credentials.CredentialScope
import dev.rubentxu.pipeline.v2.domain.credentials.SecretFile
import dev.rubentxu.pipeline.v2.domain.credentials.SecretText
import dev.rubentxu.pipeline.v2.domain.credentials.SshPrivateKey
import dev.rubentxu.pipeline.v2.domain.credentials.UsernameColonPassword
import dev.rubentxu.pipeline.v2.domain.credentials.UsernamePassword
import dev.rubentxu.pipeline.v2.domain.credentials.Zip
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.io.File
import java.nio.file.Path
import java.util.concurrent.TimeUnit

/**
 * Compatibility corpus smoke tests — ONE test method per fixture.
 *
 * Per-fixture granularity is deliberate (AGENTS.md test-efficiency rules):
 * every fixture is independently selectable via
 * `--tests 'CompatibilityCorpusTest.fixture11*'`, gets its own timing
 * attribution in the JUnit XML, and a change to one fixture no longer
 * forces re-running the other twelve.
 *
 * Fixtures are functional (exit code + parseable events), NOT
 * timing-sensitive — unlike the process-kill/resume UAT classes protected
 * by AGENTS.md rule 11. Any future parallelization must be a measured,
 * explicit decision with a recorded baseline.
 *
 * Fixtures use the public `StageScope.sh` options to preserve their declared
 * script-block semantics through compilation and execution.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
class CompatibilityCorpusTest {

    private fun fixtureDir(): File =
        generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
            .map { File(it, "v2/compatibility") }
            .firstOrNull { it.isDirectory }
            ?: error("Cannot locate v2/compatibility/ via directory walk from ${System.getProperty("user.dir")}")

    private fun fixture(name: String): Path =
        fixtureDir().resolve(name).toPath().also { p ->
            assertTrue(p.toFile().isFile) { "Corpus fixture not found: $name" }
        }

    /**
     * Fixtures that fail at runtime (exit non-zero).
     *
     * After v0.33.1 (corpus-closure cycle), fixtures 02, 10, 13, 14 now PASS:
     * - Fixture 02 (withEnv): now canonical with core.withEnv
     * - Fixture 10 (archiveArtifacts): now canonical with core.archiveArtifacts
     * - Fixture 13 (timestamps): now canonical with core.timestamps
     * - Fixture 14 (withCredentials): now canonical with core.withCredentials
     *
     * `load` step still quarantined under INC-024 — not exercised by these fixtures.
     */
    private val runtimeFailureFixtures: Set<String> = emptySet()

    /**
     * Fixtures classified HISTORICAL: they rely on the legacy in-process
     * compilation behavior (implicit StageScope receivers inside `script {}`)
     * that the installed binary's compiler rejects with a typed compile error
     * (WU-LPR-103). The SUPPORTED surface per WU_LPR_032 admission is
     * `script { line("...") }`; raw Kotlin control flow with `echo()` calls
     * inside `script {}` is not admitted. Characterized: exit 1, compile
     * diagnostic "cannot be called in this context with an implicit receiver".
     */
    private val historicalCompileFailureFixtures: Set<String> =
        setOf("05-scripted-if.pipeline.kts")

    /**
     * Run a fixture that is expected to succeed (exit 0).
     */
    private fun runFixturePass(name: String) {
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        assertEquals(0, exitCode) { "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}" }
        assertTrue(stdout.startsWith("[")) { "Fixture $name stdout must start with '['" }
        assertTrue(stdout.endsWith("]")) { "Fixture $name stdout must end with ']'" }

        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }
    }

    /**
     * Run a fixture classified HISTORICAL that fails at COMPILE time with a
     * typed diagnostic (not silently and not with an unrelated crash).
     */
    private fun runFixtureCompileFail(name: String) {
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        assertEquals(1, exitCode) { "Fixture $name should fail compile with exit 1 but got $exitCode" }
        val events = JsonEventLog.decode(stdout)
        assertTrue(
            events.any { it.javaClass.simpleName == "CompilationFinished" },
            "Fixture $name must emit CompilationFinished",
        )
        assertTrue(
            stdout.contains("ERROR") || stdout.contains("error"),
            "Fixture $name must surface a typed compile diagnostic",
        )
    }

    /**
     * Run a fixture that is expected to fail (exit non-zero).
     * Used for fixtures with known runtime failures.
     */
    private fun runFixtureFail(name: String) {
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stderr = process.errorStream.bufferedReader().readText().trim()

        assertNotEquals(0, exitCode) { "Fixture $name should exit non-zero but got 0. stderr: $stderr" }
    }

    @Test fun fixture01Basic() = runFixturePass("01-basic.pipeline.kts")

    @Test fun fixture02Environment() = runFixturePass("02-environment.pipeline.kts")

    @Test fun fixture03Stages() = runFixturePass("03-stages.pipeline.kts")

    @Test fun fixture04Sh() = runFixturePass("04-sh.pipeline.kts")

    @Test fun fixture05ScriptedIf() = runFixtureCompileFail("05-scripted-if.pipeline.kts")

    @Test fun fixture06Loop() = runFixturePass("06-loop.pipeline.kts")

    @Test fun fixture08WithEnv() = runFixturePass("08-withEnv-pipeline.pipeline.kts")

    @Test fun fixture09ShThenEcho() = runFixturePass("09-sh-then-echo.pipeline.kts")

    // S2-B10 / G4 (2026-09-13): flipped from runFixtureFail. The legacy
    // CanonicalArchiveArtifactsNodeDispatcher anchored its glob against absolute paths
    // (workspaceRoot = controlDirRoot.resolve("workspace")) and therefore could never match,
    // so this fixture failed end-to-end with
    // `ArtifactArchiveFailed: No files matched glob pattern 'build/libs/*.jar'`.
    // The REGISTRY_PRIMARY flip routes core.archiveArtifacts through CoreArchiveArtifactsStep,
    // whose adapter uses the certified AntStyleGlob engine (frozen delta D1), so the fixture
    // now genuinely passes. Evidence of the pre-flip defect:
    // docs/v2/07-uat/evidence/s2-b10-g2/fixture10-legacy-glob-defect.json
    @Test fun fixture10SmokeE2E() = runFixturePass("10-smoke-e2e.pipeline.kts")

    @Test fun fixture11WorkflowControl() = runFixturePass("11-workflow-control.pipeline.kts")

    @Test fun fixture12ErrorHandling() = runFixturePass("12-error-handling.pipeline.kts")

    @Test fun fixture13WorkspaceHelpers() = runFixturePass("13-workspace-helpers.pipeline.kts")

    @Test fun fixture14CredentialsBindings() = runFixturePassWithCredentialsStore("14-credentials-bindings.pipeline.kts")

    /**
     * Run a fixture that is expected to succeed (exit 0) and needs a seeded
     * local credentials store (WU-LPR-103: fixture 14 binds 7 credential
     * kinds; without a store the credential lease fails closed with the typed
     * StoreUnavailable INFRASTRUCTURE failure). The store is created in a
     * temp dir with the same LocalSecretStore the installed binary loads via
     * PIPELINE_CREDENTIALS_STORE / PIPELINE_STORE_PASSPHRASE.
     */
    private fun runFixturePassWithCredentialsStore(name: String) {
        val storeDir = java.nio.file.Files.createTempDirectory("corpus-cred-store")
        val storePath = storeDir.resolve("credentials.bin")
        val passphrase = "corpus-passphrase-103"
        LocalSecretStore(storePath, passphrase.toCharArray()).use { store ->
            store.add(CredentialsId("string-creds"), SecretText(CredentialsId("string-creds"), CredentialScope.GLOBAL, "string-secret-value".toByteArray()))
            store.add(CredentialsId("userpass-creds"), UsernamePassword(CredentialsId("userpass-creds"), CredentialScope.GLOBAL, "dbuser", "dbpass".toByteArray()))
            store.add(CredentialsId("ssh-creds"), SshPrivateKey(CredentialsId("ssh-creds"), CredentialScope.GLOBAL, "git", "KEYDATA".toByteArray()))
            store.add(CredentialsId("file-creds"), SecretFile(CredentialsId("file-creds"), CredentialScope.GLOBAL, "file-secret-content".toByteArray(), "secret.txt"))
            store.add(CredentialsId("cert-creds"), Certificate(CredentialsId("cert-creds"), CredentialScope.GLOBAL, "keystorebytes".toByteArray()))
            store.add(CredentialsId("zip-creds"), Zip(CredentialsId("zip-creds"), CredentialScope.GLOBAL, mapOf("entry.txt" to "zipdata".toByteArray())))
            store.add(CredentialsId("ucp-creds"), UsernameColonPassword(CredentialsId("ucp-creds"), CredentialScope.GLOBAL, "u", "p".toByteArray()))
        }
        try {
            val path = fixture(name)
            val appBin = AppBinSupport.discover()
            println("DEBUG-LPR103 store=$storePath exists=${storePath.toFile().exists()} size=${if (storePath.toFile().exists()) java.nio.file.Files.size(storePath) else -1}")

            val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
                .redirectOutput(ProcessBuilder.Redirect.PIPE)
                .redirectError(ProcessBuilder.Redirect.PIPE)
            pb.environment()["PIPELINE_CREDENTIALS_STORE"] = storePath.toString()
            pb.environment()["PIPELINE_STORE_PASSPHRASE"] = passphrase

            val process = pb.start()
            val exitCode = process.waitFor()
            val stdout = process.inputStream.bufferedReader().readText().trim()

            assertEquals(0, exitCode) { "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()} stdout tail: ${stdout.takeLast(1200)}" }
            val events = JsonEventLog.decode(stdout)
            assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }
        } finally {
            storePath.toFile().delete()
            storeDir.toFile().delete()
        }
    }

    /**
     * WU-LPR-103 forever-fitness: fixture14 WITHOUT a credentials store must
     * fail closed with a TYPED StepFailed event (StoreUnavailable), never
     * silently (StageStarted -> RunFinished(failure) with no step event).
     */
    @Test
    fun fixture14WithoutStoreFailsTyped() {
        val path = fixture("14-credentials-bindings.pipeline.kts")
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        assertEquals(1, exitCode) { "Fixture 14 without store should exit 1 but got $exitCode" }
        val events = JsonEventLog.decode(stdout)
        val stepFailed = events.filter { it.kind == "StepFailed" }
        assertTrue(stepFailed.isNotEmpty()) {
            "Credential-lease rejection must emit a typed StepFailed event. Events: ${events.map { it.kind }}"
        }
        assertTrue(
            stepFailed.any { (it as? dev.rubentxu.pipeline.v2.events.StepFailed)?.message?.contains("credential") == true },
            "StepFailed must carry the credential-scope failure message",
        )
    }

    @Test fun fixture15Error() = runFixtureFail("15-error.pipeline.kts")

    @Test fun fixture16Sleep() = runFixturePass("16-sleep.pipeline.kts")

    @Test fun fixture17WriteFile() = runFixturePass("17-writeFile.pipeline.kts")

    @Test fun fixture18CleanWs() = runFixturePass("18-cleanWs.pipeline.kts")

    @Test fun fixture19IsUnix() = runFixturePass("19-isunix.pipeline.kts")

    @Test fun fixture20PwdTmp() = runFixturePass("20-pwd-tmp.pipeline.kts")

    /**
     * WU-G5R.6: Verifies that the structural waitUntil block (G5R.4 + G5R.5)
     * produces a correct durable trace via the canonical dispatch path.
     * The fixture creates a marker file, waits until it exists (succeeds immediately
     * on first poll), then removes the marker.
     */
    @Test
    fun fixture22WaitUntil() = runFixturePass("22-wait-until.pipeline.kts")

    /**
     * Verifies that a script with compilation errors exits with non-zero code.
     * INC-R10-ARC-001: compilation failure is a FAILURE outcome, not success.
     */
    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = fixtureDir().listFiles { f -> f.extension == "kts" }.orEmpty()
        assertEquals(21, fixtures.size, "Corpus must have 21 valid fixtures (WU-G5R6 added 22-wait-until; 07 and 99 moved to broken/)")

        val names = fixtures.map { it.name }.toSet()
        assertTrue(names.contains("01-basic.pipeline.kts"))
        assertTrue(names.contains("02-environment.pipeline.kts"))
        assertTrue(names.contains("03-stages.pipeline.kts"))
        assertTrue(names.contains("04-sh.pipeline.kts"))
        assertTrue(names.contains("05-scripted-if.pipeline.kts"))
        assertTrue(names.contains("06-loop.pipeline.kts"))
        assertTrue(names.contains("08-withEnv-pipeline.pipeline.kts"))
        assertTrue(names.contains("09-sh-then-echo.pipeline.kts"))
        assertTrue(names.contains("10-smoke-e2e.pipeline.kts"))
        assertTrue(names.contains("11-workflow-control.pipeline.kts"))
        assertTrue(names.contains("12-error-handling.pipeline.kts"))
        assertTrue(names.contains("13-workspace-helpers.pipeline.kts"))
        assertTrue(names.contains("14-credentials-bindings.pipeline.kts"))
        assertTrue(names.contains("15-error.pipeline.kts"))
        assertTrue(names.contains("16-sleep.pipeline.kts"))
        assertTrue(names.contains("17-writeFile.pipeline.kts"))
        assertTrue(names.contains("18-cleanWs.pipeline.kts"))
        assertTrue(names.contains("19-isunix.pipeline.kts"))
        assertTrue(names.contains("20-pwd-tmp.pipeline.kts"))
    }
}
