package dev.rubentxu.pipeline.v2.dsl

import org.junit.jupiter.api.Assertions.*
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.DisplayName
import dev.rubentxu.pipeline.v2.domain.CredentialsId

/**
 * Tests for the withCredentials DSL and WithCredentialsBlock.
 *
 * These tests verify the DSL façade for Jenkins-style withCredentials binding.
 * The block desugars to StepSpec.WithCredentialsBlock with typed CredentialsBinding.
 *
 * Scenario CR-BD-001: string binding desugars correctly
 * Scenario CR-BD-002: usernamePassword binding desugars correctly
 * Scenario CR-BD-014: declarative environment(name, CredentialsId) desugars to withCredentials
 */
@DisplayName("PipelineDsl withCredentials tests")
class PipelineDslWithCredentialsTest {

    @Test
    fun `withCredentials string binding desugars to WithCredentialsBlock`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.string(
                                CredentialsId("github"),
                                "API_KEY"
                            )
                        )
                    ) {
                        sh("printenv API_KEY")
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.WithCredentialsBlock

        assertEquals("github", step.credentialsId.value)
        assertEquals("API_KEY", step.purpose)
        assertEquals(1, step.bindings.size)

        val binding = step.bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.STRING, binding.kind)
        assertEquals("github", binding.credentialsId.value)
        assertEquals("API_KEY", binding.variable)
    }

    @Test
    fun `withCredentials usernamePassword binding desugars correctly`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.usernamePassword(
                                CredentialsId("db-creds"),
                                "DB_USER",
                                "DB_PASS"
                            )
                        )
                    ) {
                        sh("echo \$DB_USER \$DB_PASS")
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.WithCredentialsBlock

        assertEquals("db-creds", step.credentialsId.value)
        assertEquals("DB_USER", step.purpose)

        val binding = step.bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.USERNAME_PASSWORD, binding.kind)
        assertEquals("DB_USER", binding.usernameVariable)
        assertEquals("DB_PASS", binding.passwordVariable)
    }

    @Test
    fun `environment credentialsId variable desugars to withCredentials`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    environment(CredentialsId("github"), "GITHUB_TOKEN") {
                        sh("echo \$GITHUB_TOKEN")
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.WithCredentialsBlock

        assertEquals("github", step.credentialsId.value)
        assertEquals("GITHUB_TOKEN", step.purpose)
        assertEquals(1, step.bindings.size)

        val binding = step.bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.STRING, binding.kind)
        assertEquals("GITHUB_TOKEN", binding.variable)
    }

    @Test
    fun `withCredentials block contains inner steps`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.string(
                                CredentialsId("api-key"),
                                "SECRET"
                            )
                        )
                    ) {
                        sh("curl https://api.example.com")
                        echo("Done")
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.WithCredentialsBlock

        assertEquals(2, step.steps.size)
        assertTrue(step.steps[0] is StepSpec.Shell)
        assertTrue(step.steps[1] is StepSpec.Echo)
    }

    @Test
    fun `WithCredentialsBlock has correct name and type`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.string(
                                CredentialsId("test"),
                                "VAR"
                            )
                        )
                    ) {
                        sh("echo test")
                    }
                }
            }
        }

        val stage = spec.stages.single()
        val step = stage.steps.single() as StepSpec.WithCredentialsBlock

        assertEquals("withCredentials", step.name)
        assertEquals("withCredentials", step.type)
    }

    // =========================================================================
    // ML-R10: 5 NEW factories × kind desugar (Dsl-Widening-CR-4..8)
    // =========================================================================

    @Test
    fun `sshUserPrivateKey factory desugars to Kind_SSH_USER_PRIVATE_KEY`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.sshUserPrivateKey(
                                CredentialsId("ssh-key"),
                                "SSH_KEY_FILE"
                            )
                        )
                    ) {
                        sh("cat \$SSH_KEY_FILE")
                    }
                }
            }
        }

        val step = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock)
        val binding = step.bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.SSH_USER_PRIVATE_KEY, binding.kind)
        assertEquals("SSH_KEY_FILE", binding.keyFileVariable)
        assertNull(binding.passphraseVariable)
        assertNull(binding.usernameVariable)
    }

    @Test
    fun `sshUserPrivateKey factory with optional args persists variable names`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.sshUserPrivateKey(
                                CredentialsId("ssh-key"),
                                "SSH_KEY_FILE",
                                "SSH_PP",
                                "SSH_USER"
                            )
                        )
                    ) {
                        sh("cat \$SSH_KEY_FILE")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals("SSH_KEY_FILE", binding.keyFileVariable)
        assertEquals("SSH_PP", binding.passphraseVariable)
        assertEquals("SSH_USER", binding.usernameVariable)
    }

    @Test
    fun `file factory desugars to Kind_FILE`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.file(
                                CredentialsId("pem"),
                                "DEPLOY_PEM"
                            )
                        )
                    ) {
                        sh("cat \$DEPLOY_PEM")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.FILE, binding.kind)
        assertEquals("DEPLOY_PEM", binding.variable)
    }

    @Test
    fun `certificate factory desugars to Kind_CERTIFICATE`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.certificate(
                                CredentialsId("keystore"),
                                "KEYSTORE"
                            )
                        )
                    ) {
                        sh("ls \$KEYSTORE")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.CERTIFICATE, binding.kind)
        assertEquals("KEYSTORE", binding.keystoreVariable)
        assertNull(binding.aliasVariable)
        assertNull(binding.passwordVariable)
    }

    @Test
    fun `certificate factory with optional args persists all variable names`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.certificate(
                                CredentialsId("keystore"),
                                "KEYSTORE",
                                "KEY_ALIAS",
                                "KEY_PASS"
                            )
                        )
                    ) {
                        sh("ls \$KEYSTORE")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals("KEYSTORE", binding.keystoreVariable)
        assertEquals("KEY_ALIAS", binding.aliasVariable)
        assertEquals("KEY_PASS", binding.passwordVariable)
    }

    @Test
    fun `zip factory desugars to Kind_ZIP`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.zip(
                                CredentialsId("zip-archive"),
                                "ZIP_PATH"
                            )
                        )
                    ) {
                        sh("unzip \$ZIP_PATH")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.ZIP, binding.kind)
        assertEquals("ZIP_PATH", binding.variable)
    }

    @Test
    fun `usernameColonPassword factory desugars to Kind_USERNAME_COLON_PASSWORD`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.usernameColonPassword(
                                CredentialsId("db"),
                                "U_P"
                            )
                        )
                    ) {
                        sh("echo \$U_P")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertEquals(StepSpec.CredentialsBinding.Kind.USERNAME_COLON_PASSWORD, binding.kind)
        assertEquals("U_P", binding.variable)
    }

    // =========================================================================
    // Ergonomic shape tests: parameter names match Jenkins verbatim, optionals nullable
    // =========================================================================

    @Test
    fun `sshUserPrivateKey optional args default to null`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.sshUserPrivateKey(
                                CredentialsId("ssh-key"),
                                "SSH_KEY_FILE"
                            )
                        )
                    ) {
                        sh("cat \$SSH_KEY_FILE")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertNull(binding.passphraseVariable)
        assertNull(binding.usernameVariable)
    }

    @Test
    fun `certificate optional args default to null`() {
        val spec = pipeline {
            stages {
                stage("Test") {
                    withCredentials(
                        listOf(
                            StepSpec.CredentialsBinding.certificate(
                                CredentialsId("keystore"),
                                "KEYSTORE"
                            )
                        )
                    ) {
                        sh("ls \$KEYSTORE")
                    }
                }
            }
        }

        val binding = (spec.stages.single().steps.single() as StepSpec.WithCredentialsBlock).bindings.single()
        assertNull(binding.aliasVariable)
        assertNull(binding.passwordVariable)
    }

    // =========================================================================
    // Variable-name persistence tests: factory carries variable name into payload
    // =========================================================================

    @Test
    fun `sshUserPrivateKey carries keyFileVariable into binding payload`() {
        val binding = StepSpec.CredentialsBinding.sshUserPrivateKey(
            CredentialsId("ssh-creds"),
            "SSH_KEY_FILE"
        )
        assertEquals("SSH_KEY_FILE", binding.keyFileVariable)
        assertEquals(CredentialsId("ssh-creds"), binding.credentialsId)
    }

    @Test
    fun `certificate carries keystoreVariable into binding payload`() {
        val binding = StepSpec.CredentialsBinding.certificate(
            CredentialsId("cert-creds"),
            "KEYSTORE"
        )
        assertEquals("KEYSTORE", binding.keystoreVariable)
        assertEquals(CredentialsId("cert-creds"), binding.credentialsId)
    }
}
