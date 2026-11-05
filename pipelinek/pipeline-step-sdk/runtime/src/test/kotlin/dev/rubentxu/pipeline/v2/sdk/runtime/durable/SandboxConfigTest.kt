package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * The guarantee: the runtime refuses to be configured with a profile its own
 * posture does not admit, instead of running the steps with the behaviour of a
 * profile that confines nothing.
 *
 * ## Why the guarantee exists
 *
 * The posture this runtime runs under declares which sandbox profiles it may
 * run under, and that declaration had no reader: the configuration accepted
 * any profile, and the confinement of the local profile is applied to the
 * local profile alone. A run configured with a profile outside the posture
 * would therefore have executed UNCONFINED, and it would have done it
 * silently — the operator asked for a boundary and got the absence of one.
 *
 * The refusal happens where the configuration is built, so no path can carry a
 * profile this runtime cannot honour.
 *
 * ## What this instrument cannot see
 *
 * That no OTHER path can build such a configuration: the instrument covers the
 * one constructor, and every configuration in this runtime goes through it.
 */
class SandboxConfigTest {

    @Test
    fun `the legacy profiles are accepted`() {
        assertEquals(SandboxProfile.NONE, SandboxConfig(SandboxProfile.NONE).profile)
        assertEquals(SandboxProfile.LOCAL, SandboxConfig(SandboxProfile.LOCAL).profile)
    }

    @Test
    fun `the confined profile is accepted by the posture`() {
        // The posture admits it, so the configuration does not refuse it. Whether THIS
        // host can honour it is the admission's question, and it is answered with the
        // provider's evidence, not by the configuration.
        assertEquals(SandboxProfile.CONFINED, SandboxConfig(SandboxProfile.CONFINED).profile)
    }

    @Test
    fun `a profile outside the posture fails closed`() {
        val refused = assertThrows<SandboxProfileUnsupportedException> {
            SandboxConfig(SandboxProfile.valueOf("OS"))
        }

        assertTrue(
            refused.message.orEmpty().contains("os"),
            "the refusal names the profile it will not run: ${refused.message}",
        )
    }

    @Test
    fun `the refusal names the profiles the posture does admit`() {
        val refused = assertThrows<SandboxProfileUnsupportedException> {
            SandboxConfig(SandboxProfile.valueOf("OS"))
        }

        assertTrue(
            refused.message.orEmpty().contains("none") && refused.message.orEmpty().contains("local"),
            "the refusal names what the posture admits: ${refused.message}",
        )
    }
}
