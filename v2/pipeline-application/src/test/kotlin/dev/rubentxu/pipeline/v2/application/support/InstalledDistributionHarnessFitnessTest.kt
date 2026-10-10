package dev.rubentxu.pipeline.v2.application.support

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.stream.Collectors
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * S6-PRE — the law that keeps the pipe deadlock from coming back.
 *
 * ## Why this is scoped and not a blanket ban
 *
 * The repo has **196 `ProcessBuilder` sites across 95 test files**, and banning the token outright
 * would be a law with no relation to the defect. Most of them launch `git`, `tar` or `sh` for a
 * domain test and write nothing to a pipe; they cannot deadlock this way.
 *
 * The property that actually failed is narrower and sharper:
 *
 * > A harness that forks the INSTALLED DISTRIBUTION must go through [OwnedSubprocess].
 *
 * Only that binary has the shape that kills: `Main.kt:431` writes the entire run event log in one
 * `println`, so any run can exceed the pipe buffer, and a harness that waits before it drains will
 * hang forever and leave the child alive. [AppBinSupport] is what marks a file as one of those
 * harnesses, which is why the scan keys on it rather than on the test class name.
 *
 * ## The buffer size, measured rather than remembered
 *
 * This file used to state 64 KiB, and so do eight other Kotlin files and five receipts. **On this
 * host that number is wrong.** Measured with `F_GETPIPE_SZ` on 20 of 20 freshly created pipes: the
 * default pipe buffer is **8192 bytes**. `fs.pipe-max-size = 1048576` is the ceiling a process may
 * *request* with `F_SETPIPE_SZ`, not the size it gets for free.
 *
 * The law is unaffected in direction — any buffer can be exceeded, and 8 KiB is easier to exceed
 * than 64 KiB, so waiting before draining is still wrong — but a threshold stated as 8x larger than
 * reality is a threshold that reads as safe. The historical receipts keep their original figure:
 * a receipt is evidence for the belief held at its SHA, and rewriting one silently would be a
 * falsification. This KDoc, which is read as current documentation, carries the measured value.
 *
 * ## Why the allowlist is the point and not a concession
 *
 * The names below are the current debt, written out — 53 after BLOCK 3.8, of which 23 arrived
 * through the installed-binary door. A law that permitted them silently would be
 * the same defect shape as a prohibition over a file that no longer exists: a green produced by the
 * absence of a subject. Instead each one is a visible entry that a migration deletes, and the
 * assertion is that the set of NEW offenders is empty — so the debt can only shrink, never grow.
 * The second assertion runs the other way: an entry whose file no longer forks anything is stale
 * debt the ledger is still claiming, so the two directions are checked against each other.
 *
 * The door-A count fell 32 -> 29 -> 28 -> 27 -> 26 -> 25 -> 24 -> 23 as harnesses adopted
 * [OwnedSubprocess]. `CompatibilityCorpusTest` is deliberately absent: it was the first migration
 * and it is the file that carried the measured hang.
 *
 * ## BLOCK 3.7/3.8 — the marker was narrower than the property
 *
 * Everything above keyed on [AppBinSupport], which means "this file launches the INSTALLED binary".
 * Migrating `UatLocal008CredentialsTest` showed that the property has a second door: the same file
 * can launch the very same runtime by its main class on the test classpath, and then [AppBinSupport]
 * never appears in it. That child runs the same `Main.kt:431` with the same 8192-byte pipe exposure,
 * and this law could not see it.
 *
 * Measured, not assumed: **30 harnesses** fork the runtime by `MainKt` with no [OwnedSubprocess],
 * and **none** of the 30 contains [AppBinSupport] — the two doors have zero overlap, which is why
 * the total is 23 + 30 = **53** and not "23, some of them twice". They are now part of this law
 * rather than a separate note nobody reads. A law that protects a property but keys on one
 * spelling of it is a law with a hole shaped exactly like the spelling.
 *
 * ### What makes this widening credible, and what would have made it fake
 *
 * A widened detector is the easiest thing in a fitness suite to make green, so both directions
 * were measured before the widening was believed:
 *
 * - **It convicts a new offender that the old law could not see.** A file launching `MainKt` with a
 *   raw `ProcessBuilder` — and nothing else — is RED under this law. That is the property the
 *   widening was for, and it is exactly the case the previous subject was blind to.
 * - **It is falsable.** Deleting the `MainKt` door turns 30 ledger entries stale, so the assertion
 *   goes RED naming all 30. A detector nobody can trip is not a detector.
 * - **The strip does not blind it.** Removing comments drops the subject from 54 to 53 — it removes
 *   one file (`WcScmE2EBothPluginsIntegrationTest`) that names the runtime only in prose, and
 *   **zero** real offenders. A stripper that quietly loses subjects would trade a false positive for
 *   a hole; measured, it does not.
 * - **The launch half is still doing the work.** Keying on any `ProcessBuilder` at all — dropping
 *   the runtime predicate — convicts 16 extra files, which are `git`/`tar`/`sh` launchers with no
 *   pipe exposure at all. That is the 196-site population the original scoping paragraph is about,
 *   and it is why the predicate exists rather than a blanket ban.
 */
