package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.file.attribute.PosixFilePermissions

/**
 * Entry point for an ephemeral cycle, invoked as a JVM class
 * (`CycleCliKt`).
 *
 * BQ4 audit (2026-10-16, vertical): the CLI exists so the cycle
 * has an end-to-end surface — a real binary that reads a
 * manifest, parses it, runs the probes, and closes the cycle.
 * The CLI deliberately does NOT shell out (`bash -c`) and does
 * NOT depend on the product classpath; the HF0/HF1 executor
 * inside the harness does the work, and HF2..HF6 are
 * fail-closed as `UnsupportedFidelity`.
 *
 * Usage:
 *
 *   java -cp <harness-classpath> \
 *     dev.rubentxu.pipeline.v2.harness.cycle.CycleCliKt \
 *     --cycle-id <id> \
 *     --manifest <path-to-manifest.json> \
 *     [--cycle-dir <path>] \
 *     [--source-sha <40-hex>] \
 *     [--dry-run]
 *
 * Exit codes:
 *   0  - the cycle closed (Promoted -> Closed) and the cycle
 *        directory was deleted.
 *   1  - the cycle was Refused (manifest invalid, path traversal,
 *        probe failure, contentSha256 mismatch, etc.).
 *   64 - the cycle was Refused with `UnsupportedFidelity: HF$n`.
 *   75 - the cycle is BlockedExternal (HF1 needs a product-side
 *        executor; the runner is product-agnostic).
 *   2  - argument parsing failure.
 */
fun main(args: Array<String>) {
    val parsed = parseArgs(args) ?: run {
        System.err.println(USAGE)
        System.exit(2)
        return
    }

    val cycleDir = parsed.cycleDir ?: defaultCycleDir(parsed.cycleId)
    ensureOutsideGitRepo(cycleDir)

    Files.createDirectories(
        cycleDir,
        PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
    )

    val runner = CycleRunner(
        cycleId = parsed.cycleId,
        manifestPath = parsed.manifestPath,
        cycleDir = cycleDir,
    )

    val prepareOutcome = runner.prepare()
    val prepareState = (prepareOutcome as? CycleOutcome.Next)?.nextState
    if (prepareState !is CycleState.Running) {
        System.err.println("prepare refused: $prepareState")
        System.exit(1)
    }

    val manifest = runner.manifest
        ?: error("prepare returned Running but manifest is null; this is a defect")

    val executor = ProbeExecutorDiscovery.resolve(cycleDir = cycleDir)
    val outcomes = runner.runProbes(executor)

    // The cycle's terminal state is decided by the decider, not by
    // the CLI. We inspect it to drive the exit code and the close
    // path. HF2..HF6 manifest at the executor level: the runner
    // translates them to evidenceRefs that begin with "unsupported:"
    // and the cycle is then driven through the standard
    // observe / promote / close path. The exit code is the cycle's
    // own verdict; the CLI does not invent one.
    runner.observeAll(parsed.sourceSha)
    // P0-D (audit 2026-10-16): a textual reference like
    // `replacement:foo` is a label, not a verified substitute.
    // The decider now refuses any `replacementEvidence` whose
    // shape is not `permanent-contract:<id>` (an in-repo test or
    // fitness) or `certifier-receipt:<sha256>` (a signed
    // receipt). The CLI composes one of those two depending on
    // what the manifest's `ReplacementPlan.kind` declares:
    //   PERMANENT_CONTRACT -> "permanent-contract:<id>"
    //   EXTERNAL_CERTIFIER -> "certifier-receipt:<id>" (the id
    //     here is the receipt id; the certifier will overwrite
    //     it with a real sha256 once the receipt is signed)
    val substituteEvidence = when (manifest.replacement.kind) {
        ReplacementPlan.ReplacementKind.PERMANENT_CONTRACT ->
            "permanent-contract:${manifest.replacement.id}"
        ReplacementPlan.ReplacementKind.EXTERNAL_CERTIFIER ->
            // The certifier-receipt id is the manifest's
            // declared id; the certifier will sign a sha256
            // later and the cycle is blocked-external until it
            // does. We refuse to use a free-form string here:
            // the shape must match the verifier.
            "certifier-receipt:${manifest.replacement.id}"
    }
    runner.requestPromote(replacementEvidence = substituteEvidence)
    runner.requestClose("cycle closed by CycleCli")

    val terminal = runner.state
    val exitCode = when (terminal) {
        is CycleState.Closed -> {
            if (!parsed.dryRun) {
                runner.finalise()
            }
            0
        }
        is CycleState.Refused -> {
            val reason = terminal.reason
            if (reason.startsWith("UnsupportedFidelity")) 64 else 1
        }
        is CycleState.BlockedExternal -> 75
        else -> {
            System.err.println("cycle did not reach a terminal state: $terminal")
            1
        }
    }
    System.err.println("outcomes:")
    outcomes.forEach { System.err.println("  $it") }
    System.err.println("terminal state: $terminal")
    System.exit(exitCode)
}

