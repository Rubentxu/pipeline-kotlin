package dev.rubentxu.pipeline.v2.output.store

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The guarantee: a store is told what it may do, and the answer comes from ONE fact.
 *
 * The capacity this serves: the output plane's write/read split. A reader must never reconcile
 * durable state — reconciliation truncates the uncommitted tail of a stream, and an uncommitted tail
 * is exactly what a live writer looks like right now — so a reader that reconciled could destroy a
 * live writer's bytes.
 *
 * That is why the role is a fact and not four flags spread over four call sites: the four
 * predicates below are DERIVED from the role, so a reader cannot be "reconciled but still read-only"
 * and a writer cannot be "reconciled but still refused a write". The table is the whole contract,
 * and these rows are it, one role at a time.
 *
 * These rows are pure: a role in, a table of permissions out. No store, no directory, no clock.
 */
class StoreAuthorityTest {

    @Test
    fun `a store opened without recovery is a reader`() {
        val authority = storeAuthority(recoveryPermitted = false)

        assertInstanceOf(
            StoreAuthority.Reader::class.java,
            authority,
            "a store that may not recover is a reader",
        )
    }

    @Test
    fun `a reader may read and may do nothing else`() {
        val authority = storeAuthority(recoveryPermitted = false)

        assertFalse(authority.mayRecover, "a reader never reconciles")
        assertFalse(authority.mayWrite, "a reader never writes")
        assertTrue(authority.mayRead, "a reader serves reads")
        assertFalse(
            authority.requiresReconciliationForReads,
            "a reader is never refused a read for want of reconciliation",
        )
    }

    @Test
    fun `a reader stays a reader even when it is told it reconciled`() {
        val authority = storeAuthority(recoveryPermitted = false, reconciled = true)

        assertInstanceOf(
            StoreAuthority.Reader::class.java,
            authority,
            "the reconciliation flag does not apply to a reader: it never reconciles, so it never could",
        )
        assertFalse(
            authority.mayWrite,
            "mayWrite is false for a reader BY CONSTRUCTION, not by a flag that could disagree",
        )
    }

    @Test
    fun `a writer that has not reconciled may recover but may not write`() {
        val authority = storeAuthority(recoveryPermitted = true, reconciled = false)

        assertInstanceOf(
            StoreAuthority.Writer::class.java,
            authority,
            "a store that may recover is a writer",
        )
        assertTrue(authority.mayRecover, "a writer may reconcile")
        assertFalse(authority.mayWrite, "a writer that has not reconciled may not write yet")
        assertFalse(authority.mayRead, "and its reads are refused until it has")
        assertTrue(
            authority.requiresReconciliationForReads,
            "a read before the reconciliation is refused, and the refusal is stated here",
        )
    }

    @Test
    fun `a reconciled writer may write and may read`() {
        val authority = storeAuthority(recoveryPermitted = true, reconciled = true)

        assertInstanceOf(
            StoreAuthority.Writer::class.java,
            authority,
            "a store that may recover is a writer",
        )
        assertTrue(authority.mayRecover, "a writer may reconcile")
        assertTrue(authority.mayWrite, "a reconciled writer may write")
        assertTrue(authority.mayRead, "and may read")
        assertFalse(
            authority.requiresReconciliationForReads,
            "there is nothing left to reconcile, so nothing withholds the read",
        )
    }

    @Test
    fun `the writer remembers whether it reconciled`() {
        assertEquals(
            StoreAuthority.Writer(reconciled = false),
            storeAuthority(recoveryPermitted = true),
            "the writer carries its own state, so the predicates are not re-derived per call site",
        )
        assertEquals(
            StoreAuthority.Writer(reconciled = true),
            storeAuthority(recoveryPermitted = true, reconciled = true),
            "and it carries it truthfully",
        )
    }
}
