package dev.rubentxu.pipeline.v2.sdk.runtime.durable

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * WU-RP-041 / S2-S3: the trust-profile ADT pin. Proves the declared separation
 * between trusted single-tenant execution and (absent) multi-tenant constrained
 * execution, and that best-effort capabilities cannot be claimed as containment.
 */
class RunnerTrustProfileTest {

    @Test
    fun `trusted single tenant allows none and local sandbox profiles`() {
        val profiles = RunnerTrustProfile.TrustedSingleTenant.allowedSandboxProfiles
        assertEquals(setOf(SandboxProfile.NONE, SandboxProfile.LOCAL), profiles)
    }

    @Test
    fun `multi-tenant constrained profile is not constructible in L3 - fail closed with ADR citation`() {
        val ex = assertThrows(SandboxProfileUnsupportedException::class.java) {
            RunnerTrustProfile.multiTenantConstrained()
        }
        val msg = ex.message!!
        assertTrue(msg.contains("ADR-0016"), "must cite ADR-0016: $msg")
        assertTrue(msg.contains("M5") && msg.contains("M9"), "must cite milestones: $msg")
    }

    @Test
    fun `os sandbox profile remains rejected - the trust ladder has no L3 untrusted rung`() {
        assertThrows(SandboxProfileUnsupportedException::class.java) { SandboxProfile.OS() }
    }
}
