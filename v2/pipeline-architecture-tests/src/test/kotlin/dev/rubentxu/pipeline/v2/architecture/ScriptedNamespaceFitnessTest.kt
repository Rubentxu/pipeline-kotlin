package dev.rubentxu.pipeline.v2.architecture

import dev.rubentxu.pipeline.v2.application.CoreStepRegistryFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path

/**
 * S4-R — the `scripted.` operation namespace is a NAMING RULE, and naming rules
 * without a test are comments.
 *
 * ## The hole this fills
 *
 * `ScriptedRegistryInvoker` builds its operation address as
 * `"scripted." + stepKey`, and its KDoc claims:
 *
 * ```text
 * Namespace rule: `scripted.` + stepKey; collision-checked by fitness tests.
 * ```
 *
 * That claim was false. The only test mentioning the class verifies that the
 * scripted shell reaches the registry, not that the namespace is collision-free.
 * A grep for `scriptedStepId` found its definition, its use, and two legacy
 * adaptors — no fitness anywhere.
 *
 * Why that matters more than a stale comment: the prefix is a **durable address
 * component**. Every scripted row in every journal carries `scripted.<key>` as
 * its `stepId`, and `stepId` is inside the fingerprint. Renaming the prefix would
 * not fail any test; it would silently make every scripted row unfindable, so a
 * resume would believe previously-issued effects were fresh and re-run them.
 *
 * ADR-0103 RPL-1 explicitly permits a frontend to own a different address space
 * and RPL-5 forbids that space from changing replay semantics. Both halves are
 * only meaningful while the address itself is pinned. This test is the pin.
 *
 * ## What is source-level and why
 *
 * `scriptedStepId` is a `private` member of a companion object, so it cannot be
 * called from here. The rule is therefore asserted against its source, and the
 * non-collision property is computed from the real production registry — the part
 * that could actually be wrong is arithmetic over real keys, not the presence of
 * a string literal.
 */
class ScriptedNamespaceFitnessTest {

    private val v2Root: Path = ScannerSupport.v2Root()

    private val invokerSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/ScriptedRegistryInvoker.kt",
    )

    private fun sourceText(): String = Files.readString(invokerSource)

    /**
     * Only production sources. A test may legitimately rebuild a scripted address in
     * order to assert something about it, and this file necessarily contains the
     * rule's own text — scoping the law to `src/main` is what makes it about the
     * durable namespace rather than about spelling.
     */
    private fun productionFilesContaining(token: String): List<Path> =
        ScannerSupport.walkKotlinFiles(v2Root)
            .filter { it.toString().replace('\\', '/').contains("/src/main/") }
            .filter { Files.readString(it).contains(token) }
            .map { it.toAbsolutePath().normalize() }

    @Test
    fun `the scripted namespace prefix is pinned`() {
        val text = sourceText()

        assertTrue(
            text.contains("\"scripted.\" + key.value"),
            "The scripted operation namespace must stay `\"scripted.\" + stepKey`. ADR-0103 RPL-1 " +
                "permits this address space to differ from the declarative one and RPL-5 requires it not " +
                "to change replay semantics, but the prefix itself is a durable component: it is inside " +
                "the fingerprint of every scripted row ever written. Changing it silently orphans that " +
                "history. If the address really must change, that is a compatibility cut declared on " +
                "ScriptedArtifactIdentity.runtimeCompatibilityVersion, not a rename.",
        )
    }

    @Test
    fun `the namespace rule has exactly one constructor`() {
        val constructors = productionFilesContaining("\"scripted.\" +")

        assertEquals(
            1,
            constructors.size,
            "Exactly one place may build the scripted step id. A second constructor is a second " +
                "addressing rule, and two rules cannot both be the durable namespace. Found in:\n" +
                constructors.joinToString("\n") { "\t$it" },
        )
        assertEquals(
            invokerSource.toAbsolutePath().normalize(),
            constructors.single().toAbsolutePath().normalize(),
            "The single constructor must be the one the invoker itself uses.",
        )
    }

    /**
     * The collision the KDoc promised to check. `"scripted." + a == b` can only
     * happen for keys `a`, `b` in the registry if some key already begins with
     * the prefix — which would mean a scripted row could be mistaken for a
     * declarative one, or two frontends could address the same operation.
     */
    @Test
    fun `no registered step key can collide with the scripted namespace`() {
        val keys = CoreStepRegistryFactory.registry().keys()
        assertTrue(keys.isNotEmpty(), "the production registry must not be empty")

        val offending = keys.filter { it.value.startsWith(SCRIPTED_PREFIX) }

        assertTrue(
            offending.isEmpty(),
            "A registered StepKey begins with the scripted namespace prefix, so its scripted address " +
                "would collide with another address in the registry:\n" +
                offending.joinToString("\n") { "\t$it" } +
                "\nRename the step key, not the namespace rule.",
        )

        // And the two address spaces are provably disjoint for every registered key.
        val declarative = keys.map { it.value }.toSet()
        val scripted = keys.map { SCRIPTED_PREFIX + it.value }.toSet()
        assertTrue(
            declarative.intersect(scripted).isEmpty(),
            "The declarative and scripted address spaces must be disjoint by construction.",
        )
    }

    @Test
    fun `no other module rebuilds the scripted step id`() {
        val offenders = productionFilesContaining("scriptedStepId")
            .filter { it != invokerSource.toAbsolutePath().normalize() }

        assertFalse(
            offenders.isNotEmpty(),
            "Only ScriptedRegistryInvoker may build the scripted operation address. Other callers must " +
                "go through the invoker, or a second caller could address a scripted operation with " +
                "different inputs and produce a row nothing can find. Offenders:\n" +
                offenders.joinToString("\n") { "\t$it" },
        )
    }

    private companion object {
        const val SCRIPTED_PREFIX = "scripted."
    }
}
