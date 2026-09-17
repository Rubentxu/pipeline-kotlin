package pipeline.testing

import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor

/**
 * LFC-2E3-T4 — the plugin-side half of the capability contract for `pipeline.testing`.
 *
 * Supplies the implementation of [TestingContributor.TESTING_FILESYSTEM_CAPABILITY], which
 * `core.junit` declares in its `StepContract.requiredCapabilities`. The host composes this with
 * the runtime's canonical capability bridge, so the Step is admitted through the installed CLI
 * exactly as it is in-process.
 *
 * Without this contribution `core.junit` would be rejected at prepare-time admission and never
 * execute from the CLI — the inherited gap root-caused in
 * `docs/v2/07-uat/E3_T4_REAL_FIXTURES_CLI_ACCEPTANCE_RECEIPT.md`.
 *
 * Declared through its own [StepCapabilityContributor] SPI (not as a method on the existing
 * `StepDefinitionContributor`) so that already-built plugin JARs stay binary compatible.
 */
class TestingCapabilityContributor : StepCapabilityContributor {
    override val id: String = TestingContributor.COORDINATE

    override fun capabilities(): Map<StepCapability, Any> = mapOf(
        TestingContributor.TESTING_FILESYSTEM_CAPABILITY
            to pipeline.testing.junit.DefaultJunitFilesystemOperations(),
        // LFC-2E3-R1: the publisher port. Kept separate from the read-only parser port above so a
        // grant of one never implies a grant of the other.
        TestingContributor.TESTING_PUBLISH_CAPABILITY
            to pipeline.testing.publish.DefaultReportPublishingOperations(),
    )
}
