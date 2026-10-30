package dev.rubentxu.pipeline.v2.events.durable

import dev.rubentxu.pipeline.v2.events.ArtifactArchiveFailed
import dev.rubentxu.pipeline.v2.events.ArtifactArchived
import dev.rubentxu.pipeline.v2.events.CatchErrorTriggered
import dev.rubentxu.pipeline.v2.events.CompensationArmed
import dev.rubentxu.pipeline.v2.events.CompensationFinished
import dev.rubentxu.pipeline.v2.events.CompensationStarted
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.CompilationStarted
import dev.rubentxu.pipeline.v2.events.CredentialBound
import dev.rubentxu.pipeline.v2.events.CredentialUnbound
import dev.rubentxu.pipeline.v2.events.CredentialUsed
import dev.rubentxu.pipeline.v2.events.DirDeleted
import dev.rubentxu.pipeline.v2.events.DirEntered
import dev.rubentxu.pipeline.v2.events.DirExited
import dev.rubentxu.pipeline.v2.events.DirectiveAdmitted
import dev.rubentxu.pipeline.v2.events.DirectiveDenied
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.EchoOutputCaptured
import dev.rubentxu.pipeline.v2.events.ExecutionTargetResolved
import dev.rubentxu.pipeline.v2.events.FileExistsChecked
import dev.rubentxu.pipeline.v2.events.FileRead
import dev.rubentxu.pipeline.v2.events.FileWritten
import dev.rubentxu.pipeline.v2.events.GateEvaluated
import dev.rubentxu.pipeline.v2.events.GitCheckoutCompleted
import dev.rubentxu.pipeline.v2.events.GitCheckoutFailed
import dev.rubentxu.pipeline.v2.events.GitCheckoutStarted
import dev.rubentxu.pipeline.v2.events.GitPollChanged
import dev.rubentxu.pipeline.v2.events.HtmlReportFailed
import dev.rubentxu.pipeline.v2.events.HtmlReportPublished
import dev.rubentxu.pipeline.v2.events.HtmlReportSkipped
import dev.rubentxu.pipeline.v2.events.HttpRequestFailed
import dev.rubentxu.pipeline.v2.events.HttpRequestStarted
import dev.rubentxu.pipeline.v2.events.HttpResponseReceived
import dev.rubentxu.pipeline.v2.events.HttpStatusRejected
import dev.rubentxu.pipeline.v2.events.InputAborted
import dev.rubentxu.pipeline.v2.events.InputDenied
import dev.rubentxu.pipeline.v2.events.InputProceed
import dev.rubentxu.pipeline.v2.events.InputRequested
import dev.rubentxu.pipeline.v2.events.LockAcquireFailed
import dev.rubentxu.pipeline.v2.events.LockAcquired
import dev.rubentxu.pipeline.v2.events.LockReleased
import dev.rubentxu.pipeline.v2.events.LockRequested
import dev.rubentxu.pipeline.v2.events.LockSkipped
import dev.rubentxu.pipeline.v2.events.MilestoneAborted
import dev.rubentxu.pipeline.v2.events.MilestoneReached
import dev.rubentxu.pipeline.v2.events.ParallelBranchFinished
import dev.rubentxu.pipeline.v2.events.ParallelBranchStarted
import dev.rubentxu.pipeline.v2.events.PluginEventEmitted
import dev.rubentxu.pipeline.v2.events.PostConditionSelected
import dev.rubentxu.pipeline.v2.events.PwdResolved
import dev.rubentxu.pipeline.v2.events.RetryAttemptFinished
import dev.rubentxu.pipeline.v2.events.RetryAttemptStarted
import dev.rubentxu.pipeline.v2.events.RunCorrelated
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.RunStarted
import dev.rubentxu.pipeline.v2.events.SagaTerminal
import dev.rubentxu.pipeline.v2.events.StageFinished
import dev.rubentxu.pipeline.v2.events.StageMarkedUnstable
import dev.rubentxu.pipeline.v2.events.StageSkipped
import dev.rubentxu.pipeline.v2.events.StageStarted
import dev.rubentxu.pipeline.v2.events.StashCreated
import dev.rubentxu.pipeline.v2.events.StashFailed
import dev.rubentxu.pipeline.v2.events.StashRestored
import dev.rubentxu.pipeline.v2.events.StepAdmissionObserved
import dev.rubentxu.pipeline.v2.events.StepFailed
import dev.rubentxu.pipeline.v2.events.StepFinished
import dev.rubentxu.pipeline.v2.events.StepStarted
import dev.rubentxu.pipeline.v2.events.TimeoutScheduled
import dev.rubentxu.pipeline.v2.events.TimeoutTriggered
import dev.rubentxu.pipeline.v2.events.TimestampsEntered
import dev.rubentxu.pipeline.v2.events.TimestampsExited
import dev.rubentxu.pipeline.v2.events.UnixDetected
import dev.rubentxu.pipeline.v2.events.WaitUntilCompleted
import dev.rubentxu.pipeline.v2.events.WaitUntilPolled
import dev.rubentxu.pipeline.v2.events.WorkflowLoaded
import dev.rubentxu.pipeline.v2.events.WsCleaned

