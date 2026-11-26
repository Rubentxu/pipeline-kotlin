package dev.rubentxu.pipeline.v2.dsl

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue

/**
 * The canonical wire payload of a JUnit report publication declaration.
 *
 * The producer states every fact the publication is judged on — the selecting pattern, the
 * declared format and its version, the media type (kept apart from the semantics), whether a
 * no-match is tolerable, and the optional expectation and group — and none of it is inferred
 * from a file name or a directory.
 *
 * The key order written here is the canonical form the runtime codec pins in
 * `CorePublishJUnitReportsWireCodec` (pipeline-runtime); the compatibility between the two is
 * measured by `CorePublishJUnitReportsWireTest`, so the façade and the step are ONE format
 * under test, not two hand-written ones.
 */
object JUnitPublicationPayload {

    /** The registry key the declaration lowers to. */
    val STEP_KEY: PluginStepId = PluginStepId("core.junit.publishReports")

    /** The format kind the step verifies by default. */
    const val JUNIT_XML: String = "junit-xml"

    /** The versionable format claim carried with [JUNIT_XML] by default. */
    const val JUNIT_XML_VERSION: String = "1"

    /** The transport spelling of JUnit reports, separate from their semantics. */
    const val JUNIT_XML_MEDIA_TYPE: String = "application/xml"

    /** The default hard cap on a report the parser will read (10 MiB). */
    const val DEFAULT_MAX_REPORT_BYTES: Long = 10L * 1024L * 1024L

    /**
     * Builds the canonical payload text of one publication declaration.
     *
     * @param artifacts the Ant-style pattern declaring which workspace files are the reports.
     * @param reportFormat the format kind the reports claim to carry.
     * @param formatVersion the version of that format.
     * @param mediaType the transport media type, declared apart from the semantics.
     * @param required whether a pattern that matched no report fails the step; a no-match is
     *   never a pass either way — when false the verdict says ABSENT instead.
     * @param expectedCount the number of reports the publication must find; null declares no
     *   expectation at all.
     * @param expectedGroup the report group this publication belongs to; null declares no group.
     * @param archiveName the logical name the archive is published with; null archives unnamed.
     * @param maxReportBytes the hard cap on the size of a report the parser will read.
     * @return the encoded input, in the canonical form of the step's codec.
     */
    fun encode(
        artifacts: String,
        reportFormat: String = JUNIT_XML,
        formatVersion: String = JUNIT_XML_VERSION,
        mediaType: String = JUNIT_XML_MEDIA_TYPE,
        required: Boolean = true,
        expectedCount: Int? = null,
        expectedGroup: String? = null,
        archiveName: String? = null,
        maxReportBytes: Long = DEFAULT_MAX_REPORT_BYTES,
    ): EncodedStepValue {
        require(artifacts.isNotBlank()) { "publishJUnitReports requires a non-blank artifacts pattern" }
        require(reportFormat.isNotBlank()) { "publishJUnitReports requires a declared reportFormat" }
        require(formatVersion.isNotBlank()) { "publishJUnitReports requires a declared formatVersion" }
        require(mediaType.isNotBlank()) { "publishJUnitReports requires a declared mediaType" }
        val fields = mutableListOf(
            "\"kind\":\"junitPublish\"",
            "\"artifacts\":${jsonString(artifacts)}",
            "\"reportFormat\":${jsonString(reportFormat)}",
            "\"formatVersion\":${jsonString(formatVersion)}",
            "\"mediaType\":${jsonString(mediaType)}",
            "\"required\":$required",
            "\"maxReportBytes\":$maxReportBytes",
        )
        archiveName?.let { fields.add("\"archiveName\":${jsonString(it)}") }
        expectedGroup?.let { fields.add("\"expectedGroup\":${jsonString(it)}") }
        expectedCount?.let { fields.add("\"expectedCount\":$it") }
        return EncodedStepValue(fields.joinToString(",", "{", "}"))
    }

    private fun jsonString(value: String): String = buildString {
        append('"')
        for (ch in value) {
            when (ch) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append("\\u%04x".format(ch.code)) else append(ch)
            }
        }
        append('"')
    }
}

/**
 * Declares the explicit publication of the JUnit reports a pattern selects.
 *
 * The publication verifies every selected file parses as the declared format and then captures
 * it through the run's archive port, so the step's receipts are the verified capture receipts
 * (digest and size read back from the store). A glob that matched nothing is never a pass:
 * unless [required] is false the step fails; with [required] = false the verdict says ABSENT.
 *
 * The declaration rules are declared-only: [expectedCount] and [expectedGroup] take effect
 * solely when the author states them, and no Jenkins-style build policy is forced.
 *
 * A body scope is a stage scope in this DSL ([StepBodyScope] extends [StageScope]), so this one
 * façade is the same declaration at stage level and inside `steps { }`, `dir { }` or any other
 * body — one payload authority, not two.
 *
 * @param artifacts the Ant-style pattern declaring which workspace files are the reports.
 * @param reportFormat the format kind the reports claim to carry.
 * @param formatVersion the version of that format.
 * @param mediaType the transport media type, apart from the report semantics.
 * @param required whether a no-match fails the step (default true).
 * @param expectedCount the number of reports the publication must find, when declared.
 * @param expectedGroup the report group label, when declared.
 * @param archiveName the logical name the archive is published with, when declared.
 * @param maxReportBytes the hard cap on the size of a report the parser will read.
 */
fun StageScope.publishJUnitReports(
    artifacts: String,
    reportFormat: String = JUnitPublicationPayload.JUNIT_XML,
    formatVersion: String = JUnitPublicationPayload.JUNIT_XML_VERSION,
    mediaType: String = JUnitPublicationPayload.JUNIT_XML_MEDIA_TYPE,
    required: Boolean = true,
    expectedCount: Int? = null,
    expectedGroup: String? = null,
    archiveName: String? = null,
    maxReportBytes: Long = JUnitPublicationPayload.DEFAULT_MAX_REPORT_BYTES,
) {
    registryStep(
        stepKey = JUnitPublicationPayload.STEP_KEY,
        encodedInput = JUnitPublicationPayload.encode(
            artifacts, reportFormat, formatVersion, mediaType,
            required, expectedCount, expectedGroup, archiveName, maxReportBytes,
        ),
    )
}
