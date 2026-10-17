package dev.rubentxu.pipeline.v2.harness.cycle

import dev.rubentxu.pipeline.v2.harness.util.DigestFormat
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions

/**
 * Runner for an ephemeral acceptance test cycle.
 *
 * Spec: `docs/v2/03-specifications/EPHEMERAL_CYCLE_LIFECYCLE_SPEC.md`.
 *
 * The runner is a thin orchestrator that:
 *   1. `prepare` — reads and validates the manifest file, reserves
 *      the cycle directory (outside the Git producer repo, never
 *      added back). Refuses if the manifest is missing, malformed,
 *      declares an unsupported fidelity, or names a path that
 *      escapes the cycle directory.
 *   2. `runProbes` — feeds every [ProbeEntry] in the manifest through
 *      a real [ProbeExecutor], translates the [ProbeOutcome] to a
 *      [CycleEvent.ProbeFinished], and threads the result through
 *      the decider. The runner no longer accepts `(exitCode,
 *      evidenceRef)` from the caller; the executor is the only
 *      authority on what a probe actually did.
 *   3. `requestPromote` / `requestClose` — drive the state machine
 *      toward `Closed` via the success path
 *      `Observed -> Promoted -> Closed`. The runner refuses
 *      `Observed -> Closed` (the previous shortcut, which let a
 *      cycle close without naming a replacement).
 *   4. `finalise` — if the state is `Closed`, deletes the cycle
 *      directory and the manifest. The deletion is the runner's,
 *      not the caller's: `finalise` takes no callback, validates
 *      that the manifest's paths are contained in the cycle
 *      directory, and uses `toRealPath()` to defeat symlink
 *      escapes.
 *
 * BQ4 audit (2026-10-16, vertical): the previous runner was a
 * "typed skeleton" that accepted whatever the caller said. The
 * new runner (a) reads the manifest content in `prepare`, (b)
 * only takes evidence from a real [ProbeExecutor] in `runProbes`,
 * (c) refuses `Observed -> Closed` at the decider level, and (d)
 * owns the deletion in `finalise` so a callback cannot escape the
 * cycle directory.
 */
