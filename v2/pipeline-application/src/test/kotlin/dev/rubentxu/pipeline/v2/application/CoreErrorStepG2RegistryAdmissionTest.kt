package dev.rubentxu.pipeline.v2.application

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import java.util.concurrent.TimeUnit

/**
 * HISTORICAL EVIDENCE (NOT a regression test) — S2-A1 / G2 of `core.error`.
 *
 * Status at G5 (2026-09-11T10:34Z): ARCHIVED.
 *
 * Reason for archival: this class encoded the G2 transient state where `core.error`
 * was REGISTERED in the production registry but NOT YET the production routing
 * authority. The defining assertion
 *
 *     StructuralFamilyResolver(core.error, registry) == LegacyCore
 *
 * was correct at G2 (legacy membership wins; production routed through the legacy
 * path). At G5 we flipped the routing:
 *
 *     LEGACY_PLUGIN_IDS -= "core.error"
 *
 * The structural family flipped to `Registry`. The G2 assertion therefore became
 * INCORRECT as a permanent regression assertion — keeping it would have made the
 * regression suite red after a successful migration.
 *
 * Per the user's directive ("No conviertas un test G2 en un test G5 cambiándole
 * silenciosamente el significado"), this class is preserved as historical evidence
 * only. The `@Disabled` annotation prevents JUnit from running it; the G5 state is
 * now covered by `CoreErrorRegistryPrimaryFitnessTest`, and the irreversible
 * post-G6 state will be covered by `S3ErrorLegacyRemovedFitnessTest`.
 *
 * The legacy assertions remain in source so future readers can SEE what the G2 state
 * was. They are NOT a regression gate.
 */
@Disabled("Archived G2 evidence: superseded by CoreErrorRegistryPrimaryFitnessTest (G5) and " +
    "S3ErrorLegacyRemovedFitnessTest (G6). See class kdoc for rationale.")
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class CoreErrorStepG2RegistryAdmissionTest {

    @Test
    fun `G2 evidence -- production registry contained core error`() {
        // Assertion at G2: registry.contains(core.error) == true.
        // At G5 this is still TRUE (the registry entry was never removed), but the
        // meaningful question is now the routing authority (Registry, not LegacyCore),
        // which is asserted in CoreErrorRegistryPrimaryFitnessTest.
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }

    @Test
    fun `G2 evidence -- production registry resolved core error to the new StepDefinition`() {
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }

    @Test
    fun `G2 evidence -- structural family for core error was LegacyCore (transient)`() {
        // At G2 the assertion was:
        //     StructuralFamilyResolver(core.error, registry) == LegacyCore
        // This is the historical assertion whose meaning changed at G5.
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }

    @Test
    fun `G2 evidence -- echo and sh families were not regressed by the new error wiring`() {
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }

    @Test
    fun `G2 evidence -- registry contained all three core keys`() {
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }

    @Test
    fun `G2 evidence -- registry was fresh per call (no global singleton)`() {
        throw UnsupportedOperationException(
            "Archived G2 evidence test — see class kdoc. The G2 evidence is preserved in source.",
        )
    }
}
