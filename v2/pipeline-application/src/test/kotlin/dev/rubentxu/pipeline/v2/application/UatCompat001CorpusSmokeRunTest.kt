package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.support.Subprocess
import dev.rubentxu.pipeline.v2.application.support.requireExited
import dev.rubentxu.pipeline.v2.application.support.AppBinSupport
import dev.rubentxu.pipeline.v2.events.durable.JsonEventLog
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Tag
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.nio.file.Files
import java.nio.file.Path
import java.util.concurrent.TimeUnit
import dev.rubentxu.pipeline.v2.application.support.CliRun
import dev.rubentxu.pipeline.v2.application.support.OwnedSubprocess
import java.time.Duration

/**
 * UAT-COMPAT-001: Compatibility corpus smoke-run.
 * Verifies that all corpus fixtures compile and run successfully.
 * Closes E2-06 + M2 exit criterion.
 *
 * Each fixture exercises the public compatibility DSL and produces events.
 *
 * S0-C: the class-level budget was `@Timeout(120)`, which is what governs the
 * per-fixture sweep below. 7719b273 raised two individual methods from 180s to
 * 600s but left the class default at 120s, so the sweep still inherited the
 * small budget and timed out under full-suite contention.
 *
 * Evidence this is a budget problem and not a defect: the same class run in
 * isolation is 2/2 green in 5m39s (5:39 > 2:00), while under `check` it aborted
 * with TimeoutException and no assertion failure. The sweep forks a pipelinek
 * process per corpus fixture, so its wall time scales with fixture count and
 * with whatever else the suite is doing concurrently.
 *
 * 600 matches the per-method budget 7719b273 already established for these
 * sweeps, so the class default now agrees with the methods it governs.
 */
@Timeout(value = 600, unit = TimeUnit.SECONDS)
@Tag("release-scale")
class UatCompat001CorpusSmokeRunTest {

