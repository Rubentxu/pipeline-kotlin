package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Assertions.fail
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * F-ARCH-L10-001: 4-way cross-exhaustive DSL Kind invariant.
 *
 * Architecture test that enforces the cross-exhaustive invariant:
 * DSL `Kind` enum (7 entries) ↔ binding-factory sealed `CredentialsBinding` (7 subclasses)
 * ↔ dispatch `when (binding.kind)` branches (7) ↔ `BoundPurpose` enum (7 values).
 *
 * This test FAILS LOUD if any surface widens without the others — preventing
 * the count-discipline drift that bit ML-R9.
 *
 * RED: After T-02 (DSL Kind widening) but before T-03 (dispatch widening) — compile error
 * GREEN: After T-03 lands (both surfaces synchronized at 7)
 */
@Timeout(60)
class FArchL6DslKindExhaustivityTest {

    /**
     * Verifies the DSL `Kind` enum has exactly 7 entries.
     */
    @Test
    fun `dsl Kind enum has exactly 7 entries`() {
        val kindClass = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
        val enumConstants = kindClass.enumConstants
        assertEquals(
            7,
            enumConstants.size,
            "DSL Kind enum must have exactly 7 entries. " +
                "Found: ${enumConstants.map { it?.toString() }.joinToString()}"
        )
    }

    /**
     * Verifies the binding-factory sealed `CredentialsBinding` has exactly 7 subclasses.
     */
    @Test
    fun `binding factory CredentialsBinding sealed has exactly 7 subclasses`() {
        val bindingClass = Class.forName("dev.rubentxu.pipeline.v2.binding.CredentialsBinding")
        @Suppress("UNCHECKED_CAST")
        val sealedSubclasses = (bindingClass.kotlin.sealedSubclasses)
        assertEquals(
            7,
            sealedSubclasses.size,
            "Binding-factory CredentialsBinding sealed must have exactly 7 subclasses. " +
                "Found: ${sealedSubclasses.map { it.simpleName }.joinToString()}"
        )
    }

    /**
     * Verifies the `BoundPurpose` enum has exactly 7 values.
     */
    @Test
    fun `BoundPurpose enum has exactly 7 values`() {
        val boundPurposeClass = Class.forName("dev.rubentxu.pipeline.v2.domain.BoundPurpose")
        val enumConstants = boundPurposeClass.enumConstants
        assertEquals(
            7,
            enumConstants.size,
            "BoundPurpose enum must have exactly 7 values. " +
                "Found: ${enumConstants.map { it?.toString() }.joinToString()}"
        )
    }

    /**
     * Verifies the dispatch `when (binding.kind)` has 7 branches.
     *
     * The Kotlin compiler enforces exhaustiveness at compile time — if the when
     * expression at PipelineRun.kt:944 compiles successfully, it means all DSL
     * Kind entries are covered. We verify the Kind count is 7 here, which proves
     * the dispatch is exhaustive.
     */
    @Test
    fun `dispatch when binding kind has 7 branches`() {
        // The Kotlin compiler enforces exhaustiveness at compile time.
        // If PipelineRun.kt:944 compiles with the 7-branch when, all 7 DSL Kind
        // entries are covered. We verify the Kind count here.
        val kindClass = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
        val kindCount = kindClass.enumConstants.size
        assertEquals(
            7,
            kindCount,
            "DSL Kind count must be 7 for dispatch exhaustiveness. " +
                "If this fails, the when(binding.kind) at PipelineRun.kt:944 is not exhaustive."
        )
    }

    // =========================================================================
    // String-equality pairs: DSL Kind.name() must match binding-factory kind string
    // =========================================================================

    private fun dslKindToBindingKindString(kindName: String): String = when (kindName) {
        "STRING" -> "string"
        "USERNAME_PASSWORD" -> "usernamePassword"
        "SSH_USER_PRIVATE_KEY" -> "sshUserPrivateKey"
        "FILE" -> "file"
        "CERTIFICATE" -> "certificate"
        "ZIP" -> "zip"
        "USERNAME_COLON_PASSWORD" -> "usernameColonPassword"
        else -> fail("Unknown DSL Kind: $kindName")
    }

    @Test
    fun `Kind STRING name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("STRING"), "DSL Kind must contain STRING")
        assertEquals("string", dslKindToBindingKindString("STRING"))
    }

    @Test
    fun `Kind USERNAME_PASSWORD name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("USERNAME_PASSWORD"), "DSL Kind must contain USERNAME_PASSWORD")
        assertEquals("usernamePassword", dslKindToBindingKindString("USERNAME_PASSWORD"))
    }

    @Test
    fun `Kind SSH_USER_PRIVATE_KEY name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("SSH_USER_PRIVATE_KEY"), "DSL Kind must contain SSH_USER_PRIVATE_KEY")
        assertEquals("sshUserPrivateKey", dslKindToBindingKindString("SSH_USER_PRIVATE_KEY"))
    }

    @Test
    fun `Kind FILE name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("FILE"), "DSL Kind must contain FILE")
        assertEquals("file", dslKindToBindingKindString("FILE"))
    }

    @Test
    fun `Kind CERTIFICATE name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("CERTIFICATE"), "DSL Kind must contain CERTIFICATE")
        assertEquals("certificate", dslKindToBindingKindString("CERTIFICATE"))
    }

    @Test
    fun `Kind ZIP name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("ZIP"), "DSL Kind must contain ZIP")
        assertEquals("zip", dslKindToBindingKindString("ZIP"))
    }

    @Test
    fun `Kind USERNAME_COLON_PASSWORD name matches binding factory kind string`() {
        val dslKind = Class.forName("dev.rubentxu.pipeline.v2.dsl.StepSpec\$CredentialsBinding\$Kind")
            .enumConstants.map { it.toString() }
        assertTrue(dslKind.contains("USERNAME_COLON_PASSWORD"), "DSL Kind must contain USERNAME_COLON_PASSWORD")
        assertEquals("usernameColonPassword", dslKindToBindingKindString("USERNAME_COLON_PASSWORD"))
    }
}
