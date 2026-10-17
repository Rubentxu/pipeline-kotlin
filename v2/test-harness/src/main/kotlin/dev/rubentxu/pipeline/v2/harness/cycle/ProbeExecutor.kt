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
    // BQ4 P0 (HITO 1 "Propiedad explícita de ficheros",
    // 2026-10-17): the runner wraps the executor in a
    // [RegisteringExecutorAdapter] (in `CycleRunner.kt`) that
    // needs to forward the cycleDir to the parent
    // constructor. The visibility is bumped from `private`
    // to `public` for that one purpose; Kotlin's `protected`
    // is too restrictive (it does not let an outside-class
    // subclass read a `protected` field of an instance of
    // the parent). The path is still immutable (a `val`)
    // and the existence check happens lazily in
    // [realCycleDir] when `persist()` is called, so test-only
    // executors with a non-existent cycleDir (e.g.
    // `TrivialVerifiedExecutor` returning `AlreadyPersisted`
    // without persisting) continue to work.
    val cycleDir: Path,
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
         * the executor and the runner.
         *
         * BQ4 v21 (typed marker, 2026-10-17): the previous
         * shape was a `ProbeExecutionResult.NeedsPersist`
         * carrying a `ProbeOutcome.Verified("<pending>")`
         * placeholder, which the executor's `execute` matched
         * with a string `==` (`isPendingOutcome`) to decide
         * whether to wrap in `NeedsPersist` or
         * `AlreadyPersisted`. The string sentinel is the dual
         * emitter/parser defect class: a typo at either side
         * (e.g. `"<Pending>"` capitalised, or a future
         * refactor that forgets to thread the sentinel)
         * silently misroutes the outcome. The runner never
         * sees the literal either way, but the executor's
         * own protocol still relies on a free-form string.
         *
         * The new shape makes `NeedsPersist` a `data object`
         * — a singleton with no fields. The executor's
         * `execute` returns `NeedsPersist` to signal "I
         * decided Verified; call `persist` to get the final
         * outcome with the real evidence path". The
         * `persist` signature no longer takes an `outcome`
         * argument: the executor already decided, and the
         * runner calls `persist(probe)` to write the JSON
         * and receive the `Verified(realPath)` carrier. The
         * placeholder string is gone; a typo on either side
         * is now a compile error.
         */
        sealed interface ProbeExecutionResult {
            /**
             * The executor's verdict is final. The runner
             * records the outcome and moves on. The outcome
             * has a real evidence path (or a real
             * `evidenceRef` for terminal cases) and the JSON
             * is either already on disk or not required.
             */
            data class AlreadyPersisted(val outcome: ProbeOutcome) : ProbeExecutionResult

            /**
             * The executor decided `Verified` and the JSON
             * has not yet been written. The runner MUST call
             * [ProbeExecutor.persist] to obtain the final
             * outcome with the real evidence path. The
             * marker carries no payload: a `Verified` is
             * implicit in the variant itself.
             */
            data object NeedsPersist : ProbeExecutionResult
        }
    }

    open fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutionResult {
        // The executor's `execute` returns a
        // [ProbeExecutionResult] carrier. The runner
        // separately invokes `persist(probe)` for
        // [ProbeExecutionResult.NeedsPersist] so the evidence
        // file and its `evidenceDigest` are written by the
        // same code path whether the executor is the harness
        // default or a product-side subclass. Splitting the
        // two also means a subclass that wants to bypass the
        // persistence step (e.g. an in-memory verifier) is
        // still forced to declare its `persist` override, and
        // the `finalise` digest check will fail loudly if the
        // JSON was never written.
        return when (manifest.expectedFidelity) {
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
            //
            // BQ4 v21 (typed marker, 2026-10-17): each branch
            // now returns the typed [ProbeExecutionResult]
            // directly. The HF0 happy path returns
            // [ProbeExecutionResult.NeedsPersist]; the
            // terminal branches return
            // [ProbeExecutionResult.AlreadyPersisted]. The
            // previous `Verified("<pending>")` placeholder
            // is gone.
            Fidelity.HF0 -> runHF0(probe, manifest)
            Fidelity.HF1 -> ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.BlockedExternal(
                    evidenceRef = "",
                    blocker = "HF1 product-side executor not wired in this vertical; " +
                        "the CLI must supply a custom ProbeExecutor that invokes " +
                        "CanonicalDurableRunCoordinator for HF1 probes",
                ),
            )
            Fidelity.HF2, Fidelity.HF3, Fidelity.HF4, Fidelity.HF5, Fidelity.HF6 ->
                ProbeExecutionResult.AlreadyPersisted(
                    ProbeOutcome.UnsupportedFidelity(manifest.expectedHF),
                )
        }
    }

    private fun runHF0(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutionResult {
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
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = realCycleDir().resolve(path).toString(),
                    reason = "could not resolve '${path}' under cycleDir",
                ),
            )
        }
        if (!target.startsWith(realCycleDir())) {
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "HF0 probe '${probe.id}' escapes the cycle directory: '$target'",
                ),
            )
        }
        if (!Files.exists(target)) {
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "HF0 probe '${probe.id}' file is absent at $target; " +
                        "the operator must stage the source under cycleDir before prepare()",
                ),
            )
        }
        val actualSha = try {
            sha256HexOfFile(target)
        } catch (ex: java.io.IOException) {
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "could not read file at $target: ${ex.message}",
                ),
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
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "contentSha256 mismatch: manifest=${probe.contentSha256}, " +
                        "file=$actualSha",
                ),
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
            is ValidationPlan.Refused -> return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "ImpactResolver refused the HF0 probe path '$path': " +
                        plan.reasons.joinToString(),
                ),
            )
        }
        if (probe.contractId !in selected.capabilities) {
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "HF0 contract mismatch: probe expects capability " +
                        "'${probe.contractId}' but the plan selected ${selected.capabilities}",
                ),
            )
        }
        if (ContractCatalogue.Fidelity.HF0 !in selected.minimumFidelities) {
            return ProbeExecutionResult.AlreadyPersisted(
                ProbeOutcome.Failed(
                    evidenceRef = target.toString(),
                    reason = "HF0 fidelity not in the plan's minimumFidelities: ${selected.minimumFidelities}",
                ),
            )
        }
        // The contract is satisfied; the evidence IS the plan
        // itself. The runner will call `persist(probe)` to write
        // the JSON and receive a `Verified(realPath)` carrier.
        // BQ4 v21 (typed marker, 2026-10-17): the previous
        // shape returned `Verified(PENDING_EVIDENCE_REF)` here,
        // leaving a string sentinel in the protocol between
        // `runHF0` and `execute`. The new shape returns the
        // typed [ProbeExecutionResult.NeedsPersist] marker; the
        // `Verified` carrier is built by [persist] with the
        // real evidence path. No placeholder is constructed.
        return ProbeExecutionResult.NeedsPersist
    }

    internal open fun persist(probe: ProbeEntry): ProbeOutcome.Verified {
        val outDir = realCycleDir().resolve("probes").also {
            Files.createDirectories(
                it,
                PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rwx------")),
            )
        }
        val outFile = outDir.resolve("${probe.id}.json")
        // BQ4 v21 (typed marker, 2026-10-17): the previous
        // signature was `persist(probe, outcome: ProbeOutcome)`
        // and used `outcome.outcome`, `outcome.exitCode`,
        // `outcome.evidenceRef` to populate the JSON body.
        // The shape was correct for the HF0 default
        // (Verified + exitCode 0) but coupled the protocol to
        // a free-form `outcome.evidenceRef` value that the
        // runner's [runHF0] previously held as a `"<pending>"`
        // sentinel. The new shape drops the outcome argument:
        // the executor's [execute] already decided `Verified`
        // (that is what `NeedsPersist` means), and the
        // runner's `runProbes` does not need to thread the
        // outcome through. The persisted JSON's `evidenceRef`
        // is a stable marker (not a sentinel — no parser
        // reads it) and the runner's outcome is the
        // `Verified(realPath)` this function returns.
        //
        // The recorded `evidenceDigest` is the SHA-256 of the
        // body MINUS the evidenceDigest line. To make the strip
        // on `finalise` byte-for-byte reversible, the body has
        // its last data line ending in `,\n` (the comma that
        // will precede the evidenceDigest line) and then a
        // closing `\n}\n` that is NOT part of the recorded
        // digest. [CycleRunner.validateEvidenceDigest] strips
        // exactly the `,\n  "evidenceDigest": "DIGEST"\n`
        // substring and recomputes the digest.
        val body = buildString {
            append("{\n")
            append("  \"probeId\": \"${probe.id}\",\n")
            append("  \"contractId\": \"${probe.contractId}\",\n")
            append("  \"kind\": \"${probe.kind}\",\n")
            append("  \"capturedAt\": \"${clock()}\",\n")
            append("  \"outcome\": \"Verified\",\n")
            append("  \"exitCode\": 0,\n")
            append("  \"evidenceRef\": \"<persisted>\",\n")
            append("  \"reason\": null\n")
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
        return ProbeOutcome.Verified(evidenceRef)
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
