package dev.rubentxu.pipeline.v2.domain.durable

/**
 * Declared recovery/reconciliation behaviour of a durable operation (B1.2c2-CDE.2-b4).
 *
 * The durable protocol consumes only this typed property of [StepMetadata] to decide whether a
 * RUNNING-journaled operation needs special external reconciliation; it never selects concrete
 * behaviour by a Step name. Identity selects metadata; metadata expresses the recovery property.
 *
 * Concrete recovery mechanics (e.g. reattaching to and polling a real external process via a control
 * directory) live behind a compatibility boundary, not in the generic durable decision.
 */
sealed interface RecoveryPolicy {
    /** No special recovery: a RUNNING journal follows the generic replay/reconcile path. */
    data object None : RecoveryPolicy

    /**
     * The operation runs an external process whose RUNNING state is reconcilable from a control
     * directory (reattach / poll / classify). Today this is the running-subprocess (shell) operation
     * of the legacy core world; the catalog declares it, the protocol never names the Step.
     */
    data object ExternalSubprocess : RecoveryPolicy
}
