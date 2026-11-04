package dev.rubentxu.pipeline.v2.domain.isolation

/**
 * The things a host can be asked to PROVE about a run's confinement.
 *
 * ## The word is "prove"
 *
 * A capability here is not a name of a feature and not a flag. It is a claim that
 * a named provider can enforce a named boundary, and it is worthless without the
 * evidence that backs it — which is why a proven capability always travels with
 * the evidence that proves it ([ProvenCapability]) and never alone.
 *
 * The consequence is the whole point of this file: the legacy trusted posture
 * proves NONE of these. It applies a deny-list to the environment, changes the
 * working directory and refuses a step that declares the network capability, and
 * none of that confines a filesystem, a process tree or an evaluation. A product
 * that reported "sandbox: yes" because a profile was named would be claiming a
 * boundary it does not have.
 *
 * ## Why evaluation isolation is a capability and not a detail
 *
 * A script that is about to be evaluated is code that runs before any step
 * exists, in the same process, with the same ambient authority as the compiler.
 * A provider that confines the steps but evaluates the script in the parent
 * process has confined the part that was already trusted and left the part that
 * was not. So "the DSL evaluation is isolated" is a claim about the provider
 * itself, and it is listed here for that reason.
 */
enum class IsolationCapability {
    /** The run's filesystem reach is bounded to what the request allows. */
    FilesystemConfinement,

    /** The run executes under a user namespace, or an equivalent identity boundary. */
    UserNamespace,

    /** The run's network reach is bounded to what the request allows. */
    NetworkConfinement,

    /** The run's process tree cannot outlive or escape the boundary it was given. */
    ProcessConfinement,

    /** The run's resource consumption can be bounded by the request's budget. */
    ResourceLimits,

    /** Credential material is bound to the run's grant, not to the ambient machine. */
    CredentialIsolation,

    /** A launcher can start the run inside the boundary it claims. */
    SandboxLauncher,

    /** The DSL evaluation itself happens inside the boundary, not in the parent process. */
    DslEvaluationIsolation,
}

/**
 * A capability, together with the evidence that proves it.
 *
 * The evidence is a human-readable string on purpose: the refusal an operator
 * reads has to be able to say WHY a capability was believed, and a bare
 * capability name is a claim nobody can check.
 */
data class ProvenCapability(
    val capability: IsolationCapability,
    val evidence: String,
)

/**
 * What the host says about its own isolation, before anything is run.
 *
 * The three cases are the three honest answers, and the third one matters most:
 * a host that cannot say what it proves must not be reported as a host that
 * proves nothing, because the difference between "I confine nothing" and "I
 * could not find out whether I confine" is the difference between a refusal an
 * operator can act on and one they cannot.
 */
sealed interface IsolationAttestation {
    /**
     * The only environment this build offers: the legacy trusted posture.
     *
     * It is an environment, and it is the one that is in force today, but it
     * proves no [IsolationCapability] — the deny-list it applies is not a
     * boundary, and the difference has to stay visible in the verdict.
     */
    data object TrustedProfileOnly : IsolationAttestation

    /**
     * A real provider, with the evidence for each capability it proves.
     *
     * The list is the provider's own claims: an empty list is a provider that
     * proves nothing, which is not the same as no provider at all.
     */
    data class Provider(val proven: List<ProvenCapability>) : IsolationAttestation

    /**
     * A provider was configured but could not be probed.
     *
     * Reported instead of an empty [Provider] because a host that failed to
     * answer has not answered "nothing" — it has failed to answer.
     */
    data class Unprobeable(val reason: String) : IsolationAttestation
}
