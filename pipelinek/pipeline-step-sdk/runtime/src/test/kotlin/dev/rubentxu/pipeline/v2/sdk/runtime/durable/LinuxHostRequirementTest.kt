package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertDoesNotThrow
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The rule that says which hosts may run a durable shell is a decision, and a decision about the
 * platform is one that can be wrong without anybody noticing, because it only fires where it is
 * wrong.
 *
 * **The guarantee.** A host that is not Linux is refused before any child is created, with the
 * refusal that names Linux; and a host that is Linux — whatever case it spells its name in — is
 * not refused. The requirement fails CLOSED: the question is never left open because the platform
 * could not be read.
 *
 * **The observable capacity it serves.** An operator on a host the durable shell does not support
 * is told that the durable shell requires Linux, instead of getting a failure that looks like the
 * product's own defect: a child that never started, a control directory with nothing in it, or a
 * step that ends without a reason.
 *
 * **Why this instrument and not another.** The rule is a function of one fact — the platform's
 * name — so the cheapest instrument that can fail is a call to it. The alternative was a run on a
 * host that is not Linux, which is not available on the machine that runs the gate; and a test
 * that reads the source to find the check would freeze the shape of the code instead of the
 * behaviour. Naming the platform as an argument is what makes the rule callable at all, and the
 * default keeps the launch's own call exactly as it was.
 *
 * **What this does not freeze.** That the launch consults this rule, and that no other path
 * creates a child on a host that is not Linux: those are observed where the child is created, not
 * here. What is checked here is the rule's own verdict, which is the part that can be wrong while
 * every observation succeeds.
 */
class LinuxHostRequirementTest {

    @Test
    fun `a host that is not Linux is refused`() {
        // The refusal is the platform's, and it is raised before anything else happens: the point
        // of the requirement is that the product does not half-run on a host it cannot serve.
        val refusal = assertThrows(
            LinuxRequiredException::class.java,
            { requireLinuxHost("Mac OS X") },
            "a host that is not Linux was not refused",
        )

        assertTrue(
            refusal.message.orEmpty().contains("Linux"),
            "the refusal does not name the platform it requires: ${refusal.message}",
        )
    }

    @Test
    fun `a Linux host is not refused`() {
        assertDoesNotThrow(
            { requireLinuxHost("Linux") },
            "a Linux host was refused",
        )
    }

    @Test
    fun `a host that spells Linux in another case is not refused`() {
        // The rule judges the platform's name, not the spelling of the name: a host that says
        // LINUX is a Linux host, and refusing it would refuse the product on its own platform.
        assertDoesNotThrow(
            { requireLinuxHost("LINUX") },
            "a Linux host that spells its name in capitals was refused",
        )
    }
}