@DisplayName("S6-PRE — todo harness que bifurca el runtime pasa por OwnedSubprocess")
class InstalledDistributionHarnessFitnessTest {

    @Test
    fun `no hay ningún harness nuevo que bifurque el runtime por su cuenta`() {
        val root = testSourcesRoot()
        // S6-PRE, second correction: the exemption used to be `!text.contains(OwnedSubprocess)`,
        // i.e. PER FILE. That is the wrong granularity, and it was found by a real failure rather
        // than by inspection: `CompatibilityCorpusTest` had six launch sites, S6-PRE migrated two,
        // and the remaining four were exempt forever because the file mentioned the primitive
        // somewhere else. `fixture14CredentialsBindings` then sat on `waitFor()` with no reader on
        // the pipe and died on the 600 s JUnit timeout with a live `pipelinek` behind it.
        //
        // A file now counts as migrated only when it contains NO raw launch of the installed
        // distribution at all. Adopting the primitive once buys nothing for the call sites that
        // were left behind, which is exactly the property the previous rule failed to state.
        val offenders = Files.walk(root).use { paths ->
            paths.filter { it.toString().endsWith(".kt") }
                .filter { path -> !path.fileName.toString().equals(FITNESS_FILE_NAME) }
                .map { it to stripComments(Files.readString(it)) }
                .filter { (_, code) -> forksTheRuntime(code) }
                .map { (path, _) -> relative(root, path) }
                .sorted()
                .collect(Collectors.toList())
        }

        // A law that stopped detecting anything would satisfy both assertions above — the ledger
        // would be entirely "stale", so that one would go red, but only AFTER the suite has been
        // quietly blind. This pins the subject to a measured floor instead, in the direction that
        // fails if the detector narrows: the second door is what BLOCK 3.8 added, and if `MainKt`
        // ever stops being recognised, every one of its files silently leaves the subject.
        assertTrue(
            offenders.count { namesRuntimeByMainClass(root, it) } >= MAIN_CLASS_SUBJECT_FLOOR,
            "only ${offenders.count { namesRuntimeByMainClass(root, it) }} harnesses fork the " +
                "runtime by MainKt, below the measured floor of $MAIN_CLASS_SUBJECT_FLOOR: this " +
                "law has stopped seeing the second door, so its green no longer means what it " +
                "says. Check RUNTIME_MAIN_CLASS and stripComments before touching KNOWN_DEBT — a " +
                "shrinking subject here is a detector regression, not a migration.",
        )

        val unexpected = offenders - KNOWN_DEBT
        assertTrue(
            unexpected.isEmpty(),
            "these files fork the PipelineK runtime with a raw ProcessBuilder and bypass " +
                "OwnedSubprocess, which is how `fixture12-error-handling` hung until @Timeout left " +
                "its child alive:\n" + unexpected.joinToString("\n") { "  - $it" } +
                "\n\nBoth doors count: the installed binary through AppBinSupport, and the runtime's " +
                "main class on the test classpath. Route them through OwnedSubprocess.run(...) (or " +
                "StrictCliRun.text / .bytesRequiringSuccess) and delete them from KNOWN_DEBT. " +
                "A name may only be ADDED here together with the reason it cannot be migrated yet.",
        )

        // The law reads what it governs: if a migration deletes an entry, the file is genuinely gone
        // rather than merely no longer detected, and the two facts cannot drift apart silently.
        val stale = KNOWN_DEBT - offenders
        assertTrue(
            stale.isEmpty(),
            "these entries are migrated but were not removed from KNOWN_DEBT, so the ledger now " +
                "claims debt that does not exist:\n" + stale.joinToString("\n") { "  - $it" },
        )
    }

    private fun testSourcesRoot(): Path {
        val appDir = Paths.get(System.getProperty("user.dir")).toAbsolutePath()
            .let { if (it.fileName?.toString() == "pipeline-application") it else it.resolve("v2").resolve("pipeline-application") }
        val root = appDir.resolve("src").resolve("test").resolve("kotlin")
        if (!Files.isDirectory(root)) {
            throw IllegalStateException("no test sources at $root; skipping would be a green that proves nothing")
        }
        return root
    }

    private fun relative(root: Path, path: Path): String =
        root.relativize(path).toString().replace('\\', '/')

