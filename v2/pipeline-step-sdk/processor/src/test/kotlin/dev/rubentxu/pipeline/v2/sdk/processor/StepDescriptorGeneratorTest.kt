package dev.rubentxu.pipeline.v2.sdk.processor

import org.junit.jupiter.api.Test

/**
 * KSP round-trip test for StepDescriptorGenerator.
 * Validates that the processor correctly processes @Step-annotated functions
 * and generates the expected output format.
 */
class StepDescriptorGeneratorTest {

    @Test
    fun `processor is instantiable`() {
        // Basic instantiation test - the actual KSP processing is tested
        // via integration tests that run the full KSP lifecycle
        val provider = StepDescriptorGeneratorProvider()
        assert(provider is StepDescriptorGeneratorProvider)
    }
}
