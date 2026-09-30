package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path

/**
 * WU-RP-020 fitness: the event sequence has exactly ONE authority.
 *
 * `SqliteEventStore.appendAssigned` assigns the durable per-run sequence when,
 * and only when, the incoming sequence is `0`. An emitter that passes anything
 * else bypasses that authority. With `UNIQUE(run_id, sequence)` on the table
 * such an emitter is not merely untidy: it either corrupts the log or fails the
 * run outright.
 *
 * Two real violations existed and were fixed in WU-RP-020:
 *  - `WithCredentialsExecutor`: `var sequence = 1L`, a local counter that
 *    restarted at 1 on every bind and every teardown, so every
 *    `CredentialBound` claimed sequence 1 of its run.
 *  - `GitCheckoutExecutor`: `sequence = req.stepIndex.toLong()`, a step ORDINAL
 *    used as a run sequence.
 *
 * Both were found by a one-off grep. This test is the standing guard so a
 * fourteenth site cannot be added unnoticed.
 */
class FArchSequenceAuthorityFitnessTest {

    @Test
    fun `happy path — no production emitter assigns its own event sequence`() {
        val findings = ScannerSupport.findExplicitSequenceAssignment(ScannerSupport.v2Root())
        assertTrue(
            findings.isEmpty(),
            """
            Production code must not assign an event sequence itself. The store is
            the only authority: pass `sequence = 0L` and let appendAssigned assign.

            Offenders: ${findings.joinToString("\n") { "${it.file}:${it.line}  ${it.excerpt}" }}
            """.trimIndent(),
        )
    }

    @Nested
    inner class ViolationFixture {
        @TempDir
        lateinit var tempDir: Path

        // The scanner reads only `/src/main/`, so fixtures must live there.
        private val mainDir: Path
            get() = tempDir.resolve("src/main/kotlin").also { it.toFile().mkdirs() }

        private fun write(name: String, body: String) =
            mainDir.resolve(name).toFile().writeText(body)

        @Test
        fun `scanner flags a local counter restarting at one`() {
            write("Bad1.kt", "class E { fun go() { var sequence = 1L; emit(sequence = sequence++) } }")
            val findings = ScannerSupport.findExplicitSequenceAssignment(tempDir)
            assertTrue(findings.isNotEmpty(), "a local sequence++ emitter must be flagged")
        }

        @Test
        fun `scanner flags a step ordinal used as a run sequence`() {
            write("Bad2.kt", "class E { fun go(r: Req) { emit(sequence = r.stepIndex.toLong()) } }")
            val findings = ScannerSupport.findExplicitSequenceAssignment(tempDir)
            assertTrue(findings.isNotEmpty(), "an explicit non-zero sequence must be flagged")
        }

        @Test
        fun `scanner flags a hardcoded one`() {
            write("Bad3.kt", "class E { fun go() { emit(sequence = 1L) } }")
            val findings = ScannerSupport.findExplicitSequenceAssignment(tempDir)
            assertTrue(findings.isNotEmpty(), "sequence = 1L must be flagged")
        }

        @Test
        fun `scanner allows a decode that reads the persisted sequence field`() {
            // Real shape from JsonEventLog.decodeEvent.
            write(
                "GoodDecode.kt",
                """
                class Log {
                    private fun decode(s: String): Any? {
                        val sequence = EventJsonFields.longField(s, "sequence") ?: return null
                        return sequence
                    }
                }
                """.trimIndent(),
            )
            val findings = ScannerSupport.findExplicitSequenceAssignment(tempDir)
            assertTrue(
                findings.isEmpty(),
                "reading the sequence back out of persisted data is a decode, not an emit: $findings",
            )
        }

        @Test
        fun `scanner allows the correct forms`() {
            write(
                "Good.kt",
                """
                class E {
                    fun go() {
                        emit(sequence = 0L)                      // ask the store
                        copy(sequence = assignedSequence)         // store projection
                        decode(sequence = sequence)               // read model
                        // sequence = 99L                          // comment, ignored
                    }
                }
                """.trimIndent(),
            )
            val findings = ScannerSupport.findExplicitSequenceAssignment(tempDir)
            assertTrue(
                findings.isEmpty(),
                "0L, the store projection and a decode pass-through are not violations: $findings",
            )
        }
    }
}
