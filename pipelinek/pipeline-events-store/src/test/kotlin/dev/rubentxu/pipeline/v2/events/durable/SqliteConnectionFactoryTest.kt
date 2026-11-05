package dev.rubentxu.pipeline.v2.events.durable

import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The factory refuses a database it cannot put in WAL mode, with the class it promises.
 *
 * ## The guarantee
 *
 * A database whose journal mode is not `wal` is refused with an
 * [IllegalStateException], and the refusal names the mode it found. The CLASS is
 * the contract: the KDoc of [SqliteConnectionFactory.open] names it, and the
 * admission layer of the store says in its own words that this refusal is a
 * programmer/invariant defect that must escape rather than be converted into an
 * operational answer about a path.
 *
 * The refusal is a state and not an invalid argument. The path the caller named
 * is not what failed; the mode the engine reported is. A consumer that caught
 * [IllegalArgumentException] because the wording suggested an invalid argument
 * would miss this one entirely, and would go on believing that the store is
 * fine.
 *
 * ## The capacity this serves
 *
 * The store that writes an operation journal depends on WAL for its durability.
 * A database that silently fell back to another journal mode would keep working
 * and would stop being durable, and the operator would find out when the
 * journal was lost. The refusal is what turns that silent fallback into a fact
 * somebody can read.
 *
 * ## Why this instrument
 *
 * The factory is called in process and the refusal is read off the throw. The
 * database is in memory, so nothing is written to the filesystem and nothing is
 * persisted: SQLite reports `memory` for an in-memory database and cannot put
 * one in WAL mode, and that is exactly the refusal this file witnesses.
 *
 * There is no deeper lane here. The guarantee is about the class the factory
 * raises, and the factory is the whole of the subject: an integration lane
 * would add a file on the disk and prove nothing this row does not.
 *
 * ## What this does not freeze
 *
 * The wording of the refusal is not frozen beyond the mode it names, and the
 * mode is the engine's own word and not this product's. What is frozen is the
 * class of the refusal and the fact that the refusal happens at all.
 */
class SqliteConnectionFactoryTest {

    @Test
    fun `a database that cannot be in WAL mode is refused with the class the contract names`() {
        val refusal = assertThrows(IllegalStateException::class.java) {
            SqliteConnectionFactory.open(":memory:")
        }

        // The refusal names the mode the engine reported, so whoever reads it
        // can see what the database is actually doing.
        assertTrue(
            refusal.message?.contains("memory") == true,
            "the refusal does not name the mode it found: ${refusal.message}",
        )
    }
}