/**
 * The wire vocabulary of the durable event log: one constant per kind this binary reads or writes.
 *
 * ## Why the constant's NAME is the wire
 *
 * Every domain type declares its kind with the word it is named with: a `RunStarted` travels as
 * `RunStarted`. That is a CONVENTION, not a coincidence to be policed by a second table of
 * literals, and a convention can live in the constant names themselves. Each kind then has ONE
 * spelling, and there is no copy of it anywhere to drift.
 *
 * The convention is CERTIFIED rather than assumed. [EventKindMatrix.of] binds every domain type to
 * a constant, so a type nobody entered does not compile; and because the domain's own kind literal
 * is a string the compiler cannot compare with anything, the encoder and the decoder compare what
 * they carry against the constant's wire, and refuse a disagreement.
 */
internal enum class EventKind {
    RunStarted,
    RunCorrelated,
    CompilationStarted,
    CompilationFinished,
    RunFinished,
    StageStarted,
    StageFinished,
    StageSkipped,
    StepStarted,
    StepFinished,
    AgentResolved,
    ExecutionTargetResolved,
    ParallelBranchStarted,
    ParallelBranchFinished,
    RetryAttemptStarted,
    RetryAttemptFinished,
    TimeoutScheduled,
    StepFailed,
    EchoOutputCaptured,
    CredentialBound,
    CredentialUsed,
    CredentialUnbound,
    GitCheckoutStarted,
    GitCheckoutCompleted,
    GitCheckoutFailed,
    GitPollChanged,
    FileWritten,
    FileRead,
    FileExistsChecked,
    ArtifactArchived,
    ArtifactArchiveFailed,
    DirEntered,
    DirExited,
    DirDeleted,
    WsCleaned,
    CatchErrorTriggered,
    StageMarkedUnstable,
    WorkflowLoaded,
    WaitUntilPolled,
    WaitUntilCompleted,
    PwdResolved,
    UnixDetected,
    MilestoneReached,
    MilestoneAborted,
    TimeoutTriggered,
    TimestampsEntered,
    TimestampsExited,
    StepAdmissionObserved,
    StashCreated,
    StashRestored,
    StashFailed,
    HtmlReportPublished,
    HtmlReportSkipped,
    HtmlReportFailed,
    DirectiveAdmitted,
    DirectiveDenied,
    GateEvaluated,
    PostConditionSelected,
    LockRequested,
    LockAcquired,
    LockReleased,
    LockSkipped,
    LockAcquireFailed,
    InputRequested,
    InputProceed,
    InputAborted,
    InputDenied,
    HttpRequestStarted,
    HttpResponseReceived,
    HttpStatusRejected,
    HttpRequestFailed,
    CompensationArmed,
    CompensationStarted,
    CompensationFinished,
    SagaTerminal,
    PluginEventEmitted;
    /**
     * The wire string this kind travels as.
     *
     * It is the constant's own name, so the vocabulary cannot hold two spellings of the same kind.
     */
    val wire: String get() = name
}

