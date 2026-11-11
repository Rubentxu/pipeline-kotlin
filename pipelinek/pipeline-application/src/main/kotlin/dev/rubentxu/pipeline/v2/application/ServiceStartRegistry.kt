package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.service.ServiceRefusal
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.contentOrNull
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.attribute.PosixFilePermission
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
 * Where the record of the starts is kept between two lives of the service.
 *
 * ## Why a port
 *
 * Because the failure of a write has to be producible ON DEMAND: a
 * degradation that cannot be provoked cannot be tested, and a degradation that
 * is not tested is a promise nobody keeps.
 *
 * ## What the port says about itself
 *
 * The [location] is where the record lives, in the words the refusals use: a
 * refusal that does not say which file it is about leaves the operator
 * looking for it.
 *
 * ## What the record holds
 *
 * The fingerprint of a request and the identity of a run. NEVER a credential:
 * the file says which runs the operator started, and it does not say how any
 * client proved who it was.
 */
internal interface ServiceStartStore {

    /** Where the record lives, in the words the refusals use. */
    val location: String

    /** The record as it stands, or null when there is none. */
    fun read(): String?

    /** Writes the record whole, replacing what was there. */
    fun write(document: String)
}

/**
 * The record of the starts, in a file next to the socket of the service.
 *
 * ## Why the file is private and the write is atomic
 *
 * The file says which runs the operator started, so it is `0600`: the
 * operator's own processes are the only ones that read it. The write is
 * ATOMIC: the document is written beside the target and moved over it, so a
 * reader sees either the previous record or this one, and a machine that
 * dies in the middle of a write leaves the previous record and not a broken
 * one.
 */
internal class FileServiceStartStore(private val path: Path) : ServiceStartStore {

    /** Where the record lives, in the words the refusals use. */
    override val location: String = path.toString()

    /** The record as it stands, or null when the file is not there. */
    override fun read(): String? =
        if (Files.exists(path)) Files.readString(path) else null