    private companion object {
        /**
         * S6-PRE: the SUBPROCESS's own contract, separate from the `@Timeout(600)` on the methods.
         *
         * The sweep runs 31 sequential CLI spawns in ~180 s on an idle box, so 120 s PER FIXTURE is
         * a wide margin: it catches a wedged CLI and cannot be tripped by a loaded machine. The two
         * budgets must stay distinct — a class watchdog that fires first reports the wrong thing
         * and, because it does not own the process, leaves the child alive.
         */
        val FIXTURE_DEADLINE: Duration = Duration.ofSeconds(120)
    }

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
        "28-zip-slip-defense.pipeline.kts", // CVE-2023-32981 containment proof must raise a typed failure
    )

    /**
     * WU-LPR-071 (CR corpus closure): fixture 14 exercises withCredentials with seven
     * binding kinds, which requires a provisioned credentials store. The corpus runner
     * seeds an ephemeral store once and exports the env contract
     * (PIPELINE_CREDENTIALS_STORE / PIPELINE_STORE_PASSPHRASE) to every fixture process.
     */
    private val corpusPassphrase = "corpus-passphrase-0.36.0"

    /**
     * S6-PRE: the SUBPROCESS's own contract, separate from the `@Timeout(600)` on the methods.
     *
     * The sweep takes ~180 s for 31 sequential CLI spawns on an idle box, so 120 s PER FIXTURE is a
     * wide margin: it catches a wedged CLI and cannot be tripped by a loaded machine. The two
     * budgets must stay distinct — a class watchdog that fires first reports the wrong thing and,
     * because it does not own the process, leaves the child alive.
     */
    private fun sweepFixture(
        fixture: Path,
        workspace: Path,
        storePath: Path,
    ): CliRun.Completed {
        val appBin = AppBinSupport.discover()
        val name = fixture.fileName.toString()
        // The fixture is STAGED into the workspace, which is part of the contract and not
        // incidental: the corpus was written against a run whose script lives in the attached
        // directory, and the very first migration of this sweep dropped the staging call and
        // silently changed which file was being run.
        val staged = stageFixture(name, workspace)
        val command = if (fixturesWithIsolatedWorkspace.contains(name)) {
            listOf(appBin.toString(), "run", "--format", "json", "--isolated", staged.toString())
        } else {
            listOf(appBin.toString(), "run", "--format", "json", "--workspace", workspace.toString(), staged.toString())
        }

        // S6-PRE: this used to `waitFor()` and only THEN read stdout, with stderr read lazily
        // inside a failure branch. `12-error-handling` emits more than 64 KiB through
        // Main.kt:431, so the pipe filled, the CLI's `main` blocked in writeBytes, and this sweep
        // hung until @Timeout fired — 600 s per fixture, and it left the child running.
        val result = OwnedSubprocess.run(
            command = command,
            timeout = FIXTURE_DEADLINE,
            environment = mapOf(
                "PIPELINE_CREDENTIALS_STORE" to storePath.toString(),
                "PIPELINE_STORE_PASSPHRASE" to corpusPassphrase,
            ),
        )

        assertTrue(result is CliRun.Completed) {
            "Fixture $name did not finish within ${FIXTURE_DEADLINE.seconds}s; pid=" +
                "${(result as? CliRun.TimedOut)?.diagnostics?.pid}. That is an ENVIRONMENT signal, " +
                "not a verdict about the fixture: re-run it alone before reading it as a defect."
        }
        return result as CliRun.Completed
    }

    /**
     * Fixtures that must run in a PipelineK-managed scratch rather than in the
     * attached staging directory.
     *
     * The corpus is not uniform and the workspace mode is part of each fixture's
     * contract. `CompatibilityCorpusTest` already encodes the same two modes per
     * fixture; this set mirrors it for the smoke runner.
     *
     * RP034-Id, first failure: fixture 10 does `git clone /tmp/smoke-repo .` and
     * git refuses a non-empty destination. The staging directory already holds
     * the copied fixture, so the attached mode cannot host it.
     *
     * RP034-Id, second failure: fixture 11 calls bare `deleteDir()`. Since the
     * ADR-0102 guard became live, that is correctly refused on an attached root
     * (`--workspace <dir>` means USER-owned). A PipelineK-owned scratch keeps the
     * Step's wipe contract intact.
     *
     * RP034-Id, third failure — and the reason this is NOT "just run everything
     * with --isolated": fixture 29 writes `build/utils/mix/manifest.yaml` in one
     * stage and reads it in four later stages. Under `--isolated` each stage
     * gets its own scratch, so `readYaml` cannot see what `writeYaml` produced.
     * Cross-stage workspace continuity is Jenkins semantics and is exactly what
     * an ATTACHED root provides; `Managed` deliberately does not. Fixtures with
     * that shape must keep the shared attached workspace.
     */
    private val fixturesWithIsolatedWorkspace: Set<String> = setOf(
        "10-smoke-e2e.pipeline.kts",
        "11-workflow-control.pipeline.kts",
    )

    /**
     * Mutable fixtures (zip / unzip / archiveArtifacts / mixed / findFiles / etc.)
     * persist outputs under their declarative `build/` paths. Running them in the
     * checked-in corpus directory would reuse stale outputs from a previous run
     * or contaminate the corpus directory itself, so every fixture is copied
     * into a JUnit temporary directory before the installed binary is invoked
     * (the WU-LPR-075 pattern). For most fixtures that staging directory is then
     * passed as `--workspace`; see [fixturesWithIsolatedWorkspace] for the ones
     * that must not use it.
     */
    private fun stageFixture(name: String, workspace: Path): Path =
        workspace.resolve(name).also { destination ->
            Files.copy(discoverFixtures().first { it.fileName.toString() == name }, destination)
        }

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
    // C13: the budget was 180s for a sweep that OBSERVED needs ~170-175s of
    // real work (30 fixtures, each spawning the installed CLI). The baseline
    // `a1441573` completed this suite in 337.9s for 2 tests with 0 failures --
    // i.e. it was already passing by under 2% of the per-test budget, with no
    // margin. C12's VCS-marker interlock added ~13s of legitimate extra work
    // and tipped it over. The defect is the budget, not the code: a timeout
    // that only passes when the machine is fast enough is not a gate.
    // 600s is ~3x the observed requirement, which is a real safety factor
    // rather than a race. Both sweeps are 30 sequential CLI spawns.
    @Timeout(value = 600, unit = TimeUnit.SECONDS)
    fun `corpus smoke-runs green and satisfies M2 exit criterion`(@TempDir workspace: Path) {
        AppBinSupport.discover()

        val fixtures = discoverFixtures()
        assertEquals(31, fixtures.size, "Corpus must have 31 valid fixtures (WU-LPR-076 keeps the count in lock-step with CompatibilityCorpusTest; WU-LPR-089 added 31-stash-unstash; WU-LPR-090 added 32-publish-html)")

        val appBin = AppBinSupport.discover()
        val failures = mutableListOf<String>()
        val controlRoot = java.nio.file.Files.createTempDirectory("compat-corpus-ctrl")
        val storePath = seedCorpusCredentialsStore(controlRoot)

        fixtures.forEach { fixture ->
            val runResult = sweepFixture(fixture, workspace, storePath)
            val name = fixture.fileName.toString()
            val exitCode = runResult.exitCode
            val stdout = runResult.stdout.trim()

            val isBroken = brokenFixtures.contains(name)

            if (isBroken) {
                if (exitCode == 0) {
                    failures.add("$name: expected non-zero exit but got 0")
                }
            } else {
                // Other fixtures must exit 0
                if (exitCode != 0) {
                    failures.add("$name: exit $exitCode, stderr: ${runResult.stderr}")
                } else {
                    val events = JsonEventLog.decode(stdout)
                    if (events.isEmpty()) {
                        failures.add("$name: no events produced")
                    }
                }
            }
        }

        assertTrue(failures.isEmpty(), "Corpus must have zero failures: $failures")
    }

    // S6-PRE.12: the second sweep `each corpus fixture produces non-empty event stream` was DELETED,
    // not weakened. It called this class's own sweepFixture over the same 31 fixtures, the same
    // staging, the same credential store and the same binary, and then asserted strictly less:
    // `events.isNotEmpty()` where the surviving test asserts the exit code FIRST and the event
    // count second. A run that fails this one always fails the survivor, and never the reverse, so
    // it could only ever fail when the real test had already failed. It cost 31 extra CLI spawns
    // (~193 s measured) and bought no coverage.
    //
    // It is deleted AFTER the harness fix and AFTER a green gate, never before: eliminating the
    // exercise that provoked the failure at the same time as fixing it would make it impossible to
    // tell a fixed harness from a silenced one.
}
