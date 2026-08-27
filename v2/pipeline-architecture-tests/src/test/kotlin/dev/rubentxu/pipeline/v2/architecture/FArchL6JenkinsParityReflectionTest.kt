package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * F-ARCH-L6-003: Jenkins verbatim signature assertion.
 *
 * Architecture test that enforces constructor parameter names match the Jenkins
 * credentials-binding catalog byte-for-byte for all 7 binding types.
 *
 * Jenkins catalog §1.6 (lines 109-115) defines the exact parameter names:
 * - string: credentialsId, variable
 * - usernamePassword: credentialsId, usernameVariable, passwordVariable
 * - sshUserPrivateKey: credentialsId, keyFileVariable, passphraseVariable, usernameVariable
 * - file: credentialsId, variable
 * - certificate: keystoreVariable, credentialsId, aliasVariable, passwordVariable
 * - zip: variable, credentialsId
 * - usernameColonPassword: variable, credentialsId
 *
 * RED: ClassNotFoundException (no CredentialsBinding types yet — T-07 will create them)
 * GREEN: After T-07, all 7 binding classes have exact Jenkins parameter names
 */
class FArchL6JenkinsParityReflectionTest {

    /**
     * Jenkins catalog parameter names for each binding type.
     * Extracted verbatim from JENKINS_FAMILIARITY_CATALOG.md §1.6 lines 109-115.
     */
    private val jenkinsCatalogParams = mapOf(
        "StringBinding" to setOf("credentialsId", "variable"),
        "UsernamePasswordBinding" to setOf("credentialsId", "usernameVariable", "passwordVariable"),
        "SshUserPrivateKeyBinding" to setOf("credentialsId", "keyFileVariable", "passphraseVariable", "usernameVariable"),
        "FileBinding" to setOf("credentialsId", "variable"),
        "CertificateBinding" to setOf("keystoreVariable", "credentialsId", "aliasVariable", "passwordVariable"),
        "ZipBinding" to setOf("variable", "credentialsId"),
        "UsernameColonPasswordBinding" to setOf("variable", "credentialsId")
    )

    /**
     * Reflection gate: verifies StringBinding constructor param names match catalog.
     *
     * RED: ClassNotFoundException (no StringBinding yet — T-07 will create it)
     * GREEN: After T-07, StringBinding has credentialsId, variable parameter names
     */
    @Test
    fun `string_binding_constructor_param_names_match_catalog`() {
        // Load the class — will throw ClassNotFoundException if not yet created
        val stringBindingClass = Class.forName(
            "dev.rubentxu.pipeline.v2.binding.StringBinding"
        )

        // Get primary constructor (first constructor)
        val constructor = stringBindingClass.constructors.firstOrNull()
            ?: throw AssertionError("StringBinding has no constructors")

        val paramNames = constructor.parameters.map { it.name }.toSet()
        val expected = jenkinsCatalogParams["StringBinding"]
            ?: throw IllegalStateException("Catalog missing StringBinding entry")

        assertEquals(
            expected,
            paramNames,
            "StringBinding constructor parameter names must match Jenkins catalog verbatim: $expected"
        )
    }

    /**
     * Full catalog verification for all 7 binding types.
     * Each binding class constructor must have parameter names matching catalog exactly.
     */
    @Test
    fun `all_binding_constructor_params_match_jenkins_catalog`() {
        val failures = mutableListOf<String>()

        for ((bindingName, expectedParams) in jenkinsCatalogParams) {
            try {
                val fqcn = "dev.rubentxu.pipeline.v2.binding.$bindingName"
                val bindingClass = Class.forName(fqcn)

                val constructor = bindingClass.constructors.firstOrNull()
                    ?: throw AssertionError("$bindingName has no constructors")

                val paramNames = constructor.parameters.map { it.name }.toSet()

                if (paramNames != expectedParams) {
                    failures.add(
                        "$bindingName: expected params $expectedParams, got $paramNames"
                    )
                }
            } catch (e: ClassNotFoundException) {
                // RED state — class doesn't exist yet (T-07 will create it)
                failures.add(
                    "$bindingName: ClassNotFoundException — class not yet created (T-07 will implement)"
                )
            }
        }

        if (failures.isNotEmpty()) {
            throw AssertionError(
                "Jenkins parity failures:\n${failures.joinToString("\n")}"
            )
        }
    }
}
