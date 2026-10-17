package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
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
        // BQ4 v12 (cycle carrier self-protect, 2026-10-17):
        // a `Verified("")` is a Verified without evidence —
        // a fabrication. The carrier refuses blanks at
        // construction. The 3 "no evidence" variants
        // (NoEvidence, UnsupportedFidelity, BlockedExternal)
        // intentionally accept blank evidenceRef; their
        // meaning is "no evidence was produced" and the
        // blank is the absent-case signal.
        init { require(evidenceRef.isNotBlank()) { "Verified.evidenceRef must not be blank" } }
        override val exitCode: Int = 0
        override val outcome = Outcome.Verified
    }

    /** The probe ran but the contract was not satisfied. */
    data class Failed(override val evidenceRef: String, val reason: String) : ProbeOutcome {
        init {
            require(evidenceRef.isNotBlank()) { "Failed.evidenceRef must not be blank" }
            require(reason.isNotBlank()) { "Failed.reason must not be blank" }
        }
        override val exitCode: Int = 1
        override val outcome = Outcome.Failed
    }

    /** The probe's contract was vacuous (e.g. an AAT with no mutation target). */
    data class Skipped(override val evidenceRef: String) : ProbeOutcome {
        init { require(evidenceRef.isNotBlank()) { "Skipped.evidenceRef must not be blank" } }
        override val exitCode: Int = 0
        override val outcome = Outcome.Skipped
    }

    /** The probe could not be executed (e.g. its source file is missing). */
    data class NotRun(override val evidenceRef: String, val reason: String) : ProbeOutcome {
        init {
            require(evidenceRef.isNotBlank()) { "NotRun.evidenceRef must not be blank" }
            require(reason.isNotBlank()) { "NotRun.reason must not be blank" }
        }
        override val exitCode: Int = 2
        override val outcome = Outcome.NotRun
    }

    /** The probe ran but produced no observable evidence at all. */
    data class NoEvidence(override val evidenceRef: String) : ProbeOutcome {
        // NoEvidence intentionally accepts blank evidenceRef;
        // see Verified.init for the rationale.
        override val exitCode: Int = 3
        override val outcome = Outcome.NoEvidence
    }

    /** The probe produced evidence for a different commit than the manifest declares. */
    data class OtherCommit(override val evidenceRef: String, val observed: String) : ProbeOutcome {
        init {
            require(evidenceRef.isNotBlank()) { "OtherCommit.evidenceRef must not be blank" }
            require(observed.isNotBlank()) { "OtherCommit.observed must not be blank" }
        }
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

    companion object {
        /**
         * The result of [execute] is a typed carrier that says
         * whether the runner still needs to call [persist]. The
         * carrier is the single source of truth for the
         * "already persisted vs needs persist" protocol between
         * the executor and the runner; the previous shape used a
         * `ProbeOutcome.Verified("<pending>")` sentinel that the
         * runner matched with a string `==`, which is the dual
         * emitter/parser defect class: a typo at either side
         * silently misroutes the outcome. The sealed carrier
         * makes the protocol unmissable: the runner matches on a
         * closed ADT and the compiler forces the consumer to
         * handle both cases.
         *
         * `outcome` is the typed [ProbeOutcome] the executor
         * decided; `NeedsPersist` says "the executor decided but
         * did not write the JSON; the runner must call persist";
         * `AlreadyPersisted` says "the JSON is already on disk
         * (either the executor wrote it or the outcome is
         * terminal and no JSON is required)".
         */
        sealed interface ProbeExecutionResult {
            val outcome: ProbeOutcome

            data class AlreadyPersisted(override val outcome: ProbeOutcome) : ProbeExecutionResult
            data class NeedsPersist(override val outcome: ProbeOutcome) : ProbeExecutionResult
        }

        /**
         * Public predicate for subclasses (in other modules) that
         * need to wrap their own [ProbeOutcome] in
         * [ProbeExecutionResult] the same way [execute] does.
         * The literal [PENDING_EVIDENCE_REF] stays `internal` so
         * the runner never sees a free-form string; the predicate
         * is the only public surface. Returning `true` means
         * "the runner still needs to call `persist`".
         *
         * BQ4 P0-D follow-up (2026-10-17): the previous shape
         * required product-side executors to read the internal
         * constant directly, which Kotlin's module boundary
         * correctly refused. The predicate is the public seam
         * the protocol now exposes.
         */
        fun isPendingOutcome(outcome: ProbeOutcome): Boolean =
            outcome is ProbeOutcome.Verified && outcome.evidenceRef == PENDING_EVIDENCE_REF

        /**
         * Internal bookkeeping marker used by [runHF0] to signal
         * "the executor decided Verified but has not yet written
         * the evidence file". The string is private to this
         * class: [execute] matches on it to wrap the outcome in
         * [ProbeExecutionResult.NeedsPersist] or
         * [ProbeExecutionResult.AlreadyPersisted], and the
         * runner never sees the literal. The marker is internal
         * because the test class in the same package builds
         * [ProbeOutcome.Verified] fixtures that exercise the
         * `NeedsPersist` branch without a real persist step.
         */
        internal const val PENDING_EVIDENCE_REF = "<pending>"
    }

    open fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutionResult {
        // The executor's `execute` returns the raw outcome wrapped
        // in a [ProbeExecutionResult] carrier. The runner
        // separately invokes `persist(probe, outcome)` so the
        // evidence file and its `evidenceDigest` are written by
        // the same code path whether the executor is the harness
        // default or a product-side subclass. Splitting the two
        // also means a subclass that wants to bypass the
        // persistence step (e.g. an in-memory verifier) is still
        // forced to declare its `persist` override, and the
        // `finalise` digest check will fail loudly if the JSON
        // was never written.
        val raw = when (manifest.expectedFidelity) {
            // BQ4 fidelity (2026-10-17): the previous shape
            // matched the wire string `"HF0"` / `"HF1"` and
            // had a bare `else` for the rest. A typo at the
            // wire (e.g. "hf0" lowercase) would silently fall
            // to `else` and surface as `UnsupportedFidelity`
            // — the dual emitter/parser defect class. The
            // manifest's [init] now guarantees the string is
            // one of [CycleManifest.ALLOWED_FIDELITIES] and
            // [CycleManifest.expectedFidelity] parses it to
            // the typed [Fidelity] enum, so the match is
            // exhaustive and the compiler forces a branch
            // for every case (HF2..HF6 land in the
            // `UnsupportedFidelity` arm).
            Fidelity.HF0 -> runHF0(probe, manifest)
            Fidelity.HF1 -> ProbeOutcome.BlockedExternal(
                evidenceRef = "",
                blocker = "HF1 product-side executor not wired in this vertical; " +
                    "the CLI must supply a custom ProbeExecutor that invokes " +
                    "CanonicalDurableRunCoordinator for HF1 probes",
            )
            Fidelity.HF2, Fidelity.HF3, Fidelity.HF4, Fidelity.HF5, Fidelity.HF6 ->
                ProbeOutcome.UnsupportedFidelity(manifest.expectedHF)
        }
        // The only outcome that needs persist is the HF0 happy
        // path, where `runHF0` returns `Verified(PENDING_EVIDENCE_REF)`.
        // Every other branch is terminal or already-persisted and
        // does not need the runner to write JSON. The match is on
        // the outcome type and the pending sentinel, not on a
        // free-form string.
        return if (isPendingOutcome(raw)) {
            ProbeExecutionResult.NeedsPersist(raw)
        } else {
            ProbeExecutionResult.AlreadyPersisted(raw)
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
        // BQ4 v20 (case-sensitive digest comparison, 2026-10-17):
        // the previous shape was
        // `!actualSha.equals(probe.contentSha256, ignoreCase = true)`.
        // `probe.contentSha256` is strict-validated to lowercase
        // hex in [ProbeEntry.init] and `sha256HexOfFile` always
        // returns lowercase, so the case-insensitive comparison
        // was a defensive default that became a silent acceptance
        // of a mixed-case recorded digest. The comparison is now
        // a plain `!=` so a tampered or upper-cased record is
        // refused loudly rather than silently miscompared.
        if (actualSha != probe.contentSha256) {
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
        return ProbeOutcome.Verified(evidenceRef = PENDING_EVIDENCE_REF)
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
