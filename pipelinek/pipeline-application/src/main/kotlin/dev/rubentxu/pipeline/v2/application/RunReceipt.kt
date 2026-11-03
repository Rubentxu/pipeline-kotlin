package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.application.durable.OutputPlaneProvider
import dev.rubentxu.pipeline.v2.events.CompilationFinished
import dev.rubentxu.pipeline.v2.events.DomainEvent
import dev.rubentxu.pipeline.v2.events.RunFinished
import dev.rubentxu.pipeline.v2.events.StepFinished
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Base64

/**
 * The receipt of a run: what the run concluded, and what it wrote, in ONE document.
 *
 * ## What this answers
 *
 * "How did the run end, and what did it leave behind?"
 *
 * Answering that used to mean launching the product again, once per question: one launch to
 * learn the outcome, one to learn the operations, one per operation to learn its bytes. Each
 * launch is a process, and a suite that asks three questions of one run pays for three
 * processes. The receipt is the run's own account of itself, written BY the run while it still
 * holds everything it knows: the consumer reads a file instead of asking the product to look.
 *
 * ## Why the run writes it and not the reader
 *
 * A reader that reconstructs the answer has to re-derive it, and a re-derived answer is a
 * second authority: it can disagree with the run that produced the facts. The run knows its
 * outcome because it decided it; it knows its operations because it wrote them; it knows their
 * bytes because it committed them. Nothing here re-derives anything.
 *
 * ## Why the operations keep their COMPLETE identity
 *
 * The Output Plane stores its streams under a folded projection of the stream identity, so the
 * directory listing cannot tell an operation from another one whose id folds onto the same
 * name. The identity that disambiguates them is the one the frame index holds, and it is
 * published whole — the same identity [MainOperationsCli] publishes, read from the same
 * authority. A receipt that published a folded name would answer a question nobody asked.
 *
 * ## Why the bytes are a PAGE and not the whole stream
 *
 * The bytes published here are exactly the bounded page the `console` verb publishes, read
 * through the same service with the same bound. A receipt that published MORE than the reader
 * can get would be a second, richer byte authority; a receipt that published LESS would leave
 * the consumer to guess. Same bytes, same bound, same reader.
 *
 * ## Why a refused read publishes NO bytes
 *
 * A refusal is a fact about the QUESTION, not about the run. The receipt has one shape, and
 * inventing a per-operation refusal inside it would make the reader handle two shapes for the
 * same field. The operation is published with no bytes, which says exactly what is true: this
 * operation's bytes could not be read.
 */
internal object RunReceipt {

    /**
     * The schema of the published document.
     *
     * Version 1 is the first shape of this surface: the run's identity, its verdict, its exit
     * code and the operations it committed, with their bytes.
     */
    const val SCHEMA: String = "pipelinek.run-receipt/1"

    /**
     * What the run concluded.
     *
     * @property outcome the outcome of the LAST [RunFinished] the run published, or empty when
     *   the run published none.
     * @property steps how many steps the run finished.
     * @property cacheKey the compilation identity the run ran under, as `version-value`, or
     *   empty when the run published no compilation.
     */
    data class Verdict(
        val outcome: String,
        val steps: Int,
        val cacheKey: String,
    )

    /**
     * One operation of the run, and the bytes it committed.
     *
     * @property operation the operation id, complete, as it was minted.
     * @property bytes the operation's bytes, base64 encoded.
     */
    data class Operation(
        val operation: String,
        val bytes: String,
    )

    /**
     * The verdict of a run, read from the events the run published.
     *
     * ## Why the LAST [RunFinished] and not the first
     *
     * A durable run id carries every attempt made under it, so the same run id can publish
     * more than one ending. The ending that describes the run is the one it published LAST:
     * the last word is the one the run stands behind, and the same rule the exit decision
     * already follows when it reads the run's last event.
     *
     * ## Why the cache key is `version-value`
     *
     * The version and the digest are two facts, and the digest alone does not say which
     * algorithm produced it. Publishing them together — the version first, so the reader can
     * split them — keeps the key unambiguous: two keys with the same digest and different
     * versions are different keys, and a reader that sees only the digest cannot tell.
     *
     * Pure: the same events always produce the same verdict.
     */
    fun verdictOf(events: List<DomainEvent>): Verdict {
        val outcome = events.filterIsInstance<RunFinished>().lastOrNull()?.outcome.orEmpty()
        val steps = events.count { it is StepFinished }
        val cacheKey = events.filterIsInstance<CompilationFinished>().lastOrNull()
            ?.cacheKey
            ?.takeIf { it.value.isNotEmpty() }
            ?.let { key -> key.version + "-" + key.value }
            .orEmpty()
        return Verdict(outcome = outcome, steps = steps, cacheKey = cacheKey)
    }

