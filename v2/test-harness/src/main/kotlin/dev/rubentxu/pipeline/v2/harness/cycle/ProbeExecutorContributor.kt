package dev.rubentxu.pipeline.v2.harness.cycle

import java.nio.file.Path
import java.util.ServiceLoader

/**
 * Discovery seam for product-side [ProbeExecutor] implementations.
 *
 * BQ4 audit (2026-10-16, P1): the harness's default [ProbeExecutor]
 * is product-agnostic and returns `BlockedExternal` for HF1 because
 * the harness does not depend on `:pipeline-application`. To run HF1
 * for real, an executor must be supplied that invokes a real
 * `PipelineSpec` through the canonical coordinator. The contract
 * for that executor is a `ProbeExecutor` subclass.
 *
 * The harness cannot import that subclass (it would couple the
 * harness to the product). The hexagonal-friendly contract is a
 * ServiceLoader binding: a product that wants its HF1 probes to
 * run for real contributes a [ProbeExecutorContributor] under
 * `META-INF/services/`, and the harness discovers it at startup.
 *
 * Discovery rules:
 * - At most ONE contributor is allowed in the classpath. Multiple
 *   contributors is a contract violation: the cycle cannot decide
 *   which one wins, and "first wins" is exactly the failure mode
 *   the rule against `StepDefinitionContributor` collisions was
 *   written to prevent.
 * - When no contributor is present, the harness uses its own
 *   [ProbeExecutor] (HF0 only; HF1 returns `BlockedExternal`).
 * - The contributor's `create(cycleDir)` MUST return a
 *   [ProbeExecutor] that owns its own state. The harness does not
 *   retain the contributor instance; only the executor it returns.
 */
fun interface ProbeExecutorContributor {
    fun create(cycleDir: Path): ProbeExecutor
}

/**
 * The single chokepoint the [CycleCli] uses to obtain a
 * [ProbeExecutor] for a cycle. The default executor is the
 * harness-internal one; if a [ProbeExecutorContributor] is on
 * the classpath, its product is preferred.
 *
 * The discovery is performed once per cycle. Re-discovery within a
 * single cycle is forbidden: a re-scan that picks up a different
 * contributor mid-run would split the cycle's evidence across two
 * authority domains.
 */
object ProbeExecutorDiscovery {

    /**
     * Returns the [ProbeExecutor] the cycle should use. If a
     * `ProbeExecutorContributor` is registered via ServiceLoader, its
     * `create(cycleDir)` is used. Otherwise the harness default is
     * returned. Multiple contributors cause a fail-closed error.
     */
    fun resolve(cycleDir: Path): ProbeExecutor {
        val contributors = ServiceLoader.load(
            ProbeExecutorContributor::class.java,
            ProbeExecutorContributor::class.java.classLoader,
        ).toList()
        return when (contributors.size) {
            0 -> ProbeExecutor(cycleDir = cycleDir)
            1 -> contributors.single().create(cycleDir)
            else -> error(
                "multiple ProbeExecutorContributor implementations on the classpath: " +
                    contributors.joinToString { it::class.java.name } +
                    "; the harness refuses to choose. Remove all but one.",
            )
        }
    }
}
