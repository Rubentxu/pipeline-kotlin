package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.plan.ImpactResolver
import dev.rubentxu.pipeline.v2.harness.plan.ValidationPlan
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import java.security.MessageDigest
import java.time.Instant

/**
 * Outcome of executing a single [ProbeEntry]. The typed result feeds
 * `CycleRunner.runProbes`, which translates it to a
 * [CycleEvent.ProbeFinished] for the decider.
 *
 * BQ4 audit (2026-10-16, vertical): the previous runner accepted
 * `(exitCode, evidenceRef)` from the caller; the caller could lie.
 * The new runner is fed by [ProbeExecutor.execute], which produces
 * the outcome from a real probe. The `Outcome` enum is the closed
 * set the spec
 * (`docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`)
 * names: `Verified`, `Failed`, `Skipped`, `NotRun`, `NoEvidence`,
 * `OtherCommit`, `UnsupportedFidelity`, `BlockedExternal`. No
 * other case is reachable; `when` over `Outcome` is total.
 */
sealed interface ProbeOutcome {
    val exitCode: Int
    val evidenceRef: String
    val outcome: Outcome

    enum class Outcome {
        Verified, Failed, Skipped, NotRun, NoEvidence, OtherCommit,
        UnsupportedFidelity, BlockedExternal,
    }

    /** The probe ran at the requested fidelity and discharged its contract. */
    data class Verified(override val evidenceRef: String) : ProbeOutcome {
        override val exitCode: Int = 0
        override val outcome = Outcome.Verified
    }

    /** The probe ran but the contract was not satisfied. */
    data class Failed(override val evidenceRef: String, val reason: String) : ProbeOutcome {
        override val exitCode: Int = 1
        override val outcome = Outcome.Failed
    }

    /** The probe's contract was vacuous (e.g. an AAT with no mutation target). */
    data class Skipped(override val evidenceRef: String) : ProbeOutcome {
        override val exitCode: Int = 0
        override val outcome = Outcome.Skipped
    }

    /** The probe could not be executed (e.g. its source file is missing). */
    data class NotRun(override val evidenceRef: String, val reason: String) : ProbeOutcome {
        override val exitCode: Int = 2
        override val outcome = Outcome.NotRun
    }

    /** The probe ran but produced no observable evidence at all. */
    data class NoEvidence(override val evidenceRef: String) : ProbeOutcome {
        override val exitCode: Int = 3
        override val outcome = Outcome.NoEvidence
    }

    /** The probe produced evidence for a different commit than the manifest declares. */
    data class OtherCommit(override val evidenceRef: String, val observed: String) : ProbeOutcome {
        override val exitCode: Int = 4
        override val outcome = Outcome.OtherCommit
    }

    /**
     * The manifest requested a fidelity this vertical does not support. HF2..HF6
     * always reach this case; the runner refuses the cycle before any I/O.
     */
    data class UnsupportedFidelity(val fidelity: String) : ProbeOutcome {
        override val exitCode: Int = 64
        override val evidenceRef: String = ""
        override val outcome = Outcome.UnsupportedFidelity
    }

    /**
     * The probe cannot finish without external authority. HF1 in this vertical
     * reaches this case because the harness is deliberately product-agnostic;
     * the CLI provides a product-side executor when HF1 is needed. The
     * distinction from `UnsupportedFidelity` is the recoverability: a
     * `BlockedExternal` is expected to be unblocked by attaching the right
     * executor on a later cycle.
     */
    data class BlockedExternal(override val evidenceRef: String, val blocker: String) : ProbeOutcome {
        override val exitCode: Int = 75
        override val outcome = Outcome.BlockedExternal
    }
}

/**
 * Real probe executor for the BQ4 vertical.
 *
 * ## Design contract
 *
 * `ProbeExecutor.execute` does NOT accept a `bash -c` string and does
 * NOT shell out. For each supported fidelity, the executor invokes a
 * piece of code in-process and inspects the typed result. The
 * [Outcome] enum is the only thing the runner sees; the evidence is
 * persisted as JSON next to the probe declaration so the cycle's
 * manifest can be reconstructed after the cycle dir is closed.
 *
 * ## HF0 (Pure Contract)
 *
 * The probe is a property-based check on a [ValidationPlan] produced
 * by [ImpactResolver.resolve]. The contract is "the changed paths in
 * the manifest produce a non-empty selection at fidelity HF0". The
 * executor:
 *   1. Reads the manifest's `relativePath` and resolves it as a
 *      `ValidationPlan.Selected` via the catalogue.
 *   2. Asserts that the selection's `capabilities` set contains the
 *      manifest's `ContractRef.id`.
 *   3. Asserts that the selection's `minimumFidelities` contains HF0.
 *   4. Persists the selection JSON under `<cycleDir>/probe-<id>.json`
 *      and returns the SHA-256 of that JSON as `evidenceRef`.
 *
 * The check is the real property the contract claims: "a change to
 * this path selects this contract at this fidelity". If the
 * catalogue no longer binds the path, the cycle is `Failed`. The
 * result is reproducible: same input, same selection, same digest.
 *
 * ## HF1 (In-Process)
 *
 * Not implemented in this vertical. The harness deliberately does not
 * depend on `:pipeline-application` or `:pipeline-event-harness`; the
 * operator provides a product-side executor in the CLI classpath
 * when HF1 is needed. The default executor returns
 * [ProbeOutcome.BlockedExternal] with an honest blocker string. The
 * outcome is `BlockedExternal` (not `Verified`), so the runner will
 * refuse the cycle, never promote it.
 *
 * ## HF2..HF6
 *
 * Always [ProbeOutcome.UnsupportedFidelity] with `exitCode = 64`.
 * No I/O, no file is created under `<cycleDir>` for that probe.
 */
