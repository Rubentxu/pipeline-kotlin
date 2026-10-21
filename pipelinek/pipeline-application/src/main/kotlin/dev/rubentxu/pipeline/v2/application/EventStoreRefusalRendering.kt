package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.EventStoreRefusal

/**
 * The one operator-facing rendering of an event-store refusal.
 * The text lives here rather than at each verb because it is a public contract: operators and
 * scripts grep it, and `events` has printed `db not found` since it first read a run. Rendering it
 * in five places is how the five places drift apart.
 * The exit code is deliberately not in here. Whether an unusable store is a usage error (`2`) or a
 * failed run (`1`) is a property of the verb's contract, and collapsing it into this function would
 * make one verb's meaning the default for all the others.
*/
internal fun EventStoreRefusal.render(): String = when (this) {
    is EventStoreRefusal.Absent -> "Error: db not found: $path"
    is EventStoreRefusal.NoDirectory -> "Error: no directory for db: $path"
    is EventStoreRefusal.NotReadable -> "Error: db is not readable: $path ($detail)"
    is EventStoreRefusal.NoEventHistory -> "Error: db has no event history: $path"
}