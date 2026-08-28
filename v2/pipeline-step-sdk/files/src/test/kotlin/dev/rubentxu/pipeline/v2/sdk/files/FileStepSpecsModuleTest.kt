package dev.rubentxu.pipeline.v2.sdk.files

import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Assertions.*

/**
 * Module-level test for `:pipeline-step-sdk:files`.
 *
 * Verifies that the module compiles and is correctly wired in settings.gradle.kts.
 *
 * RED: ClassNotFoundException (no module yet)
 * GREEN: Module compiles and settings wiring is correct
 */
class FileStepSpecsModuleTest {

    @Test
    fun `module_resolves_settings_gradle_wiring`() {
        // This test verifies the module is correctly included in settings.gradle.kts
        // by checking that the package exists and can be imported.
        // If the module is not wired, this will throw ClassNotFoundException.
        val clazz = Class.forName("dev.rubentxu.pipeline.v2.sdk.files.FileStepSpecs")
        assertNotNull(clazz, "FileStepSpecs class must exist")
    }

    @Test
    fun `files_module_has_correct_package`() {
        // Verify the package structure is correct
        val packageName = "dev.rubentxu.pipeline.v2.sdk.files"
        val clazz = Class.forName("$packageName.FileStepSpecs")
        assertEquals(packageName, clazz.`package`.name)
    }
}
