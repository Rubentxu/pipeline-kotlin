package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.output.OutputStreamAddress
import dev.rubentxu.pipeline.v2.output.OutputStreamId
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Path

/**
 * The operations of a run, with their complete identity.
 *
 * ## What this answers
 *
 * "Which operations did this run have, and where are their bytes?"
 *
 * The Output Plane keeps every byte of a run under a directory whose name is a
 * **folded** projection of the stream identity: every character outside
 * `[A-Za-z0-9._-]` becomes an underscore. The bytes of an operation whose id
 * contains a `:` therefore live under a name that no longer contains it, and a
 * consumer that reads the directory listing cannot tell which operation it is
 * looking at. It can compare two names and guess. It cannot recover one.
 *
 * The frame index already holds the truth — it writes the whole identifier into
 * its own files and reads it back from there — and its own documentation says
 * that a consumer who wants ids asks it. What was missing was the surface: the
 * truth was inside the product and outside the reach of anything that was not
 * the product.
 *
 * This verb is that surface. It publishes the identity of every stream a run
 * declared, and it publishes it **complete**: the operation id as it was
 * minted, with its separators, not a name derived from one.
 *
 * ## Why the identity is published and not the directory
 *
 * A consumer that has to guess an identity cannot check it. Two operations
 * whose ids fold onto the same directory are indistinguishable to a guesser
 * and distinct to a reader: one of them is the one whose bytes are there. The
 * published list makes them two rows instead of one, which is the difference
 * between a defect a consumer can see and a defect nobody can.
 *
 * ## Why the operations are grouped
 *
 * An operation is the unit a consumer addresses: `console` takes an operation
 * id, and the operation owns one stream per channel. A flat list of streams
 * would make the consumer rebuild that grouping from the id, which is the same
 * parsing this verb exists to remove.
 */
internal object MainOperationsCli {

    /**
     * The schema of the published document.
     *
     * Version 1 is the first shape of this surface. It publishes the identity
     * of the run's operations and the streams each one owns, and nothing else:
     * a consumer that needs bytes asks `console`, and a consumer that needs
     * records asks `inspect`.
     */
    const val SCHEMA: String = "pipelinek.operations/1"

    private const val USAGE: String =
        "Usage: pipelinek operations <runId> --control-root <dir> [--format text|json]\n" +
            "\n" +
            "Lists the operations of a run with their complete identity, and the streams\n" +
            "each one owns. The identity is the operation id as it was minted; it is not a\n" +
            "name derived from the Output Plane's directory layout.\n"

    /**
     * One operation of a run, and the streams it owns.
     *
     * @property operation the operation id, complete, as it was minted.
     * @property streams the ids of the streams this operation owns, complete.
     */
    data class OperationListing(
        val operation: String,
        val streams: List<String>,
    )

    /**
     * The operations a set of stream ids denotes, grouped by operation.
     *
     * The grouping is by the operation id the stream id carries, and the order
     * is the order of the first appearance of each operation, so two readers
     * of the same plane see the same list.
     *
     * A stream whose id does not have this producer's shape is **not**
     * published: it has no operation to belong to, and inventing one would
     * attribute bytes to an operation that never ran.
     */
    fun operationsOf(streams: List<OutputStreamId>): List<OperationListing> {
        val byOperation = LinkedHashMap<String, MutableList<String>>()
        for (stream in streams) {
            val address = OutputStreamAddress.parse(stream) ?: continue
            byOperation.getOrPut(address.operationId) { ArrayList() }.add(stream.value)
        }
        return byOperation.map { (operation, owned) -> OperationListing(operation, owned) }
    }

    /**
     * The published document for [runId].
     *
     * Pure: it takes the listing and renders it, and it renders the same
     * listing the same way every time. The consumer reads this document, not
     * the model behind it, so a change in the rendering is a change in what
     * the consumer receives.
     */
    fun document(runId: String, operations: List<OperationListing>): JsonObject =
        buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("runId", JsonPrimitive(runId))
            put(
                "operations",
                JsonArray(
                    operations.map { listing ->
                        buildJsonObject {
                            put("operation", JsonPrimitive(listing.operation))
                            put(
                                "streams",
                                JsonArray(listing.streams.map { JsonPrimitive(it) }),
                            )
                        }
                    },
                ),
            )
        }

    /**
     * The text rendering: one complete operation identity per line.
     *
     * The line format is the identity and nothing else, because the consumer
     * of this format is a program that has to ADDRESS an operation, and an
     * address that has to be taken apart is an address that can be taken apart
     * wrongly.
     */
    fun text(operations: List<OperationListing>): String =
        operations.joinToString(separator = "\n", postfix = if (operations.isEmpty()) "" else "\n") {
            it.operation
        }

    /**
     * Entry point for `pipelinek operations <runId> --control-root <dir>`.
     *
     * The exit code is 0 when the listing was published, 2 when the request
     * itself was unusable. A run the plane has never heard of is not an
     * unusable request: it is a run with no operations, and it is published as
     * an empty list, because "this run wrote nothing" and "I asked about a run
     * that does not exist" are different facts and only one of them is a
     * failure of the request.
     *
     * The verb owns two options, and the admission says so. An option it does
     * not own used to be dropped without a word, so `operations <runId>
     * --control-root <dir> --bogus-flag` published an empty listing and exited
     * 0 — a false answer about a run the operator never asked about.
     */
    fun main(args: Array<String>): Int {
        val admission = CliOptionAdmission.admit(args, OPERATIONS_OPTIONS)
        val accepted = when (admission) {
            is CliAdmission.Rejected -> {
                System.err.println(admission.error.refusalLines("operations").joinToString("\n"))
                System.err.println(USAGE)
                return 2
            }
            is CliAdmission.Admitted -> admission
        }
        val runId = accepted.positionals.firstOrNull()
        val controlRoot = accepted.values["--control-root"]
        val format = accepted.values["--format"] ?: "text"

        if (runId == null) {
            System.err.println("operations: a run id is required.")
            System.err.println(USAGE)
            return 2
        }
        if (controlRoot.isNullOrBlank()) {
            System.err.println("operations: --control-root is required: the Output Plane lives under it.")
            System.err.println(USAGE)
            return 2
        }
        if (format != "text" && format != "json") {
            System.err.println("operations: --format must be text or json.")
            System.err.println(USAGE)
            return 2
        }

        val store = OutputPlaneProvider.storeForReading(Path.of(controlRoot))
        val operations = operationsOf(store.frameIndex().streamsOfRun(runId))

        if (format == "json") {
            val line = Json { prettyPrint = false; encodeDefaults = true }
                .encodeToString(JsonObject.serializer(), document(runId, operations)) + "\n"
            System.out.write(line.toByteArray(Charsets.UTF_8))
        } else {
            System.out.write(text(operations).toByteArray(Charsets.UTF_8))
        }
        System.out.flush()
        return 0
    }
}