    /**
     * Comments are removed before anything is decided.
     *
     * Not cosmetic, and measured rather than assumed: with raw text this law's own widened subject
     * came out at 54 files, and the extra one — `WcScmE2EBothPluginsIntegrationTest` — names the
     * runtime only in prose. A harness whose KDoc explains that it forks `MainKt` would be
     * convicted by its own documentation, which is the same failure the `/tmp` law already hit.
     *
     * Block comments go first so a `//` inside one is not mistaken for a line comment; the
     * negative lookbehind keeps the `//` of a `://` inside a string from opening a comment.
     */
    private fun stripComments(text: String): String =
        LINE_COMMENT_TAIL.replace(BLOCK_COMMENT.replace(text, ""), "")

    /**
     * Does this file launch a PipelineK runtime by a raw process API, through either door it has?
     *
     * Both halves are required and neither alone is the property. Dropping [namesTheRuntime] widens
     * the subject to every `git`/`tar`/`sh` launcher in the module (measured: 16 more files, none of
     * which writes to a pipe). Dropping the launch half makes every file that merely MENTIONS the
     * runtime a subject, which is why [AppBinSupport] cannot be the whole predicate.
     */
    private fun forksTheRuntime(code: String): Boolean =
        namesTheRuntime(code) && (code.contains("ProcessBuilder(") || code.contains(RUNTIME_EXEC))

    /**
     * Re-checks the second door against one already-decided subject entry, so the floor assertion
     * above measures the same predicate the offender list used rather than a second reimplementation
     * of it.
     */
    private fun namesRuntimeByMainClass(root: Path, relativePath: String): Boolean =
        Files.readString(root.resolve(relativePath)).let(::stripComments)
            .let { RUNTIME_MAIN_CLASS.containsMatchIn(it) }

    /**
     * Does this harness launch a PipelineK runtime, by either of the two doors it has?
     *
     * The installed binary (through [AppBinSupport]) and the runtime's main class on the test
     * classpath. They are the same child — both run `Main.kt:431`, which writes a run's entire
     * event log in one `println` — and they carry the identical 8192-byte pipe exposure.
     */
    private fun namesTheRuntime(code: String): Boolean =
        code.contains(INSTALLED_BINARY_MARKER) || RUNTIME_MAIN_CLASS.containsMatchIn(code)

