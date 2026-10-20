package dev.rubentxu.pipeline.v2.output

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the product is allowed to say about its own output, and what it is not.
 * [OutputAdoption] is the checkable form of the ADR that chose segment reservation with recovery
 * Over three other candidates. Its answer decides whether " is implemented" is a claim or a
 * hope, so the two functions here are guards rather than documentation -- and both have already
 * been wrong once, in ways the compiler could not see. Each of those two defects gets its own test
 * below, named after the mistake rather than after the method.
 * Nothing here touches a filesystem: the whole contract is set algebra over two enums, which is why
 * a unit test is the right instrument and why this file can exist at all.
*/
class OutputAdoptionTest {

    private val all: Set<OutputAdoptionObligation> = OutputAdoptionObligation.entries.toSet()

    // --- mayClaimCrashConsistency: a conjunction, so any single gap disqualifies ---------

    @Test
    fun `a full discharge permits the claim`() {
        assertTrue(OutputAdoption.mayClaimCrashConsistency(all))
    }

    @Test
    fun `each obligation alone leaves the claim forbidden`() {
        // Pinned one at a time rather than as a loop over the complement, so a failure names the
        // obligation that stopped mattering. All three are conjunctive terms: dropping any one of
        // them has to be enough, and this is where that stops being true.
        for (missing in OutputAdoptionObligation.entries) {
            val partial = all - missing
            assertFalse(
                OutputAdoption.mayClaimCrashConsistency(partial),
                "claiming crash consistency without $missing was allowed",
            )
        }
    }

    @Test
    fun `nothing discharged forbids the claim`() {
        assertFalse(OutputAdoption.mayClaimCrashConsistency(emptySet()))
    }

    // --- invariantsBlockedBy takes the DISCHARGED set and complements it inside -----------

    @Test
    fun `a fully discharged adoption blocks nothing`() {
        assertEquals(emptyList<OutputCrashInvariant>(), OutputAdoption.invariantsBlockedBy(all))
    }

    @Test
    fun `the parameter is the discharged set, so an undischarged writer blocks everything`() {
        // THE INVERSION DEFECT. This function once took a parameter named `undischarged`, and the
        // test that exercised it passed the *discharged* set: it type-checked, passed, and
        // reported the exact inverse of the truth -- a run with every obligation discharged was
        // certified as blocked by all six invariants. A name the caller can confuse with its
        // negation is a defect the compiler will not catch, so the only defence is a test that
        // Says which way round the argument goes. Handing it a set missing only must report
        // everything blocked; the inverted version reports nothing.
        val withoutWriter = all - OutputAdoptionObligation.WRITER_RESERVES_BEFORE_APPEND

        assertEquals(OutputAdoption.invariants, OutputAdoption.invariantsBlockedBy(withoutWriter))
    }

    // --- the union, not the first match ------------------------------------------------

    @Test
    fun `two independent gaps block the invariants of both`() {
        // THE SHORT-CIRCUIT DEFECT. This function once returned on the first match it found, so a
        // pipeline that reserved correctly but had neither a committed-offset reader nor a recovery
        // Entry point reported only blocked and quietly vouched for and -- precisely the
        // two that recovery underpins. The failure mode is silent: the returned list is a real
        // list of real invariants, just too short, so nothing downstream can tell it from a
        // deliberate answer.
        // Is DISCHARGED here on purpose. If it were not, the first branch would addAll all six
        // and mask whether the later branches run at all -- which is why the earlier version of
        // this test was unable to distinguish the union from the short circuit.
        val onlyWriterReserved = setOf(OutputAdoptionObligation.WRITER_RESERVES_BEFORE_APPEND)

        assertEquals(
            listOf(
                OutputCrashInvariant.I3_ORDER_IS_DENSE,
                OutputCrashInvariant.I5_NO_DUPLICATE_ON_RECOVERY,
                OutputCrashInvariant.I6_NO_AMBIGUOUS_SLOT,
            ),
            OutputAdoption.invariantsBlockedBy(onlyWriterReserved),
        )
    }

