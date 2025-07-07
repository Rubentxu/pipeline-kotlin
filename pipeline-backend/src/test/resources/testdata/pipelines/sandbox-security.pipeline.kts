#!/usr/bin/env kotlin

pipeline {
    agent {
        docker("openjdk:21")
    }
    
    sandbox {
        isolationLevel = "PROCESS"
        resourceLimits {
            maxMemoryMB = 512
            maxCpuTimeMs = 30000
            maxWallTimeMs = 60000
            maxThreads = 10
        }
        securityPolicy = "STRICT"
        allowedPaths = ["/workspace", "/tmp"]
        networkPolicy {
            allowOutbound = false
            allowedHosts = ["github.com", "maven.central.org"]
        }
        monitorViolations = true
    }
    
    environment {
        "SECURE_MODE" += "true"
        "LOG_LEVEL" += "DEBUG"
    }
    
    stages {
        stage("Security Validation") {
            steps {
                echo("Testing sandbox security controls...")
                
                // Basic operations that should work
                sh("echo 'Hello from secure sandbox'")
                sh("pwd")
                sh("whoami")
                
                // File operations within allowed paths
                writeFile("security-test.txt", "This is a security test")
                writeFile("/tmp/temp-file.txt", "Temporary file")
                
                val content = readFile("security-test.txt")
                echo("File content: " + content)
                
                echo("Basic security validation passed")
            }
        }
        
        stage("Resource Monitoring") {
            steps {
                echo("Testing resource monitoring...")
                
                // Operations that should be monitored but allowed
                sh("sleep 2")  // Should not exceed time limits
                
                // Memory usage test
                writeFile("large-file.txt", "x".repeat(1000))  // Small file, should be allowed
                
                // CPU usage test
                sh("for i in {1..10}; do echo $i; done")  // Light CPU usage
                
                // Check resource usage
                checkResourceUsage()
                
                echo("Resource monitoring working correctly")
            }
        }
        
        stage("Network Access Control") {
            steps {
                echo("Testing network access control...")
                
                try {
                    // This should be blocked
                    sh("curl http://google.com")
                    echo("ERROR: Should not reach here")
                } catch (e: Exception) {
                    echo("Correctly blocked access to google.com")
                }
                
                try {
                    // This should be allowed
                    sh("curl https://github.com/api")
                    echo("Successfully accessed allowed host")
                } catch (e: Exception) {
                    echo("Network access to allowed host failed: " + e.message)
                }
                
                echo("Network access control working correctly")
            }
        }
        
        stage("File Access Control") {
            steps {
                echo("Testing file access control...")
                
                // Allowed operations
                dir("/tmp") {
                    writeFile("temp-test.txt", "temporary content")
                    val tempContent = readFile("temp-test.txt")
                    echo("Temp file content: " + tempContent)
                }
                
                try {
                    // This should be blocked
                    readFile("/etc/passwd")
                    echo("ERROR: Should not be able to read /etc/passwd")
                } catch (e: Exception) {
                    echo("Correctly blocked access to /etc/passwd")
                }
                
                echo("File access control working correctly")
            }
        }
        
        stage("Process Isolation") {
            steps {
                echo("Testing process isolation...")
                
                // Check process isolation
                val pid = sh("echo $$", returnStdout = true)
                echo("Running in process: " + pid)
                
                val processes = sh("ps aux | wc -l", returnStdout = true)
                echo("Process count: " + processes)
                
                // Verify sandbox boundaries
                checkSandboxBoundaries()
                
                echo("Process isolation working correctly")
            }
        }
        
        stage("Security Violation Detection") {
            steps {
                echo("Testing security violation detection...")
                
                // Normal operations should not trigger violations
                sh("echo 'normal operation'")
                writeFile("normal.txt", "normal content")
                
                // Check for any security violations
                val violations = checkSecurityViolations()
                
                if (violations.isEmpty()) {
                    echo("No security violations detected")
                } else {
                    echo("Security violations found: " + violations)
                    error("Security violations detected!")
                }
                
                echo("Security violation detection working correctly")
            }
        }
    }
    
    post {
        always {
            echo("Cleaning up sandbox resources...")
            cleanupSandbox()
            
            // Final security report
            generateSecurityReport()
        }
        
        success {
            echo("All sandbox security tests passed")
        }
        
        failure {
            echo("Sandbox security test failed - check violations")
            dumpSecurityLog()
        }
    }
}