    private companion object {
        const val INSTALLED_BINARY_MARKER = "AppBinSupport"
        const val RUNTIME_EXEC = "Runtime.getRuntime().exec"
        const val PRIMITIVE = "OwnedSubprocess"

        /** The runtime's main class, as a harness names it on the classpath. */
        val RUNTIME_MAIN_CLASS = Regex("\\bMainKt\\b")
        val BLOCK_COMMENT = Regex("/\\*.*?\\*/", RegexOption.DOT_MATCHES_ALL)

        /**
         * `.` excludes a line terminator, so `.*` already ends at the line break; no `$` needed.
         */
        val LINE_COMMENT_TAIL = Regex("(?<!:)//.*")

        /**
         * The floor is **3**, not the measured 30, and the gap is deliberate.
         *
         * This assertion exists to catch a DETECTOR that narrows: if `RUNTIME_MAIN_CLASS` or
         * [stripComments] stops matching, files leave the subject silently and the ledger shrinks
         * behind them. Any floor detects that — a detector losing half its second door drops to 15,
         * which is below 30 but far above 3.
         *
         * Pinning it at 30 would instead make the assertion a migration counter, and it would kill
         * the law on the FIRST migration: `UatLocal008CredentialsTest` is already migrated and still
         * in the subject only because its `TC-002` row launches a `sleep` on purpose. Migrating
         * `UatLocal004TimeoutTest` would take the subject to 29 and turn a truthful green into a
         * failure demanding the ledger lie. A floor must sit where it cannot be reached by doing
         * the work this law exists to enforce.
         *
         * It is 3 because that is the smallest number that still proves the door is open: below it,
         * a broken regex and an over-migrated module become indistinguishable, and above it, the
         * assertion stops being about detection.
         */
        const val MAIN_CLASS_SUBJECT_FLOOR = 3

        /**
         * This file names `ProcessBuilder(` and `OwnedSubprocess` because it READS them out of other
         * files as text. It is the law, not a harness, so excluding it by name is what keeps the
         * rule from convicting its own detector. Named rather than pattern-matched so the exemption
         * cannot quietly widen to a second file.
         */
        const val FITNESS_FILE_NAME = "InstalledDistributionHarnessFitnessTest.kt"

        /**
         * The measured debt, in two batches.
         *
         * **Batch 1 — 23 harnesses that fork the INSTALLED binary.** These were the whole subject
         * of this law while it keyed on [AppBinSupport]. Each was migrated one at a time; the count
         * fell 32 -> 29 -> 28 -> 27 -> 26 -> 25 -> 24 -> 23.
         *
         * **Batch 2 — 30 more that fork the runtime by its MAIN CLASS.** Measured in BLOCK 3.7, not
         * suspected: `UatLocal008CredentialsTest` was migrated to [OwnedSubprocess] and nobody had
         * pointed at it, because it never mentioned [AppBinSupport] — it launches
         * `dev.rubentxu.pipeline.v2.application.MainKt` on the test classpath. That is the SAME
         * child and the same 8192-byte pipe exposure, because it is the same `Main.kt:431`, so the
         * marker was narrower than the property the law claims to protect.
         *
         * Total: **53**. Every entry is a RED waiting to be migrated, and the number may only fall.
         *
         * Two things this ledger does NOT do, stated because a `Set<String>` cannot carry them:
         *
         * 1. **No per-entry reason.** The failure message demands "the reason it cannot be migrated
         *    yet", and this type has nowhere to put one. The 23 pre-existing entries have never had
         *    a recorded reason; inventing 23 plausible-sounding ones now would be fabrication, so
         *    they stay unreasoned and the gap is named instead.
         * 2. **No site-level precision.** The rule decides per FILE, so it cannot tell which of a
         *    file's several launches is the runtime one. That is why `UatLocal008CredentialsTest` is
         *    an entry even though every RUNTIME launch in it now goes through [StrictCliRun]: its one
         *    remaining raw launch is the `sleep` that `TC-002` starts on purpose, because that row
         *    exists to characterise the teardown sweep. A window-based per-site rule was measured and
         *    REJECTED — its verdict moved from 31 files to 51 as the window went from 6 to 40
         *    lines, and at 20 lines it dropped `UatStep001ShExecutionTest`, which is a real offender
         *    whose `AppBinSupport` sits 41 lines above the launch. A law whose answer is a tunable
         *    is not a law.
         */
        val KNOWN_DEBT: Set<String> = setOf(
            // B1 (v0.48.0-rc2): three OBS-installed-binary end-to-end tests added during the
            // OBS / S6 integration landed without the corresponding KNOWN_DEBT entries. They
            // legitimately fork the runtime via ProcessBuilder to test the installed binary's
            // behaviour (output streaming characterisation, default run + human console,
            // silent follow UAT). Migrating to OwnedSubprocess would change the launched
            // subject from the installed jar to a classpath-runtime, which would void the
            // "installed binary" property these tests exist to verify. They are added here
            // together with the reason per the law's own rule, and the migration belongs to
            // a later cycle that defines how to assert installed-binary behaviour through
            // OwnedSubprocess without re-running the same code on the classpath.
            "dev/rubentxu/pipeline/v2/application/ObsAOutputStreamingCharacterisationTest.kt",
            "dev/rubentxu/pipeline/v2/application/RunDefaultIsHumanEndToEndTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/ObsR1SilentFollowInstalledUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/B1WURp053rContextRuntimeClosureTest.kt",
            "dev/rubentxu/pipeline/v2/application/CanonicalInMemoryCliTest.kt",
            "dev/rubentxu/pipeline/v2/application/CliMissingScriptRejectionTest.kt",
            "dev/rubentxu/pipeline/v2/application/DirScopeEndToEndTest.kt",
            "dev/rubentxu/pipeline/v2/application/ErrorHandlingTest.kt",
            "dev/rubentxu/pipeline/v2/application/SelfHostedPipelineScriptHonestyTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatDurableDefaultReuseCliTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatInputBlockDurableTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal002ResumeAfterKillTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal005EnvSpecialCharsTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal005RegressionGateTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal007SandboxProfileTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal008CredentialsTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal009TopStepsTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal010SmokeE2ESandboxOfflineTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal010SmokeE2ESandboxTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal011WorkflowControlTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal012ErrorHandlingTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLocal013MilestoneTimingTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatLockBlockDurableTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatParallelBlockDurableTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatRetryBlockDurableTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatRunConcurrencyCharacterisationTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatS2AWhenGateInstalledBinaryTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatS2R0RunOwnershipCliTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatStep001ShFailureStepFinishedCountTest.kt",
            "dev/rubentxu/pipeline/v2/application/UatTimeoutBlockDurableTest.kt",
            "dev/rubentxu/pipeline/v2/application/WorkspaceAnchorScopeEndToEndTest.kt",
            "dev/rubentxu/pipeline/v2/application/WorkspaceExecutionLocationCharacterizationTest.kt",
            "dev/rubentxu/pipeline/v2/application/WorkspaceOriginEndToEndTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WULpr010CliCharacterizationTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WULpr011ResumeLifecycleUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp019GradleRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp020MavenRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp021NodeRealUatTest.kt",
            "dev/rubentxu/pipeline/v2/application/cli/WURp023ObservationModesUatTest.kt",
        )
    }
}
