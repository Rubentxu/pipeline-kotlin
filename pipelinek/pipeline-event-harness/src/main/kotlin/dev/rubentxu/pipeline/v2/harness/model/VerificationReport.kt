package dev.rubentxu.pipeline.v2.harness.model

/**
 * Full acceptance verification report. Evaluation ABOUT a run — never a
 * DomainEvent of the run, never persisted into the journal.
 */
data class VerificationReport(
    val contract: String,
    val result: VerificationResult,
    val observedTerminalOutcome: PipelineOutcome?,
)
