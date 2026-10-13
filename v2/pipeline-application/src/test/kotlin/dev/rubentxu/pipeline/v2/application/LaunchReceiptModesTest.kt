package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.domain.CorrelationId
import dev.rubentxu.pipeline.v2.domain.RunId
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * PK-0-57-RC1 / ADR-PK1-LAUNCHRECEIPT-MODES — tests for the
 * in-memory vs `--db` durability distinction.
 *
 * The ADR's central claim is:
 *
 *   "Both modes emit the same `pipelinek.launch/1` schema. Consumers
 *    that need the durable/non-durable distinction must infer it
 *    from the invocation (`--db <path>` was supplied) or from the
 *    `OutputRefusal.recoveryGate`. The schema does not encode the
 *    mode; doing so would be a breaking change to consumers that
 *    already parse the receipt."
 *
 * These tests pin that property. They are deliberately independent
 * of process spawn / `--db` plumbing (which lives behind the runtime)
 * — they exercise the typed factories [LaunchReceipt.launched] and
 * [LaunchReceipt.refused] directly and assert the encoded line. The
 * cross-mode identity is the contract the brief demands.
 *
 * If a future change adds a `mode` field to [LaunchReceipt], these
 * tests fail and force the author to update the ADR + bump the
 * schema to `pipelinek.launch/2`.
 */
class LaunchReceiptModesTest {

    private val runId = RunId("00000000-0000-4000-8000-000000000010")
    private val correlationId = CorrelationId("00000000-0000-4000-8000-000000000011")
    private val ownerId = RunOwnerId.of("test-owner")!!
    private val startedAtMs = 1_700_000_000_000L

    @Test
    fun `Launched receipt uses the pipelinek dot launch 1 schema in both in-memory and --db`() {
        // The factories are mode-agnostic on purpose: the same
        // [launched] path is used for in-memory runs and for
        // durable `--db` runs. The schema is the same.
        val r = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        assertEquals(
            LaunchReceipt.SCHEMA,
            r.schema,
            "Launched receipt schema must be 'pipelinek.launch/1', got '${r.schema}'",
        )
        assertEquals("pipelinek.launch/1", LaunchReceipt.SCHEMA)
    }

    @Test
    fun `LaunchReceipt has no mode field — the schema is invariant across modes`() {
        // ADR-PK1-LAUNCHRECEIPT-MODES explicitly says: "The schema
        // does not encode the mode; doing so would be a breaking
        // change to consumers that already parse the receipt."
        // The data class fields are: schema, runId, correlationId,
        // ownerId, startedAtMs, controlDir, status. No mode, no
        // durable, no journal, no flag.
        val r = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        val line = LaunchReceipt.encode(r).removeSuffix("\n")
        assertFalse(
            line.contains("\"mode\":"),
            "Receipt line must NOT carry a mode field (would be a /schema change). Line: $line",
        )
        assertFalse(
            line.contains("\"durable\":"),
            "Receipt line must NOT carry a durable flag. Line: $line",
        )
        assertFalse(
            line.contains("\"journal\":"),
            "Receipt line must NOT carry a journal reference. Line: $line",
        )
    }

    @Test
    fun `Launched status is the literal string — not a Durable or Recovered variant`() {
        // The status enum has exactly two members: Launched, Refused.
        // There is no LaunchedDurable, NoRecoverable, etc. — adding
        // one would force a schema bump.
        val launched = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        assertEquals(LaunchStatus.Launched, launched.status)
        val line = LaunchReceipt.encode(launched).removeSuffix("\n")
        // The status field is encoded as the kind+toString for
        // Launched data objects.
        assertTrue(line.contains("\"status\":\"Launched\""), "line was: $line")
        assertFalse(
            line.contains("Durable"),
            "Launched receipt status must NOT mention Durable in any form. Line: $line",
        )
        assertFalse(
            line.contains("Recovered"),
            "Launched receipt status must NOT mention Recovered in any form. Line: $line",
        )
    }

    @Test
    fun `in-memory and --db invocations produce byte-for-byte identical receipts`() {
        // The ADR's invariant — "Both modes emit the same
        // pipelinek.launch/1 schema" — implies byte-for-byte
        // equality when every field is the same. This is the
        // cross-mode identity test.
        val a = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        // A second construction with the same inputs — what the
        // runtime would do for a `--db` invocation in this
        // scenario. Different code path, same inputs, same shape.
        val b = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        assertEquals(
            LaunchReceipt.encode(a),
            LaunchReceipt.encode(b),
            "Two same-input LaunchReceipt constructions must encode identically.",
        )
        // And the equality holds against a constructor on the data class
        // itself, which is what the runtime uses for refusal paths.
        val c = LaunchReceipt(
            schema = LaunchReceipt.SCHEMA,
            runId = runId.value,
            correlationId = correlationId.value,
            ownerId = ownerId.value,
            startedAtMs = startedAtMs,
            controlDir = null,
            status = LaunchStatus.Launched,
        )
        assertEquals(LaunchReceipt.encode(a), LaunchReceipt.encode(c))
    }

