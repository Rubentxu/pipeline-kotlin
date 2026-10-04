package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * B2 — retention POLICY: what may be deleted, and by whose authority.
 *
 * ## What is in this file and what is not
 *
 * The split is the same one the modules make. This file pins the **pure decision** — the policy
 * turning a lifecycle into an intent, or into nothing — and it needs no store, no filesystem and no
 * `@TempDir`, which is what makes it a legitimate part of the published contract module.
 *
 * The rows that actually delete bytes live in `:pipeline-output-store` (`OutputPruneTest`). They
 * were in this file until BLOCK 2 split the module, and keeping them here would have forced the
 * published contract module to depend on its own implementation to compile its tests — which is the
 * coupling the split exists to remove.
 *
 * ## The law these tests defend
 *
 * The store can destroy bytes; it cannot decide that they are destroyable. It never learns whether
 * a run is still running, and the moment it guessed, it would become a second authority on run
 * lifecycle — a fact the runtime already owns and can be wrong about in a different direction.
 *
 * So deletion travels as an [OutputPruneIntent], whose constructors are the only permissions that
 * exist. There is deliberately no `prune(runId, force)`, and no case for a live run. **These tests
 * cannot assert that a running run's output survives, because there is no way to ask for that
 * deletion** — the type system holds the property, and the rows here check the policy that decides
 * which intent the runtime is handed.
 */
class OutputRetentionTest {

    @Test
    fun `RunTerminalPlus keeps a running run and releases a finished one`() {
        assertNull(
            RetainUntil.RunTerminalPlus.authorize("r1", RunLifecycle.StillRunning),
            "a running run's output must never be authorised for deletion",
        )
        val terminal = RetainUntil.RunTerminalPlus.authorize("r1", RunLifecycle.Terminal)
        assertInstanceOf(OutputPruneIntent.RunReachedTerminalState::class.java, terminal, "got $terminal")
        assertEquals("r1", terminal!!.runId)

        // The intent names the run and nothing else. There is deliberately no outcome to assert on
        // here: retention asks "has the run ended?", the engine owns "how did it end?", and a
        // second copy of that answer in a prune intent is how the two would drift apart.
    }

    @Test
    fun `Forever never authorises, not even after the run ends`() {
        // The row that matters: retention holds are enforced by the policy answering `null`, not
        // by a check somewhere downstream that a caller may forget. Forever on a terminal run must
        // still be nothing.
        assertNull(RetainUntil.Forever.authorize("r1", RunLifecycle.StillRunning))
        assertNull(RetainUntil.Forever.authorize("r1", RunLifecycle.Terminal))
    }

    @Test
    fun `ExplicitReleaseOnly never self-authorises and the operator intent is a separate case`() {
        assertNull(RetainUntil.ExplicitReleaseOnly.authorize("r1", RunLifecycle.StillRunning))
        assertNull(RetainUntil.ExplicitReleaseOnly.authorize("r1", RunLifecycle.Terminal))
        // What the operator has instead is an intent they construct themselves, carrying who asked.
        val released = OutputPruneIntent.OperatorReleased("r1", "oncall@example")
        assertEquals("oncall@example", (released as OutputPruneIntent.OperatorReleased).requestedBy)
    }
}