    /**
     * The published document for [runId].
     *
     * Pure: it takes the facts and renders them, and it renders the same facts the same way
     * every time. The consumer reads this document, not the model behind it, so a change in
     * the rendering is a change in what the consumer receives.
     */
    fun document(
        runId: String,
        verdict: Verdict,
        exitCode: Int,
        operations: List<Operation>,
    ): JsonObject =
        buildJsonObject {
            put("schema", JsonPrimitive(SCHEMA))
            put("runId", JsonPrimitive(runId))
            put(
                "verdict",
                buildJsonObject {
                    put("outcome", JsonPrimitive(verdict.outcome))
                    put("steps", JsonPrimitive(verdict.steps))
                    put("cacheKey", JsonPrimitive(verdict.cacheKey))
                },
            )
            put("exitCode", JsonPrimitive(exitCode))
            put(
                "operations",
                JsonArray(
                    operations.map { operation ->
                        buildJsonObject {
                            put("operation", JsonPrimitive(operation.operation))
                            put("bytes", JsonPrimitive(operation.bytes))
                        }
                    },
                ),
            )
        }

    /**
     * The text rendering: one line, ended by a newline.
     *
     * One line, because the receipt is read by a program that wants one value per line, and
     * ended by a newline, because a file that does not end with a newline is a file whose last
     * line some readers do not see.
     */
    fun render(document: JsonObject): String =
        Json { prettyPrint = false; encodeDefaults = true }
            .encodeToString(JsonObject.serializer(), document) + "\n"

    /**
     * The document of a run that has already happened, read from what the run left behind.
     *
     * The operations come from the same authority the `operations` verb reads, and their bytes
     * from the same reader the `console` verb reads, with the same bound. Nothing here is
     * re-derived: what the run committed is what is published.
     */
    fun capture(
        controlDirRoot: Path,
        runId: String,
        events: List<DomainEvent>,
        exitCode: Int,
    ): JsonObject {
        val store = OutputPlaneProvider.storeForReading(controlDirRoot)
        val operations = MainOperationsCli.operationsOf(store.frameIndex().streamsOfRun(runId)).map { listing ->
            Operation(
                operation = listing.operation,
                bytes = Base64.getEncoder().encodeToString(
                    readOperationBytes(controlDirRoot, runId, listing.operation),
                ),
            )
        }
        return document(runId, verdictOf(events), exitCode, operations)
    }

    /**
     * Write the document to [path], in one step a reader can observe.
     *
     * The write is atomic: the document is written to a temporary file in the SAME directory
     * and then moved onto the destination, so a reader sees either the previous document or
     * the whole new one, never half of either. A reader that caught a half-written receipt
     * would read a truncated run as a run that did nothing.
     */
    fun write(path: Path, document: JsonObject) {
        val target = path.toAbsolutePath()
        val directory = target.parent ?: Path.of(".")
        Files.createDirectories(directory)
        val temporary = Files.createTempFile(directory, TEMPORARY_PREFIX, TEMPORARY_SUFFIX)
        try {
            Files.write(temporary, render(document).toByteArray(Charsets.UTF_8))
            Files.move(
                temporary,
                target,
                StandardCopyOption.ATOMIC_MOVE,
                StandardCopyOption.REPLACE_EXISTING,
            )
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * Write the receipt of a run that just happened, WHEN one was asked for.
     *
     * The path is the opt-in: no path, no receipt, and no trace of a receipt in the run. The
     * decision lives here so that both execution worlds ask the same question in the same way,
     * and so that no world publishes a receipt nobody asked for.
     */
    fun publishWhenRequested(
        path: String?,
        controlDirRoot: Path,
        runId: String,
        events: List<DomainEvent>,
        exitCode: Int,
    ) {
        path?.let { requested ->
            publish(Path.of(requested), controlDirRoot, runId, events, exitCode)
        }
    }

    /**
     * Write the receipt of a run that just happened, and say why it could not be written.
     *
     * ## Why a failure here does not take the run down
     *
     * The receipt is a report ABOUT the run. A run that finished — succeeded or failed — has
     * already decided its exit code, and a report that cannot be written is not a reason to
     * change the answer the run earned. The failure is announced on stderr, where every other
     * operator-facing diagnostic goes, and the run keeps the exit it had.
     */
    fun publish(
        path: Path,
        controlDirRoot: Path,
        runId: String,
        events: List<DomainEvent>,
        exitCode: Int,
    ) {
        try {
            write(path, capture(controlDirRoot, runId, events, exitCode))
        } catch (failure: Exception) {
            System.err.println(
                "Error: the run receipt was not written to $path: " +
                    (failure.message ?: failure.javaClass.simpleName),
            )
        }
    }

    /**
     * The bytes of one operation, as the reader of the console would get them.
     *
     * A refusal publishes no bytes: the receipt has one shape for the bytes of an operation,
     * and a second shape for "these bytes could not be read" would make the reader carry two
     * readings of the same field.
     */
    private fun readOperationBytes(controlDirRoot: Path, runId: String, operation: String): ByteArray =
        when (
            val read = ConsoleReadService.read(
                controlDirRoot = controlDirRoot,
                runId = runId,
                opId = operation,
                after = null,
                maxBytes = ConsoleReadService.DEFAULT_PAGE_BYTES,
            )
        ) {
            is ConsoleReadService.Result.Page -> read.page.bytes
            is ConsoleReadService.Result.Refused -> ByteArray(0)
        }

    /** The prefix of the temporary file a receipt is written through. */
    private const val TEMPORARY_PREFIX: String = ".pipelinek-run-receipt-"

    /** The suffix of the temporary file a receipt is written through. */
    private const val TEMPORARY_SUFFIX: String = ".json"
}
