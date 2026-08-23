package com.pipeline.v2.domain

/**
 * Classification of failure kinds for error reporting.
 * Used by [com.pipeline.v2.dsl.StepSpec.Error] step type.
 */
enum class FailureKind {
    /** Infrastructure-level failure (network, disk, etc.) */
    INFRASTRUCTURE,
    /** Network-level failure */
    NETWORK,
    /** Script-level failure (command exited non-zero) */
    SCRIPT,
    /** User-level failure (invalid input, etc.) */
    USER,
    /** Timeout exceeded */
    TIMEOUT,
    /** Unknown failure */
    UNKNOWN,
}
