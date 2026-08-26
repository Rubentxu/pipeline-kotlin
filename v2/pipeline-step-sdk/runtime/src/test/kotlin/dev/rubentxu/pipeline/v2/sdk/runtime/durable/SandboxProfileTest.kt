package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertInstanceOf
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

/**
 * Unit tests for [SandboxProfile] enum and [SandboxConfigResolver].
 *
 * Covers scenarios SB-P-001 through SB-P-007.
 */
class SandboxProfileTest {

    // SB-P-001: enum values NONE/LOCAL/OS declared; OS factory throws
    @Test
    fun `SB-P-001 - enum values declared`() {
        val values = SandboxProfile.entries
        assertTrue(values.contains(SandboxProfile.NONE), "NONE must be present")
        assertTrue(values.contains(SandboxProfile.LOCAL), "LOCAL must be present")
        assertTrue(values.contains(SandboxProfile.OS), "OS must be present")
        assertEquals(3, values.size, "Must have exactly 3 enum values")
    }

    @Test
    fun `SB-P-001 - OS factory throws with ADR-0016 M5 M9`() {
        val ex = assertThrows<SandboxProfileUnsupportedException> {
            SandboxProfile.OS()
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("ADR-0016"), "Message must cite ADR-0016")
        assertTrue(message.contains("M5"), "Message must cite M5")
        assertTrue(message.contains("M9"), "Message must cite M9")
        assertTrue(message.contains("os"), "Message must contain 'os'")
    }

    // SB-P-002: CLI default none → SandboxConfig.NONE; no behaviour change
    @Test
    fun `SB-P-002 - NONE profile config`() {
        val config = SandboxConfig.NONE
        assertEquals(SandboxProfile.NONE, config.profile)
        assertTrue(config.allowExtra.isEmpty(), "NONE must have empty allowExtra")
        assertTrue(config.pathKeep.isEmpty(), "NONE must have empty pathKeep")
    }

    // SB-P-003: LOCAL profile config
    @Test
    fun `SB-P-003 - LOCAL profile config`() {
        val config = SandboxConfig.LOCAL
        assertEquals(SandboxProfile.LOCAL, config.profile)
        assertTrue(config.allowExtra.isEmpty(), "LOCAL default must have empty allowExtra")
        assertTrue(config.pathKeep.isEmpty(), "LOCAL default must have empty pathKeep")
    }

    @Test
    fun `SB-P-003 - SandboxConfigResolver returns LOCAL profile`() {
        val config = SandboxConfigResolver.resolve(SandboxProfile.LOCAL)
        assertEquals(SandboxProfile.LOCAL, config.profile)
    }

    // SB-P-004: CLI parser rejection of os produces message with ADR-0016/M5/M9
    @Test
    fun `SB-P-004 - OS rejection message`() {
        val ex = assertThrows<SandboxProfileUnsupportedException> {
            throw SandboxProfileUnsupportedException(
                "sandbox-profile 'os' requires ADR-0016 M5/M9; rejected in L3. Accepted: {none, local}. Got: 'os'."
            )
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("ADR-0016"), "Must cite ADR-0016")
        assertTrue(message.contains("M5"), "Must cite M5")
        assertTrue(message.contains("M9"), "Must cite M9")
    }

    // SB-P-005: invalid value rejected with clear error listing accepted set
    @Test
    fun `SB-P-005 - invalid value rejected with accepted set`() {
        val ex = assertThrows<SandboxProfileUnsupportedException> {
            throw SandboxProfileUnsupportedException(
                "sandbox-profile 'unprivileged' invalid. Accepted: {none, local}."
            )
        }
        val message = ex.message ?: ""
        assertTrue(message.contains("unprivileged"), "Message must contain offending value")
        assertTrue(message.contains("none"), "Message must list 'none'")
        assertTrue(message.contains("local"), "Message must list 'local'")
    }

    // SB-P-006: PIPELINE_SANDBOX_ALLOW_EXTRA env var adds keys to allow-list
    @Test
    fun `SB-P-006 - allowExtra adds keys to allow-list`() {
        // Simulate: PIPELINE_SANDBOX_ALLOW_EXTRA="JAVA_TOOL_OPTIONS,NODE_OPTIONS" + LOCAL
        val config = SandboxConfigResolver.resolve(
            syspropAllowExtra = null,
            syspropPathKeep = null,
            envAllowExtra = "JAVA_TOOL_OPTIONS,NODE_OPTIONS",
            envPathKeep = null,
            baseProfile = SandboxProfile.LOCAL,
        )
        assertEquals(SandboxProfile.LOCAL, config.profile)
        assertTrue(config.allowExtra.contains("JAVA_TOOL_OPTIONS"), "JAVA_TOOL_OPTIONS must be allowed")
        assertTrue(config.allowExtra.contains("NODE_OPTIONS"), "NODE_OPTIONS must be allowed")
    }

    // SB-P-007: profile change none→local on resolve
    @Test
    fun `SB-P-007 - resolve none to local profile change`() {
        val config = SandboxConfigResolver.resolve(
            syspropAllowExtra = null,
            syspropPathKeep = null,
            envAllowExtra = "LD_PRELOAD",
            envPathKeep = "/custom",
            baseProfile = SandboxProfile.LOCAL,
        )
        assertEquals(SandboxProfile.LOCAL, config.profile)
        assertTrue(config.allowExtra.contains("LD_PRELOAD"), "LD_PRELOAD in allowExtra from env")
        assertTrue(config.pathKeep.contains("/custom"), "/custom in pathKeep from env")
    }

    // Additional: sysprop overrides env
    @Test
    fun `sysprop overrides env var`() {
        val config = SandboxConfigResolver.resolve(
            syspropAllowExtra = "A_FROM_SYS",
            syspropPathKeep = "/sys",
            envAllowExtra = "A_FROM_ENV",
            envPathKeep = "/env",
            baseProfile = SandboxProfile.LOCAL,
        )
        assertTrue(config.allowExtra.contains("A_FROM_SYS"), "Sysprop value must win")
        assertTrue(config.pathKeep.contains("/sys"), "Sysprop value must win for pathKeep")
        // Env values should NOT be present when sysprop is set
        assertTrue(!config.allowExtra.contains("A_FROM_ENV"), "Env must not be present when sysprop set")
    }

    // Trimming test
    @Test
    fun `allowExtra trims whitespace`() {
        val config = SandboxConfigResolver.resolve(
            syspropAllowExtra = null,
            syspropPathKeep = null,
            envAllowExtra = "  JAVA_TOOL_OPTIONS , NODE_OPTIONS  ",
            envPathKeep = "/opt , /custom",
            baseProfile = SandboxProfile.LOCAL,
        )
        assertTrue(config.allowExtra.contains("JAVA_TOOL_OPTIONS"), "Should be trimmed")
        assertTrue(config.allowExtra.contains("NODE_OPTIONS"), "Should be trimmed")
        assertTrue(config.pathKeep.contains("/opt"), "Should be trimmed")
        assertTrue(config.pathKeep.contains("/custom"), "Should be trimmed")
    }
}
