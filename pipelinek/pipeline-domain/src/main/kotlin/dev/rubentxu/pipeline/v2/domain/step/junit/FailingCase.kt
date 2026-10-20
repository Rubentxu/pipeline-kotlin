package dev.rubentxu.pipeline.v2.domain.step.junit

/**
 * A single failing `<testcase>` extracted from a JUnit XML report.
 *
 * Carries the raw `message` and `type` attributes from the
 * `<failure>` or `<error>` child element so the pipeline author can
 * react to specific failure modes without re-parsing the XML.
 *
 * The `message` may be empty if the upstream tool emitted no message
 * (rare but legal per the schema); the pipeline author MUST be
 * prepared for empty messages.
 *
 * @property suiteName  the `<testsuite>` name this case belongs to
 * @property caseName   the `<testcase>` name attribute
 * @property classname  the `<testcase>` classname attribute (FQN when present)
 * @property message    the failure/error `message` attribute (may be empty)
 * @property type       the failure/error `type` attribute (e.g. "java.lang.AssertionError")
 * @property isError    true if the child was `<error>`, false if `<failure>`
 */
data class FailingCase(
    val suiteName: String,
    val caseName: String,
    val classname: String,
    val message: String,
    val type: String,
    val isError: Boolean,
) {
    init {
        require(suiteName.isNotEmpty()) { "suiteName must not be empty" }
        require(caseName.isNotEmpty()) { "caseName must not be empty" }
    }
}