/**
 * The matrix between the domain's event types and the wire's kind strings.
 *
 * ## What it owns
 *
 * ONE table saying which kind belongs to which domain type. The encoder asks it before it writes,
 * the decoder dispatches on it, and no other list of kinds exists in this module: the one that used
 * to sit beside the decoder's table is gone, because it could only restate what this table
 * already says.
 *
 * ## What the compiler already holds
 *
 * [of] is exhaustive over the sealed [DomainEvent], so a domain type nobody entered here does not
 * compile. The decoder's own table is exhaustive over [EventKind], so a constant with no decoder
 * arm does not compile either.
 *
 * ## What the compiler cannot hold
 *
 * The WIRING. An arm that builds the wrong type for a kind, and a domain type whose own kind
 * literal no longer names its constant, are both strings to the compiler and both invisible to it.
 * They are checked where they can be seen: by the encoder before it writes a row, and by the
 * decoder after it reads one.
 */
internal object EventKindMatrix {
    /**
     * The constant [event]'s type owns.
     *
     * Exhaustive over the sealed [DomainEvent] on purpose: a new domain type with no constant
     * here is a compile error, not a row that travels under a kind no reader can decode.
     *
     * The table carries one branch per domain type because that is the only shape a compiler can
     * hold against a sealed hierarchy. The branch names its TYPE, so the compiler can see that a
     * type was entered; it cannot see that the constant's wire is the literal the type declares,
     * which is why the encoder and the decoder compare the two where they can be seen.
     *
     * The complexity of the branch is the number of types, which is the price of that guarantee,
     * and the branch carries the suppression for it rather than the baseline being widened. One of
     * the types is deprecated without ever having had a producer, and it is still part of the
     * sealed hierarchy, so the deprecation warning is suppressed here as well.
     */
    @Suppress("CyclomaticComplexMethod", "DEPRECATION")
    fun of(event: DomainEvent): EventKind = when (event) {
        is RunStarted -> EventKind.RunStarted
        is RunCorrelated -> EventKind.RunCorrelated
        is CompilationStarted -> EventKind.CompilationStarted
        is CompilationFinished -> EventKind.CompilationFinished
        is RunFinished -> EventKind.RunFinished
        is StageStarted -> EventKind.StageStarted
        is StageFinished -> EventKind.StageFinished
        is StageSkipped -> EventKind.StageSkipped
        is StepStarted -> EventKind.StepStarted
        is StepFinished -> EventKind.StepFinished
        is dev.rubentxu.pipeline.v2.events.AgentResolved -> EventKind.AgentResolved
        is ExecutionTargetResolved -> EventKind.ExecutionTargetResolved
        is ParallelBranchStarted -> EventKind.ParallelBranchStarted
        is ParallelBranchFinished -> EventKind.ParallelBranchFinished
        is RetryAttemptStarted -> EventKind.RetryAttemptStarted
        is RetryAttemptFinished -> EventKind.RetryAttemptFinished
        is TimeoutScheduled -> EventKind.TimeoutScheduled
        is StepFailed -> EventKind.StepFailed
        is EchoOutputCaptured -> EventKind.EchoOutputCaptured
        is CredentialBound -> EventKind.CredentialBound
        is CredentialUsed -> EventKind.CredentialUsed
        is CredentialUnbound -> EventKind.CredentialUnbound
        is GitCheckoutStarted -> EventKind.GitCheckoutStarted
        is GitCheckoutCompleted -> EventKind.GitCheckoutCompleted
        is GitCheckoutFailed -> EventKind.GitCheckoutFailed
        is GitPollChanged -> EventKind.GitPollChanged
        is FileWritten -> EventKind.FileWritten
        is FileRead -> EventKind.FileRead
        is FileExistsChecked -> EventKind.FileExistsChecked
        is ArtifactArchived -> EventKind.ArtifactArchived
        is ArtifactArchiveFailed -> EventKind.ArtifactArchiveFailed
        is DirEntered -> EventKind.DirEntered
        is DirExited -> EventKind.DirExited
        is DirDeleted -> EventKind.DirDeleted
        is WsCleaned -> EventKind.WsCleaned
        is CatchErrorTriggered -> EventKind.CatchErrorTriggered
        is StageMarkedUnstable -> EventKind.StageMarkedUnstable
        is WorkflowLoaded -> EventKind.WorkflowLoaded
        is WaitUntilPolled -> EventKind.WaitUntilPolled
        is WaitUntilCompleted -> EventKind.WaitUntilCompleted
        is PwdResolved -> EventKind.PwdResolved
        is UnixDetected -> EventKind.UnixDetected
        is MilestoneReached -> EventKind.MilestoneReached
        is MilestoneAborted -> EventKind.MilestoneAborted
        is TimeoutTriggered -> EventKind.TimeoutTriggered
        is TimestampsEntered -> EventKind.TimestampsEntered
        is TimestampsExited -> EventKind.TimestampsExited
        is StepAdmissionObserved -> EventKind.StepAdmissionObserved
        is StashCreated -> EventKind.StashCreated
        is StashRestored -> EventKind.StashRestored
        is StashFailed -> EventKind.StashFailed
        is HtmlReportPublished -> EventKind.HtmlReportPublished
        is HtmlReportSkipped -> EventKind.HtmlReportSkipped
        is HtmlReportFailed -> EventKind.HtmlReportFailed
        is DirectiveAdmitted -> EventKind.DirectiveAdmitted
        is DirectiveDenied -> EventKind.DirectiveDenied
        is GateEvaluated -> EventKind.GateEvaluated
        is PostConditionSelected -> EventKind.PostConditionSelected
        is LockRequested -> EventKind.LockRequested
        is LockAcquired -> EventKind.LockAcquired
        is LockReleased -> EventKind.LockReleased
        is LockSkipped -> EventKind.LockSkipped
        is LockAcquireFailed -> EventKind.LockAcquireFailed
        is InputRequested -> EventKind.InputRequested
        is InputProceed -> EventKind.InputProceed
        is InputAborted -> EventKind.InputAborted
        is InputDenied -> EventKind.InputDenied
        is HttpRequestStarted -> EventKind.HttpRequestStarted
        is HttpResponseReceived -> EventKind.HttpResponseReceived
        is HttpStatusRejected -> EventKind.HttpStatusRejected
        is HttpRequestFailed -> EventKind.HttpRequestFailed
        is CompensationArmed -> EventKind.CompensationArmed
        is CompensationStarted -> EventKind.CompensationStarted
        is CompensationFinished -> EventKind.CompensationFinished
        is SagaTerminal -> EventKind.SagaTerminal
        is PluginEventEmitted -> EventKind.PluginEventEmitted
    }

