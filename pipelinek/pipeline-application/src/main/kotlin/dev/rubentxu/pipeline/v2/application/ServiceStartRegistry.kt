package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.jsonPrimitive
import java.io.IOException
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.concurrent.CompletableFuture

/**
 * What one accepted start is remembered as.
 *
 * @property digest the fingerprint of the request that was made.
 * @property runId the identity of the run that was started.
 * @property correlationId the token the service chose and passed to the run.
 * @property ownerId the authority that holds the run's lease.
 * @property startedAtMs when the run took its ownership.
 */
internal data class ServiceStartRecord(
    val digest: String,
    val runId: String,
    val correlationId: String,
    val ownerId: String,
    val startedAtMs: Long,
)

/**
 * The claim a caller gets on an idempotency key.
 *
 * The four shapes are the four situations a repeated request can find, and
 * each one says what the caller has to do next: answer with what is known,
 * wait for what is being started, start it, or refuse.
 */
internal sealed interface ServiceStartClaim {

    /** The key is already in the record, and the request is the same one. */
    data class Known(val record: ServiceStartRecord) : ServiceStartClaim

    /**
     * The key is being started RIGHT NOW, by another request.
     *
     * The future is completed when that start finishes. A caller that waits
     * for it answers with the same run the other request will answer with,
     * and a start that is in flight is never a SECOND start.
     */
    data class Awaiting(val future: CompletableFuture<ServiceStartRecord>) : ServiceStartClaim

    /** The key was used before with a different request. */
    data object Conflict : ServiceStartClaim

    /** The key is free, and this caller owns the start. */
    data class Owner(val future: CompletableFuture<ServiceStartRecord>) : ServiceStartClaim
}

/**
 * The record of the starts this service made, and the promise that a key
 * means one request.
 *
 * ## What it holds
 *
 * For every accepted start, the KEY the client chose and the run that key
 * started. The fingerprint of the request travels with it, because the
 * promise is not "this key names a run" but "this key names this request":
 * the same key with another request is a conflict, not a second run.
 *
 * ## Why it is a FILE and not a table in memory
 *
 * Because the promise has to survive the service. A table in memory would
 * make the promise true only until the service restarts, and a client that
 * retries after a restart would get a second run for the same key. The file
 * is small, it is written atomically, and it is read once when the service
 * starts.
 *
 * ## Why the claim is taken BEFORE the run is started
 *
 * Because two requests can arrive at the same time, and the second one has
 * to find the first one's start and NOT start a second run. The claim is
 * what the second one finds, and it is released when the start fails, so a
 * request that failed can be tried again.
 *
 * ## What it costs, and what it does not promise
 *
 * The records are KEPT: one entry per key that started a run, for the life
 * of the service, and the file is rewritten whole on every start. The cost
 * of the FILE is O(n) per start and the cost of the MEMORY is O(n) as well,
 * and both are the price of a promise that has to survive the service: a
 * record that was forgotten is a key that names two runs. A service of long
 * life that sees many keys accumulates them, and that is a cost the
 * operator takes on when he lets the service start runs.
 */
