# Compiler Plugin

This module contains the K2 compiler plugin implementation for the Pipeline @Step system.

## Overview

The compiler plugin automatically transforms functions marked with `@Step` annotation to inject PipelineContext as the first parameter. This provides a seamless development experience similar to `@Composable` in Jetpack Compose.

## How It Works

### Before Transformation (Source Code)
```kotlin
@Step(name = "myStep", description = "My step", category = StepCategory.BUILD)
suspend fun myStep(parameter: String) {
    // PipelineContext is available automatically
    pipelineContext.logger.info("Executing step")
}
```

### After Transformation (Generated Code)
```kotlin
@Step(name = "myStep", description = "My step", category = StepCategory.BUILD)
suspend fun myStep(pipelineContext: PipelineContext, parameter: String) {
    // PipelineContext is now explicitly injected
    pipelineContext.logger.info("Executing step")
}
```

## Architecture

The compiler plugin follows the modern K2 architecture:

### Core Components
- **StepCompilerPluginRegistrar**: Main plugin registrar
- **StepFirExtensionRegistrar**: FIR (K2 Frontend) extension registration
- **StepIrTransformer**: IR transformation for context injection
- **StepContextParameterExtension**: Context parameter handling

### Testing Framework
Following the official template structure:
- `testData/box/`: Codegen tests that verify successful compilation
- `testData/diagnostics/`: Diagnostic tests that verify error reporting
- `generateTests` task: Automatically generates JUnit tests from testData

## Usage

### Testing

Run standard tests:
```bash
gradle :pipeline-steps-system:compiler-plugin:test
```

Generate tests from testData:
```bash
gradle :pipeline-steps-system:compiler-plugin:generateTests
```

Run performance tests:
```bash
gradle :pipeline-steps-system:compiler-plugin:performanceTest
```

### Development

Add test cases by creating `.kt` files in:
- `testData/box/` for compilation tests
- `testData/diagnostics/` for diagnostic tests

The `generateTests` task will automatically create corresponding JUnit tests.

## Features

### Automatic Context Injection
- Injects PipelineContext as the first parameter
- Maintains original function signature for user code
- Preserves all annotations and metadata

### Error Reporting
- Validates @Step usage (top-level functions only)
- Reports missing required parameters
- Provides clear diagnostic messages

### Performance
- Optimized for fast compilation
- Minimal overhead during transformation
- Efficient caching of analysis results

## Dependencies

- **plugin-annotations**: Core annotation definitions
- **core**: Runtime PipelineContext types (compile-only)
- **kotlin-compiler-embeddable**: K2 compiler APIs
- **JUnit 5**: Testing framework

## Configuration

The plugin is automatically configured when using the Gradle plugin. No manual configuration is required.

## Troubleshooting

### Enable Debug Logging
```bash
gradle build -Ddev.rubentxu.pipeline.steps.debug=true
```

### Check Generated Code
Generated step registry code is available in `build/generated/steps/`

### Common Issues
1. **@Step not working**: Ensure the Gradle plugin is applied
2. **Compilation errors**: Check that functions are top-level and suspend
3. **Missing context**: Verify the compiler plugin is properly loaded