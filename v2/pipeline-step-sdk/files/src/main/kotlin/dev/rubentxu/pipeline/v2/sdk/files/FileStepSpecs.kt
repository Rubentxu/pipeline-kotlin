package dev.rubentxu.pipeline.v2.sdk.files

/**
 * File step specifications for the SDK layer.
 *
 * This module provides SDK-level accessors to file operations.
 * The actual data classes (WriteFile, ReadFile, FileExists) are defined
 * in the sealed StepSpec hierarchy in `:pipeline-scripting-api`.
 *
 * The T-04 task adds the actual StepSpec data classes to PipelineDsl.kt.
 * This file provides SDK-side re-exports and type aliases for convenience.
 *
 * ## ML-R7 L7 Tier
 *
 * @see <a href="ADR-0046 §D2">ADR-0046 §D2 — L7 Jenkins top-steps parity</a>
 */

// TODO(T-04): Add WriteFile, ReadFile, FileExists StepSpec data classes
// TODO(T-06): Add FileWriteExecutor, FileReadExecutor, FileExistsExecutor
// These will be populated in subsequent tasks.