    /**
     * Does [declaredKind] name the kind [certified] travels as?
     *
     * This is the rule itself, with no failure shape attached: the matrix says this type
     * travels as one kind, the event declares one kind, and the two have to be the same
     * word. A domain type whose own literal drifted away from its constant is a string
     * to the compiler, so this is where the two are compared.
     */
    fun declares(certified: EventKind, declaredKind: String): Boolean =
        certified.wire == declaredKind

    /**
     * [certified], when the event declares the kind it travels as, and a failure when it
     * does not.
     *
     * This is the shape the writer needs: a row that reaches the store under a kind
     * nobody asked for is worse than a loud refusal, so the disagreement throws instead
     * of answering null. The row is built after this returns, so nothing of it is
     * written when the two disagree.
     */
    fun certify(certified: EventKind, declaredKind: String): EventKind {
        check(declares(certified, declaredKind)) {
            "the matrix says type ${certified.name} travels as \"${certified.wire}\" " +
                "but the event declares kind \"$declaredKind\""
        }
        return certified
    }

    /**
     * The constant whose wire is [wire], or null when this binary knows no such kind.
     *
     * Derived from the vocabulary itself, so it cannot answer for a kind the vocabulary does not
     * hold and there is no second list of literals to keep in step with the first.
     */
    fun fromWire(wire: String): EventKind? = BY_WIRE[wire]

    /**
     * Does this binary know [wire]?
     *
     * [fromWire] asked as a question.
     */
    fun knows(wire: String): Boolean = wire in BY_WIRE

    /** The vocabulary by wire. Derived, so it cannot name a kind the enum does not hold. */
    private val BY_WIRE: Map<String, EventKind> = EventKind.entries.associateBy { it.wire }
}
