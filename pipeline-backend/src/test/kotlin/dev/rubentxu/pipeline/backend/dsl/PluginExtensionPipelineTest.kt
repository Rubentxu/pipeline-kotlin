package dev.rubentxu.pipeline.backend.dsl

import dev.rubentxu.pipeline.testing.kotest.PipelineTestSpec
import dev.rubentxu.pipeline.testing.mocks.wildcard
import dev.rubentxu.pipeline.testing.MockResult
import io.kotest.matchers.shouldBe

/**
 * Tests for Pipeline DSL Plugin System functionality using the Pipeline Testing Framework
 * Validates plugin discovery, loading, security validation, and lifecycle management
 */
class PluginExtensionPipelineTest : PipelineTestSpec() {
    
    init {
        
        testPipeline(
            "Plugin Discovery and Loading - should discover and load plugins from JAR files",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("notification-plugin.jar")
                            load("database-plugin.jar")
                        }
                        
                        stages {
                            stage("Plugin Loading Test") {
                                steps {
                                    echo("Testing plugin discovery and loading...")
                                    
                                    // Steps provided by notification-plugin.jar
                                    sendNotification("slack", "#build", "Build started")
                                    sendEmail("team@company.com", "Build Status", "Build is running")
                                    
                                    // Steps provided by database-plugin.jar
                                    connectDatabase("postgresql://localhost:5432/testdb")
                                    executeQuery("SELECT COUNT(*) FROM builds")
                                    
                                    echo("All plugins loaded and working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock plugin loading
                mockStep("loadPlugin") {
                    customBehavior { args ->
                        val plugin = args["plugin"] as String
                        MockResult(output = "Plugin loaded successfully: $plugin")
                    }
                }
                
                // Mock notification plugin steps
                mockStep("sendNotification") {
                    returnExitCode(0)
                    returnOutput("Notification sent successfully")
                }
                
                mockStep("sendEmail") {
                    returnExitCode(0)
                    returnOutput("Email sent successfully")
                }
                
                // Mock database plugin steps
                mockStep("connectDatabase") {
                    returnExitCode(0)
                    returnOutput("Database connection established")
                }
                
                mockStep("executeQuery") {
                    returnExitCode(0)
                    returnOutput("Query result: 42")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                // Verify notification plugin steps
                stepWasCalled("sendNotification")
                stepCalledWith("sendNotification", mapOf(
                    "type" to "slack",
                    "channel" to "#build",
                    "message" to "Build started"
                ))
                
                stepWasCalled("sendEmail")
                stepCalledWith("sendEmail", mapOf(
                    "to" to "team@company.com",
                    "subject" to "Build Status",
                    "body" to "Build is running"
                ))
                
                // Verify database plugin steps
                stepWasCalled("connectDatabase")
                stepCalledWith("connectDatabase", mapOf("url" to "postgresql://localhost:5432/testdb"))
                
                stepWasCalled("executeQuery")
                stepCalledWith("executeQuery", mapOf("query" to "SELECT COUNT(*) FROM builds"))
            }
        )
        
        testPipeline(
            "Plugin Security Validation - should validate plugin signatures and bytecode",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("signed-plugin.jar") {
                                requireSignature = true
                                trustedAuthors = ["Company DevOps Team"]
                            }
                            load("verified-plugin.jar") {
                                checkBytecode = true
                                allowedPackages = ["com.company.plugins"]
                            }
                        }
                        
                        stages {
                            stage("Security Validation Test") {
                                steps {
                                    echo("Testing plugin security validation...")
                                    
                                    // Steps from signed plugin
                                    secureOperation("sensitive-data")
                                    
                                    // Steps from verified plugin
                                    verifiedStep("parameter")
                                    
                                    echo("Security validation passed for all plugins")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock security validation
                mockStep("validatePluginSecurity") {
                    customBehavior { args ->
                        val plugin = args["plugin"] as String
                        MockResult(output = "Security validation passed for: $plugin")
                    }
                }
                
                // Mock secure plugin steps
                mockStep("secureOperation") {
                    returnExitCode(0)
                    returnOutput("Secure operation completed")
                }
                
                mockStep("verifiedStep") {
                    returnExitCode(0)
                    returnOutput("Verified step executed")
                }
            },
            verificationBlock = {
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                stepWasCalled("secureOperation")
                stepCalledWith("secureOperation", mapOf("data" to "sensitive-data"))
                
                stepWasCalled("verifiedStep")
                stepCalledWith("verifiedStep", mapOf("parameter" to "parameter"))
                
                // All security validations should pass
                allStepsSucceeded()
            }
        )
        
        testPipeline(
            "Plugin Isolation - should isolate plugins using separate ClassLoaders",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("plugin-a.jar") {
                                isolationLevel = "CLASSLOADER"
                                allowedPackages = ["com.company.plugin.a"]
                            }
                            load("plugin-b.jar") {
                                isolationLevel = "CLASSLOADER"
                                allowedPackages = ["com.company.plugin.b"]
                            }
                        }
                        
                        stages {
                            stage("Plugin Isolation Test") {
                                steps {
                                    echo("Testing plugin isolation...")
                                    
                                    // Steps from plugin A - should not interfere with plugin B
                                    pluginAOperation("data-a")
                                    
                                    // Steps from plugin B - should not interfere with plugin A
                                    pluginBOperation("data-b")
                                    
                                    // Check isolation
                                    checkPluginIsolation()
                                    
                                    echo("Plugin isolation working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock plugin steps with different ClassLoaders
                mockStep("pluginAOperation") {
                    returnExitCode(0)
                    returnOutput("Plugin A operation completed (ClassLoader: PluginA)")
                }
                
                mockStep("pluginBOperation") {
                    returnExitCode(0)
                    returnOutput("Plugin B operation completed (ClassLoader: PluginB)")
                }
                
                mockStep("checkPluginIsolation") {
                    returnExitCode(0)
                    returnOutput("Plugins are properly isolated in separate ClassLoaders")
                }
            },
            verificationBlock = {
                stepWasCalled("pluginAOperation")
                stepCalledWith("pluginAOperation", mapOf("data" to "data-a"))
                
                stepWasCalled("pluginBOperation")
                stepCalledWith("pluginBOperation", mapOf("data" to "data-b"))
                
                stepWasCalled("checkPluginIsolation")
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
            }
        )
        
        testPipeline(
            "Plugin Lifecycle Management - should handle plugin initialization and cleanup",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("lifecycle-plugin.jar") {
                                initializeOnLoad = true
                                cleanupOnExit = true
                            }
                        }
                        
                        stages {
                            stage("Lifecycle Test") {
                                steps {
                                    echo("Testing plugin lifecycle management...")
                                    
                                    // Plugin should be initialized before this step
                                    checkPluginInitialization()
                                    
                                    // Use plugin functionality
                                    lifecycleOperation("test-data")
                                    
                                    echo("Plugin lifecycle test completed")
                                }
                            }
                        }
                        
                        post {
                            always {
                                echo("Pipeline post section - plugins should cleanup")
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock plugin lifecycle methods
                mockStep("initializePlugin") {
                    returnExitCode(0)
                    returnOutput("Plugin initialized successfully")
                }
                
                mockStep("checkPluginInitialization") {
                    returnExitCode(0)
                    returnOutput("Plugin is properly initialized")
                }
                
                mockStep("lifecycleOperation") {
                    returnExitCode(0)
                    returnOutput("Lifecycle operation completed")
                }
                
                mockStep("cleanupPlugin") {
                    returnExitCode(0)
                    returnOutput("Plugin cleaned up successfully")
                }
            },
            verificationBlock = {
                stepWasCalled("checkPluginInitialization")
                
                stepWasCalled("lifecycleOperation")
                stepCalledWith("lifecycleOperation", mapOf("data" to "test-data"))
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
                
                // Lifecycle steps should be called in correct order
                stepsCalledInOrder("checkPluginInitialization", "lifecycleOperation")
            }
        )
        
        testPipeline(
            "Hot Plugin Reloading - should support dynamic plugin reloading",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        stages {
                            stage("Hot Reload Test") {
                                steps {
                                    echo("Testing hot plugin reloading...")
                                    
                                    // Load initial plugin
                                    loadPlugin("dynamic-plugin.jar")
                                    dynamicStep("version-1")
                                    
                                    // Reload updated plugin
                                    reloadPlugin("dynamic-plugin.jar")
                                    dynamicStep("version-2")
                                    
                                    // Unload plugin
                                    unloadPlugin("dynamic-plugin.jar")
                                    
                                    echo("Hot reload functionality working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("loadPlugin") {
                    returnExitCode(0)
                    returnOutput("Plugin loaded successfully")
                }
                
                mockStep("reloadPlugin") {
                    returnExitCode(0)
                    returnOutput("Plugin reloaded with new version")
                }
                
                mockStep("unloadPlugin") {
                    returnExitCode(0)
                    returnOutput("Plugin unloaded successfully")
                }
                
                mockStep("dynamicStep") {
                    customBehavior { args ->
                        val version = args["version"] as String
                        MockResult(output = "Dynamic step executed with $version")
                    }
                }
            },
            verificationBlock = {
                stepWasCalled("loadPlugin")
                stepCalledWith("loadPlugin", mapOf("plugin" to "dynamic-plugin.jar"))
                
                stepWasCalled("reloadPlugin")
                stepCalledWith("reloadPlugin", mapOf("plugin" to "dynamic-plugin.jar"))
                
                stepWasCalled("unloadPlugin")
                stepCalledWith("unloadPlugin", mapOf("plugin" to "dynamic-plugin.jar"))
                
                stepWasCalled("dynamicStep")
                stepCallCount("dynamicStep", 2)
                stepCalledWith("dynamicStep", mapOf("version" to "version-1"))
                stepCalledWith("dynamicStep", mapOf("version" to "version-2"))
                
                // Operations should be in correct order
                stepsCalledInOrder("loadPlugin", "dynamicStep", "reloadPlugin", "dynamicStep", "unloadPlugin")
            }
        )
        
        testPipelineFailure(
            "Plugin Security Violation - should reject plugins with security issues",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("malicious-plugin.jar") {
                                requireSignature = true
                                checkBytecode = true
                                blockedPackages = ["java.lang.reflect"]
                            }
                        }
                        
                        stages {
                            stage("Security Violation Test") {
                                steps {
                                    echo("This should fail due to security violations")
                                    maliciousStep("payload")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock security validation failure
                mockStep("validatePluginSecurity") {
                    returnExitCode(1)
                    returnError("Security violation: Plugin contains malicious bytecode")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("Security violation") == true ||
                error.message?.contains("malicious") == true
            }
        )
        
        testPipelineFailure(
            "Plugin Conflict Resolution - should handle conflicting plugins",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("plugin-v1.jar")
                            load("plugin-v2.jar")  // Conflicts with v1
                        }
                        
                        stages {
                            stage("Conflict Test") {
                                steps {
                                    echo("This should fail due to plugin conflicts")
                                    conflictingStep("parameter")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock plugin conflict
                mockStep("detectPluginConflict") {
                    returnExitCode(1)
                    returnError("Plugin conflict detected: plugin-v1.jar and plugin-v2.jar both provide conflictingStep")
                }
            },
            expectedErrorMatch = { error ->
                error.message?.contains("Plugin conflict") == true ||
                error.message?.contains("conflictingStep") == true
            }
        )
        
        testPipeline(
            "Plugin Configuration - should handle plugin-specific configurations",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("configurable-plugin.jar") {
                                config {
                                    apiUrl = "https://api.example.com"
                                    timeout = 30000
                                    retryCount = 3
                                    enableLogging = true
                                }
                            }
                        }
                        
                        stages {
                            stage("Plugin Configuration Test") {
                                steps {
                                    echo("Testing plugin configuration...")
                                    
                                    // Plugin steps should use provided configuration
                                    apiCall("/users")
                                    apiCall("/builds", timeout = 60000)
                                    
                                    checkPluginConfig()
                                    
                                    echo("Plugin configuration working correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                // Mock configured plugin steps
                mockStep("apiCall") {
                    customBehavior { args ->
                        val endpoint = args["endpoint"] as String
                        val timeout = args["timeout"] as? Int ?: 30000
                        MockResult(output = "API call to $endpoint completed (timeout: ${timeout}ms)")
                    }
                }
                
                mockStep("checkPluginConfig") {
                    returnExitCode(0)
                    returnOutput("Plugin configuration: apiUrl=https://api.example.com, timeout=30000, retryCount=3")
                }
            },
            verificationBlock = {
                stepWasCalled("apiCall")
                stepCallCount("apiCall", 2)
                stepCalledWith("apiCall", mapOf("endpoint" to "/users"))
                stepCalledWith("apiCall", mapOf("endpoint" to "/builds", "timeout" to 60000))
                
                stepWasCalled("checkPluginConfig")
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
            }
        )
        
        testPipeline(
            "Plugin Dependencies - should handle plugin dependencies correctly",
            testBlock = {
                pipelineScriptContent("""
                    pipeline {
                        agent {
                            docker("openjdk:21")
                        }
                        
                        plugins {
                            load("base-plugin.jar") {
                                priority = 1
                            }
                            load("extension-plugin.jar") {
                                priority = 2
                                dependsOn = ["base-plugin"]
                            }
                        }
                        
                        stages {
                            stage("Plugin Dependencies Test") {
                                steps {
                                    echo("Testing plugin dependencies...")
                                    
                                    // Base plugin step (loaded first)
                                    baseOperation("foundation")
                                    
                                    // Extension plugin step (depends on base)
                                    extendedOperation("advanced", baseData = "foundation")
                                    
                                    echo("Plugin dependencies resolved correctly")
                                }
                            }
                        }
                    }
                """.trimIndent())
                
                mockStep("echo") {
                    returnOutput("")
                }
                
                mockStep("baseOperation") {
                    returnExitCode(0)
                    returnOutput("Base operation completed")
                }
                
                mockStep("extendedOperation") {
                    returnExitCode(0)
                    returnOutput("Extended operation completed using base data")
                }
            },
            verificationBlock = {
                stepWasCalled("baseOperation")
                stepCalledWith("baseOperation", mapOf("data" to "foundation"))
                
                stepWasCalled("extendedOperation")
                stepCalledWith("extendedOperation", mapOf("data" to "advanced", "baseData" to "foundation"))
                
                // Base plugin step should be called before extension plugin step
                stepsCalledInOrder("baseOperation", "extendedOperation")
                
                stepWasCalled("echo")
                stepCallCount("echo", 2)
            }
        )
    }
}