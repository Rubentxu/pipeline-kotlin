package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * F-ARCH-L7-001: Jenkins verbatim signature assertion for file/artefact steps.
 *
 * Architecture test that enforces the 5 new StepSpec data classes match the Jenkins
 * familiarity catalog parameter order exactly, with documented extensions where
 * the implementation has evolved beyond the original Jenkins verbatim.
 *
 * Jenkins catalog (ML-R7 / ADR-0023 / ADR-0052) defines the exact constructor parameter
 * order for the verbatim subset of the surface:
 * - WriteFile(file, text, encoding)                                 — Jenkins verbatim
 * - ReadFile(file, encoding)                                       — Jenkins verbatim
 * - FileExists(file)                                               — Jenkins verbatim
 * - WithEnv(overrides, steps)                                      — Jenkins verbatim
 *   (overrides typed as List<String> to honour Jenkins catalogue §1.1 line 40;
 *    steps added for the nested block payload.)
 * - ArchiveArtifacts(artifacts, allowEmptyArchive, excludes, fingerprint,
 *                    artifactName)                                 — Jenkins verbatim + E1.1 extension
 *   (artifactName was added by LFC-2 E1 / T7 / core.artifact.query to carry the
 *    per-run artifact identifier; it is a strictly typed, optional String? and
 *    does not change Jenkins parameter semantics.)
 *
 * Kotlin 2.x does not reliably preserve constructor parameter names in bytecode
 * even with -Xemit-parameters across all build environments. Therefore this test
 * verifies: (1) constructor param COUNT and TYPES, (2) field names.
 *
 * RED: ClassNotFoundException (no WriteFile/ReadFile/FileExists/WithEnv/ArchiveArtifacts classes yet)
 * GREEN: After T-04, all 5 step classes have correct Jenkins (or Jenkins + E1.1) shape.
 *
 * WU-LPR-080 refinements:
 *   - WriteFile: was enforced as 4-params (name, file, text, encoding) but Jenkins
 *     and the production code (`StepSpec.WriteFile`) only carry (file, text, encoding).
 *     The KDoc at lines 12-17 already stated the correct 3-param shape; the data table
 *     and the in-test writeFile_shape_matches_jenkins_catalog test contradicted it.
 *   - ArchiveArtifacts: was enforced as 4-params (artifacts, allowEmptyArchive, excludes,
 *     fingerprint) but E1.1 / T7 added a fifth optional `artifactName: String?` field
 *     to carry the per-run artifact identifier for the core.archiveArtifacts →
 *     core.artifact.query bridge. The Jenkins catalogue still matches on the first 4;
 *     the additional field is a strictly typed, optional, additive extension.
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
                "Ljava/util/List;",     // overrides (List<String> per Jenkins catalog §1.1 line 40)
                "Ljava/util/List;"      // steps
            ),
            fieldNames = setOf("overrides", "steps")
        ),
        "ArchiveArtifacts" to StepShape(
            paramTypeDescriptors = listOf(
                "Ljava/lang/String;",  // artifacts
                "Ljava/lang/Boolean;", // allowEmptyArchive
                "Ljava/lang/String;",  // excludes
                "Ljava/lang/Boolean;", // fingerprint
                "Ljava/lang/String;"   // artifactName (E1.1 / T7: optional String?
                                       //   carried by the JVM as the boxed reference
                                       //   type Ljava/lang/String; — optional)
            ),
            fieldNames = setOf("artifacts", "allowEmptyArchive", "excludes", "fingerprint", "artifactName")
        )
    )

    /**
     * Verifies WriteFile shape matches Jenkins catalog:
     * - Constructor has 3 parameters (file, text, encoding)
     * - Fields include file, text, encoding
     *
     * Note: a previous version of this test required 4 parameters
     * (`name, file, text, encoding`). The Jenkins verbatim signature is
     * `(file: String, text: String, encoding: String)` per
     * JENKINS_FAMILIARITY_CATALOG.md §1.1 line 35, and the production
     * `StepSpec.WriteFile` data class (PipelineDsl.kt:365) carries exactly
     * those three fields. The test was refined in WU-LPR-080 to drop the
     * non-existent `name` constructor parameter and align with the catalog
     * and the production code. WU-LPR-080 receipt records the refinement
     * against the Jenkins verbatim contract (ADR-0023 / ADR-0052).
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
