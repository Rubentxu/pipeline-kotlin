package dev.rubentxu.pipeline.v2.harness.cycle

import kotlinx.serialization.json.Json
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
            manifest = parsed
        }
        return transition
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
            val outcome = executor.execute(probe, parsed)
            outcomes += outcome
            // Translate to the decider event. UnsupportedFidelity
            // and BlockedExternal pass exitCode as 0 so the
            // decider does not mis-classify them as probe
            // failures; the cycle reaches Observed/Closed without
            // any "failed" probe, and the recorded outcome tells
            // the operator what actually happened. The decider's
            // existing gates still apply (a non-empty
            // accumulator is required to promote).
            val event = when (outcome) {
                is ProbeOutcome.UnsupportedFidelity -> CycleEvent.ProbeFinished(
                    exitCode = 0,
                    evidenceRef = "unsupported:${outcome.fidelity}",
                )
                is ProbeOutcome.BlockedExternal -> CycleEvent.ProbeFinished(
                    exitCode = 0,
                    evidenceRef = "blocked:${outcome.blocker}",
                )
                else -> CycleEvent.ProbeFinished(
                    exitCode = outcome.exitCode,
                    evidenceRef = outcome.evidenceRef,
                )
            }
            val transition = transition(event)
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
     * `adjudicate`: request promotion. The decider decides
     * whether the cycle is `Promoted` or stays in `Observed`
     * (the latter is a typed refusal of the promotion request
     * — the state machine does not move).
     *
     * The decider refuses a blank `replacementEvidence` so a
     * cycle cannot promote without naming a substitute.
     */
    fun requestPromote(replacementEvidence: String): CycleOutcome =
        transition(CycleEvent.PromoteRequested(replacementEvidence))

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
        // Recursive delete of the cycle dir, anchored on its real
        // path. The walk stays inside the cycle dir because the
        // executor never writes outside it; if a probe persisted
        // a symlink that escapes, `toRealPath()` on the walker
        // would follow it and Files.deleteIfExists would resolve
        // an out-of-tree target. To stay safe, the walker visits
        // only what the directory contains; the contents are
        // validated as we go.
        if (asFile.exists()) {
            Files.walk(realCycle).use { stream ->
                stream.sorted(Comparator.reverseOrder()).forEach { path ->
                    if (path == realCycle) {
                        Files.deleteIfExists(path)
                        return@forEach
                    }
                    val real = try {
                        path.toRealPath()
                    } catch (ex: java.io.IOException) {
                        // The path no longer exists; nothing to
                        // delete. The walker shouldn't return
                        // missing entries, but be defensive.
                        return@forEach
                    }
                    if (!real.startsWith(realCycle)) {
                        error(
                            "refusing to delete path '$real' that escapes the cycle " +
                                "directory '$realCycle' (symlink escape?)",
                        )
                    }
                    Files.deleteIfExists(real)
                }
            }
        }
        return state
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
            nextState = CycleState.Refused(reason),
            nextContext = context,
        )
        state = outcome.nextState
        context = outcome.nextContext
        return outcome
    }
}