private const val USAGE = """usage: CycleCliKt
    --cycle-id <id>           (required)
    --manifest <path>         (required; snake_case JSON)
    [--cycle-dir <path>]      (default: workspace/.pipelinek-cycles/<cycle-id>/)
    [--source-sha <40-hex>]   (default: 40 zeros, accept-only for now)
    [--dry-run]               (do not call finalise; keep cycle dir for inspection)
"""

private data class CliArgs(
    val cycleId: String,
    val manifestPath: Path,
    val cycleDir: Path?,
    val sourceSha: String,
    val dryRun: Boolean,
)

private fun parseArgs(args: Array<String>): CliArgs? {
    var cycleId: String? = null
    var manifestPath: Path? = null
    var cycleDir: Path? = null
    var sourceSha: String = "0".repeat(40)
    var dryRun = false
    var i = 0
    while (i < args.size) {
        when (val a = args[i]) {
            "--cycle-id" -> {
                require(i + 1 < args.size) { "--cycle-id requires a value" }
                cycleId = args[i + 1]
                i += 2
            }
            "--manifest" -> {
                require(i + 1 < args.size) { "--manifest requires a value" }
                manifestPath = Paths.get(args[i + 1])
                i += 2
            }
            "--cycle-dir" -> {
                require(i + 1 < args.size) { "--cycle-dir requires a value" }
                cycleDir = Paths.get(args[i + 1])
                i += 2
            }
            "--source-sha" -> {
                require(i + 1 < args.size) { "--source-sha requires a value" }
                sourceSha = args[i + 1]
                i += 2
            }
            "--dry-run" -> {
                dryRun = true
                i++
            }
            "--help", "-h" -> {
                System.err.println(USAGE)
                System.exit(0)
            }
            else -> {
                System.err.println("unknown argument: $a")
                return null
            }
        }
    }
    if (cycleId == null || manifestPath == null) return null
    return CliArgs(
        cycleId = cycleId,
        manifestPath = manifestPath,
        cycleDir = cycleDir,
        sourceSha = sourceSha,
        dryRun = dryRun,
    )
}

private fun defaultCycleDir(cycleId: String): Path =
    Paths.get("workspace").resolve(".pipelinek-cycles").resolve(cycleId)

/**
 * The cycle directory must live OUTSIDE the Git producer repo. We
 * check this defensively here, on top of the `.gitignore` rule
 * added in the BQ4 vertical: the runner cannot guarantee the
 * caller passed a sane path. The check is "the cycle dir's
 * absolute path is not contained in any parent that is a Git
 * working tree". This is a heuristic — for full belt-and-braces,
 * the operator can pass `--cycle-dir` outside the repo.
 */
private fun ensureOutsideGitRepo(cycleDir: Path) {
    val abs = cycleDir.toAbsolutePath().normalize()
    var probe: Path? = abs
    while (probe != null) {
        if (Files.exists(probe.resolve(".git"))) {
            error(
                "refusing to create cycleDir inside a Git working tree: " +
                    "'$abs' is under '$probe' which contains a .git directory",
            )
        }
        probe = probe.parent
    }
}
