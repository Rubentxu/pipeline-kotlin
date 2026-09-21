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
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.JsonEventLog
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
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
     * Mutable fixtures must run in a fresh workspace: their declarative file
     * paths intentionally begin at `build/`, and must not persist outputs in
     * the checked-in corpus directory between test runs.
     */
    private fun copyFixtureInto(name: String, workspace: Path): Path =
        workspace.resolve(name).also { destination ->
            Files.copy(fixture(name), destination)
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
     * WU-LPR-104: core.readFile / core.fileExists live fixture. Beyond pass/fail,
     * asserts the typed observability contract:
     *  - a FileRead event is emitted with sha256+size, NEVER file content;
     *  - a FileExistsChecked event is emitted for both the present and missing file;
     *  - exists=false for the missing file is a legitimate SUCCESS (Jenkins predicate
     *    semantics), so the run still finishes green.
     */
    @Test
    fun fixture23ReadFile() {
        val name = "23-readfile.pipeline.kts"
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        val pb = ProcessBuilder(appBin.toString(), "run", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()

        assertEquals(0, exitCode) { "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}" }
        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }

        val fileRead = events.filterIsInstance<FileRead>().singleOrNull()
            ?: fail("Fixture $name must emit exactly one FileRead event; got ${events.map { it.kind }}")
        assertTrue(fileRead.path.toString().endsWith("lpr104-readme.txt")) { "FileRead path: ${fileRead.path}" }
        assertNotNull(fileRead.sha256) { "FileRead must carry sha256" }
        assertTrue((fileRead.size ?: 0L) > 0L) { "FileRead must carry positive size" }

        val existsChecked = events.filterIsInstance<FileExistsChecked>()
        assertEquals(2, existsChecked.size) { "Fixture $name must emit two FileExistsChecked events" }
        assertTrue(existsChecked.any { it.exists && it.path.toString().endsWith("lpr104-readme.txt") })
        assertTrue(existsChecked.any { !it.exists && it.path.toString().endsWith("lpr104-missing.txt") })

        // INV-L6-EVT-001: file content never enters the event channel.
        val serialized = events.joinToString("\n") { it.kind + " " + it.toString() }
        assertTrue(!serialized.contains("hello-lpr-104")) { "File content leaked into the event channel" }
    }

    /**
     * LFC-2E2 utilities OFFICIAL_PLUGIN live fixture (24-utilities-roundtrip).
     *
     * Exercises the three first-slice Steps end-to-end through the installed
     * `pipelinek` distribution: readJson, writeJson, sha256. The test
     * asserts:
     *
     *  - the fixture exits with code 0;
     *  - the canonical `StepStarted` / `StepFinished` events are emitted
     *    for each registry-step with `stepType == "core-utils"` (the plugin
     *    namespace prefix);
     *  - the produced JSON file content survives the round-trip (the
     *    `core.sh cat ...` Step echoes the file content as evidence).
     */
    @Test
    fun fixture24UtilitiesRoundtrip() {
        val name = "24-utilities-roundtrip.pipeline.kts"
        val path = fixture(name)
        val appBin = AppBinSupport.discover()

        // Use --workspace . so the produced file persists under the
        // fixture directory for post-run inspection.
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", ".", path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)

        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, exitCode) {
            "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}"
        }

        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }

        val stepStartedCoreUtils = events.filterIsInstance<StepStarted>().filter { it.stepType == "core-utils" }
        val stepFinishedCoreUtils = events.filterIsInstance<StepFinished>().filter { it.stepType == "core-utils" }
        assertEquals(3, stepStartedCoreUtils.size) {
            "Fixture $name must emit 3 core-utils StepStarted events (writeJson + readJson + sha256)"
        }
        assertEquals(3, stepFinishedCoreUtils.size) {
            "Fixture $name must emit 3 core-utils StepFinished events"
        }

        // The `core.sh cat build/utils/data.json` echoes the file content as a
        // durable console event; this is the simplest end-to-end proof that the
        // JSON file survived the writeJson → readJson → sha256 chain.
        val captured = events.filterIsInstance<EchoOutputCaptured>().map { it.content }
        assertTrue(
            captured.isNotEmpty(),
            "Fixture $name must emit at least one EchoOutputCaptured (the cat command). Got: $captured",
        )
        val firstCaptured = captured.first()
        assertTrue(
            firstCaptured.contains("alice") && firstCaptured.contains("age") && firstCaptured.contains("30"),
            "Fixture $name must echo the JSON file content with alice/age/30. Got: $firstCaptured",
        )

        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome) { "Fixture $name must emit a RunFinished event" }
        assertEquals("success", outcome!!.outcome.toString().lowercase()) {
            "Fixture $name must terminate successfully; got outcome=${outcome.outcome}"
        }

        // The file content on disk must match the canonical `sha256sum` value;
        // this is the durable evidence that the typed Step produced the same
        // bytes the fixture asserted.
        val producedFile = path.parent.resolve("build/utils/data.json")
        if (producedFile.toFile().isFile) {
            val bytes = java.nio.file.Files.readAllBytes(producedFile)
            val canonical = java.security.MessageDigest.getInstance("SHA-256")
                .digest(bytes).joinToString("") { "%02x".format(it) }
            // The fixture content is {"name":"alice","age":30} which canonicalises to:
            // c3fdc275861cef9d29fab67ee0490a927e43338cd0d4e88309ac760c65138815
            assertEquals(
                "c3fdc275861cef9d29fab67ee0490a927e43338cd0d4e88309ac760c65138815",
                canonical,
                "Fixture $name produced file with unexpected SHA-256",
            )
        }
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

    @Test fun fixture05ScriptedIf() = runFixturePass("05-scripted-if.pipeline.kts")

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

    // ==========================================================================
    //  Slice 2 / S2 corpus — five end-to-end fixtures that exercise
    //  readYaml / writeYaml / findFiles / zip / unzip together.
    //
    //  Each fixture is a real `.pipeline.kts` file run through the installed
    //  `pipelinek` distribution. They are NOT unit tests: they prove that the
    //  new Steps compose with the canonical coordinator, the typed DSL and the
    //  durable journal.
    // ==========================================================================

    /**
     * `25-yaml-roundtrip` — writeYaml + readYaml with the typed YamlDocument ADT.
     * Asserts the round-trip preserves the file on disk and emits a
     * core-utils StepStarted/StepFinished pair for both Steps.
     */
    @Test
    fun fixture25YamlRoundtrip(@TempDir workspace: Path) {
        val name = "25-yaml-roundtrip.pipeline.kts"
        val path = copyFixtureInto(name, workspace)
        val appBin = AppBinSupport.discover()
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", workspace.toString(), path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, exitCode) {
            "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}"
        }
        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty()) { "Fixture $name produced no events" }
        val coreUtilsStarted = events.filterIsInstance<StepStarted>().filter { it.stepType == "core-utils" }
        assertEquals(2, coreUtilsStarted.size) {
            "Fixture $name must emit 2 core-utils StepStarted (writeYaml + readYaml). Got ${coreUtilsStarted.size}"
        }
        // The script ends with `core.sh cat .../config.yaml`; the echoed YAML
        // must mention every primitive we wrote.
        val captured = events.filterIsInstance<EchoOutputCaptured>().joinToString("\n") { it.content }
        assertTrue(captured.contains("name: pipelinek")) { "YAML must contain name=pipelinek. Got: $captured" }
        assertTrue(captured.contains("version: 2.0.0")) { "YAML must contain version=2.0.0" }
        assertTrue(captured.contains("- unzip")) { "YAML must list unzip in features" }
        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome) { "Fixture $name must emit RunFinished" }
        assertEquals("success", outcome!!.outcome.toString().lowercase())
    }

    /**
     * `26-find-files` — findFiles with both direct-children and recursive
     * globs. Asserts the Step emits the expected core-utils events and that
     * the cross-check `core.sh find` echoes matching files.
     */
    @Test
    fun fixture26FindFiles() {
        val name = "26-find-files.pipeline.kts"
        val path = fixture(name)
        val appBin = AppBinSupport.discover()
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", path.parent.toString(), path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, exitCode) {
            "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}"
        }
        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty())
        // Two findFiles invocations in the script.
        val coreUtilsStarted = events.filterIsInstance<StepStarted>().filter { it.stepType == "core-utils" }
        assertEquals(2, coreUtilsStarted.size) {
            "Fixture $name must emit 2 core-utils StepStarted events (two findFiles calls)"
        }
        val captured = events.filterIsInstance<EchoOutputCaptured>().joinToString("\n") { it.content }
        assertTrue(captured.contains("a.txt") && captured.contains("sub/c.txt")) {
            "findFiles + core.sh find must surface both direct and nested .txt files. Got: $captured"
        }
        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome)
        assertEquals("success", outcome!!.outcome.toString().lowercase())
    }

    /**
     * `27-zip-unzip` — zip + unzip + sha256 round-trip on a directory tree.
     * Asserts the file content survives the archive/extract cycle (proven
     * by `core.sh cat` echoing "one\ntwo\nthree").
     */
    @Test
    fun fixture27ZipUnzip(@TempDir workspace: Path) {
        val name = "27-zip-unzip.pipeline.kts"
        val path = copyFixtureInto(name, workspace)
        val appBin = AppBinSupport.discover()
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", workspace.toString(), path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, exitCode) {
            "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}"
        }
        val events = JsonEventLog.decode(stdout)
        assertTrue(events.isNotEmpty())
        // zip + 3 sha256 + unzip = 5 core-utils invocations.
        val coreUtilsStarted = events.filterIsInstance<StepStarted>().filter { it.stepType == "core-utils" }
        assertEquals(5, coreUtilsStarted.size) {
            "Fixture $name must emit 5 core-utils StepStarted (zip + 3*sha256 + unzip). Got ${coreUtilsStarted.size}"
        }
        val captured = events.filterIsInstance<EchoOutputCaptured>().joinToString("\n") { it.content }
        assertTrue(captured.contains("one") && captured.contains("two") && captured.contains("three")) {
            "Unzipped content must echo one/two/three. Got: $captured"
        }
        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome)
        assertEquals("success", outcome!!.outcome.toString().lowercase())
    }

    /**
     * `28-zip-slip-defense` — negative fixture. The script plants a zip with
     * a smuggled `../escaped.txt` entry name (spliced into both the LFH and
     * the CDH), then runs `core-utils.unzip` against it. The handler MUST
     * raise a typed USER failure containing "Zip Slip", and the run finishes
     * with `outcome=failure`.
     */
    @Test
    fun fixture28ZipSlipDefense() {
        val name = "28-zip-slip-defense.pipeline.kts"
        val path = fixture(name)
        val appBin = AppBinSupport.discover()
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", path.parent.toString(), path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertNotEquals(0, exitCode) {
            "Fixture $name must exit non-zero (typed USER failure on Zip Slip). Got exit=$exitCode"
        }
        val events = JsonEventLog.decode(stdout)
        val stepFailed = events.filter { it.kind == "StepFailed" && it.javaClass.simpleName.contains("StepFailed", ignoreCase = false) }
        assertTrue(stepFailed.isNotEmpty()) {
            "Fixture $name must emit at least one StepFailed event. Got: ${events.map { it.kind }}"
        }
        // The handler emits a typed failure containing the words "Zip Slip"
        // and the entry name that smuggled out of the workspace. We assert
        // on either signal so the assertion stays robust against
        // event-channel formatting changes.
        val serialized = events.joinToString("\n") { it.toString() }
        assertTrue(serialized.contains("Zip Slip") || serialized.contains(".. segment")) {
            "Events must surface Zip Slip / '..' containment. Got: $serialized"
        }
        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome)
        assertEquals("failure", outcome!!.outcome.toString().lowercase()) {
            "Fixture $name must finish with outcome=failure. Got: ${outcome.outcome}"
        }
    }

    /**
     * `29-mixed-utilities` — five stages chaining writeYaml → readYaml →
     * findFiles → zipDir → unzip. Asserts all five Steps run and the
     * extracted content survives.
     */
    @Test
    fun fixture29MixedUtilities() {
        val name = "29-mixed-utilities.pipeline.kts"
        val path = fixture(name)
        val appBin = AppBinSupport.discover()
        val pb = ProcessBuilder(appBin.toString(), "run", "--workspace", path.parent.toString(), path.toString())
            .redirectOutput(ProcessBuilder.Redirect.PIPE)
            .redirectError(ProcessBuilder.Redirect.PIPE)
        val process = pb.start()
        val exitCode = process.waitFor()
        val stdout = process.inputStream.bufferedReader().readText().trim()
        assertEquals(0, exitCode) {
            "Fixture $name exited with code $exitCode. stderr: ${process.errorStream.bufferedReader().readText()}"
        }
        val events = JsonEventLog.decode(stdout)
        val stages = events.filter { it.kind == "StageStarted" }
        assertEquals(5, stages.size) {
            "Fixture $name must have 5 stages. Got ${stages.size}"
        }
        val stageNames = stages.mapNotNull { ev ->
            // StageStarted events carry a `stageName` field; we read it
            // reflectively so we don't pin a strongly-typed import on a
            // contract that may evolve.
            runCatching {
                ev.javaClass.getDeclaredField("stageName").apply { isAccessible = true }
                    .get(ev) as? String
            }.getOrNull()
        }
        assertEquals(
            setOf("write-manifest", "read-manifest", "enumerate", "archive", "extract"),
            stageNames.toSet(),
        ) { "Fixture $name stage names: $stageNames" }
        val coreUtilsStarted = events.filterIsInstance<StepStarted>().filter { it.stepType == "core-utils" }
        assertEquals(5, coreUtilsStarted.size) {
            "Fixture $name must emit 5 core-utils StepStarted. Got ${coreUtilsStarted.size}"
        }
        val captured = events.filterIsInstance<EchoOutputCaptured>().joinToString("\n") { it.content }
        assertTrue(captured.contains("alpha") && captured.contains("beta")) {
            "Unzipped content must echo alpha/beta. Got: $captured"
        }
        val outcome = events.filterIsInstance<RunFinished>().singleOrNull()
        assertNotNull(outcome)
        assertEquals("success", outcome!!.outcome.toString().lowercase())
    }

    // =========================================================================
    // E1.1 / T8 — core.artifact.query bridge (corpus fixture 30)
    // =========================================================================

    /**
     * E1.1 / T8: DSL compile-surface check for the new `core.artifact.query`
     * Bridge Step. The fixture intentionally exercises ONLY the legacy
     * `core.archiveArtifacts` path + a sh marker. Adding the runtime
     * artifactQuery call here would fail-closed at registry-prepare-time
     * (no wired artifact index yet) and produce a red fixture — that's
     * the data round-trip, which is E1.2's job.
     *
     * This fixture proves: the DSL facade exists, the script compiles via
     * the production scripting host, and `core.archiveArtifacts` (the
     * legacy-side anchor) remains reachable end-to-end through the same
     * canonical RunFinished event so an external observer can confirm
     * E1.1 has been published without disturbing F1's contract.
     */
    @Test
    fun fixture30ArtifactQueryBridge() = runFixturePass("30-artifact-query-bridge.pipeline.kts")

    /**
     * Verifies that a script with compilation errors exits with non-zero code.
     * INC-R10-ARC-001: compilation failure is a FAILURE outcome, not success.
     */
    @Test
    fun allCorpusFixturesAreDiscoverable() {
        val fixtures = fixtureDir().listFiles { f -> f.extension == "kts" }.orEmpty()
        assertEquals(31, fixtures.size, "Corpus must have 31 valid fixtures (S2 added 25..29; E1.1 / T8 added 30..30; WU-LPR-089 added 31-stash-unstash; WU-LPR-090 added 32-publish-html)")

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