internal class ServiceStartRegistry private constructor(
    private val path: Path?,
) {

    /** The lock that guards both tables and the file. */
    private val lock = Any()

    /** The starts that are DONE. */
    private val records = LinkedHashMap<String, ServiceStartRecord>()

    /** The starts that are IN FLIGHT, by key. */
    private val pending = LinkedHashMap<String, PendingStart>()

    /**
     * The record of [key], or null when the key names none.
     */
    fun find(key: String): ServiceStartRecord? = synchronized(lock) { records[key] }

    /**
     * The claim on [key] for the request whose fingerprint is [digest].
     */
    fun claim(key: String, digest: String): ServiceStartClaim = synchronized(lock) {
        val known = records[key]
        if (known != null) {
            return if (known.digest == digest) {
                ServiceStartClaim.Known(known)
            } else {
                ServiceStartClaim.Conflict
            }
        }
        val inFlight = pending[key]
        if (inFlight != null) {
            return if (inFlight.digest == digest) {
                ServiceStartClaim.Awaiting(inFlight.future)
            } else {
                ServiceStartClaim.Conflict
            }
        }
        val future = CompletableFuture<ServiceStartRecord>()
        pending[key] = PendingStart(digest, future)
        ServiceStartClaim.Owner(future)
    }

    /**
     * The start of [key] is DONE, and this is what it was.
     *
     * The record is written to the file BEFORE the future is completed, so
     * a caller that is woken up by the future reads a file that already
     * says what the answer says.
     */
    fun complete(key: String, record: ServiceStartRecord) {
        synchronized(lock) {
            records[key] = record
            val inFlight = pending.remove(key)
            try {
                persist()
            } catch (_: Exception) {
                // The record STAYS in memory and the promise holds for the
                // lifetime of this service. The file that carries it
                // across restarts could not be written, and that is a
                // fault of the machine, not of the run: the run started,
                // the client has to be answered, and a client that is
                // left without an answer would read the closed connection
                // as a failure of the start.
                //
                // What the failure costs is stated rather than hidden: the
                // promise degrades to the lifetime of this service, so a
                // client that retries AFTER a restart may get a second run
                // for the same key.
            }
            inFlight?.future?.complete(record)
        }
    }

    /**
     * The start of [key] FAILED, and the key is free again.
     *
     * The key is released because nothing was started: a request that could
     * not start a run has to be able to ask again. The future is completed
     * with the failure, so a caller that was waiting for it is answered
     * with the same failure and does not wait for a run that will not be.
     *
     * The failure that travels is the [refusal] ITSELF and not a summary of
     * it: the caller that was waiting reads the same named refusal the owner
     * of the start read, and a client that decides whether to try again by
     * the NAME of the refusal is not told one thing by the owner and
     * another by the waiter.
     */
    fun fail(key: String, refusal: ServiceRefusal) {
        synchronized(lock) {
            val inFlight = pending.remove(key)
            inFlight?.future?.completeExceptionally(ServiceStartFailed(refusal))
        }
    }

    /**
     * Writes the record, atomically and privately.
     *
     * The file is the service's own, next to its socket, and it is `0600`:
     * it says which runs the operator started, and the operator's own
     * processes are the only ones that read it.
     */
    private fun persist() {
        val target = path ?: return
        val directory = target.parent ?: return
        val document = buildJsonObject {
            records.forEach { (key, record) ->
                put(key, buildJsonObject {
                    put("digest", JsonPrimitive(record.digest))
                    put("runId", JsonPrimitive(record.runId))
                    put("correlationId", JsonPrimitive(record.correlationId))
                    put("ownerId", JsonPrimitive(record.ownerId))
                    put("startedAtMs", JsonPrimitive(record.startedAtMs))
                })
            }
        }
        val temporary = Files.createTempFile(directory, FILE_NAME, ".tmp")
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(java.nio.file.attribute.PosixFilePermission.OWNER_READ, java.nio.file.attribute.PosixFilePermission.OWNER_WRITE),
            )
            Files.writeString(temporary, document.toString())
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }

    /**
     * A start that is in flight.
     */
    private data class PendingStart(
        val digest: String,
        val future: CompletableFuture<ServiceStartRecord>,
    )

    companion object {

        /**
         * The name of the file the service owns, next to its socket.
         */
        const val FILE_NAME: String = "starts"

        /**
         * The record of the starts in [path].
         *
         * A file that is not there is an EMPTY record: the service has not
         * started anything yet. A file that IS there and cannot be read is
         * a REFUSAL, because a record that cannot be read is a promise that
         * cannot be kept, and a service that silently forgets its promises
         * would start a second run for a key it already answered.
         */
        fun read(path: Path): ServiceStartRegistryReading {
            val registry = ServiceStartRegistry(path)
            if (!Files.exists(path)) return ServiceStartRegistryReading.Read(registry)
            val text = try {
                Files.readString(path)
            } catch (failure: IOException) {
                return ServiceStartRegistryReading.Refused(
                    "the record of the starts could not be read: ${failure.message}",
                )
            }
            if (text.isBlank()) return ServiceStartRegistryReading.Read(registry)
            val parsed = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return ServiceStartRegistryReading.Refused(
                "the record of the starts is not a document: $path",
            )
            parsed.forEach { (key, value) ->
                val entry = value as? JsonObject
                    ?: return ServiceStartRegistryReading.Refused(
                        "the record of the starts holds something that is not a start: $path",
                    )
                val record = ServiceStartRecord(
                    digest = entry.string("digest")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without a fingerprint: $path",
                        ),
                    runId = entry.string("runId")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without a run: $path",
                        ),
                    correlationId = entry.string("correlationId")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without a correlation: $path",
                        ),
                    ownerId = entry.string("ownerId")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without an owner: $path",
                        ),
                    startedAtMs = entry.long("startedAtMs")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without a moment: $path",
                        ),
                )
                registry.records[key] = record
            }
            return ServiceStartRegistryReading.Read(registry)
        }

        /** The string [key] holds, or null when it holds none. */
        private fun JsonObject.string(key: String): String? =
            (this[key] as? JsonPrimitive)?.contentOrNull

        /** The long [key] holds, or null when it holds none. */
        private fun JsonObject.long(key: String): Long? =
            (this[key] as? JsonPrimitive)?.contentOrNull?.toLongOrNull()
    }
}

/**
 * What the record of the starts held.
 */
internal sealed interface ServiceStartRegistryReading {

    /** The record was read, and it holds what it holds. */
    data class Read(val registry: ServiceStartRegistry) : ServiceStartRegistryReading

    /** The record could not be read, and this says why. */
    data class Refused(val detail: String) : ServiceStartRegistryReading
}

/**
 * A start that did not happen.
 *
 * It travels to the callers that were waiting for the same key, so that
 * they are answered with the same failure and not with a wait that never
 * ends.
 *
 * The refusal it carries is the refusal the OWNER of the start read, and
 * not a summary of it: the waiter reads the same name, so a client that
 * decides whether to try again by the name of the refusal is not told one
 * thing by the owner and another by the waiter.
 */
internal class ServiceStartFailed(val refusal: ServiceRefusal) : RuntimeException(
    refusal.kind,
)
