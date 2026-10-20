package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * Closed set of typed failure reasons emitted by the `core.junit`
 * parser. Each case carries enough detail to surface a meaningful
 * error message without losing the schema variant that was rejected.
 *
 * These are the parser-level failure kinds. The Step's
 * [dev.rubentxu.pipeline.v2.domain.CommonExecutionResult.failureKind]
 * maps them to `FailureKind.INPUT_INVALID`; the parser-specific
 * reason is preserved in the typed failure message and the
 * `JunitReadFailed(kind)` event.
 */
sealed interface JunitParseFailure {

    /** The glob pattern matched no file. */
    data class MissingFile(val glob: String) : JunitParseFailure

    /** The file is not well-formed XML or has an unexpected root element. */
    data class Malformed(val reason: String) : JunitParseFailure

    /**
     * The XML is well-formed but uses a schema variant the cycle does
     * NOT handle (e.g. JUnit 5 standalone, custom extensions).
     */
    data class UnsupportedSchema(val details: String) : JunitParseFailure
}