    /** Writes the record whole, replacing what was there. */
    override fun write(document: String) {
        val directory = path.parent ?: return
        val temporary = Files.createTempFile(directory, ServiceStartRegistry.FILE_NAME, ".tmp")
        try {
            Files.setPosixFilePermissions(
                temporary,
                setOf(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE),
            )
            Files.writeString(temporary, document)
            try {
                Files.move(temporary, path, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(temporary, path, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(temporary)
        }
    }
}

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

    /**
     * The key was claimed by a start whose OUTCOME is unknown.
     *
     * The claim was written to the file before the child was launched, and
     * the service died before that start could say what it became: the run
     * may be alive, and the key may not be used again, because a second run
     * for the same key is exactly what the key promises there is not.
     *
     * The [correlationId] is the token the service gave to the lost run, and
     * it is what lets the operator FIND it: the child wrote it in its own
     * correlated fact.
     *
     * The [reconciliation] is what the operator does with that run once it
     * is found. A state that names the problem and not the remedy sends the
     * operator to look for one, and the answer to that search is written
     * down here: the operations the product already has over the substrate,
     * and the condition that tells which one applies.
     */
    data class EffectUnknown(
        val correlationId: String,
        val reconciliation: String = ServiceStartReconciliation.guide(correlationId),
    ) : ServiceStartClaim

    /** The key is free, and this caller owns the start. */
    data class Owner(val future: CompletableFuture<ServiceStartRecord>) : ServiceStartClaim

    /**
     * The key COULD NOT be claimed, because the claim could not be written.
     *
     * The claim is what makes the promise of the key survive a restart: it
     * is written to the file BEFORE the child is launched, and a service that
     * died with the claim on disk knows that the key is in flight. A claim
     * that could not be written is not a claim, and a start that cannot be
     * remembered is a start that cannot be promised: the run it would launch
     * could be launched a second time after a restart, and the promise of the
     * key —one key, one request— would be broken exactly where it was made.
     *
     * So the start does not happen, and the refusal says so by name.
     */
    data class Refused(val cause: String) : ServiceStartClaim
}

/**
 * How a start whose effect is unknown is reconciled.
 *
 * ## What it names
 *
 * The two operations the product already has over the substrate, and the
 * condition that tells which one applies:
 *
 * - `runs recover` when the run's work finished and its terminal was never
 *   recorded;
 * - `runs cancel` when the run is still there and no live process works on it.
 *
 * The names are read from the CLI that offers them, so a guide that names an
 * operation that does not exist cannot be written.
 *
 * ## Why the correlation travels with the guide
 *
 * Because the correlation is what finds the run: the child wrote it in its
 * own correlated fact, and the operator reads the substrate to find the run
 * that wrote it. A guide that names the operations without naming the run
 * sends the operator looking for a run they cannot identify.
 *
 * ## What it does not promise
 *
 * That the run exists. The effect is unknown, and the run may never have
 * started: reconciling a run that is not there is a refusal of the substrate,
 * and freeing the key is the operator's decision.
 */
internal object ServiceStartReconciliation {

    /** The operation that reconciles a run whose work finished without a terminal. */
    val recover: String = "runs ${MainRunsCli.RunsVerb.Recover.spelling}"

    /** The operation that closes a run that no live process works on. */
    val cancel: String = "runs ${MainRunsCli.RunsVerb.Cancel.spelling}"

    /**
     * The line that tells the operator how to reconcile the run of [correlationId].
     */
    fun guide(correlationId: String): String =
        "the correlation of the lost start is $correlationId; find the run that wrote it in the " +
            "substrate and reconcile it with '$recover' when its work finished without a terminal, " +
            "or with '$cancel' when it is still there"
}

/**
 * A start whose result the service knows and could not write down.
 *
 * ## What it says
 *
 * - the correlation the service gave to the run: it is what finds the run;
 * - the run, when the result is that the run started;
 * - the reason the machine gave for refusing the write;
 * - what the operator has to do about it.
 *
 * ## What it does not say
 *
 * The credential of the client, and not even the fingerprint of it. The
 * degradation is said out loud, and a line that names a credential is a line
 * that ends up in a log somebody else reads. The key of the start is not said
 * either: the correlation finds the run, and the run finds the key in the
 * record of the starts.
 */
internal sealed interface ServiceStartDegradation {

    /** The correlation the service gave to the run of this start, when it gave one. */
    val correlationId: String?

    /** What the machine said when it refused the write. */
    val detail: String

    /** The lines the operator reads. */
    fun describe(): String

    /**
     * The run started, and the service cannot write it down.
     *
     * The record of the start stays in memory, so the client is answered
     * with the run that started and a retry in the life of this service gets
     * that same run. What is lost is the promise ACROSS a restart: the file
     * still holds the claim, and a service that starts again finds a key
     * whose effect is unknown.
     */
    data class Started(
        override val correlationId: String,
        val runId: String,
        override val detail: String,
    ) : ServiceStartDegradation {

        override fun describe(): String = buildString {
            appendLine("the record of the starts could not be written: $detail")
            appendLine(
                "the start of the correlation $correlationId started the run $runId and the " +
                    "service cannot write it down",
            )
            appendLine(
                "the next write of the record repairs the file; a service that stops before that " +
                    "recovers the key as claimed, with an unknown effect: reconcile the run with " +
                    "'${ServiceStartReconciliation.recover}' when its work finished without a " +
                    "terminal, or with '${ServiceStartReconciliation.cancel}' when it is still " +
                    "there",
            )
        }
    }

    /**
     * The start failed, and the service cannot release the key.
     *
     * Nothing was started, so there is no run to reconcile: the key is stuck
     * in the record of the starts and the operator frees it there.
     */
    data class Failed(
        override val correlationId: String?,
        override val detail: String,
    ) : ServiceStartDegradation {

        override fun describe(): String = buildString {
            appendLine("the record of the starts could not be written: $detail")
            val named = correlationId?.let { " of the correlation $it" }.orEmpty()
            appendLine(
                "the start$named failed and the service cannot release the key: " +
                    "no run was left behind",
            )
            appendLine(
                "the next write of the record repairs the file; a service that stops before that " +
                    "recovers the key as claimed, with an unknown effect, and there is no run to " +
                    "reconcile: the operator frees the key in the record of the starts",
            )
        }
    }
}

/**
 * Where the record of the starts says what it could not do.
 *
 * ## Why a port
 *
 * Because the degradation has to reach the OPERATOR, and the operator reads
 * the channel the composition gives it: the log of the service. A record that
 * degraded in silence would leave a promise broken with nobody noticing, and
 * a broken promise that nobody notices is the one that costs the most.
 *
 * ## What it never does
 *
 * It never fails a start, and it never starts anything: the run is what the
 * client asked for, and the diagnostic is a word about it. Whatever the
 * channel does, the answer the client reads is the answer the start produced.
 */
internal fun interface ServiceStartDiagnostics {

    /** Says that the result of a start could not be written down. */
    fun degraded(degradation: ServiceStartDegradation)
}

/**
 * The diagnostics of a record that says nothing.
 *
 * It is the default of the registry, so that a registry built outside the
 * service does not have to invent a channel in order to stay quiet.
 */
internal object NoServiceStartDiagnostics : ServiceStartDiagnostics {
    override fun degraded(degradation: ServiceStartDegradation) = Unit
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
 * ## Why the claim is PERSISTED before the child is launched
 *
 * Because a claim that lives only in memory is a claim that a crash
 * erases, and a claim that a crash erased is a key that names two runs: the
 * one that survived the crash, alive and writing, and the one the retry
 * starts. The claim is written to the file with the fingerprint of the
 * request and with the correlation the service gave to the run, and a
 * service that finds one at startup knows that the effect of that key is
 * UNKNOWN: the run may be alive, and the key is not free.
 *
 * The promise it makes is the same one the record makes, with a different
 * answer: a record says "this key started this run", and a claim says
 * "this key MAY have started a run, and the service cannot tell".
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
 *
 * What the service does NOT promise is that the file is always writable.
 * The CLAIM is the promise that has to survive a restart, so a claim that
 * could not be written is not a claim: the start is refused, and no child is
 * launched (see [ServiceStartClaim.Refused]). The RECORD of a start that
 * already happened cannot be un-written, and a record that could not be
 * persisted keeps its promise for the life of this service and not beyond
 * it. The degradation is said out loud: the [diagnostics] are told which
 * start could not be written down, which run is at stake, and what the
 * operator has to do about it. A promise that degrades in silence is a
 * promise nobody is looking after, and the operator is the one who can.
 *
 * ## What the file holds when a write fails
 *
 * The claim that was written before the child was launched. The service
 * knows more than that —it knows the run, or it knows that the start
 * failed— and the file does not, until a later write succeeds: the file is
 * written whole, so the next write repairs it. A service that dies in that
 * window recovers a key whose effect is unknown, which is the safe answer:
 * the run may be alive, and the key is not free. The degradation is named
 * when it happens, so the operator knows whether a run is at stake or
 * nothing was left behind.
 */
internal class ServiceStartRegistry private constructor(
    private val store: ServiceStartStore,
    private val diagnostics: ServiceStartDiagnostics,
) {

    /** The lock that guards both tables and the file. */
    private val lock = Any()

    /** The starts that are DONE. */
    private val records = LinkedHashMap<String, ServiceStartRecord>()

    /**
     * The starts whose EFFECT is unknown, by key.
     *
     * A claim is here from the moment it is taken until the start is
     * answered —done or failed— and it is here FOREVER when the service
     * died in between: that is the whole point of writing it down.
     */
    private val claims = LinkedHashMap<String, ServiceStartClaimRecord>()

    /** The starts that are IN FLIGHT, by key. */
    private val pending = LinkedHashMap<String, PendingStart>()

    /**
     * The record of [key], or null when the key names none.
     */
    fun find(key: String): ServiceStartRecord? = synchronized(lock) { records[key] }

    /**
     * The claim on [key] for the request whose fingerprint is [digest].
     *
     * The [correlationId] is the token the service chose for the run, and it
     * is written with the claim: a claim whose run is lost is found by the
     * correlation the child wrote in its own correlated fact.
     */
    fun claim(key: String, digest: String, correlationId: String): ServiceStartClaim =
        synchronized(lock) {
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
            val persisted = claims[key]
            if (persisted != null) {
                // The effect of this key is UNKNOWN: the service claimed it
                // and died before the start could say what it became. The
                // key is not free, because the run it may have started may
                // still be alive.
                return if (persisted.digest == digest) {
                    ServiceStartClaim.EffectUnknown(persisted.correlationId)
                } else {
                    ServiceStartClaim.Conflict
                }
            }
            val future = CompletableFuture<ServiceStartRecord>()
            pending[key] = PendingStart(digest, future)
            claims[key] = ServiceStartClaimRecord(digest, correlationId)
            // The claim is written BEFORE the child is launched, and it is
            // written or it is nothing: a claim that only lives in this
            // process dies with it, and the key would be free again for a
            // run that may still be alive.
            try {
                persist()
            } catch (failure: Exception) {
                pending.remove(key)
                claims.remove(key)
                return ServiceStartClaim.Refused(failure.shortDiagnostic())
            }
            ServiceStartClaim.Owner(future)
        }

    /**
     * The start of [key] is DONE, and this is what it was.
     *
     * The record is written to the file BEFORE the future is completed, so
     * a caller that is woken up by the future reads a file that already
     * says what the answer says.
     *
     * When the file refuses the write, the record STAYS in memory and the
     * degradation is named: the client is answered with the run that
     * started, and the operator is told that the record of that start is
     * not on the disk.
     */
    fun complete(key: String, record: ServiceStartRecord) {
        synchronized(lock) {
            records[key] = record
            claims.remove(key)
            val inFlight = pending.remove(key)
            persistOutcome { detail ->
                ServiceStartDegradation.Started(
                    correlationId = record.correlationId,
                    runId = record.runId,
                    detail = detail,
                )
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
     *
     * When the file refuses the release, the key stays claimed ON THE DISK
     * and the degradation is named: the service knows that no run was left
     * behind, and the operator is told that the key has to be freed by hand.
     */
    fun fail(key: String, refusal: ServiceRefusal) {
        synchronized(lock) {
            val inFlight = pending.remove(key)
            val claimed = claims.remove(key)
            persistOutcome { detail ->
                ServiceStartDegradation.Failed(
                    correlationId = claimed?.correlationId,
                    detail = detail,
                )
            }
            inFlight?.future?.completeExceptionally(ServiceStartFailed(refusal))
        }
    }

    /**
     * What the failure says about itself, in one line.
     *
     * The name of the failure and its own words, without the stack: a
     * diagnostic that has to be read in a log is a diagnostic that nobody
     * reads.
     */
    private fun Throwable.shortDiagnostic(): String {
        val cls = this::class.simpleName ?: this.javaClass.name
        val msg = message?.take(120)?.replace('\n', ' ')
        return if (msg.isNullOrBlank()) cls else "$cls: $msg"
    }

    /**
     * Writes the record, and NAMES the degradation when it cannot.
     *
     * The failure of a write is not swallowed: a start whose result the
     * service knows and could not write down is a promise degraded, and a
     * degraded promise that nobody is told about is a broken promise. The
     * degradation says which start it was, which run is at stake and what the
     * operator has to do about it.
     *
     * What it does not do is fail the start: the run started, the client has
     * to be answered, and a client left without an answer would read the
     * closed connection as a failure of the start.
     */
    private fun persistOutcome(degradation: (String) -> ServiceStartDegradation) {
        try {
            persist()
        } catch (failure: Exception) {
            diagnostics.degraded(degradation(failure.shortDiagnostic()))
        }
    }

    /**
     * Writes the record, atomically and privately.
     *
     * The file is the service's own, next to its socket, and it is `0600`:
     * it says which runs the operator started, and the operator's own
     * processes are the only ones that read it.
     *
     * The CLAIMS are written too, and they are written FIRST: a key that is
     * both claimed and started is a key whose run is known, and the record
     * is the stronger fact of the two.
     */
    private fun persist() {
        val document = buildJsonObject {
            claims.forEach { (key, claim) ->
                put(key, buildJsonObject {
                    put("digest", JsonPrimitive(claim.digest))
                    put("correlationId", JsonPrimitive(claim.correlationId))
                })
            }
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
        store.write(document.toString())
    }

    /**
     * A start that is in flight.
     */
    private data class PendingStart(
        val digest: String,
        val future: CompletableFuture<ServiceStartRecord>,
    )

    /**
     * A start that was CLAIMED and never answered.
     *
     * The fingerprint of the request and the correlation the service gave to
     * the run: enough to tell a retry of the SAME request from a conflict,
     * and enough to FIND the run the service lost.
     */
    private data class ServiceStartClaimRecord(
        val digest: String,
        val correlationId: String,
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
         *
         * The [diagnostics] are told about the degradations this record
         * suffers from now on.
         */
        fun read(
            path: Path,
            diagnostics: ServiceStartDiagnostics = NoServiceStartDiagnostics,
        ): ServiceStartRegistryReading = read(FileServiceStartStore(path), diagnostics)

        /**
         * The record of the starts in [store].
         *
         * A store that holds nothing is an EMPTY record: the service has not
         * started anything yet. A store that IS there and cannot be read is
         * a REFUSAL, because a record that cannot be read is a promise that
         * cannot be kept, and a service that silently forgets its promises
         * would start a second run for a key it already answered.
         */
        fun read(
            store: ServiceStartStore,
            diagnostics: ServiceStartDiagnostics = NoServiceStartDiagnostics,
        ): ServiceStartRegistryReading {
            val registry = ServiceStartRegistry(store, diagnostics)
            val text = try {
                store.read()
            } catch (failure: Exception) {
                return ServiceStartRegistryReading.Refused(
                    "the record of the starts could not be read: ${failure.message}",
                )
            } ?: return ServiceStartRegistryReading.Read(registry)
            if (text.isBlank()) return ServiceStartRegistryReading.Read(registry)
            val parsed = try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (_: Exception) {
                null
            } ?: return ServiceStartRegistryReading.Refused(
                "the record of the starts is not a document: ${store.location}",
            )
            parsed.forEach { (key, value) ->
                val entry = value as? JsonObject
                    ?: return ServiceStartRegistryReading.Refused(
                        "the record of the starts holds something that is not a start: ${store.location}",
                    )
                val digest = entry.string("digest")
                    ?: return ServiceStartRegistryReading.Refused(
                        "the record of the starts holds a start without a fingerprint: ${store.location}",
                    )
                val correlationId = entry.string("correlationId")
                    ?: return ServiceStartRegistryReading.Refused(
                        "the record of the starts holds a start without a correlation: ${store.location}",
                    )
                val runId = entry.string("runId")
                if (runId == null) {
                    // A start with no run is a CLAIM: the service wrote it
                    // before it launched the child, and it never learned
                    // what that child became.
                    registry.claims[key] = ServiceStartClaimRecord(digest, correlationId)
                    return@forEach
                }
                val record = ServiceStartRecord(
                    digest = digest,
                    runId = runId,
                    correlationId = correlationId,
                    ownerId = entry.string("ownerId")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without an owner: ${store.location}",
                        ),
                    startedAtMs = entry.long("startedAtMs")
                        ?: return ServiceStartRegistryReading.Refused(
                            "the record of the starts holds a start without a moment: ${store.location}",
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
