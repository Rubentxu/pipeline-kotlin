package dev.rubentxu.pipeline.v2.application.harness

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import dev.rubentxu.pipeline.v2.application.DslCompiledPipelineCompiler
import dev.rubentxu.pipeline.v2.application.durable.CanonicalDurableRunCoordinator
import dev.rubentxu.pipeline.v2.application.durable.CanonicalNodeDispatcher
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeFailure
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopeOutcome
import dev.rubentxu.pipeline.v2.application.durable.credentials.CredentialScopePort
import dev.rubentxu.pipeline.v2.application.SystemClock
import dev.rubentxu.pipeline.v2.domain.Digest
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.domain.RunOutcome
import dev.rubentxu.pipeline.v2.domain.step.StepRegistry
import dev.rubentxu.pipeline.v2.dsl.PipelineSpec
import dev.rubentxu.pipeline.v2.events.durable.InMemoryEventStore
import dev.rubentxu.pipeline.v2.events.durable.InMemoryOperationJournal
import dev.rubentxu.pipeline.v2.events.durable.InMemoryReplayCursorStore
import dev.rubentxu.pipeline.v2.harness.catalog.ContractCatalogue.Fidelity
import dev.rubentxu.pipeline.v2.harness.cycle.CycleManifest
import dev.rubentxu.pipeline.v2.harness.cycle.ProbeEntry
import dev.rubentxu.pipeline.v2.harness.cycle.ProbeExecutor
import dev.rubentxu.pipeline.v2.harness.cycle.ProbeOutcome
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.DefaultEffectReplayPolicy
import dev.rubentxu.pipeline.v2.sdk.runtime.durable.ShOptions
import java.nio.file.Files
import java.nio.file.Path
import kotlinx.coroutines.runBlocking

/**
 * Product-side [ProbeExecutor] that runs HF1 probes for real
 * through the canonical [CanonicalDurableRunCoordinator].
 *
 * BQ4 audit (2026-10-16, P1 close-out): the harness's default
 * [ProbeExecutor] is product-agnostic and returns
 * `BlockedExternal` for HF1. This subclass extends the harness
 * executor (the only direction allowed: the harness defines the
 * base, the product specialises the behaviour), discovers the
 * same coordinator the in-process [PipelineRule] already certifies
 * (no privileged path, no shim, no second retry/journal/replay
 * authority), and maps a typed [RunOutcome] to a [ProbeOutcome].
 *
 * The wiring here is INTENTIONALLY the same as
 * [dev.rubentxu.pipeline.v2.application.support.PipelineRule.run]:
 * in-memory stores, default replay policy, no-op credential
 * scope. The HF1 probe is a real [CanonicalDurableRunCoordinator]
 * run, not a hand-rolled executor that bypasses the durable
 * pipeline. If the in-process template changes, this class
 * changes with it, and `PipelineRule` is the single place that
 * documents the contract.
 *
 * ## Discovery — this executor is NOT discovered
 *
 * **Measured 2026-10-17, and this section previously said the
 * opposite.** The harness's [ProbeExecutorDiscovery] resolves via
 * `ServiceLoader.load(ProbeExecutorContributor::class.java)`. As of
 * that measurement there is **no `ProbeExecutorContributor`
 * implementation anywhere in the repository** and **no
 * `META-INF/services/dev.rubentxu.pipeline.v2.harness.cycle.ProbeExecutorContributor`
 * file** — `resolve()` therefore takes its `0 ->` branch and
 * returns the harness default, whose HF1 outcome is
 * `BlockedExternal`.
 *
 * So the previous claim ("when the cycle runs with
 * `:pipeline-application` on the classpath, this executor is used")
 * described an intended seam that was never wired. Nothing ran this
 * class in production; it shipped inside the distribution JAR and was
 * constructed only by its own tests.
 *
 * Consequences, stated so no reader infers a guarantee from this file:
 *
 * - HF1 does **not** execute for real through `CycleCli` today. Any
 *   future claim that it does requires a contributor registration,
 *   not this class.
 * - The `RunOutcome -> ProbeOutcome` mapping below is covered
 *   adversarially by `InProcessProductProbeExecutorTest` and is
 *   retained on that basis alone.
 * - It now lives in `src/test` (moved out of `src/main` under
 *   ADR-PK-DF-002 H0/R1). Shipping QA scaffolding in the consumer
 *   binary was the defect; the class was dead in that JAR.
 *
 * Wiring the contributor was deliberately **not** done as part of
 * that move: it would re-create the runtime coupling that the same
 * ADR removes, and HF1-as-real-run is not a guarantee any current
 * receipt claims.
 *
 * ## HF0 delegation
 *
 * HF0 is not this executor's responsibility: the harness default
 * already has a real HF0 path (catalogue + content SHA verification
 * + evidence JSON). This class delegates HF0 to the super
 * implementation so a manifest that mixes HF0 and HF1 probes still
 * gets the right answer for the HF0 rows.
 *
 * ## Mapping RunOutcome -> ProbeExecutionResult
 *
 * - [RunOutcome.Success] -> `NeedsPersist`. The runner then
 *   calls `persist`, which writes the evidence JSON and returns
 *   the real `Verified(realPath)` the cycle records. The
 *   executor never fabricates an evidence path of its own.
 * - [RunOutcome.Failure] -> `AlreadyPersisted` with
 *   [ProbeOutcome.Failed] carrying `"<kind>: <message>"` so the
 *   reason is recoverable from the cycle's evidence.
 * - [RunOutcome.Unstable] -> `AlreadyPersisted` with
 *   [ProbeOutcome.Failed] carrying `"UNSTABLE: ..."`. A cycle
 *   that wanted to distinguish `unstable` would need a
 *   [ProbeOutcome] case for it; the closed set is what it is.
 * - [RunOutcome.Aborted] -> `AlreadyPersisted` with
 *   [ProbeOutcome.BlockedExternal] carrying `"aborted"`. The
 *   cycle was interrupted, not failed; a new run may succeed.
 */
