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
import java.nio.file.Path

/**
 * The producer's explicit declaration of what a JUnit report is, and of how it is published.
 *
 * Nothing here is guessed from a file name or a directory: [artifacts] is the Ant-style pattern
 * the author declared, [reportFormat] and [formatVersion] name the format version the reports
 * claim to carry, and [mediaType] is the transport spelling kept deliberately apart from that
 * semantics — a `.xml` under any name is only a candidate the pattern selected, and the parse
 * is what decides whether the bytes hold that format. The kind itself is not a choice: this
 * step verifies [JUnitXmlReportFormat.KIND] and declaring any other format fails closed at
 * decode, before one file is read.
 *
 * The declaration carries the verdict rules too, and they are all declared-only:
 *  - `required` (default true): a publication whose pattern matched no report is NOT a pass.
 *  - `expectedCount`: when non-null, the number of reports the publication must find; when
 *    null the publication declares no expectation and none is invented.
 *  - `expectedGroup`: a label for the report group, carried into the output only when
 *    declared. It never forces a build policy: no Jenkins-style threshold is implied here.
 *
 * Backward compatibility: this is a NEW key. `core.archiveArtifacts` and every existing step
 * keep their bytes and their semantics untouched; this step only CONSUMES the capture port
 * (`ArchiveArtifactsOperations`) and never redefines it.
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
        require(reportFormat == JUnitXmlReportFormat.KIND) {
            "core.junit.publishReports verifies only '${JUnitXmlReportFormat.KIND}'; the declared " +
                "reportFormat '$reportFormat' names a format this step cannot verify — refusing to decode"
        }
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

/** One published report: the identities of the captured bytes plus the totals the parse read from THOSE bytes. */
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
 * The publication succeeded: every matched report was captured through the artifact-archive
 * port and parsed from its own captured bytes. [receipts] carry the identities the
 * CaptureReceipts read back from the store (digest and size, never supplied by the step),
 * and every total was parsed from the bytes those identities name: the output never
 * describes bytes other than the ones the capture secured.
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
 * order this codec writes (the compat assertion lives in `CorePublishJUnitReportsStepTest`), so
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
                description = "the format the producer declares the reports carry; only junit-xml is " +
                    "verifiable and any other declared kind fails the decode (default junit-xml)",
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
 *  3. an expected count the author declared is checked against the selection BEFORE the
 *     capture, and the group label is carried only when declared. No build policy is forced;
 *  4. the matched set is handed to the artifact-archive capture port
 *     ([ArchiveArtifactsOperations]) FIRST: the capture secures the bytes write-once at
 *     their digest addresses and answers with `CaptureReceipt`s whose digest and size are
 *     read back from the store;
 *  5. the parse then reads THE CAPTURE, never the workspace file again: every report is
 *     parsed from the bytes read back through the port against its own receipt (a regular
 *     stored node, of the receipt's size, digesting to the receipt's identity), so the
 *     summary published corresponds EXACTLY to the bytes the published digest names — a
 *     report the workspace rewrites after its capture cannot alter what the publication
 *     declares about it. Absent, invalid and non-matching stay three different verdicts:
 *     an invalid report is a USER failure about captured bytes, and a capture the port
 *     cannot answer for is an ENGINE failure, never a publication.
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
            // A JSON null is a JsonPrimitive whose content is the text "null": reading it as
            // text turns an undeclared group into the group named "null". Only a primitive that
            // is not the null literal carries a value.
            fun prim(name: String): String? =
                (obj[name] as? kotlinx.serialization.json.JsonPrimitive)
                    ?.takeIf { it !is kotlinx.serialization.json.JsonNull }?.content
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

            // The capture comes FIRST: the port secures the matched bytes write-once and
            // answers with receipts whose digest and size are read back from the store.
            // Everything the publication declares below is read from THE CAPTURE, never
            // from the workspace files again, so a report rewritten after this point
            // cannot change what the publication says about it.
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
                is ArchiveArtifactsFailed -> PublishJUnitReportsFailureOutput(
                    failureKind = result.failureKind,
                    message = "core.junit.publishReports: capture failed: ${result.message}",
                )
                is ArchiveArtifactsSuccess -> publishFromCapture(input, ops, result)
            }
        }

    /**
     * Publishes from the capture, never from the workspace: each receipt is read back
     * verified through the port and its bytes are what the parse reads, so every fact
     * the output declares is a fact about the bytes the receipt's digest names.
     */
    private fun publishFromCapture(
        input: PublishJUnitReportsInput,
        ops: ArchiveArtifactsOperations,
        result: ArchiveArtifactsSuccess,
    ): TypedStepOutput {
        // A success naming no receipt would publish "PUBLISHED" about nothing: the
        // capture is the authority and it names the bytes it holds.
        if (result.files.isEmpty()) {
            return PublishJUnitReportsFailureOutput(
                failureKind = FailureKind.ENGINE,
                message = "core.junit.publishReports: the capture port succeeded without receipts; " +
                    "refusing to publish a summary about bytes nobody names",
            )
        }
        val reports = mutableListOf<PublishedReport>()
        for (receipt in result.files) {
            val relative = receipt.name.value
            // The size is a fact the store read back at capture time, so the empty and
            // the cap verdicts decide from the capture too — before any byte is read.
            if (receipt.size == 0L) {
                return PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.USER,
                    message = "core.junit.publishReports: ${input.reportFormat} report is empty (0 bytes): $relative",
                )
            }
            if (receipt.size > input.maxReportBytes) {
                return PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.USER,
                    message = "core.junit.publishReports: $relative is ${receipt.size} bytes " +
                        "(maxReportBytes=${input.maxReportBytes}); refusing to parse",
                )
            }
            val bytes = when (val read = ops.openCapture(receipt)) {
                is CapturedBytesRefusal -> return PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.ENGINE,
                    message = "core.junit.publishReports: the captured bytes for '$relative' " +
                        "could not be verified: ${read.message}",
                )
                is CapturedBytes -> read.bytes
            }
            val summary = try {
                // The parse reads THE CAPTURE: the bytes the port just verified against the
                // receipt. Reading the workspace file again here would publish totals about
                // bytes no digest names — the window the capture→parse order exists to close.
                JUnitReportParser.parse(java.io.ByteArrayInputStream(bytes), relative)
            } catch (e: JUnitReportParseException) {
                return PublishJUnitReportsFailureOutput(
                    failureKind = FailureKind.USER,
                    message = "core.junit.publishReports: $relative is not valid ${input.reportFormat} " +
                        "v${input.formatVersion} (declared mediaType ${input.mediaType}): ${e.message ?: "unknown"}",
                )
            }
            reports += PublishedReport(
                relPath = relative,
                sha256 = receipt.blobId.value,
                sizeBytes = receipt.size,
                tests = summary.tests,
                failures = summary.failures,
                errors = summary.errors,
                skipped = summary.skipped,
            )
        }
        return PublishedReportsOutput(group = input.expectedGroup, receipts = reports)
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