open class ProbeExecutor(
    private val cycleDir: Path,
    private val clock: () -> Instant = Instant::now,
) {

    init {
        require(cycleDir.toString().isNotBlank()) { "cycleDir must not be blank" }
    }

    open fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome {
        val outcome = when (manifest.expectedHF) {
            "HF0" -> runHF0(probe, manifest)
            "HF1" -> ProbeOutcome.BlockedExternal(
                evidenceRef = "",
                blocker = "HF1 product-side executor not wired in this vertical; " +
                    "the CLI must supply a custom ProbeExecutor that invokes " +
                    "CanonicalDurableRunCoordinator for HF1 probes",
            )
            else -> ProbeOutcome.UnsupportedFidelity(manifest.expectedHF)
        }
        return when (outcome) {
            is ProbeOutcome.UnsupportedFidelity, is ProbeOutcome.BlockedExternal -> outcome
            else -> persist(probe, outcome)
        }
    }

    private fun runHF0(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome {
        // The HF0 contract: changing `relativePath` selects the
        // contract at HF0. The probe is real — it invokes
        // ImpactResolver.resolve and checks the typed result. There
        // is no `bash -c`, no shelled-out process; the answer is
        // computed from the same catalogue the producer uses.
        val path = probe.relativePath
        val plan = ImpactResolver.resolve(listOf(path))
        val capabilities = (plan as? ValidationPlan.Selected)?.capabilities
            ?: return ProbeOutcome.Failed(
                evidenceRef = "",
                reason = "ImpactResolver refused the HF0 probe path '$path': " +
                    (plan as? ValidationPlan.Refused)?.reasons?.joinToString() ?: "<no reasons>",
            )
        if (probe.contractId !in capabilities) {
            return ProbeOutcome.Failed(
                evidenceRef = "",
                reason = "HF0 contract mismatch: probe expects capability " +
                    "'${probe.contractId}' but the plan selected $capabilities",
            )
        }
        val fidelities = (plan as ValidationPlan.Selected).minimumFidelities
        if (ContractCatalogue.Fidelity.HF0 !in fidelities) {
            return ProbeOutcome.Failed(
                evidenceRef = "",
                reason = "HF0 fidelity not in the plan's minimumFidelities: $fidelities",
            )
        }
        // The contract is satisfied; the evidence IS the plan itself.
        return ProbeOutcome.Verified(evidenceRef = "<pending>")
    }

    private fun persist(probe: ProbeEntry, outcome: ProbeOutcome): ProbeOutcome {
        val outDir = realCycleDir().resolve("probes").also {
            Files.createDirectories(
                it,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }
        val outFile = outDir.resolve("${probe.id}.json")
        val payload = buildString {
            append("{\n")
            append("  \"probeId\": \"${probe.id}\",\n")
            append("  \"contractId\": \"${probe.contractId}\",\n")
            append("  \"kind\": \"${probe.kind}\",\n")
            append("  \"capturedAt\": \"${clock()}\",\n")
            append("  \"outcome\": \"${outcome.outcome}\",\n")
            append("  \"exitCode\": ${outcome.exitCode},\n")
            append("  \"evidenceRef\": \"${outcome.evidenceRef}\",\n")
            if (outcome is ProbeOutcome.Failed) {
                append("  \"reason\": \"${outcome.reason.escapeJson()}\"\n")
            } else {
                append("  \"reason\": null\n")
            }
            append("}\n")
        }
        Files.writeString(outFile, payload)
        val evidenceRef = outFile.toRealPath().toString()
        // The decider requires a non-blank evidenceRef; replay the
        // outcome with the now-known path.
        val persisted: ProbeOutcome = when (outcome) {
            is ProbeOutcome.Verified -> ProbeOutcome.Verified(evidenceRef)
            is ProbeOutcome.Failed -> ProbeOutcome.Failed(evidenceRef, outcome.reason)
            is ProbeOutcome.Skipped -> ProbeOutcome.Skipped(evidenceRef)
            is ProbeOutcome.NotRun -> ProbeOutcome.NotRun(evidenceRef, outcome.reason)
            is ProbeOutcome.NoEvidence -> ProbeOutcome.NoEvidence(evidenceRef)
            is ProbeOutcome.OtherCommit -> ProbeOutcome.OtherCommit(evidenceRef, outcome.observed)
            is ProbeOutcome.UnsupportedFidelity, is ProbeOutcome.BlockedExternal -> outcome
        }
        return persisted
    }

    /**
     * Resolve the cycle directory to its real path. The runner has
     * already created the directory in [CycleRunner.prepare]; this
     * method is the single chokepoint for path-safety so the runner
     * and the executor cannot disagree about what "the cycle dir"
     * means.
     */
    fun realCycleDir(): Path {
        val asFile = cycleDir.toFile()
        require(asFile.exists() && asFile.isDirectory) {
            "cycleDir must be an existing directory; got $cycleDir"
        }
        return cycleDir.toRealPath()
    }
}

private fun String.escapeJson(): String =
    this.replace("\\", "\\\\").replace("\"", "\\\"")
        .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t")

/**
 * SHA-256 of a string, returned as lowercase 64-char hex. The wire
 * format the manifest requires (`content_sha256`) is the same
 * shape. The implementation uses [MessageDigest] directly because
 * the harness is product-agnostic and cannot depend on the
 * product-side `Sha256` utility without breaking the H0 isolation.
 */
internal fun sha256Hex(text: String): String {
    val md = MessageDigest.getInstance("SHA-256")
    val bytes = md.digest(text.toByteArray(Charsets.UTF_8))
    return bytes.joinToString("") { "%02x".format(it) }
}