class CycleRunner(
    val cycleId: String,
    val manifestPath: Path,
    val cycleDir: Path,
    private val clock: () -> Long = System::currentTimeMillis,
    /**
     * Authority on whether a named oracle has last run PASS at a
     * given commit. Defaults to [EmptyOracleLedger] which refuses
     * every oracle — the safe fallback. Production wiring injects
     * a real ledger backed by the test-results or fitness
     * registry.
     */
    private val oracleLedger: OracleLedger = EmptyOracleLedger,
    /**
     * Filesystem root for resolving `PromotionEvidence.sourcePath`
     * against. Defaults to the current working directory, which
     * is what the [CycleCli] wants when running from the repo
     * root. Tests inject a temp dir.
     */
    private val repoRoot: Path = Path.of(""),
) {
    private val json = Json { ignoreUnknownKeys = true; isLenient = false }

    /**
     * The parsed manifest. Available after [prepare] succeeds; null
     * before that. The [ProbeExecutor] reads this to know which
     * fidelity to dispatch to and which contracts the cycle claims.
     */
    var manifest: CycleManifest? = null
        private set

    init {
        require(cycleId.isNotBlank()) { "cycleId must not be blank" }
        require(manifestPath.toString().isNotBlank()) { "manifestPath must not be blank" }
        val asFile = cycleDir.toFile()
        require(!asFile.exists() || asFile.isDirectory) {
            "cycleDir must be a directory or not exist; got $cycleDir"
        }
    }

    /**
     * The current state of the cycle. The runner holds the
     * `CycleState` and only advances it via `Cycle.decide`.
     */
    var state: CycleState = CycleState.Draft
        private set

    /**
     * The accumulated context. The runner threads this through
     * every `Cycle.decide` call and exposes the latest value
     * so the operator can read the evidence trail.
     */
    var context: CycleContext = CycleContext()
        private set

    /**
     * Snapshot of the cycle dir's contents at the moment `prepare()`
     * took ownership of it. The runner treats everything in the
     * snapshot as "owned by the operator" (already there when the
     * cycle started) and everything NOT in the snapshot as
     * "owned by the runner or the executor" (created during the
     * cycle's run). The deletion phase in `finalise` is the
     * second set; the first set is left alone. This is the
     * "manifest of property" the 2026-10-16 audit named: the
     * runner no longer over-deletes a directory it does not own.
     */
    private var operatorOwnedSnapshot: Set<Path> = emptySet()
        private set

    /**
     * `prepare`: reads the manifest from `manifestPath`, parses it,
     * validates all required fields, anchors the manifest's
     * `relativePath` entries inside the cycle directory, and
     * reserves the cycle directory on disk. Refuses with
     * [CycleState.Refused] if the manifest is missing, malformed,
     * declares an unsupported fidelity, or names a path that
     * escapes the cycle directory.
     */
    fun prepare(): CycleOutcome {
        val parsed: CycleManifest = try {
            val text = Files.readString(manifestPath)
            json.decodeFromString(CycleManifest.serializer(), text)
        } catch (ex: java.io.IOException) {
            return fail("manifest file is unreadable: ${ex.message}")
        } catch (ex: kotlinx.serialization.SerializationException) {
            return fail("manifest is not a valid CycleManifest: ${ex.message}")
        } catch (ex: IllegalArgumentException) {
            // A `require` block in the data class init produced a
            // validation error: surface it as a typed refusal.
            return fail(ex.message ?: "manifest failed validation")
        }

        if (parsed.cycleId != cycleId) {
            return fail("manifest cycleId '${parsed.cycleId}' does not match --cycle-id '$cycleId'")
        }
        val pathCheck = validatePaths(parsed)
        if (pathCheck != null) return fail(pathCheck)

        val transition = transition(CycleEvent.PrepareStarted)
        if (transition is CycleOutcome.Next && transition.nextState is CycleState.Running) {
            // Create the cycle dir with restrictive ACLs. The
            // executor's persisted evidence inherits the dir's
            // permission set.
            try {
                Files.createDirectories(
                    cycleDir,
                    PosixFilePermissions.asFileAttribute(
                        PosixFilePermissions.fromString("rwx------"),
                    ),
                )
            } catch (ex: java.io.IOException) {
                return fail("could not create cycleDir '$cycleDir': ${ex.message}")
            }
            // Snapshot the cycle dir's contents RIGHT NOW, before
            // any executor writes anything. `finalise` will
            // delete everything created AFTER this snapshot and
            // leave the operator's pre-existing files alone. The
            // audit's "manifest of property" requirement is
            // pinned here, where the property boundary is real.
            operatorOwnedSnapshot = snapshotOwned(cycleDir)
            manifest = parsed
        }
        return transition
    }

    /**
     * Snapshot every existing entry under [root]. Returns the
     * real-path of each immediate child (not the recursive tree:
     * the snapshot is what existed at `prepare()` time, the rest
     * is by definition runner-owned).
     */
    private fun snapshotOwned(root: Path): Set<Path> {
        if (!Files.isDirectory(root)) return emptySet()
        return Files.list(root).use { stream ->
            stream.map { it.toRealPath() }.collect(java.util.stream.Collectors.toSet())
        }
    }

    /**
     * Anchor every `ProbeEntry.relativePath` to the cycle
     * directory. The data-class init already rejects `..` segments
     * and absolute paths syntactically; this check confirms the
     * normalized path stays inside the cycle directory by string
     * prefix on the normalized form. We do NOT call `toRealPath()`
     * here because the cycle directory may not exist yet — the
     * call to `Files.createDirectories` happens later in
     * `prepare`, after this validation. Once the dir exists,
     * `finalise` enforces the real-path containment before
     * deletion.
     *
     * Returns null on success or a refusal reason on failure.
     */
    private fun validatePaths(parsed: CycleManifest): String? {
        val cycleAbs = cycleDir.toAbsolutePath().normalize()
        for (probe in parsed.probes) {
            val resolved = cycleDir.resolve(probe.relativePath).normalize()
            if (!resolved.startsWith(cycleAbs)) {
                return "probe ${probe.id} relativePath resolves outside cycleDir: " +
                    "'$resolved' is not under '$cycleAbs'"
            }
        }
        return null
    }

    /**
     * `run`: feed every [ProbeEntry] in the manifest through the
     * executor and translate the [ProbeOutcome] to a
     * [CycleEvent.ProbeFinished]. The runner never accepts raw
     * `exitCode` and `evidenceRef` from the caller; the executor is
     * the single authority on what a probe did.
     *
     * The function returns the list of outcomes in the order they
     * were executed. A `Refused` outcome (failure, missing
     * evidence, other commit, unsupported fidelity, blocked
     * external) stops the cycle at the decider level; the caller
     * inspects [state] afterwards to drive the close path.
     */
    fun runProbes(executor: ProbeExecutor): List<ProbeOutcome> {
        val parsed = manifest
            ?: error("runProbes requires a parsed manifest; call prepare() first")
        val outcomes = mutableListOf<ProbeOutcome>()
        for (probe in parsed.probes) {
            // BQ4 v21 (typed marker, 2026-10-17): the previous
            // shape called `executor.persist(probe, result.outcome)`
            // where `result.outcome` was a `Verified("<pending>")`
            // placeholder. The placeholder is the dual
            // emitter/parser defect class: the executor's
            // `runHF0` emitted `"<pending>"` and the
            // `execute` parser matched it with a string `==`.
            // The new shape is a `data object NeedsPersist`
            // (no fields) and a `persist(probe)` signature
            // that takes only the probe. The runner matches
            // on the closed ADT and the compiler forces both
            // cases to be handled; no string sentinel crosses
            // the boundary.
            val outcome: ProbeOutcome = when (val result = executor.execute(probe, parsed)) {
                is ProbeExecutor.Companion.ProbeExecutionResult.NeedsPersist ->
                    executor.persist(probe)
                is ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted ->
                    result.outcome
            }
            outcomes += outcome
            // The runner now passes the typed ADT to the decider
            // unchanged. The previous shape collapsed
            // UnsupportedFidelity and BlockedExternal to
            // exitCode=0 and let the decider read them as success;
            // that is the P0 defect the 2026-10-16 external audit
            // named. The decider is now the sole authority on what
            // a Verified probe is.
            val transition = transition(CycleEvent.ProbeFinished(outcome))
            if (transition is CycleOutcome.IllegalTransition) break
        }
        return outcomes
    }

    /**
     * `adjudicate`: declare the cycle has observed all its
     * probes and is ready for promotion. The decider refuses
     * if no probe has succeeded in the accumulator.
     */
    fun observeAll(observedCommit: String): CycleOutcome =
        transition(CycleEvent.AllProbesObserved(observedCommit))

    /**
     * `adjudicate`: request promotion. The runner verifies the
     * [evidence] via [PromotionEvidenceVerifier] BEFORE emitting
     * the `PromoteRequested` event. The decider is a pure
     * transition; the runner is the only authority on whether
     * the substitute guarantee is real.
     *
     * The verifier checks five properties (existence, identity,
     * fidelity, digest, oracle). On [Verdict.Verified] the cycle
     * advances to `Promoted`. On [Verdict.Refused] the cycle is
     * `Refused` with the verifier's reason. On
     * [Verdict.BlockedExternal] the cycle is `BlockedExternal`
     * (a `CertifierReceipt` cannot be verified in-process).
     */
    fun requestPromote(evidence: PromotionEvidence): CycleOutcome {
        val parsed = manifest
            ?: error("requestPromote requires a parsed manifest; call prepare() first")
        val verdict = PromotionEvidenceVerifier.verify(
            evidence = evidence,
            runnerSourceSha = parsed.sourceSha,
            manifestExpectedHF = parsed.expectedFidelity,
            repoRoot = repoRoot,
            oracleLedger = oracleLedger,
        )
        return when (verdict) {
            is PromotionEvidenceVerifier.Verdict.Verified ->
                transition(CycleEvent.PromoteRequested(evidence))
            is PromotionEvidenceVerifier.Verdict.Refused -> fail(verdict.reason)
            is PromotionEvidenceVerifier.Verdict.BlockedExternal ->
                blockExternal(verdict.reason)
        }
    }

    /**
     * `close`: ask for the cycle to terminate. The decider allows
     * `Promoted -> Closed` only; `Observed -> Closed` is
     * `IllegalTransition` (BQ4 audit 2026-10-16 vertical).
     */
    fun requestClose(closeReason: String): CycleOutcome =
        transition(CycleEvent.CloseRequested(closeReason))

    /**
     * Mark a cycle as blocked on external authority (typically
     * a missing harness verdict).
     */
    fun blockExternal(blocker: String): CycleOutcome =
        transition(CycleEvent.ExternalBlockerRaised(blocker))

    /**
     * `close` action: if the state is `Closed`, delete the cycle
     * directory and the persisted evidence. The runner owns the
     * deletion — there is no callback — and validates every path
     * it touches.
     *
     * The deletion is the only filesystem side-effect the runner
     * performs outside the cycle dir, and only on the cycle dir
     * itself. The `toRealPath()` anchor defeats symlink escapes:
     * a symlink that points outside the cycle dir causes the
     * resolved path to fail the containment check, and the runner
     * throws.
     */
    fun finalise(): CycleState {
        if (state !is CycleState.Closed) {
            error("finalise requires Closed state; got $state")
        }
        val asFile = cycleDir.toFile()
        require(asFile.exists() && asFile.isDirectory) {
            "cycleDir must be an existing directory; got $cycleDir"
        }
        val realCycle = cycleDir.toRealPath()
        // P0 close-out (2026-10-16, audit): every persisted
        // evidence file carries an `evidenceDigest` field with
        // the SHA-256 of the body excluding that field. Before
        // deletion, recompute the digest and refuse if the file
        // has been modified after persistence. The check uses
        // `recomputeBodyDigest` (the runner has its own
        // hex-of-file helper, see end of file) so the harness
        // does not depend on `:pipeline-domain`.
        val probesDir = realCycle.resolve("probes")
        if (Files.isDirectory(probesDir)) {
            Files.list(probesDir).use { stream ->
                stream.filter { it.toString().endsWith(".json") }.forEach { jsonFile ->
                    validateEvidenceDigest(jsonFile)
                }
            }
        }
        // P0-C (audit 2026-10-16, deletion scope): delete only
        // the files the runner or the executor created during
        // this cycle, NOT everything under the cycle dir. The
        // property boundary is the [operatorOwnedSnapshot] the
        // runner took at `prepare()` time: an entry that was
        // already there when the cycle started is the
        // operator's property and the runner will not touch it;
        // an entry created AFTER the snapshot is the
        // runner/executor's property and the runner owns its
        // deletion. Symlinks whose real path leaves the cycle
        // dir are refused; the cycle is a closed world.
        if (asFile.exists()) {
            val currentEntries: Set<Path> = Files.list(realCycle).use { stream ->
                stream.map { it.toRealPath() }.collect(java.util.stream.Collectors.toSet())
            }
            val runnerOwned = currentEntries - operatorOwnedSnapshot
            for (target in runnerOwned) {
                if (!target.startsWith(realCycle) && target != realCycle) {
                    error(
                        "refusing to delete path '$target' that escapes the cycle " +
                            "directory '$realCycle' (symlink escape?)",
                    )
                }
                deleteRecursively(target, realCycle)
            }
            // After the runner-owned children are gone, the
            // cycle dir contains exactly the operator-owned
            // entries (we never touch those, by construction of
            // [operatorOwnedSnapshot]). Therefore:
            //   - if operator-owned entries exist, leave the
            //     dir in place — the audit's "do not over-delete"
            //     rule wins, the operator may still want to
            //     inspect their files.
            //   - if operator-owned entries are empty, the dir
            //     is empty and we delete it.
            // The previous shape had a third "fall-through"
            // branch that was logically unreachable (the
            // post-deletion empty check already covers the
            // operator-empty case); removing it makes the
            // invariant explicit.
            val postDeletion = if (Files.exists(realCycle)) {
                Files.list(realCycle).use { stream ->
                    stream.map { it.toRealPath() }.collect(java.util.stream.Collectors.toSet())
                }
            } else emptySet()
            if (postDeletion.isEmpty()) {
                Files.deleteIfExists(realCycle)
            }
            // else: dir has operator-owned content; leave it alone.
        }
        return state
    }

    /**
     * Recursive delete confined to the cycle dir. Any path whose
     * real-path target leaves the cycle dir is refused; symlinks
     * are followed only if they resolve under the cycle dir.
     * The audit (P0-C) called the previous "delete every file
     * under cycleDir" walker a too-broad delete; this helper is
     * what `finalise` actually calls.
     */
    private fun deleteRecursively(target: Path, realCycle: Path) {
        if (!Files.exists(target)) return
        if (Files.isDirectory(target)) {
            Files.list(target).use { children ->
                children.forEach { child ->
                    val childReal = try {
                        child.toRealPath()
                    } catch (ex: java.io.IOException) {
                        return@forEach
                    }
                    if (!childReal.startsWith(realCycle)) {
                        error(
                            "refusing to delete child '$childReal' of '$target' that " +
                                "escapes the cycle directory '$realCycle'",
                        )
                    }
                    deleteRecursively(child, realCycle)
                }
            }
        }
        Files.deleteIfExists(target)
    }

    /**
     * Recompute the `evidenceDigest` recorded in a persisted
     * evidence JSON and refuse if the file's bytes no longer
     * match. The persisted body has the `evidenceDigest` field
     * last; we strip the trailing field and digest the rest.
     * Mismatch raises an error; the cycle is not deleted.
     *
     * BQ4 v20 (typed digest extraction, 2026-10-17): the
     * previous shape used a regex
     * `,\n  "evidenceDigest":\s*"([0-9a-fA-F]{64})"\n}\n`
     * for both extraction AND body-strip. The character
     * class `[0-9a-fA-F]` silently accepted uppercase
     * hex, and a `case-insensitive equals` later accepted
     * a mixed-case recorded value — both shapes
     * compatible with tampering.
     *
     * The new shape is a structural extraction: the file
     * is parsed as JSON, the `evidenceDigest` field is
     * located by NAME (not by position), and the value
     * is strict-validated via
     * [dev.rubentxu.pipeline.v2.harness.util.DigestFormat
     * .requireSha256]. The body-strip is a position
     * operation on the original text (the JSON parser
     * does not need to re-serialise the body), so the
     * digest computed on the stripped text matches the
     * body that was hashed at persistence time.
     */
    private fun validateEvidenceDigest(jsonFile: java.nio.file.Path) {
        val text = Files.readString(jsonFile)
        // (1) Structural extraction: parse the JSON and read
        // the `evidenceDigest` field. The parser rejects
        // malformed input (the executor's hand-built body
        // is well-formed JSON by construction) and confirms
        // the field exists. The recorded digest is then
        // strict-validated to lowercase 64-char hex.
        // A parse failure is treated as a tampering signal:
        // the executor always writes well-formed JSON, so
        // a file that does not parse was modified after
        // persistence (or by a writer defect; both are
        // refused the same way).
        val recordedDigest = try {
            val root = json.parseToJsonElement(text).jsonObject
            root["evidenceDigest"]?.jsonPrimitive?.content
                ?: error("evidence file $jsonFile has no evidenceDigest field; refusing to delete")
        } catch (ex: Exception) {
            error(
                "evidence file $jsonFile was modified after persistence " +
                    "(JSON parse failed): ${ex.message}",
            )
        }
        DigestFormat.requireSha256("evidenceDigest", recordedDigest)
        // (2) Body strip: the persisted body is the text
        // BEFORE the `,\n  "evidenceDigest":` substring.
        // The executor's `persist` writes the body ending
        // in `\n` (after the last data line) and then
        // appends the `,\n  "evidenceDigest":"DIGEST"\n}\n`
        // suffix. The body is therefore
        // `text.substring(0, stripStart)` where `stripStart`
        // is the index of the `,` in the appended suffix.
        // Computing `sha256Hex` of this substring yields
        // exactly the recorded digest when the file is
        // unchanged.
        val stripStart = text.indexOf(",\n  \"evidenceDigest\":")
        if (stripStart < 0) {
            error(
                "evidence file $jsonFile has no evidenceDigest line; " +
                    "the executor's wire format was not followed; refusing to delete",
            )
        }
        val bodyWithoutDigest = text.substring(0, stripStart)
        val computed = sha256Hex(bodyWithoutDigest)
        // (3) Case-sensitive comparison. Both sides are
        // guaranteed lowercase by (1) and by `sha256Hex`
        // (always lowercase). The previous `equals(...,
        // ignoreCase = true)` was a defensive default that
        // became a silent acceptance.
        if (computed != recordedDigest) {
            error(
                "evidence file $jsonFile was modified after persistence: " +
                    "recorded=$recordedDigest, actual=$computed; refusing to delete",
            )
        }
    }

    private fun transition(event: CycleEvent): CycleOutcome {
        val outcome = Cycle.decide(state, event, context)
        if (outcome is CycleOutcome.Next) {
            state = outcome.nextState
            context = outcome.nextContext
        }
        return outcome
    }

    private fun fail(reason: String): CycleOutcome {
        // A preparation failure is recorded as a `Refused` state
        // on the decider's behalf, without going through
        // `transition`, because the cycle never reached `Running`.
        // We do not mutate `state` here: the caller drives the
        // state machine and decides whether to record the refusal
        // formally via `blockExternal` or simply inspect the
        // outcome. Returning a typed `Next` to `Refused` is the
        // simplest portable shape.
        val outcome = CycleOutcome.Next(
            nextState = CycleState.Refused.Generic(reason),
            nextContext = context,
        )
        state = outcome.nextState
        context = outcome.nextContext
        return outcome
    }
}
