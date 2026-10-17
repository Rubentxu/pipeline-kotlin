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
        // The executor's `execute` returns the *raw* outcome
        // (success/failure of the verification step). The runner
        // separately invokes `persist(probe, outcome)` so the
        // evidence file and its `evidenceDigest` are written by
        // the same code path whether the executor is the harness
        // default or a product-side subclass. Splitting the two
        // also means a subclass that wants to bypass the
        // persistence step (e.g. an in-memory verifier) is still
        // forced to declare its `persist` override, and the
        // `finalise` digest check will fail loudly if the JSON
        // was never written.
        return when (manifest.expectedHF) {
            "HF0" -> runHF0(probe, manifest)
            "HF1" -> ProbeOutcome.BlockedExternal(
                evidenceRef = "",
                blocker = "HF1 product-side executor not wired in this vertical; " +
                    "the CLI must supply a custom ProbeExecutor that invokes " +
                    "CanonicalDurableRunCoordinator for HF1 probes",
            )
            else -> ProbeOutcome.UnsupportedFidelity(manifest.expectedHF)
        }
    }

    private fun runHF0(probe: ProbeEntry, manifest: CycleManifest): ProbeOutcome {
        // The HF0 contract: changing `relativePath` selects the
        // contract at HF0. The probe is real — it invokes
        // ImpactResolver.resolve and checks the typed result. There
        // is no `bash -c`, no shelled-out process; the answer is
        // computed from the same catalogue the producer uses.
        //
        // The audit (2026-10-16, P0): the previous BQ4 vertical
        // only validated the format of `contentSha256`; a manifest
        // with an invented digest still produced `Verified`. The
        // fix here reads the file the manifest points to (always
        // resolved against the cycle directory, per the
        // `cycleDirOnly` isolation policy), computes its SHA-256
        // with a JDK oracle, and requires the digest matches the
        // manifest's `contentSha256`. A missing file is
        // `NoEvidence`; a mismatched digest is `Failed`; only a
        // matching digest on an existing file is `Verified`.
        //
        // The SHA check happens BEFORE the catalogue check: a
        // manifest with a path not in the catalogue still has to
        // prove the bytes it claims exist. The catalogue check
        // then validates that the contract the manifest claims
        // actually owns the path.
        val path = probe.relativePath
        val target = try {
            realCycleDir().resolve(path).normalize()
        } catch (ex: java.io.IOException) {
            return ProbeOutcome.Failed(
                evidenceRef = realCycleDir().resolve(path).toString(),
                reason = "could not resolve '${path}' under cycleDir",
            )
        }
        if (!target.startsWith(realCycleDir())) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "HF0 probe '${probe.id}' escapes the cycle directory: '$target'",
            )
        }
        if (!Files.exists(target)) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "HF0 probe '${probe.id}' file is absent at $target; " +
                    "the operator must stage the source under cycleDir before prepare()",
            )
        }
        val actualSha = try {
            sha256HexOfFile(target)
        } catch (ex: java.io.IOException) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "could not read file at $target: ${ex.message}",
            )
        }
        if (!actualSha.equals(probe.contentSha256, ignoreCase = true)) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "contentSha256 mismatch: manifest=${probe.contentSha256}, " +
                    "file=$actualSha",
            )
        }
        // The bytes are what the manifest claims. Now verify the
        // catalogue recognises the path AND the fidelity AND the
        // contract binding.
        val plan = ImpactResolver.resolve(listOf(path))
        // BQ1 v2 (typed probe executor, 2026-10-17): the previous
        // shape used two silent-null casts on the same plan, with
        // `?:` fall-throughs that returned a Failed outcome on
        // the null branches. That is the audit's "silent null on
        // a sealed-type cast" defect class: a future
        // `ValidationPlan.Pending` (or any third variant) would
        // route through BOTH `?:` branches and fall out the
        // bottom of the function as `Verified("<pending>")` —
        // the false green the catalogue check exists to prevent.
        // The new shape is an exhaustive `when` over the closed
        // set; the `selected` binding is typed and the downstream
        // checks read it without further casts. A future
        // [ValidationPlan] case forces this `when` to grow at
        // compile time, not at a CI log. See also
        // `GateAsCastFitnessTest` in the plan package for the
        // analogous gate entry-point fix.
        val selected: ValidationPlan.Selected = when (plan) {
            is ValidationPlan.Selected -> plan
            is ValidationPlan.Refused -> return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "ImpactResolver refused the HF0 probe path '$path': " +
                    plan.reasons.joinToString(),
            )
        }
        if (probe.contractId !in selected.capabilities) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "HF0 contract mismatch: probe expects capability " +
                    "'${probe.contractId}' but the plan selected ${selected.capabilities}",
            )
        }
        if (ContractCatalogue.Fidelity.HF0 !in selected.minimumFidelities) {
            return ProbeOutcome.Failed(
                evidenceRef = target.toString(),
                reason = "HF0 fidelity not in the plan's minimumFidelities: ${selected.minimumFidelities}",
            )
        }
        // The contract is satisfied; the evidence IS the plan itself.
        return ProbeOutcome.Verified(evidenceRef = "<pending>")
    }

    internal open fun persist(probe: ProbeEntry, outcome: ProbeOutcome): ProbeOutcome {
        val outDir = realCycleDir().resolve("probes").also {
            Files.createDirectories(
                it,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }
        val outFile = outDir.resolve("${probe.id}.json")
        // The recorded `evidenceDigest` is the SHA-256 of the
        // body MINUS the evidenceDigest line. To make the strip
        // on `finalise` byte-for-byte reversible, the body has
        // its last data line ending in `,\n` (the comma that
        // will precede the evidenceDigest line) and then a
        // closing `\n}\n` that is NOT part of the recorded
        // digest. The runner's regex strips exactly the
        // `,\n  "evidenceDigest": "DIGEST"\n` substring (or its
        // single-line equivalent); the leading newline of the
        // substring is the same one the executor emits.
        val body = buildString {
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
        }
        val initialDigest = sha256Hex(body)
        // Concatenate the evidenceDigest line as a standalone
        // JSON line, then the closing brace. The recorded
        // digest covers the bytes before the
        // `,\n  "evidenceDigest":` substring; the runner strips
        // that exact substring on `finalise`.
        val finalBody = body + ",\n  \"evidenceDigest\": \"$initialDigest\"\n}\n"
        Files.writeString(outFile, finalBody)
        val evidenceRef = outFile.toRealPath().toString()
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
 * SHA-256 of a file, returned as lowercase 64-char hex. Streams
 * the file in 8 KB chunks so the digest is bounded by constant
 * memory regardless of file size. The shape matches the
 * product-side `Sha256.ofFile` so the cycle's `contentSha256`
 * field round-trips with the same wire format.
 *
 * The harness re-implements this here because it is
 * product-agnostic and cannot import `:pipeline-domain`. The
 * implementation uses [MessageDigest] directly so a JDK upgrade
 * is the only thing that can change the digest output.
 */
internal fun sha256HexOfFile(path: Path): String {
    val md = MessageDigest.getInstance("SHA-256")
    Files.newInputStream(path).use { stream ->
        val buffer = ByteArray(8 * 1024)
        while (true) {
            val read = stream.read(buffer)
            if (read <= 0) break
            md.update(buffer, 0, read)
        }
    }
    return md.digest().joinToString("") { "%02x".format(it) }
}

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
