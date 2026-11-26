package dev.rubentxu.pipeline.v2.runtime

import dev.rubentxu.pipeline.v2.artefacts.local.AntStyleGlob
import dev.rubentxu.pipeline.v2.domain.ExecutionLocation
import dev.rubentxu.pipeline.v2.domain.FailureKind
import dev.rubentxu.pipeline.v2.domain.PipelineFailure
import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.StepArgument
import dev.rubentxu.pipeline.v2.domain.StepArgumentType
import dev.rubentxu.pipeline.v2.domain.StepArguments
import dev.rubentxu.pipeline.v2.domain.StepDescriptor
import dev.rubentxu.pipeline.v2.domain.StepOutcome
import dev.rubentxu.pipeline.v2.domain.durable.Effect
import dev.rubentxu.pipeline.v2.domain.durable.ReplayPolicy
import dev.rubentxu.pipeline.v2.domain.durable.TypedStepOutput
import dev.rubentxu.pipeline.v2.domain.step.EncodedStepValue
import dev.rubentxu.pipeline.v2.domain.step.StepCodec
import dev.rubentxu.pipeline.v2.domain.step.StepContract
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepHandler
import dev.rubentxu.pipeline.v2.domain.step.StepRegistryBuilder
import dev.rubentxu.pipeline.v2.domain.step.EXECUTION_LOCATION_CAPABILITY
import dev.rubentxu.pipeline.v2.domain.workspace.ExecutionLocation as ExecutionSite
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportParseException
import dev.rubentxu.pipeline.v2.sdk.junit.step.JUnitReportParser
import java.io.BufferedInputStream
import java.nio.file.Files
import java.nio.file.Path

/**
 * The producer's explicit declaration of what a JUnit report is, and of how it is published.
 *
 * Nothing here is guessed from a file name or a directory: [artifacts] is the Ant-style pattern
 * the author declared, [reportFormat] and [formatVersion] name the format version the reports
 * claim to carry, and [mediaType] is the transport spelling kept deliberately apart from that
 * semantics — a `.xml` under any name is only a candidate the pattern selected, and the parse
 * is what decides.
 *
 * The declaration carries the verdict rules too, and they are all declared-only:
 *  - `required` (default true): a publication whose pattern matched no report is NOT a pass.
 *  - `expectedCount`: when non-null, the number of reports the publication must find; when
 *    null the publication declares no expectation and none is invented.
 *  - `expectedGroup`: a label for the report group, carried into the output only when
 *    declared. It never forces a build policy: no Jenkins-style threshold is implied here.
 *
 * Backward compatibility: this is a NEW key. `core.archiveArtifacts` and every existing step
 * keep their bytes and their semantics untouched; this step only CONSUMES the capture port of
 * PKH-339 (`ArchiveArtifactsOperations`) and never redefines it.
 */
data class PublishJUnitReportsInput(
    val artifacts: String,
    val reportFormat: String = JUnitXmlReportFormat.KIND,
    val formatVersion: String = JUnitXmlReportFormat.VERSION,
    val mediaType: String = JUnitXmlReportFormat.MEDIA_TYPE,
    val required: Boolean = true,
    val expectedCount: Int? = null,
    val expectedGroup: String? = null,
    val archiveName: String? = null,
    val maxReportBytes: Long = DEFAULT_MAX_REPORT_BYTES,
) {
    init {
        require(artifacts.isNotBlank()) { "core.junit.publishReports requires a non-blank artifacts pattern" }
        require(reportFormat.isNotBlank()) { "reportFormat must name the format the producer declares" }
        require(formatVersion.isNotBlank()) { "formatVersion must be versionable: a format without a version cannot be pinned" }
        require(mediaType.isNotBlank()) { "mediaType must be declared apart from the report semantics" }
        require(expectedCount == null || expectedCount >= 0) { "expectedCount must be non-negative when declared" }
        require(maxReportBytes > 0L) { "maxReportBytes must be positive" }
    }

    companion object {
        const val DEFAULT_MAX_REPORT_BYTES: Long = 10L * 1024L * 1024L
    }
}

/**
 * The versionable format declaration a JUnit publication carries by default.
 *
 * KIND is the semantics (what the parser will verify), VERSION makes the claim pinable, and
 * MEDIA_TYPE is only how the bytes travel — the three are separate fields on the input so a
 * producer can restate any of them without restating the others.
 */
object JUnitXmlReportFormat {
    const val KIND: String = "junit-xml"
    const val VERSION: String = "1"
    const val MEDIA_TYPE: String = "application/xml"
}

