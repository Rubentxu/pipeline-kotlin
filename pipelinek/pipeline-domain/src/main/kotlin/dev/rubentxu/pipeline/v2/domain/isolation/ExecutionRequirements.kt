package dev.rubentxu.pipeline.v2.domain.isolation

/**
 * What the operator asks the runtime to guarantee about the code it is about to run.
 *
 * This is the REQUEST, not the verdict and not the environment. It is a value: two
 * operators asking for the same thing produce equal requests, and a request can be
 * compared, printed and stored without a runtime anywhere in sight.
 *
 * ## Why a request and not a flag
 *
 * The product already had a way to ask for more isolation than it could give:
 * `--sandbox-profile os` was refused by name, with a message that said the value
 * was "not supported in this build". That refusal was true and it was useless: it
 * said WHICH word was refused and nothing about what the word asks for, so an
 * operator could not learn whether a different word, or a different question,
 * would be answerable. A request can be inspected on its own terms, and a verdict
 * can name the capabilities that were missing — which is what makes "ask for the
 * impossible" an answerable question instead of a wall.
 *
 * ## The dimensions are the ones an operator can actually state
 *
 * Filesystem, network, resources and credentials are the four things a run can be
 * trusted with, and each of them has a legacy behaviour that is already in force:
 * the workspace-scoped step, the egress gate, the unbounded budget and the
 * credential store. A request therefore says what the operator wants DONE with
 * that dimension, and the legacy behaviour is what a request that says nothing
 * gets.
 *
 * Paths and destinations are [String], not `java.nio.file.Path`: this value is
 * published, comparable and printable, and a path is only meaningful once a
 * provider resolves it against a real filesystem — which is a later question than
 * the one this value answers.
 */
enum class TrustIntent {
    /**
     * The historical PipelineK posture: one operator, one machine, one tenant.
     *
     * The run is trusted because the operator who asked for it is the operator who
     * owns the machine. Nothing about that trust is technical, and the request
     * says so by asking for no capability beyond the legacy behaviour.
     */
    TrustedSingleTenant,

    /**
     * The run is code this product does not trust with the machine.
     *
     * Nothing that exists today can honour this intent, so a request that carries
     * it is refused until a provider proves it can. The refusal is the point: the
     * alternative is to accept the word "untrusted" and confine nothing.
     */
    UntrustedIsolated,
}

/** What the run is allowed to reach on the filesystem. */
sealed interface FilesystemAccess {
    /**
     * Steps reach the run's own workspace and nothing else.
     *
     * This is the legacy behaviour, and it is what a request that names no
     * filesystem posture gets. It is a workspace, not a jail: the guard refuses
     * a path that escapes the workspace, but a process the step starts is not
     * itself confined to it.
     */
    data object WorkspaceScoped : FilesystemAccess

    /**
     * The run may read the named paths and write nothing.
     *
     * Asking for this is asking for a capability, not for a policy: only a
     * provider that confines the filesystem can honour it, and a request that
     * names it is refused by a host that cannot.
     */
    data class ApprovedReadonly(val paths: List<String>) : FilesystemAccess

    /**
     * The run writes inside its own workspace, and the rest of the system is
     * read-only to it.
     *
     * This is the difference between [WorkspaceScoped] and a boundary.
     * [WorkspaceScoped] is a rule about which paths the runtime hands to the
     * step: a process the step starts is not itself confined to them. This one
     * is a claim about the run as a whole, and only a provider that confines
     * the filesystem can make it — so a request that names it is refused by a
     * host that cannot.
     */
    data object ConfinedWorkspace : FilesystemAccess
}

/** What the run is allowed to reach on the network. */
sealed interface NetworkAccess {
    /**
     * No egress.
     *
     * In the trusted posture this is the legacy behaviour — the runtime refuses
     * a step that declares the network capability — and it stays the default.
     * In the untrusted posture it becomes a demand: the provider has to prove it
     * can deny egress, because "no step asked for the network" is a weaker
     * statement than "no socket left the machine".
     */
    data object Denied : NetworkAccess

    /**
     * Egress, constrained to the named destinations.
     *
     * The legacy `--allow-network` is a different axis and is not this: it grants
     * the egress capability to the steps that declare it, without naming any
     * destination. This is the request for a boundary, and only a provider can
     * honour it.
     */
    data class Allowlisted(val destinations: List<String>) : NetworkAccess

    /**
     * The run's CHILD PROCESS has no network of its own, and the fact is
     * enforced rather than declared.
     *
     * [Denied] is the legacy behaviour and it is a weaker statement: no step
     * asked for the network, and the runtime refuses a step that declares the
     * capability. This one is a demand — the provider has to prove that the
     * child process is put in a network namespace of its own, because "nothing
     * asked" is not "nothing left".
     *
     * The demand reaches the PROCESS and not the runtime's own process, where
     * the in-process plugins live: those are governed by the egress gate, which
     * is a different mechanism with a different owner.
     */
    data object Isolated : NetworkAccess
}

/** A CPU budget, in cores. */
data class CpuLimit(val cores: Double)

/** A memory budget, in bytes. */
data class MemoryLimit(val bytes: Long)

/** A process budget, in processes. */
data class PidsLimit(val processes: Int)

/** A wall-clock budget, in seconds. */
data class TimeLimit(val seconds: Long)

/**
 * The resources the run is allowed to consume.
 *
 * Unbounded is the legacy behaviour: a run with no budget spends what it needs.
 * Bounded is a request for a provider that can enforce limits — the four limits
 * travel together because they are enforced by the same mechanism, and a host
 * that can enforce one of them can enforce all of them.
 */
sealed interface ResourceBudget {
    /**
     * No budget: the run spends what it needs.
     *
     * This is the legacy behaviour, and it is what a request that names no
     * resource posture gets. A host that can enforce limits does not apply
     * them here.
     */
    data object Unbounded : ResourceBudget

    /**
     * A budget the run is held to.
     *
     * Every limit present is one the run may not exceed; a limit absent is
     * a dimension the run is not bounded on. Asking for a bounded budget is
     * asking for a capability, so only a host that can enforce limits
     * honours the request.
     */
    data class Bounded(
        val cpu: CpuLimit? = null,
        val memory: MemoryLimit? = null,
        val pids: PidsLimit? = null,
        val time: TimeLimit? = null,
    ) : ResourceBudget
}

/** Which credential material the run is allowed to resolve. */
sealed interface SecretGrant {
    /**
     * No credential material.
     *
     * The legacy behaviour: a run that binds nothing resolves nothing, and the
     * credential store is not even opened.
     */
    data object None : SecretGrant

    /**
     * Exactly these credentials, and no other.
     *
     * The identifiers are names, not handles: a handle that survives into a plan
     * or into telemetry is a secret that survived into somewhere it can be read.
     */
    data class Scoped(val ids: List<String>) : SecretGrant
}

/**
 * The complete request: what the operator wants the runtime to guarantee.
 *
 * Every dimension defaults to the legacy behaviour, so a request built from
 * flags the operator did not use is exactly the behaviour the product had before
 * this type existed. That is the property that keeps the legacy posture honest:
 * nothing changes for an operator who asks for nothing new.
 */
data class ExecutionRequirements(
    val trustIntent: TrustIntent = TrustIntent.TrustedSingleTenant,
    val filesystem: FilesystemAccess = FilesystemAccess.WorkspaceScoped,
    val network: NetworkAccess = NetworkAccess.Denied,
    val resources: ResourceBudget = ResourceBudget.Unbounded,
    val secrets: SecretGrant = SecretGrant.None,
)
