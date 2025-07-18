# Step Compiler Plugin Test Suite

## Overview

This test suite validates the functionality of the Step Compiler Plugin using **Kotest BDD (Behavior-Driven Development)** style testing. The tests serve as both comprehensive validation and living documentation for the plugin's behavior.

## Architecture

The Step Compiler Plugin is a **Kotlin K2 IR transformer** that:

1. **Detects** functions annotated with `@Step`
2. **Transforms** their signatures by injecting `PipelineContext` as the first parameter
3. **Preserves** original function behavior and return types
4. **Handles** suspend functions correctly (accounting for `Continuation` parameter)
5. **Leaves** non-annotated functions unchanged

### Plugin Flow

```
@Step fun deploy(app: String) -> @Step fun deploy(context: PipelineContext, app: String)
                ↓
    Kotlin K2 IR Transformation
                ↓
    StepIrTransformer.transformFunction()
                ↓
    Bytecode with injected PipelineContext
```

## Test Structure

The tests are organized using Kotest's BDD style with clear **Given/When/Then** scenarios:

### Test Categories

1. **Plugin Infrastructure Tests**
   - JAR availability and accessibility
   - Build system integration

2. **Function Transformation Tests**
   - Basic `@Step` annotation detection
   - Parameter injection verification
   - Non-annotated function preservation

3. **Signature Handling Tests**
   - Regular functions with various parameter types
   - Suspend functions (with `Continuation` handling)
   - Functions with defaults, varargs, nullable parameters
   - Inline functions

4. **DSL Extension Generation Tests**
   - StepsBlock extension generation
   - Complex annotation metadata handling
   - Integration with pipeline DSL

## Key Test Scenarios

### ✅ Basic Transformation
```kotlin
// Input
@Step
fun buildProject(name: String): String = "Building $name"

// Expected Output (IR level)
// Parameters: PipelineContext + name = 2 total
```

### ✅ Suspend Function Handling  
```kotlin
// Input
@Step
suspend fun deployToCloud(app: String): Boolean = true

// Expected Output (IR level)  
// Parameters: PipelineContext + app + Continuation = 3 total
```

### ✅ Complex Signatures
```kotlin
// Input
@Step
suspend fun processData(
    required: String,
    optional: String = "default", 
    vararg items: String
): List<String>

// Expected Output (IR level)
// Parameters: PipelineContext + required + optional + varargs + Continuation = 5 total
```

## Running Tests

### Run All BDD Tests
```bash
gradle :pipeline-steps-system:compiler-plugin:test --tests "StepCompilerPluginBDDTest"
```

### Run Specific Scenario
```bash
gradle :pipeline-steps-system:compiler-plugin:test --tests "*plugin should transform*"
```

### Debug Mode
```bash
gradle :pipeline-steps-system:compiler-plugin:test --tests "StepCompilerPluginBDDTest" --debug
```

## Test Configuration

### Dependencies Required
- **Kotest**: BDD framework and assertions (`libs.bundles.kotest`)
- **Kotlin Compiler**: K2JVMCompiler for compilation testing
- **ASM**: Bytecode analysis for verification
- **Test Fixtures**: Mock classes for avoiding circular dependencies

### Test Environment Setup
- **Temporary directories** for isolated compilation
- **Classpath building** with annotations JAR and test fixtures
- **Plugin JAR detection** from build outputs
- **Kotlin stdlib** resolution for compilation

## Implementation Details

### Bytecode Analysis
The tests use ASM (Assembly) library to analyze generated bytecode:

```kotlin
val analyzer = BytecodeAnalyzer()
val analysis = analyzer.analyzeClassFile(compiledClass)
val method = analysis.findMethod("deployApp")

// Verify transformation
method.getParameterCount() shouldBe 3  // PipelineContext + 2 original
method.hasPipelineContextParameter() shouldBe true
```

### Connascence Patterns

The plugin addresses several **connascence** patterns:

1. **Connascence of Name**: Functions must maintain their original names for DSL generation
2. **Connascence of Position**: PipelineContext is always injected as the first parameter
3. **Connascence of Type**: Parameter types must be preserved for compatibility
4. **Connascence of Algorithm**: Suspend functions require special handling for Continuation

### Parameter Count Logic

| Function Type | Original | Transformed | Total Expected |
|---------------|----------|-------------|----------------|
| Regular       | N        | N + 1       | N + 1 (PipelineContext) |
| Suspend       | N        | N + 2       | N + 1 (PipelineContext) + 1 (Continuation) |

## Test Fixtures

### Mock Classes
- `LocalPipelineContext`: Mock context for testing
- `StepCategory`: Enum for step categorization  
- `PipelineContext`: Interface for dependency injection

### Avoiding Circular Dependencies
Test fixtures prevent circular dependencies between:
- `compiler-plugin` ← → `core` (where real implementations live)
- Minimal mocks provide just enough functionality for testing

## Integration Points

### With Core Module
- Real `PipelineContext` implementation in `core/`
- `StepsBlock` DSL extensions generated at runtime
- Context injection via Kotlin Context Parameters

### With Gradle Plugin
- Automatic registration during build process
- Step validation and metadata generation
- Integration with project compilation pipeline

## Debugging Tips

### Common Issues
1. **Plugin JAR not found**: Check build outputs and system properties
2. **Classpath missing dependencies**: Verify test-fixtures and annotations JARs
3. **Transformation not applied**: Enable debug logging to see IR transformation logs

### Debug Output
The plugin provides structured logging:
```
========================================
StepIrTransformerStable: Starting transformation for module: <main>
✅ StepIrTransformerStable: PipelineContext class found
🔍 StepIrTransformerStable: Found @Step function: deployApp
🔧 StepIrTransformerStable: Añadiendo parámetro PipelineContext a deployApp
✅ StepIrTransformerStable: Parámetro PipelineContext añadido a deployApp
========================================
```

## Future Enhancements

### Planned Test Coverage
- [ ] Error handling scenarios (malformed annotations)
- [ ] Performance testing with large codebases  
- [ ] Integration testing with actual StepsBlock DSL
- [ ] Cross-module step discovery testing

### Test Framework Evolution
- [ ] Property-based testing with Kotest Property
- [ ] Data-driven tests for parameter combinations
- [ ] Snapshot testing for IR transformation verification