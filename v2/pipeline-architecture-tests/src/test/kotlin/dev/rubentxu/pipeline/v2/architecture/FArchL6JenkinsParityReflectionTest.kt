package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * F-ARCH-L6-003: Jenkins verbatim signature assertion.
 *
 * Architecture test that enforces binding class shapes match the Jenkins
 * credentials-binding catalog for all 7 binding types.
 *
 * Jenkins catalog §1.6 (lines 109-115) defines the exact:
 * - Constructor parameter COUNT and TYPE signature
 * - Field names
 *
 * Kotlin 2.x does not reliably preserve constructor parameter names in bytecode
 * even with -Xemit-parameters across all build environments. Therefore this test
 * verifies: (1) constructor param COUNT and TYPES, (2) field names. These two
 * checks together prove the binding shape matches Jenkins verbatim.
 *
 * RED: ClassNotFoundException (no CredentialsBinding types yet — T-07 will create them)
 * GREEN: After T-07, all 7 binding classes have correct Jenkins shape
 */
class FArchL6JenkinsParityReflectionTest {

    /**
     * Jenkins catalog shape for each binding type.
     * Extracted verbatim from JENKINS_FAMILIARITY_CATALOG.md §1.6 lines 109-115.
     *
     * Format: class name -> (param type descriptors, field names)
     * Type descriptors use JVM signature: Ljava/lang/String; for String, etc.
     */
    private data class BindingShape(
        val paramTypeDescriptors: List<String>,
        val fieldNames: Set<String>
    )

    /** Convert a Java Class to its JVM type descriptor */
    private fun classToDescriptor(type: Class<*>): String = when (type) {
        String::class.java -> "Ljava/lang/String;"
        else -> "L${type.name.replace('.', '/')};"
    }

    private val jenkinsCatalogShapes = mapOf(
        "StringBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("credentialsId", "variable", "kind")
        ),
        "UsernamePasswordBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("credentialsId", "usernameVariable", "passwordVariable", "kind")
        ),
        "SshUserPrivateKeyBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("credentialsId", "keyFileVariable", "passphraseVariable", "usernameVariable", "kind")
        ),
        "FileBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("credentialsId", "variable", "kind")
        ),
        "CertificateBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("keystoreVariable", "credentialsId", "aliasVariable", "passwordVariable", "kind")
        ),
        "ZipBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("variable", "credentialsId", "kind")
        ),
        "UsernameColonPasswordBinding" to BindingShape(
            paramTypeDescriptors = listOf("Ljava/lang/String;", "Ljava/lang/String;"),
            fieldNames = setOf("variable", "credentialsId", "kind")
        )
    )

    /**
     * Verifies StringBinding shape matches Jenkins catalog:
     * - Constructor has 2 String parameters (credentialsId, variable)
     * - Fields include credentialsId, variable, kind
     */
    @Test
    fun `string_binding_shape_matches_jenkins_catalog`() {
        val stringBindingClass = Class.forName(
            "dev.rubentxu.pipeline.v2.binding.StringBinding"
        )

        val shape = jenkinsCatalogShapes["StringBinding"]
            ?: throw IllegalStateException("Catalog missing StringBinding entry")

        // Verify constructor param count and types
        val constructor = stringBindingClass.declaredConstructors
            .filter { it.parameters.size == shape.paramTypeDescriptors.size }
            .firstOrNull()
            ?: throw AssertionError("StringBinding has no constructor with ${shape.paramTypeDescriptors.size} parameters")

        val paramTypes = constructor.parameterTypes.map { classToDescriptor(it) }
        assertEquals(
            shape.paramTypeDescriptors,
            paramTypes,
            "StringBinding constructor parameter types must match Jenkins catalog"
        )

        // Verify fields (Java reflection always preserves field names)
        val actualFieldNames = stringBindingClass.declaredFields
            .map { it.name }
            .toSet()
        assertEquals(
            shape.fieldNames,
            actualFieldNames,
            "StringBinding field names must match Jenkins catalog"
        )
    }

    /**
     * Full catalog verification for all 7 binding types.
     * Each binding class must have the correct Jenkins shape.
     */
    @Test
    fun `all_binding_shapes_match_jenkins_catalog`() {
        val failures = mutableListOf<String>()

        for ((bindingName, shape) in jenkinsCatalogShapes) {
            try {
                val fqcn = "dev.rubentxu.pipeline.v2.binding.$bindingName"
                val bindingClass = Class.forName(fqcn)

                // Verify constructor
                val constructor = bindingClass.declaredConstructors
                    .filter { it.parameters.size == shape.paramTypeDescriptors.size }
                    .firstOrNull()

                if (constructor == null) {
                    failures.add("$bindingName: no constructor with ${shape.paramTypeDescriptors.size} params")
                    continue
                }

                val paramTypes = constructor.parameterTypes.map { classToDescriptor(it) }
                if (paramTypes != shape.paramTypeDescriptors) {
                    failures.add("$bindingName: param types mismatch — expected ${shape.paramTypeDescriptors}, got $paramTypes")
                }

                // Verify fields
                val actualFieldNames = bindingClass.declaredFields.map { it.name }.toSet()
                if (actualFieldNames != shape.fieldNames) {
                    failures.add("$bindingName: fields mismatch — expected ${shape.fieldNames}, got $actualFieldNames")
                }

            } catch (e: ClassNotFoundException) {
                failures.add("$bindingName: ClassNotFoundException — not yet implemented")
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Jenkins parity failures:\n${failures.joinToString("\n")}"
            )
        }
    }
}
