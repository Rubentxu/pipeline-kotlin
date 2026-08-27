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
}
