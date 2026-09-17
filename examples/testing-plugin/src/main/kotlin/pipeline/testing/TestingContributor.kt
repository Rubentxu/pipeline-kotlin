package pipeline.testing

import dev.rubentxu.pipeline.v2.domain.PluginStepId
import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepDefinition
import dev.rubentxu.pipeline.v2.domain.step.StepDefinitionContributor

/**
 * LFC-2E3-T2 — `pipeline.testing` OFFICIAL_PLUGIN contributor.
 *
 * LFC-2E2 proved the `pipeline.utilities.json` coordinate scales for
 * filesystem / archive / codec families. LFC-2E3 introduces a NEW
 * dimension — structured test results + HTML report publication —
 * under a SEPARATE coordinate (`pipeline.testing@0.1.0-SNAPSHOT`).
 *
 * Production core stays unaware of either coordinate: registration
 * happens entirely through the [StepDefinitionContributor] SPI
 * discovered by `ExternalStepPluginDiscovery` at runtime.
 *
 * The first registered family is `core.junit` (this slice). Future
 * slices in LFC-2E3 (publishHTML, optional coverage/analyzers) will
 * add their own StepDefinitions through the same contributor — the
 * JAR carries ONE contributor, not one per family.
 */
class TestingContributor : StepDefinitionContributor {
    override val id: String = COORDINATE

    override fun definitions(): Iterable<StepDefinition<*, *>> = listOf(
        // LFC-2E3-T2: junit StepDefinition (this slice).
        pipeline.testing.junit.JunitStepDefinition,
    )

    companion object {
        const val COORDINATE: String = "pipeline.testing"
        const val PLUGIN_VERSION: String = "0.1.0-SNAPSHOT"

        /**
         * Filesystem capability required by `core.junit` (read a JUnit XML
         * file from disk).
         *
         * Distinct from `utilities.json.operations` and
         * `utilities.archive.operations` so the three ports cannot
         * accidentally satisfy each other — admission stays fail-closed.
         */
        val TESTING_FILESYSTEM_CAPABILITY: StepCapability =
            StepCapability("testing.filesystem.operations")
    }
}