class InProcessProductProbeExecutor(
    cycleDir: Path,
    private val specProvider: (ProbeEntry, CycleManifest) -> PipelineSpec,
    private val sourcePathFor: (ProbeEntry, CycleManifest) -> String = { probe, _ ->
        // The default identity source is the probe's relative
        // path, normalised against the cycle dir. A test that
        // wants a different identity (e.g. a `Pipeline.kts` name)
        // supplies a custom `sourcePathFor`.
        probe.relativePath
    },
    private val sourceContentFor: (ProbeEntry, CycleManifest) -> String = { probe, _ ->
        // The default source content is the probe's relative
        // path. The compiler uses `sourceContent` for the
        // definition-id fingerprint AND for the source digest,
        // and [DefinitionIdGenerator] rejects a blank value; an
        // in-process `pipeline { ... }` spec has no original
        // script text, so the probe's relative path is the
        // closest authoritative stand-in.
        probe.relativePath
    },
) : ProbeExecutor(cycleDir = cycleDir) {

    override fun execute(probe: ProbeEntry, manifest: CycleManifest): ProbeExecutor.Companion.ProbeExecutionResult {
        // HF0 stays in the harness default. The product only
        // owns HF1, and the audit's complaint was that HF1 was
        // `BlockedExternal` even though the same code path is
        // already certified in the in-process template.
        if (manifest.expectedFidelity != Fidelity.HF1) {
            return super.execute(probe, manifest)
        }
        // Mirrors the harness default: a SUCCESSFUL HF1 run is
        // `NeedsPersist` (the runner calls `persist` to write the
        // evidence JSON and receive the final
        // `Verified(realPath)`); every other outcome is terminal
        // and goes through as `AlreadyPersisted`.
        //
        // BQ4 v21 migration (2026-10-17): this executor still
        // spoke the OLD protocol — it built
        // `ProbeOutcome.Verified("<pending>")` as a placeholder
        // and asked `ProbeExecutor.isPendingOutcome` whether the
        // value was that placeholder. The refactor made
        // `NeedsPersist` a `data object` and deleted the
        // predicate, so `:pipeline-application` no longer
        // compiled and the break went unnoticed: the
        // `PendingEvidenceSentinelFitnessTest` that guards this
        // defect class scans ONLY
        // `test-harness/.../harness/cycle`, so the product-side
        // consumer was outside its reach. The typed decision
        // below removes the placeholder rather than translating
        // it.
        return when (val decision = runHF1(probe, manifest)) {
            Hf1Decision.Succeeded -> ProbeExecutor.Companion.ProbeExecutionResult.NeedsPersist
            is Hf1Decision.Terminal ->
                ProbeExecutor.Companion.ProbeExecutionResult.AlreadyPersisted(decision.outcome)
        }
    }

    /**
     * The typed result of one HF1 run.
     *
     * [Succeeded] carries no payload and no fabricated
     * `evidenceRef`: the real evidence path is produced by
     * [ProbeExecutor.persist] when the runner acts on
     * `NeedsPersist`. [Terminal] carries the actual
     * [ProbeOutcome] to record as-is.
     *
     * The previous shape returned `ProbeOutcome.Verified` with a
     * `<pending>` string that no reader ever consumed — a dead
     * semantic parameter that a reader could only misinterpret as
     * a real evidence location.
     */
    private sealed interface Hf1Decision {
        data object Succeeded : Hf1Decision
        data class Terminal(val outcome: ProbeOutcome) : Hf1Decision
    }

    private fun runHF1(probe: ProbeEntry, manifest: CycleManifest): Hf1Decision {
        val workDir = realCycleDir().resolve("work-${probe.id}")
        try {
            Files.createDirectories(workDir)
        } catch (ex: java.io.IOException) {
            return Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "could not create HF1 work dir at $workDir: ${ex.message}",
                ),
            )
        }
        val spec = try {
            specProvider(probe, manifest)
        } catch (ex: Throwable) {
            return Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "HF1 spec provider threw: ${ex::class.simpleName}: ${ex.message}",
                ),
            )
        }
        val sourcePath = sourcePathFor(probe, manifest)
        val sourceContent = sourceContentFor(probe, manifest)
        val compiled = try {
            DslCompiledPipelineCompiler.compile(
                spec = spec,
                sourcePath = sourcePath,
                sourceContent = sourceContent,
                pluginLockDigest = Digest("builtin"),
            )
        } catch (ex: Throwable) {
            return Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "DslCompiledPipelineCompiler refused: ${ex::class.simpleName}: ${ex.message}",
                ),
            )
        }
        val clock = SystemClock()
        val eventStore = InMemoryEventStore()
        val journal = InMemoryOperationJournal(clock)
        val cursorStore = InMemoryReplayCursorStore(clock)
        val registry: StepRegistry = CoreStepRegistryFactory.registry()
        val coord = CanonicalDurableRunCoordinator(
            dispatcher = CanonicalNodeDispatcher(),
            journal = journal,
            cursorStore = cursorStore,
            clock = clock,
            effectReplayPolicy = DefaultEffectReplayPolicy(),
            eventSink = eventStore,
            credentialScopePort = noOpCredentialScopePort(),
            controlDirRoot = workDir.resolve("control"),
            shOptions = ShOptions(
                workspaceRoot = workDir.resolve("workspace"),
                captureStdout = false,
                timeoutMs = null,
                env = emptyMap(),
            ),
            stepRegistry = registry,
        )
        val runId = RunId("hf1-${probe.id}")
        val outcome: RunOutcome = try {
            runBlocking { coord.run(compiled, runId) }
        } catch (ex: Throwable) {
            return Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "CanonicalDurableRunCoordinator threw: ${ex::class.simpleName}: ${ex.message}",
                ),
            )
        }
        return when (outcome) {
            // No placeholder `Verified`: a success carries no
            // evidence path of its own. `NeedsPersist` tells the
            // runner to call `persist`, and the real path comes
            // back from there.
            is RunOutcome.Success -> Hf1Decision.Succeeded
            is RunOutcome.Failure -> Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "${outcome.failure.kind.name}: ${outcome.failure.message}",
                ),
            )
            is RunOutcome.Unstable -> Hf1Decision.Terminal(
                ProbeOutcome.Failed(
                    evidenceRef = workDir.toString(),
                    reason = "UNSTABLE: ${probe.id}",
                ),
            )
            is RunOutcome.Aborted -> Hf1Decision.Terminal(
                ProbeOutcome.BlockedExternal(
                    evidenceRef = workDir.toString(),
                    blocker = "aborted: ${probe.id}",
                ),
            )
        }
    }

    private fun noOpCredentialScopePort(): CredentialScopePort = CredentialScopePort { _, _ ->
        CredentialScopeOutcome.Unavailable(
            CredentialScopeFailure.StoreUnavailable("hf1 probe executor: no credential store wired"),
        )
    }
}
