package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.RuntimeConfig
import dev.rubentxu.pipeline.v2.domain.directive.AgentLabel
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetRequirement
import dev.rubentxu.pipeline.v2.domain.directive.ExecutionTargetResolver
import dev.rubentxu.pipeline.v2.domain.directive.TargetLeaseResult
import dev.rubentxu.pipeline.v2.domain.step.StepCapability

/**
 * S3.1 — the local execution-target resolver.
 *
 * The single-host profile. It answers every [ExecutionTargetRequirement] case
 * with a typed result, and it is the ONLY place in the runtime that decides
 * whether a target exists, because a resolver is the only thing allowed to.
 *
 * ## What "local" can honestly satisfy
 *
 * [ExecutionTargetRequirement.LocalAny] always succeeds: the run is already
 * executing on the machine it will use.
 *
 * [ExecutionTargetRequirement.LocalLabels] is decided against the labels the
 * host actually advertises, derived from [RuntimeConfig.osName]. This is
 * deliberately a real, observable property of the machine and not a
 * configuration file: `agent { label 'linux' }` succeeds on Linux and is
 * refused on Windows, which is what a Jenkins user expects and what a
 * hard-coded label list would only simulate.
 *
 * [ExecutionTargetRequirement.CapabilitySet] is decided against the
 * capabilities the runtime was actually granted, not against a static table of
 * capabilities that exist in principle. A target that cannot supply what a
 * step needs is not a target this run may use, and reading the granted set is
 * what keeps the two in agreement.
 *
 * ## What it refuses, and why that is the feature
 *
 * [ExecutionTargetRequirement.Remote] is refused. There is no remote allocator
 * until RP-8, and a resolver that "succeeded" for a remote selector would be
 * running the stage somewhere the author named while the runtime never
 * contacted it — the exact silent semantic drop this train exists to remove.
 *
 * The diagnostic names RP-8 rather than saying "unsupported", because the
 * author needs to know whether to restructure their pipeline or wait. The
 * distinction between "nothing here satisfies this" and "this runtime cannot
 * satisfy it at all" is preserved in the wording of [TargetLeaseResult.Refused]
 * and is carried through into the run failure.
 *
 * ## Why the defaults are the defaults
 *
 * Both parameters default, and both defaults are decisions rather than
 * placeholders.
 *
 * The [RuntimeConfig] default is [SystemRuntimeConfig], the one adapter
 * `FArchM1CanonicalRuntimeConfigTest` recognises as the only class permitted
 * to read `System.getenv` / `System.getProperty`. This resolver asks the port
 * for the platform and never reads the host itself, so `agent(label = ...)`
 * and `core.isUnix` cannot disagree about which machine they are on: there is
 * one platform authority and this is not a second one. It is also read at
 * acquire time rather than construction time, which is what [RuntimeConfig]
 * documents and what keeps the answer honest if the JVM is reconfigured
 * mid-flight.
 *
 * The empty [grantedCapabilities] default makes a [ExecutionTargetRequirement.CapabilitySet]
 * fail closed WITH a diagnostic, whereas a resolver seeded with "everything the
 * runtime could provide in principle" would GRANT requirements nobody
 * verified. This runtime has no composition-time capability inventory —
 * capabilities are bound per invocation in the execution boundary — so the
 * honest answer until a composition root really knows the granted set is to
 * refuse, and refusing is observable rather than silent.
 */
class LocalExecutionTargetResolver(
    private val runtimeConfig: RuntimeConfig = SystemRuntimeConfig(),
    private val grantedCapabilities: Set<StepCapability> = emptySet(),
) : ExecutionTargetResolver {

    override fun acquire(requirement: ExecutionTargetRequirement): TargetLeaseResult {
        // The identifier a granted target is reported under. Deliberately the
        // platform, not a synthetic "local-0": the whole point of the local
        // profile is that the target is observable, and an operator reading an
        // event wants to know which machine ran, not which index it was in a
        // list. Read once per acquisition so every case in the `when` below
        // reports the same machine.
        val platform = runtimeConfig.osName()
        return when (requirement) {
            is ExecutionTargetRequirement.LocalAny -> TargetLeaseResult.Granted(platform)

            is ExecutionTargetRequirement.LocalLabels -> {
                val advertised = advertisedLabels(platform)
                val missing = requirement.labels - advertised
                if (missing.isEmpty()) {
                    TargetLeaseResult.Granted(platform)
                } else {
                    TargetLeaseResult.Refused(
                        "no local execution target satisfies the declared labels " +
                            "${requirement.labels.map { it.value }.sorted()}: this host " +
                            "($platform) advertises " +
                            advertised.map { it.value }.sorted() +
                            ", and the run is single-host, so it cannot look elsewhere",
                    )
                }
            }

            is ExecutionTargetRequirement.CapabilitySet -> {
                val missing = requirement.required - grantedCapabilities
                if (missing.isEmpty()) {
                    TargetLeaseResult.Granted(platform)
                } else {
                    TargetLeaseResult.Refused(
                        "the local execution target was not granted the required capabilities " +
                            missing.map { it.key }.sorted() +
                            "; a requirement is checked against what the run was actually granted, " +
                            "not against what the runtime could provide in principle",
                    )
                }
            }

            is ExecutionTargetRequirement.Remote -> TargetLeaseResult.Refused(
                "remote execution target '${requirement.selector.value}' cannot be acquired: " +
                    "this runtime is single-host and has no remote allocator. Remote allocation " +
                    "arrives with RP-8 (controller/worker with a versioned handshake, leases and " +
                    "fencing). Declaring it here fails closed rather than running the stage on the " +
                    "local host, which would silently ignore the selector.",
            )
        }
    }

    /**
     * Labels the local host advertises.
     *
     * `unix` is added for every non-Windows platform because that is the label
     * Jenkins authors actually write, and a label set that only ever contains
     * the raw os.name would refuse `agent { label 'unix' }` on a machine that
     * obviously is one.
     */
    private fun advertisedLabels(osName: String): Set<AgentLabel> = buildSet {
        add(AgentLabel(osName.lowercase()))
        if (!osName.startsWith("windows", ignoreCase = true)) {
            add(AgentLabel("unix"))
        }
    }
}