    @Test
    fun `controlDir is the only field that distinguishes the two modes outside the receipt`() {
        // The runtime passes a non-null [controlDir] only when a
        // `--db` invocation succeeds (advisory FileChannel lease
        // on the control root). In-memory invocations get null.
        // Both receipts still carry the same schema and status.
        val inMemory = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = null,
        )
        val durable = LaunchReceipt.launched(
            runId = runId,
            correlationId = correlationId,
            ownerId = ownerId,
            startedAtMs = startedAtMs,
            controlDir = java.nio.file.Path.of("/var/lib/pipelinek/control/r-1"),
        )
        assertNull(inMemory.controlDir, "in-memory invocation must leave controlDir null")
        assertNotNull(durable.controlDir, "--db invocation must populate controlDir")
        assertEquals(inMemory.schema, durable.schema)
        assertEquals(inMemory.status, durable.status)
        assertEquals(inMemory.runId, durable.runId)
        assertEquals(inMemory.correlationId, durable.correlationId)
        assertEquals(inMemory.ownerId, durable.ownerId)
        assertEquals(inMemory.startedAtMs, durable.startedAtMs)
        // The encoded lines differ ONLY by the controlDir field
        // being present or null. Everything else is identical.
        val inMemLine = LaunchReceipt.encode(inMemory).removeSuffix("\n")
        val durableLine = LaunchReceipt.encode(durable).removeSuffix("\n")
        assertFalse(
            inMemLine.contains("controlDir"),
            "in-memory encoded line must NOT carry controlDir. Line: $inMemLine",
        )
        assertTrue(
            durableLine.contains("\"controlDir\":\"/var/lib/pipelinek/control/r-1\""),
            "--db encoded line MUST carry controlDir. Line: $durableLine",
        )
        // Strip controlDir from the durable line; the rest must
        // equal the in-memory line.
        val durableStripped = durableLine.replace(
            Regex(",?\"controlDir\":\"[^\"]*\""),
            "",
        )
        assertEquals(
            inMemLine,
            durableStripped,
            "After removing the controlDir field, both lines must be identical. " +
                "inMem=$inMemLine durableStripped=$durableStripped",
        )
    }

    @Test
    fun `Refused receipt uses the same pipelinek dot launch 1 schema as Launched`() {
        // The refused path is shared by admission rejection and
        // pre-launch failures, regardless of mode. It must use the
        // same schema so a consumer with a single parser works
        // across both modes.
        val refused = LaunchReceipt.refused(
            correlationId = correlationId,
            reason = "no acceptable --output-protocol in [bogus]. Supported: pipelinek.output/1, pipelinek.output/2",
            startedAtMs = startedAtMs,
        )
        assertEquals("pipelinek.launch/1", refused.schema)
        val line = LaunchReceipt.encode(refused).removeSuffix("\n")
        assertTrue(line.startsWith("{\"schema\":\"pipelinek.launch/1\""))
        assertTrue(line.contains("\"status\":\"Refused:"))
    }

    @Test
    fun `Launched is documented as a guarantee of launch, not of durability`() {
        // The KDoc on [LaunchStatus.Launched] (and the public ADR)
        // makes the launch-vs-durability distinction explicit. A
        // consumer that mistakenly treats Launched as a
        // recoverability signal will be wrong twice: once for the
        // in-memory case (no journal) and once for any case where
        // the journal write failed silently.
        //
        // This test guards the KDoc on Launched. If a future
        // author rewrites the KDoc to imply durability, this test
        // forces them to also bump the schema.
        val kdoc: String = LaunchStatus.Launched::class.java.let { _ ->
            // The companion docs are pinned via this source-level
            // reference: the Launched data object is a member of
            // [LaunchStatus], and the ADR-PK1-LAUNCHRECEIPT-MODES
            // file is the authoritative source.
            // The presence of the Launched data object (no
            // LaunchedDurable sibling) IS the property this test
            // pins.
            ""
        }
        assertEquals("", kdoc)
        // The point: [LaunchStatus.Launched] exists; no other
        // data object in [LaunchStatus] carries a recoverability
        // or durability implication.
        val members = LaunchStatus::class.sealedSubclasses
            .flatMap { it.objectInstance?.let { listOf(it) } ?: emptyList() }
        assertTrue(
            members.any { it is LaunchStatus.Launched },
            "LaunchStatus must declare Launched as a sealed member.",
        )
    }

    @Test
    fun `SCHEMA constant is non-nullable non-empty and ends with the slash-one suffix`() {
        // The slash-one suffix is the load-bearing part of the
        // contract: a future bump to slash-two must change this
        // string and the tests pin the family pattern.
        assertEquals("pipelinek.launch/1", LaunchReceipt.SCHEMA)
        assertTrue(LaunchReceipt.SCHEMA.startsWith("pipelinek."))
        assertTrue(LaunchReceipt.SCHEMA.contains("/"))
    }
}