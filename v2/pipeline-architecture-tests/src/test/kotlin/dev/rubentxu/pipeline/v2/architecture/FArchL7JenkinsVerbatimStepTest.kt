package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * F-ARCH-L7-001: Jenkins verbatim signature assertion for file/artefact steps.
 *
 * Architecture test that enforces the 5 new StepSpec data classes match the Jenkins
 * familiarity catalog parameter order exactly.
 *
 * Jenkins catalog §1.7 (ML-R7) defines the exact constructor parameter order for:
 * - WriteFile(file, text, encoding)
 * - ReadFile(file, encoding)
 * - FileExists(file)
 * - WithEnv(overrides, steps)
 * - ArchiveArtifacts(artifacts, allowEmptyArchive, excludes, fingerprint)
 *
 * Kotlin 2.x does not reliably preserve constructor parameter names in bytecode
 * even with -Xemit-parameters across all build environments. Therefore this test
 * verifies: (1) constructor param COUNT and TYPES, (2) field names.
 *
 * RED: ClassNotFoundException (no WriteFile/ReadFile/FileExists/WithEnv/ArchiveArtifacts classes yet)
 * GREEN: After T-04, all 5 step classes have correct Jenkins shape
 */
class FArchL7JenkinsVerbatimStepTest {

    /**
     * Jenkins catalog shape for each file/artefact step type.
     * Extracted verbatim from JENKINS_FAMILIARITY_CATALOG.md §1.7 (ML-R7).
     *
     * Format: class name -> (param type descriptors, field names)
     * Type descriptors use JVM signature: Ljava/lang/String; for String, etc.
     */
    private data class StepShape(
        val paramTypeDescriptors: List<String>,
        val fieldNames: Set<String>
    )

    /** Convert a Java Class to its JVM type descriptor */
    private fun classToDescriptor(type: Class<*>): String = when (type) {
        String::class.java -> "Ljava/lang/String;"
        else -> "L${type.name.replace('.', '/')};"
    }

    private val jenkinsCatalogShapes = mapOf(
        "WriteFile" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // file
                "Ljava/lang/String;",  // text
                "Ljava/lang/String;"   // encoding
            ),
            fieldNames = setOf("file", "text", "encoding")
        ),
        "ReadFile" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // file
                "Ljava/lang/String;"   // encoding
            ),
            fieldNames = setOf("file", "encoding")
        ),
        "FileExists" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;"   // file
            ),
            fieldNames = setOf("file")
        ),
        "WithEnv" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/util/Map;",     // overrides
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("overrides", "steps")
        ),
        "ArchiveArtifacts" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // artifacts
                "Ljava/lang/Boolean;", // allowEmptyArchive
                "Ljava/lang/String;",  // excludes
                "Ljava/lang/Boolean;"  // fingerprint
            ),
            fieldNames = setOf("artifacts", "allowEmptyArchive", "excludes", "fingerprint")
        )
    )

    /**
     * Verifies WriteFile shape matches Jenkins catalog:
     * - Constructor has 4 parameters (name, file, text, encoding)
     * - Fields include name, file, text, encoding
     */
    @Test
    fun `writeFile_shape_matches_jenkins_catalog`() {
        val writeFileClass = Class.forName(
            "dev.rubentxu.pipeline.v2.dsl.StepSpec\$WriteFile"
        )

        val shape = jenkinsCatalogShapes["WriteFile"]
            ?: throw IllegalStateException("Catalog missing WriteFile entry")

        // Verify constructor param count and types
        val constructor = writeFileClass.declaredConstructors
            .filter { it.parameters.size == shape.paramTypeDescriptors.size }
            .firstOrNull()
            ?: throw AssertionError("WriteFile has no constructor with ${shape.paramTypeDescriptors.size} parameters")

        val paramTypes = constructor.parameterTypes.map { classToDescriptor(it) }
        assertEquals(
            shape.paramTypeDescriptors,
            paramTypes,
            "WriteFile constructor parameter types must match Jenkins catalog"
        )

        // Verify fields
        val actualFieldNames = writeFileClass.declaredFields
            .map { it.name }
            .toSet()
        assertEquals(
            shape.fieldNames,
            actualFieldNames,
            "WriteFile field names must match Jenkins catalog"
        )
    }

    /**
     * Full catalog verification for all 5 file/artefact step types.
     * Each step class must have the correct Jenkins shape.
     */
    @Test
    fun `all_step_shapes_match_jenkins_catalog`() {
        val failures = mutableListOf<String>()

        for ((stepName, shape) in jenkinsCatalogShapes) {
            try {
                val fqcn = "dev.rubentxu.pipeline.v2.dsl.StepSpec\$$stepName"
                val stepClass = Class.forName(fqcn)

                // Verify constructor
                val constructor = stepClass.declaredConstructors
                    .filter { it.parameters.size == shape.paramTypeDescriptors.size }
                    .firstOrNull()

                if (constructor == null) {
                    failures.add("$stepName: no constructor with ${shape.paramTypeDescriptors.size} params")
                    continue
                }

                val paramTypes = constructor.parameterTypes.map { classToDescriptor(it) }
                if (paramTypes != shape.paramTypeDescriptors) {
                    failures.add("$stepName: param types mismatch — expected ${shape.paramTypeDescriptors}, got $paramTypes")
                }

                // Verify fields
                val actualFieldNames = stepClass.declaredFields.map { it.name }.toSet()
                if (actualFieldNames != shape.fieldNames) {
                    failures.add("$stepName: fields mismatch — expected ${shape.fieldNames}, got $actualFieldNames")
                }

            } catch (e: ClassNotFoundException) {
                failures.add("$stepName: ClassNotFoundException — not yet implemented")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Jenkins parity failures:\n${failures.joinToString("\n")}"
            )
        }
    }
}
