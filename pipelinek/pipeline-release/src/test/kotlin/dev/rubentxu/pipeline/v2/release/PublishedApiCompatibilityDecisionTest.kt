package dev.rubentxu.pipeline.v2.release

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * What the comparison between two published surfaces says.
 *
 * The rule under test is the one the publication gate acts on: only a
 * REMOVAL is a break. An addition is compatible by definition, because a
 * consumer compiled against the previous artifact cannot be broken by
 * something that did not exist in it — and a gate that treated an addition as
 * a break would refuse every release that adds anything.
 *
 * Pure: two rendered surfaces in, one comparison out. No artifact, no build.
 */
class PublishedApiCompatibilityDecisionTest {

    private fun surface(rendered: String): PublishedApiSurface = PublishedApiSurfaceReader.read(rendered.trimIndent())

    private val previous = surface(
        """
        public final class dev/rubentxu/pipeline/v2/domain/Kept {
        	public fun toString ()Ljava/lang/String;
        	public fun <init> (Ljava/lang/String;)V
        }

        public final class dev/rubentxu/pipeline/v2/domain/Removed {
        	public fun toString ()Ljava/lang/String;
        }
        """,
    )

    private val candidate = surface(
        """
        public final class dev/rubentxu/pipeline/v2/domain/Kept {
        	public fun toString ()Ljava/lang/String;
        }

        public final class dev/rubentxu/pipeline/v2/domain/Added {
        	public fun toString ()Ljava/lang/String;
        }
        """,
    )

    // --- what is a break, and what is not ----------------------------------------------

    @Test
    fun `a declaration the candidate no longer publishes is a break`() {
        val comparison = PublishedApiCompatibility.compare(previous, candidate)

        assertEquals(
            listOf("dev/rubentxu/pipeline/v2/domain/Removed"),
            comparison.removedDeclarations,
        )
    }

    @Test
    fun `a member the candidate no longer publishes with the same signature is a break`() {
        // The constructor of `Kept` is gone: the candidate publishes `Kept`
        // with one member fewer. The consumer that linked that constructor
        // would not find it.
        val comparison = PublishedApiCompatibility.compare(previous, candidate)

        assertEquals(1, comparison.removedMembers.size)
        val break_ = comparison.removedMembers.single()
        assertEquals("dev/rubentxu/pipeline/v2/domain/Kept", break_.declaration)
        assertEquals("<init>", break_.member)
        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/Kept#<init>(Ljava/lang/String;)V",
            break_.removalKey,
        )
    }

    @Test
    fun `an addition is not a break`() {
        // `Added` is new. A gate that called it a break would refuse every
        // release that adds anything, and would train its readers to ignore
        // the report.
        val comparison = PublishedApiCompatibility.compare(previous, candidate)

        assertEquals(
            listOf("dev/rubentxu/pipeline/v2/domain/Added"),
            comparison.addedDeclarations,
        )
        assertTrue(
            comparison.breaks.none { it.removalKey == "dev/rubentxu/pipeline/v2/domain/Added" },
            "an addition was reported as a break",
        )
    }

    @Test
    fun `a member whose signature changed is a break named by the OLD signature`() {
        // The candidate publishes `toString` with a different return type. The
        // old line is gone, and the old line is what the consumer linked, so
        // the break is named by the old line — not by the new one, which a
        // consumer of the previous artifact never saw.
        val changed = surface(
            """
            public final class dev/rubentxu/pipeline/v2/domain/Kept {
            	public fun toString ()Ljava/lang/Object;
            	public fun <init> (Ljava/lang/String;)V
            }

            public final class dev/rubentxu/pipeline/v2/domain/Removed {
            	public fun toString ()Ljava/lang/String;
            }
            """,
        )

        val comparison = PublishedApiCompatibility.compare(previous, changed)

        val break_ = comparison.removedMembers.single { it.member == "toString" }
        assertEquals(
            "dev/rubentxu/pipeline/v2/domain/Kept#toString()Ljava/lang/String;",
            break_.removalKey,
        )
    }

    @Test
    fun `a declaration that survives keeps its surviving members out of the breaks`() {
        val comparison = PublishedApiCompatibility.compare(previous, candidate)

        assertTrue(
            comparison.removedMembers.none { it.declaration == "dev/rubentxu/pipeline/v2/domain/Removed" },
            "the members of a removed declaration were reported separately from it",
        )
    }

    // --- the decision the gate acts on ---------------------------------------------------

    @Test
    fun `a break with no approval stops the publication`() {
        // The guarantee this whole gate exists for. An empty ledger approves
        // nothing, so any break is unauthorized.
        val verdict = PublishedApiCompatibility.decide(
            module = "pipeline-domain",
            previousVersion = "0.90.0",
            previous = previous,
            candidate = candidate,
            ledger = PublishedContractLedger(emptyList()),
        )

        assertTrue(
            verdict is PublishedApiModuleVerdict.UnauthorizedBreaks,
            "an unapproved break produced $verdict",
        )
        val unauthorized = (verdict as PublishedApiModuleVerdict.UnauthorizedBreaks).unauthorized
        assertEquals(2, unauthorized.size, "the verdict did not name every unauthorized break")
    }

    @Test
    fun `a candidate that publishes everything the previous release published is compatible`() {
        val verdict = PublishedApiCompatibility.decide(
            module = "pipeline-domain",
            previousVersion = "0.90.0",
            previous = previous,
            candidate = previous,
            ledger = PublishedContractLedger(emptyList()),
        )

        assertTrue(verdict is PublishedApiModuleVerdict.Compatible, "got $verdict")
    }

    @Test
    fun `a candidate that only adds is compatible`() {
        val verdict = PublishedApiCompatibility.decide(
            module = "pipeline-domain",
            previousVersion = "0.90.0",
            previous = surface(
                """
                public final class dev/rubentxu/pipeline/v2/domain/Kept {
                	public fun toString ()Ljava/lang/String;
                }
                """,
            ),
            candidate = surface(
                """
                public final class dev/rubentxu/pipeline/v2/domain/Kept {
                	public fun toString ()Ljava/lang/String;
                }

                public final class dev/rubentxu/pipeline/v2/domain/Added {
                	public fun toString ()Ljava/lang/String;
                }
                """,
            ),
            ledger = PublishedContractLedger(emptyList()),
        )

        assertTrue(verdict is PublishedApiModuleVerdict.Compatible, "got $verdict")
    }
}
