package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRef
import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import dev.rubentxu.pipeline.v2.events.SqliteEventStore
import dev.rubentxu.pipeline.v2.events.identity.EnvelopeCodec
import dev.rubentxu.pipeline.v2.events.identity.EventCursor
import dev.rubentxu.pipeline.v2.events.identity.EventHistoryReader
import dev.rubentxu.pipeline.v2.events.identity.EventQuery

/**
 * EVT-2 minimal CLI for structured local history inspection:
 *
 *   pipeline events --db <path> <runId> [--kind K] [--subject-kind KIND:ID...] [--limit N] [--after-cursor TOKEN]
 *
 * Reads history through the EventHistory port (never parses stdout, never
 * touches the console transcript). Output = one JSON envelope per line.
 * History != stdout rule: this command's stdout is the CLI's query output;
 * no process streams are involved.
 */
object MainEventsCli {

    fun main(args: Array<String>): Int {
        var db: String? = null
        var runId: String? = null
        var kind: String? = null
        var subjectCanonical: String? = null
        var limit = 100
        var afterCursor: String? = null

        var i = 0
        while (i < args.size) {
            when (args[i]) {
                "--db" -> db = args.getOrNull(++i)
                "--kind" -> kind = args.getOrNull(++i)
                "--subject" -> subjectCanonical = args.getOrNull(++i)
                "--limit" -> limit = args.getOrNull(++i)?.toIntOrNull() ?: 100
                "--after-cursor" -> afterCursor = args.getOrNull(++i)
                else -> if (!args[i].startsWith("--") && runId == null) runId = args[i]
            }
            i++
        }

        if (db == null || runId == null) {
            System.err.println("Usage: pipeline events --db <path> <runId> [--kind K] [--subject v1:run:ID|v1:stage:ID:N|...] [--limit N] [--after-cursor TOKEN]")
            return 2
        }

        if (!java.nio.file.Files.exists(java.nio.file.Path.of(db))) {
            System.err.println("Error: db not found: $db")
            return 2
        }

        val cursor = afterCursor?.let {
            EventCursor.decode(it) ?: throw IllegalArgumentException("invalid cursor token: $it")
        }

        val store = SqliteEventStore(db)
        try {
            val reader = EventHistoryReader(store)
            val run = ResourceRefs.run(runId)
            val query: EventQuery = when {
                kind != null -> EventQuery.ByKind(kind)
                subjectCanonical != null ->
                    EventQuery.BySubject(
                        decodeSubject(subjectCanonical)
                            ?: throw IllegalArgumentException("cannot parse subject ref: $subjectCanonical"),
                    )
                else -> EventQuery.All
            }

            val envelopes = reader.history(run, query)
                .filter { cursor == null || it.sequence > cursor.lastSequence }
                .take(limit)
                .toList()

            envelopes.forEach { println(EnvelopeCodec.encode(it)) }
            if (cursor != null || envelopes.isNotEmpty()) {
                val last = envelopes.lastOrNull()?.sequence ?: cursor?.lastSequence ?: 0L
                System.err.println("evt-cursor-v1:$runId:$last")
            }
            return 0
        } finally {
            store.close()
        }
    }

    /**
     * Parses a canonical ResourceRef text (`v1:kind:seg0:seg1...`) back to a
     * ResourceRef by re-joining segments after the version/kind prefix.
     */
    private fun decodeSubject(text: String): ResourceRef? {
        val colonParts = text.split(":", limit = 3)
        if (colonParts.size < 3 || colonParts[0] != "v1") return null
        return try {
            val kind = dev.rubentxu.pipeline.v2.domain.identity.ResourceKind.valueOf(colonParts[1].uppercase())
            // Accept the full canonical text only (copied from an envelope):
            // "v1:<kind>:<seg>/<seg>/..." must reconstruct the identical
            // canonical text — no shorthand inference.
            ResourceRef(kind, colonParts[2].split("/"))
        } catch (_: Exception) {
            null
        }
    }
}