    @Test
    fun `a missing reader blocks only the density invariant`() {
        // Alone. is the invariant that stops being meaningful without a committed offset to
        // Be dense about; and belong to recovery, which is a separate entry point
        val withoutReader = all - OutputAdoptionObligation.READER_RESUMES_FROM_COMMITTED

        assertEquals(
            listOf(OutputCrashInvariant.I3_ORDER_IS_DENSE),
            OutputAdoption.invariantsBlockedBy(withoutReader),
        )
    }

    @Test
    fun `a missing recovery entry point blocks the two recovery invariants`() {
        val withoutRecovery = all - OutputAdoptionObligation.RECOVERY_IS_AN_ENTRY_POINT

        assertEquals(
            listOf(
                OutputCrashInvariant.I5_NO_DUPLICATE_ON_RECOVERY,
                OutputCrashInvariant.I6_NO_AMBIGUOUS_SLOT,
            ),
            OutputAdoption.invariantsBlockedBy(withoutRecovery),
        )
    }

    @Test
    fun `an empty discharge blocks everything in declaration order`() {
        // Order is part of the answer, not an accident of a Set's iteration: a caller that renders
        // This list to an operator has to see.. in the order the enum states them
        assertEquals(
            listOf(
                OutputCrashInvariant.I1_ACKNOWLEDGED_SURVIVES,
                OutputCrashInvariant.I2_NO_UNACKNOWLEDGED_APPEARS,
                OutputCrashInvariant.I3_ORDER_IS_DENSE,
                OutputCrashInvariant.I4_NO_DANGLING_COMMIT,
                OutputCrashInvariant.I5_NO_DUPLICATE_ON_RECOVERY,
                OutputCrashInvariant.I6_NO_AMBIGUOUS_SLOT,
            ),
            OutputAdoption.invariantsBlockedBy(emptySet()),
        )
    }

    // --- the published lists are derived, so a new constant cannot be dropped ------------

    @Test
    fun `a newly declared obligation is automatically part of the claim`() {
        // The lists are built from `entries`, so adding a case cannot silently leave it out of the
        // conjunction the way a hand-maintained list would. Spelled as an identity over `entries`
        // rather than a count: a count would still pass if one constant were dropped and another
        // added.
        assertEquals(OutputAdoptionObligation.entries.toList(), OutputAdoption.obligations)
        assertEquals(OutputCrashInvariant.entries.toList(), OutputAdoption.invariants)
        assertEquals(OutputNotEstablished.entries.toList(), OutputAdoption.notEstablished)
    }

    @Test
    fun `invariant identifiers are the ones the ADR uses`() {
        // Are the identifiers cited by and by every conformance artefact written
        // against it. A rename here is a wire-visible change in the vocabulary operators read,
        // so it is pinned rather than left to a rename refactor.
        assertEquals(
            listOf("I1", "I2", "I3", "I4", "I5", "I6"),
            OutputAdoption.invariants.map { it.id },
        )
    }

    // --- what this adoption does NOT establish ----------------------------------------

    @Test
    fun `power loss durability is named in the refusal constant`() {
        // The one sentence that has to survive into every conformance artefact. If it is ever
        // softened to "durability", the whole file's value is gone, and nothing at runtime would
        // notice -- which is exactly why it is asserted rather than trusted.
        val refusal = OutputAdoption.POWER_LOSS_NOT_CLAIMED

        assertTrue(refusal.contains("power loss"), "the refusal no longer names what is not claimed: $refusal")
        assertTrue(refusal.contains("NOT claimed"), "the refusal no longer refuses: $refusal")
        assertTrue(refusal.contains("fsync"), "the refusal no longer says what would be required: $refusal")
    }

    @Test
    fun `the unestablished properties each say what would be needed`() {
        for (property in OutputAdoption.notEstablished) {
            assertTrue(
                property.notEstablished.isNotBlank(),
                "${property.name} states no unestablished property",
            )
            assertTrue(
                property.whatItWouldTake.isNotBlank(),
                "${property.name} does not say what establishing it would take",
            )
        }
    }
}
