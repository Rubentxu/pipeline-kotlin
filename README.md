# Pipeline Kotlin

[![Build Status](https://img.shields.io/badge/build-partial-yellow.svg)](https://github.com/rubentxu/pipeline-kotlin)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)
[![Kotlin](https://img.shields.io/badge/kotlin-2.4.10-blue.svg)](https://kotlinlang.org)
[![JDK](https://img.shields.io/badge/JDK-24-orange.svg)](https://openjdk.org)
[![Gradle](https://img.shields.io/badge/Gradle-8.14.5-green.svg)](https://gradle.org)

A modern, type-safe CI/CD engine built with Kotlin DSL, offering a powerful alternative to Jenkins/Groovy and YAML-based pipeline systems.

> **⚠️ Project status: pre-alpha, M0 baseline in progress.**
>
> Verified capabilities (2026-08-21):
> - `./gradlew help` passes — module graph + build scripts evaluate correctly
> - Kotlin 2.4.10 + Gradle 8.14.5 + JDK 24 toolchain installed via `asdf`
> - `gradle/libs.versions.toml` catalog: 56 libraries, 9 bundles, 8 plugins
>
> Unverified / pending:
> - `./gradlew build` end-to-end compilation (54 source files excluded in
>   `core/build.gradle.kts` — see `docs/v2/05-roadmap/IMPLEMENTATION_BACKLOG.md`
>   E0-01 for the unblock plan)
> - KSP processor (2.4.x not published by JetBrains/Google; latest is 2.3.11)
> - Shadow fat-jar (9.6.1 uses API removed in Gradle 8.14.5; plugin commented)
> - LSP4J 0.21.0 (pipeline-lsp-server pinned; catalog has 0.24.0)
>
> Coverage, runtime behaviour, native-image, container execution, and DSL
> end-to-end flow have NOT been validated in this baseline. The badges and
> feature list below reflect the *design intent*, not the verified state.

## Table of Contents

- [Features](#features)
- [Installation](#installation)
- [Quick Start](#quick-start)
- [Usage](#usage)
- [Architecture](#architecture)
- [Project Structure](#project-structure)
- [Testing](#testing)
- [Contributing](#contributing)
- [License](#license)
- [Acknowledgments](#acknowledgments)

## Features

- **🚀 Type-Safe Kotlin DSL**: Write pipeline configurations with full IDE support, autocompletion, and compile-time validation
- **🔒 Advanced Security**: Dual sandbox model using GraalVM Isolates for script execution and containers for task isolation
- **📦 Modular Architecture**: Clean separation of concerns with multiple modules (core, CLI, backend, config)
- **🔌 Extensible Plugin System**: Based on `java.util.ServiceLoader` with Maven dependency resolution
- **🎯 100% Test Coverage**: Comprehensive test suite ensuring reliability and maintainability
- **🐳 Container-First**: Native Docker support for agent-based task execution
- **⚡ High Performance**: Asynchronous execution with Kotlin coroutines
- **🛡️ Resource Management**: CPU, memory, and execution time limits with detailed monitoring
- **🏎️ Native Compilation**: GraalVM native image support for instant startup and reduced memory footprint

## Installation

### Prerequisites

- JDK 24 (Temurin recommended; project uses 24.0.2+12)
- Gradle 8.14.5 (the wrapper at `gradle/wrapper/gradle-wrapper.properties`
  pins this version)
- Docker (for container-based agents)
- **GraalVM CE 24+** (recommended for native compilation; not yet validated
  in this baseline)

#### Installing via asdf

```bash
asdf plugin add java https://github.com/halcyon/asdf-java.git
asdf plugin add gradle https://github.com/rfrancis/asdf-gradle.git
asdf install
```

The `.tool-versions` file pins:

```
java temurin-24.0.2+12
gradle 8.14.5
```

### Building from Source

```bash
git clone https://github.com/rubentxu/pipeline-kotlin.git
cd pipeline-kotlin
./gradlew help      # validate module graph + build script evaluation
./gradlew :core:check  # compile + test only the `core` module (others have
                       # known blockers — see Status block above)
```

> `gradle build` is NOT a working command in this baseline. Use `./gradlew`
> (the wrapper) and target specific modules until the E0-01 exclude cleanup
> lands.

### Building the CLI

> ⚠️ The Shadow plugin (`com.gradleup.shadow`) is currently commented in
> `pipeline-cli/build.gradle.kts` because version 9.6.1 uses
> `addVariantsFromConfiguration(Provider, Action)` — removed in Gradle
> 8.14.5. There is no published fix. The fat-JAR build is unavailable until
> a Shadow release supports Gradle 8.14.5, or the project migrates to an
> alternative packaging strategy.

## Quick Start

### Running with Java

```bash
java -jar pipeline-cli/build/libs/pipeline-cli-0.1.0-all.jar \
  -c pipeline-cli/testData/config.yaml \
  -s pipeline-cli/testData/success.pipeline.kts
```

### Native Image Compilation

For optimal performance and faster startup times, compile to a native executable using GraalVM:

#### Method 1: Using Gradle Plugin (Recommended)

```bash
# Build the shadow JAR first
gradle :pipeline-cli:shadowJar

# Compile to native executable using the configured plugin
gradle :pipeline-cli:nativeCompile
```

#### Method 2: Direct Native Image Command

```bash
# Build the shadow JAR
gradle :pipeline-cli:shadowJar

# Compile directly with native-image
/path/to/graalvm/bin/native-image \
  -cp pipeline-cli/build/libs/pipeline-cli.jar \
  dev.rubentxu.pipeline.cli.MainKt \
  --no-fallback \
  --report-unsupported-elements-at-runtime \
  -H:+ReportExceptionStackTraces \
  -H:+UnlockExperimentalVMOptions \
  -o pipeline-cli/build/native-standalone/pipeline-cli-native
```

#### Performance Benefits

- **Executable size**: ~31MB (vs 152MB JAR)
- **Startup time**: Near-instantaneous (vs JVM warmup)
- **Memory usage**: Significantly reduced
- **Compilation time**: ~37 seconds

#### Run the Native Executable

```bash
# Test the native executable
./pipeline-cli/build/native-standalone/pipeline-cli-native --help

# Execute pipeline scripts
./pipeline-cli/build/native-standalone/pipeline-cli-native run \
  -c pipeline-cli/testData/config.yaml \
  -s pipeline-cli/testData/success.pipeline.kts
```

## Usage

### Pipeline Structure

Create a pipeline script (`example.pipeline.kts`):

```kotlin
#!/usr/bin/env kotlin
import dev.rubentxu.pipeline.dsl.*
import pipeline.kotlin.extensions.*

pipeline {
    environment {
        "DISABLE_AUTH" += "true"
        "DB_ENGINE" += "sqlite"
    }
    
    stages {
        stage("Build") {
            steps {
                echo("Starting build...")
                sh("gradle build")
                
                // Parallel execution
                parallel(
                    "Unit Tests" to Step {
                        sh("gradle test")
                    },
                    "Integration Tests" to Step {
                        sh("gradle integrationTest")
                    }
                )
            }
            post {
                always {
                    echo("Build stage completed")
                }
                failure {
                    echo("Build failed!")
                }
            }
        }
        
        stage("Deploy") {
            steps {
                retry(3) {
                    sh("kubectl apply -f k8s/")
                }
            }
        }
    }
    
    post {
        success {
            echo("Pipeline completed successfully!")
        }
        failure {
            echo("Pipeline failed!")
        }
    }
}
```

### Key DSL Features

#### Environment Variables
```kotlin
environment {
    "KEY" += "value"
    "PATH" += "${env["PATH"]}:/custom/path"
}
```

#### Parallel Execution
```kotlin
parallel(
    "task1" to Step { /* ... */ },
    "task2" to Step { /* ... */ },
    "task3" to Step { /* ... */ }
)
```

#### Error Handling
```kotlin
retry(3) {
    // Code that might fail
}

delay(1000) {
    // Code to execute after delay
}
```

#### Built-in Steps
- `echo(message)`: Print messages
- `sh(command, returnStdout)`: Execute shell commands
- `readFile(path)`: Read file contents
- `writeFile(path, content)`: Write to files
- `delay(ms) { }`: Delay execution
- `retry(times) { }`: Retry on failure

## Architecture

### Multi-DSL Support

The system supports multiple DSL engines through a unified interface:

```kotlin
interface DslEngine<TResult : Any> {
    val engineId: String
    val supportedExtensions: Set<String>
    
    suspend fun compile(scriptFile: File, context: DslCompilationContext): DslCompilationResult<TResult>
    suspend fun execute(compiledScript: CompiledScript, context: DslExecutionContext): DslExecutionResult<TResult>
    suspend fun validate(scriptContent: String, context: DslCompilationContext): DslValidationResult
}
```

### Core Components

1. **DSL Manager**: Central orchestrator for all DSL operations
2. **Execution Engine**: Manages pipeline execution with resource limits
3. **Security Manager**: Implements dual sandbox model
4. **Plugin System**: Dynamic loading of extensions
5. **Resource Monitor**: Tracks CPU, memory, and execution time

### Security Model

- **Script Sandbox**: GraalVM Isolates with restricted policies
- **Task Sandbox**: Container-based isolation for commands
- **Resource Limits**: Configurable CPU, memory, and time constraints
- **Permission System**: Fine-grained access control

## Project Structure

```
pipeline-kotlin/
├── core/                    # Core engine, DSL, and security components
├── pipeline-cli/           # Command-line interface
├── pipeline-backend/       # REST API and web services
├── pipeline-config/        # Configuration management
├── lib-examples/           # Example libraries and plugins
├── gradle/                 # Build configuration
├── libs.versions.toml      # Centralized dependency versions
└── docs/                   # Documentation
```

## Testing

The project maintains 100% test coverage with 265 passing tests.

### Running Tests

```bash
# Run all tests
gradle test

# Run tests with coverage report
gradle test jacocoTestReport

# Run specific module tests
gradle :core:test
```

### Test Categories

- **Unit Tests**: Component-level testing with MockK
- **Integration Tests**: End-to-end pipeline execution
- **Security Tests**: Sandbox and permission validation
- **Performance Tests**: Resource limit verification

## Contributing

We welcome contributions! Please follow these guidelines:

### Development Workflow

1. Fork the repository
2. Create a feature branch (`git checkout -b feature/amazing-feature`)
3. Make your changes following TDD practices
4. Ensure all tests pass
5. Commit using conventional commits
6. Open a Pull Request

### Commit Convention

We follow [Conventional Commits](https://www.conventionalcommits.org/):

- `feat:` New features
- `fix:` Bug fixes
- `docs:` Documentation changes
- `test:` Test additions or modifications
- `refactor:` Code refactoring
- `chore:` Maintenance tasks

### Code Standards

- Follow Kotlin coding conventions
- Apply SOLID principles and Clean Code practices
- Maintain hexagonal architecture patterns
- Add comprehensive tests for new features
- Update documentation as needed

## License

This project is licensed under the MIT License:

```
MIT License

Copyright (c) 2025 Rubén Torres

Permission is hereby granted, free of charge, to any person obtaining a copy
of this software and associated documentation files (the "Software"), to deal
in the Software without restriction, including without limitation the rights
to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
copies of the Software, and to permit persons to whom the Software is
furnished to do so, subject to the following conditions:

The above copyright notice and this permission notice shall be included in all
copies or substantial portions of the Software.

THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
SOFTWARE.
```

## Acknowledgments

- Thanks to the Kotlin team for creating an amazing language
- Inspired by Jenkins Pipeline and GitHub Actions
- Built with ❤️ using:
  - [Kotlin](https://kotlinlang.org/) - Programming language
  - [Gradle](https://gradle.org/) - Build automation
  - [Kotest](https://kotest.io/) - Testing framework
  - [MockK](https://mockk.io/) - Mocking library
  - [GraalVM](https://www.graalvm.org/) - Advanced JVM for security features
  - [Docker](https://www.docker.com/) - Container platform

---

**Note**: This project is under active development. For questions or issues, please visit our [GitHub repository](https://github.com/rubentxu/pipeline-kotlin).