/** One published report: the capture receipt of the bytes plus the totals the parse read. */
data class PublishedReport(
    val relPath: String,
    val sha256: String,
    val sizeBytes: Long,
    val tests: Int,
    val failures: Int,
    val errors: Int,
    val skipped: Int,
)

/**
 * The publication succeeded: every matched report was verified as [JUnitXmlReportFormat.KIND]
 * and captured through the PKH-339 port. [receipts] mirror the verified CaptureReceipts (digest
 * and size read back from the store by the adapter, never supplied here).
 */
data class PublishedReportsOutput(
    val group: String?,
    val receipts: List<PublishedReport>,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/**
 * An optional publication matched no report. This is the declaration honoured, NOT a pass: the
 * status says absent, and no bytes were captured. A required publication never reaches this
 * output — it fails.
 */
data class AbsentOptionalReportsOutput(
    val artifacts: String,
    val group: String?,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Success
}

/** Typed failure of the publication, carrying the kind the law of the case declares. */
data class PublishJUnitReportsFailureOutput(
    val failureKind: FailureKind,
    val message: String,
) : TypedStepOutput {
    override val outcome: StepOutcome get() = StepOutcome.Failure(PipelineFailure(failureKind, message))
}

/**
 * The single authority for the `core.junit.publishReports` wire format of the INPUT.
 *
 * The DSL façade in `pipeline-scripting-api` builds the payload with the same canonical key
 * order this codec writes (the compat assertion lives in `CorePublishJUnitReportsWireTest`), so
 * an author's declaration and the step's decode cannot drift unnoticed: the bytes are fixed by
 * this object and pinned against the façade by that test.
 */
object CorePublishJUnitReportsWireCodec : StepCodec<PublishJUnitReportsInput> {

    override fun encode(value: PublishJUnitReportsInput): EncodedStepValue =
        EncodedStepValue(canonicalPayload(value))

    /** The canonical JSON text of [value]; optionals appear only when declared. */
    fun canonicalPayload(value: PublishJUnitReportsInput): String {
        val fields = mutableListOf(
            "\"kind\":\"junitPublish\"",
            "\"artifacts\":${jsonString(value.artifacts)}",
            "\"reportFormat\":${jsonString(value.reportFormat)}",
            "\"formatVersion\":${jsonString(value.formatVersion)}",
            "\"mediaType\":${jsonString(value.mediaType)}",
            "\"required\":${value.required}",
            "\"maxReportBytes\":${value.maxReportBytes}",
        )
        value.archiveName?.let { fields.add("\"archiveName\":${jsonString(it)}") }
        value.expectedGroup?.let { fields.add("\"expectedGroup\":${jsonString(it)}") }
        value.expectedCount?.let { fields.add("\"expectedCount\":$it") }
        return fields.joinToString(",", "{", "}")
    }

    override fun decode(encoded: EncodedStepValue): PublishJUnitReportsInput {
        val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
            .let { it as kotlinx.serialization.json.JsonObject }
        fun text(name: String): String? =
            (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
        require(text("kind") == "junitPublish") {
            "core.junit.publishReports payload kind must be 'junitPublish'"
        }
        return PublishJUnitReportsInput(
            artifacts = obj.getValue("artifacts").let { (it as kotlinx.serialization.json.JsonPrimitive).content },
            reportFormat = text("reportFormat") ?: JUnitXmlReportFormat.KIND,
            formatVersion = text("formatVersion") ?: JUnitXmlReportFormat.VERSION,
            mediaType = text("mediaType") ?: JUnitXmlReportFormat.MEDIA_TYPE,
            required = text("required")?.toBooleanStrictOrNull() ?: true,
            expectedCount = text("expectedCount")?.toIntOrNull(),
            expectedGroup = text("expectedGroup"),
            archiveName = text("archiveName"),
            maxReportBytes = text("maxReportBytes")?.toLongOrNull() ?: PublishJUnitReportsInput.DEFAULT_MAX_REPORT_BYTES,
        )
    }

    /** The arguments this step accepts, as the catalogue publishes them. */
    override fun schema(): String = StepArguments(
        arguments = listOf(
            StepArgument(
                name = "artifacts",
                type = StepArgumentType.STRING,
                required = true,
                description = "the Ant-style pattern declaring which workspace files are the JUnit reports",
            ),
            StepArgument(
                name = "reportFormat",
                type = StepArgumentType.STRING,
                required = false,
                description = "the format the producer declares the reports carry (default junit-xml)",
            ),
            StepArgument(
                name = "formatVersion",
                type = StepArgumentType.STRING,
                required = false,
                description = "the version of the declared format",
            ),
            StepArgument(
                name = "mediaType",
                type = StepArgumentType.STRING,
                required = false,
                description = "the transport media type of the reports, declared apart from their semantics",
            ),
            StepArgument(
                name = "required",
                type = StepArgumentType.BOOLEAN,
                required = false,
                description = "whether a pattern that matched no report fails the step (default true; no-match is never a pass)",
            ),
            StepArgument(
                name = "expectedCount",
                type = StepArgumentType.NUMBER,
                required = false,
                description = "the number of reports the publication must find; declared only when the author declares it",
            ),
            StepArgument(
                name = "expectedGroup",
                type = StepArgumentType.STRING,
                required = false,
                description = "the name of the report group this publication belongs to, carried only when declared",
            ),
            StepArgument(
                name = "archiveName",
                type = StepArgumentType.STRING,
                required = false,
                description = "the logical name the archive is published with in the artifact index",
            ),
            StepArgument(
                name = "maxReportBytes",
                type = StepArgumentType.NUMBER,
                required = false,
                description = "the hard cap on the size of a report the parser will read",
            ),
        ),
    ).jsonSchema("core.junit.publishReports")

    /** The canonical JSON string literal of [value]. */
    internal fun jsonString(value: String): String = buildString {
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
 * `core.junit.publishReports` — the explicit, portable publication of JUnit reports.
 *
 * The step answers the whole publication law in one place, after the tests have run (it is
 * hostable in a `post { always { ... } }` block, which is why a failing suite never costs the
 * publication):
 *  1. the declared pattern selects the candidates (the producer's word, never a name guess);
 *  2. an empty selection is a failure unless the author declared the publication optional —
 *     and even then the status says ABSENT, never published;
 *  3. every candidate must parse as JUnit XML through the certified parser
 *     (`JUnitReportParser`): absent, invalid and non-matching are three different verdicts, and
 *     an invalid report fails the step BEFORE any byte is archived;
 *  4. only a fully verified set is handed to the PKH-339 capture port
 *     ([ArchiveArtifactsOperations]), so the publication's receipts are the verified
 *     `CaptureReceipt`s (digest and size read back from the store);
 *  5. an expected count the author declared is checked against what the pattern found, and the
 *     group label is carried only when declared. No build policy is forced.
 *
 * Capability discipline: the handler consumes EXECUTION_LOCATION (the report root) and the
 * archive port only. It never touches the event sink — `ArtifactArchived` is emitted by the
 * adapter behind the port, exactly as for `core.archiveArtifacts`.
 * Failures are returned as typed outputs (never thrown): the boundary re-classifies a thrown
 * handler as ENGINE, while these verdicts are SCRIPT (authoring law) or USER (report content).
 */
object CorePublishJUnitReportsStep {

    val KEY: PluginStepId = PluginStepId("core.junit.publishReports")

    /** The output wire format: the canonical kind plus the status the step decided. */
    private const val OUTPUT_KIND = "junitPublish"

    private val outputCodec = object : StepCodec<TypedStepOutput> {
        override fun encode(value: TypedStepOutput): EncodedStepValue = when (value) {
            is PublishedReportsOutput -> EncodedStepValue(
                buildString {
                    append("{\"kind\":\"$OUTPUT_KIND\",\"status\":\"PUBLISHED\"")
                    append(",\"group\":")
                    append(value.group?.let { CorePublishJUnitReportsWireCodec.jsonString(it) } ?: "null")
                    append(",\"reports\":[")
                    append(
                        value.receipts.joinToString(",") {
                            "{\"relPath\":${CorePublishJUnitReportsWireCodec.jsonString(it.relPath)}," +
                                "\"sha256\":${CorePublishJUnitReportsWireCodec.jsonString(it.sha256)}," +
                                "\"sizeBytes\":${it.sizeBytes}," +
                                "\"tests\":${it.tests},\"failures\":${it.failures}," +
                                "\"errors\":${it.errors},\"skipped\":${it.skipped}}"
                        },
                    )
                    append("]}")
                },
            )
            is AbsentOptionalReportsOutput -> EncodedStepValue(
                "{\"kind\":\"$OUTPUT_KIND\",\"status\":\"ABSENT\"" +
                    ",\"artifacts\":${CorePublishJUnitReportsWireCodec.jsonString(value.artifacts)}" +
                    ",\"group\":${value.group?.let { CorePublishJUnitReportsWireCodec.jsonString(it) } ?: "null"}}",
            )
            is PublishJUnitReportsFailureOutput -> EncodedStepValue(
                "{\"kind\":\"$OUTPUT_KIND\",\"status\":\"FAILED\"" +
                    ",\"failureKind\":${CorePublishJUnitReportsWireCodec.jsonString(value.failureKind.name)}" +
                    ",\"message\":${CorePublishJUnitReportsWireCodec.jsonString(value.message)}}",
            )
            else -> throw IllegalArgumentException(
                "core.junit.publishReports output codec cannot encode ${value::class.simpleName}",
            )
        }

        override fun decode(encoded: EncodedStepValue): TypedStepOutput {
            val obj = kotlinx.serialization.json.Json.parseToJsonElement(encoded.value)
                .let { it as kotlinx.serialization.json.JsonObject }
            fun prim(name: String): String? =
                (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
            require(prim("kind") == OUTPUT_KIND) {
                "core.junit.publishReports output payload kind must be '$OUTPUT_KIND'"
            }
            return when (prim("status")) {
                "PUBLISHED" -> {
                    val reports = (obj.getValue("reports") as kotlinx.serialization.json.JsonArray).map {
                        it as kotlinx.serialization.json.JsonObject
                        PublishedReport(
                            relPath = textIn(obj = it, name = "relPath")!!,
                            sha256 = textIn(obj = it, name = "sha256")!!,
                            sizeBytes = textIn(obj = it, name = "sizeBytes")!!.toLong(),
                            tests = textIn(obj = it, name = "tests")!!.toInt(),
                            failures = textIn(obj = it, name = "failures")!!.toInt(),
                            errors = textIn(obj = it, name = "errors")!!.toInt(),
                            skipped = textIn(obj = it, name = "skipped")!!.toInt(),
                        )
                    }
                    PublishedReportsOutput(group = prim("group"), receipts = reports)
                }
                "ABSENT" -> AbsentOptionalReportsOutput(
                    artifacts = prim("artifacts").orEmpty(),
                    group = prim("group"),
                )
                "FAILED" -> PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.valueOf(prim("failureKind")!!),
                    message = prim("message").orEmpty(),
                )
                else -> throw IllegalArgumentException(
                    "core.junit.publishReports output status must be PUBLISHED, ABSENT or FAILED",
                )
            }
        }

        private fun textIn(obj: kotlinx.serialization.json.JsonObject, name: String): String? =
            (obj[name] as? kotlinx.serialization.json.JsonPrimitive)?.content
    }

    private val descriptor = StepDescriptor(
        stepId = "core.junit.publishReports",
        name = "publishJUnitReports",
        jenkinsSurface = "junit",
        jenkinsDivergence = "the Jenkins junit publisher infers reports from paths and applies a " +
            "build policy; here the producer declares the pattern, the format and the expectation, " +
            "no inference happens, and no build policy is forced.",
        configRef = "",
        executionLocation = ExecutionLocation.AGENT,
        effects = listOf(Effect.WRITES_WORKSPACE),
        replayPolicy = ReplayPolicy.MEMOIZED,
    )

    private val handler: StepHandler<PublishJUnitReportsInput, TypedStepOutput> =
        StepHandler { input, ctx ->
            val site: ExecutionSite = ctx.capabilities.get(EXECUTION_LOCATION_CAPABILITY, ExecutionSite::class)
            val root: Path = site.cwd
            val matched: List<Path> = try {
                AntStyleGlob(input.artifacts).match(root = root, excludes = emptyList(), defaultExcludes = true)
            } catch (e: Exception) {
                return@StepHandler PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.SCRIPT,
                    message = "core.junit.publishReports: glob pattern '${input.artifacts}' failed: ${e.message ?: "unknown"}",
                )
            }

            if (matched.isEmpty()) {
                // No-match is never a pass. Only the author's explicit "optional" turns the
                // absence into a (still not-published) success verdict.
                if (input.required) {
                    return@StepHandler PublishJUnitReportsFailureOutput(
                        failureKind = FailureKind.SCRIPT,
                        message = "core.junit.publishReports: no report matched '${input.artifacts}' and the " +
                            "publication is required — a glob that matched nothing is not a pass",
                    )
                }
                if (input.expectedCount != null && input.expectedCount > 0) {
                    return@StepHandler PublishJUnitReportsFailureOutput(
                        failureKind = FailureKind.SCRIPT,
                        message = "core.junit.publishReports: the publication expected ${input.expectedCount} " +
                            "report(s) and matched none ('${input.artifacts}')",
                    )
                }
                return@StepHandler AbsentOptionalReportsOutput(
                    artifacts = input.artifacts,
                    group = input.expectedGroup,
                )
            }

            if (input.expectedCount != null && matched.size != input.expectedCount) {
                return@StepHandler PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.SCRIPT,
                    message = "core.junit.publishReports: expected ${input.expectedCount} report(s) " +
                        "from '${input.artifacts}' and matched ${matched.size}",
                )
            }

            // Verify every candidate BEFORE archiving anything: an invalid report is a
            // different verdict from an absent one, and no unverified byte reaches the store.
            val totals = mutableListOf<PublishedReport>()
            for (file in matched) {
                val relative = root.relativize(file).toString().replace('\\', '/')
                val size = Files.size(file)
                if (size == 0L) {
                    return@StepHandler PublishJUnitReportsFailureOutput(
                        failureKind = FailureKind.USER,
                        message = "core.junit.publishReports: ${input.reportFormat} report is empty (0 bytes): $relative",
                    )
                }
                if (size > input.maxReportBytes) {
                    return@StepHandler PublishJUnitReportsFailureOutput(
                        failureKind = FailureKind.USER,
                        message = "core.junit.publishReports: $relative is $size bytes " +
                            "(maxReportBytes=${input.maxReportBytes}); refusing to parse",
                    )
                }
                val summary = try {
                    Files.newInputStream(file).use { raw ->
                        BufferedInputStream(raw, 64 * 1024).use { JUnitReportParser.parse(it, relative) }
                    }
                } catch (e: JUnitReportParseException) {
                    return@StepHandler PublishJUnitReportsFailureOutput(
                        failureKind = FailureKind.USER,
                        message = "core.junit.publishReports: $relative is not valid ${input.reportFormat} " +
                            "v${input.formatVersion} (declared mediaType ${input.mediaType}): ${e.message ?: "unknown"}",
                    )
                }
                totals += PublishedReport(
                    relPath = relative,
                    // The digest is NOT the receipt yet: the port reads the stored bytes back
                    // after the copy. It is filled from the CaptureReceipt below.
                    sha256 = "",
                    sizeBytes = size,
                    tests = summary.tests,
                    failures = summary.failures,
                    errors = summary.errors,
                    skipped = summary.skipped,
                )
            }

            val ops: ArchiveArtifactsOperations =
                ctx.capabilities.get(ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY, ArchiveArtifactsOperations::class)
            return@StepHandler when (val result = ops.archive(
                ArchiveArtifactsInput(
                    artifacts = input.artifacts,
                    allowEmptyArchive = false,
                    excludes = "",
                    fingerprint = false,
                    name = input.archiveName,
                ),
            )) {
                is ArchiveArtifactsSuccess -> PublishedReportsOutput(
                    group = input.expectedGroup,
                    receipts = totals.map { report ->
                        val receipt = result.files.firstOrNull { it.relPath == report.relPath }
                        report.copy(
                            sha256 = receipt?.sha256 ?: report.sha256,
                            sizeBytes = receipt?.size ?: report.sizeBytes,
                        )
                    },
                )
                is ArchiveArtifactsFailed -> PublishJUnitReportsFailureOutput(
                    failureKind = result.failureKind,
                    message = "core.junit.publishReports: capture failed: ${result.message}",
                )
            }
        }

    val definition: StepDefinition<PublishJUnitReportsInput, TypedStepOutput> =
        object : StepDefinition<PublishJUnitReportsInput, TypedStepOutput> {
            override val contract: StepContract<PublishJUnitReportsInput, TypedStepOutput> = StepContract(
                key = KEY,
                descriptor = descriptor,
                inputCodec = CorePublishJUnitReportsWireCodec,
                outputCodec = outputCodec,
                requiredCapabilities = setOf(EXECUTION_LOCATION_CAPABILITY, ARTIFACT_ARCHIVE_OPERATIONS_CAPABILITY),
            )

            override val handler: StepHandler<PublishJUnitReportsInput, TypedStepOutput> =
                this@CorePublishJUnitReportsStep.handler
        }

    /** Registers the step through the same open registry seam as any other core step. */
    fun registerInto(builder: StepRegistryBuilder) {
        builder.add(definition)
    }
}
