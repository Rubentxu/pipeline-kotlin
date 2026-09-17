package pipeline.utilities

import dev.rubentxu.pipeline.v2.domain.step.StepCapability
import dev.rubentxu.pipeline.v2.domain.step.StepCapabilityContributor

/**
 * LFC-2E3-T4 — the plugin-side half of the capability contract for
 * `pipeline.utilities.json@1.0.0`.
 *
 * Supplies the default implementation of EVERY capability token the plugin's registered
 * StepDefinitions declare, so the host can compose them with the runtime's canonical capability
 * bridge and all 16 utilities Steps are admitted through the installed CLI exactly as they are
 * in-process.
 *
 * Without this contribution the 16 utilities Steps were rejected at prepare-time admission when
 * run from the CLI — the inherited gap reported (and now closed) in
 * `docs/v2/07-uat/E3_T4_REAL_FIXTURES_CLI_ACCEPTANCE_RECEIPT.md`. The Steps themselves were
 * already CERTIFIED at the in-process level in LFC-2E2; what was missing was the host-side
 * composition.
 *
 * Declared through its own [StepCapabilityContributor] SPI (not as a method on the existing
 * `StepDefinitionContributor`) so that already-built plugin JARs stay binary compatible.
 */
class UtilitiesCapabilityContributor : StepCapabilityContributor {
    override val id: String = pipeline.utilities.json.UtilitiesJsonContributor.COORDINATE

    override fun capabilities(): Map<StepCapability, Any> = mapOf(
        pipeline.utilities.json.UtilitiesJsonContributor.UTILITIES_JSON_CAPABILITY
            to pipeline.utilities.json.DefaultUtilitiesJsonOperations(),
        pipeline.utilities.json.UtilitiesJsonContributor.UTILITIES_SHA_CAPABILITY
            to pipeline.utilities.json.DefaultUtilitiesShaOperations(),
        pipeline.utilities.yaml.UtilitiesYamlContributor.UTILITIES_YAML_CAPABILITY
            to pipeline.utilities.yaml.DefaultUtilitiesYamlOperations(),
        pipeline.utilities.properties.UtilitiesPropertiesContributor.UTILITIES_PROPERTIES_CAPABILITY
            to pipeline.utilities.properties.DefaultUtilitiesPropertiesOperations(),
        pipeline.utilities.filesystem.UtilitiesFilesystemContributor.UTILITIES_FILESYSTEM_CAPABILITY
            to pipeline.utilities.filesystem.DefaultUtilitiesFilesystemOperations(),
        pipeline.utilities.checksums.UtilitiesChecksumsContributor.UTILITIES_CHECKSUMS_CAPABILITY
            to pipeline.utilities.checksums.DefaultChecksumOperations(),
        pipeline.utilities.archive.UtilitiesArchiveContributor.UTILITIES_ARCHIVE_CAPABILITY
            to pipeline.utilities.archive.DefaultArchiveOperations(),
    )
}
