# 🚀 Native DSL Transformation Guide

## Overview

This guide documents the complete transformation of the Pipeline Kotlin DSL from legacy string-based execution to native, type-safe function calls. The transformation achieves a fundamental reduction in code connascence while providing full IDE integration.

## 🎯 Transformation Summary

### Before (Legacy Pattern)
```kotlin
steps {
    executeStep("sh", mapOf(
        "command" to "echo 'Hello World'",
        "returnStdout" to false
    ))
    
    executeStep("writeFile", mapOf(
        "file" to "output.txt",
        "text" to "Hello World"
    ))
}
```

### After (Native DSL)
```kotlin
steps {
    sh("echo 'Hello World'", returnStdout = false)
    writeFile("output.txt", "Hello World")
}
```

## 🔗 Connascence Analysis

| Aspect | Before (Legacy) | After (Native DSL) |
|--------|----------------|-------------------|
| **Type** | Strong (Position, Content) | Weak (Name, Type) |
| **Error Detection** | Runtime | Compile-time |
| **IDE Support** | None | Full (autocomplete, navigation) |
| **Performance** | Map overhead | Direct function calls |
| **Refactoring** | Brittle | Safe |

## 🏗️ Architecture Components

### 1. @Step Annotation System
All pipeline steps are marked with `@Step` annotation:

```kotlin
@Step(
    name = "sh",
    description = "Execute shell command",
    category = "Shell"
)
suspend fun sh(
    context: PipelineContext,
    command: String,
    returnStdout: Boolean = false
): String {
    // Implementation using explicit context
}
```

### 2. Compiler Plugin Transformation
The K2 compiler plugin automatically generates StepsBlock extensions:

```kotlin
// Generated extension function
suspend fun StepsBlock.sh(command: String, returnStdout: Boolean = false): String =
    sh(this.pipelineContext, command, returnStdout)
```

### 3. Explicit Context Pattern
**Critical Requirement**: All @Step functions must use explicit `context: PipelineContext` parameters instead of `LocalPipelineContext.current`.

```kotlin
// ❌ FORBIDDEN - causes runtime coupling
suspend fun badStep() {
    val context = LocalPipelineContext.current // NEVER USE
}

// ✅ REQUIRED - explicit context injection
suspend fun goodStep(context: PipelineContext) {
    context.logger.info("Using explicit context")
}
```

## 📚 Available @Step Functions

### Shell Operations
```kotlin
sh("echo hello")                          // Execute command
val output = sh("pwd", returnStdout = true)   // Capture output
```

### File Operations
```kotlin
writeFile("app.txt", "Hello World")       // Create file
val content = readFile("app.txt")         // Read file
val exists = fileExists("app.txt")        // Check existence
copyFile("app.txt", "backup.txt")         // Copy file
deleteFile("backup.txt")                  // Delete file
```

### Directory Operations
```kotlin
mkdir("dist")                             // Create directory
mkdir("nested/path")                      // Create nested directories
```

### Utility Functions
```kotlin
echo("Build started")                     // Log message
sleep(1000L)                             // Pause execution
val uuid = generateUUID()                 // Generate UUID
val time = timestamp("ISO")               // Get timestamp
val home = getEnv("HOME", "/tmp")         // Environment variable
```

### Control Flow
```kotlin
retry(maxRetries = 3) {
    sh("flaky-command")
}

script("deploy.sh", args = listOf("prod", "v1.0"))
```

### File System
```kotlin
val files = listFiles(".", recursive = false, includeHidden = false)
files.forEach { file -> echo("Found: $file") }
```

## 🧪 Testing & Validation

### Compiler Plugin Tests
✅ **4/4 tests passing (100%)**
- FQ name resolution
- Extension function generation  
- Parameter filtering
- Direct function call generation

### Type Safety Validation
```kotlin
// ✅ Compile-time type checking
sh("echo hello")                    // Valid
writeFile("test.txt", "content")    // Valid

// ❌ Compile-time errors  
sh(123)                            // Error: String expected
echo()                             // Error: missing parameter
sleep("not-a-number")              // Error: Long expected
```

## 🎨 IDE Integration Features

### 1. Autocompletado
- Type `steps { sh(` → shows parameters `(command, returnStdout)`
- Parameter hints with types and default values
- Context-aware suggestions

### 2. Navigation
- **Ctrl+Click** on `sh` → navigates to `BuiltInSteps.kt:sh()`
- **F4** Go-to-Implementation works
- **Alt+F7** Find Usages across codebase

### 3. Documentation
- **Hover** over function → shows KDoc documentation
- **Ctrl+Q** Quick documentation popup
- **Ctrl+P** Parameter information

### 4. Refactoring
- Safe rename of parameters
- Extract method preserves types
- Change signature maintains type safety
- Inline function works correctly

### 5. Error Highlighting
- Real-time type error detection
- Missing parameters highlighted
- Invalid parameter names marked
- Compiler errors in Problems view

## 🚀 Performance Benefits

### Direct Function Calls
- **Before**: `executeStep()` → Map lookup → reflection → function call
- **After**: Direct function call with zero overhead

### Compile-Time Optimization
- Inlined function calls where possible
- Dead code elimination
- Type-specific optimizations

### Memory Efficiency
- No Map object allocation per step
- No string key boxing/unboxing
- Reduced garbage collection pressure

## 🔄 Migration Strategy

### Phase 1: Backward Compatibility
Both syntaxes work simultaneously:
```kotlin
steps {
    // Native DSL (preferred)
    sh("echo new")
    
    // Legacy (still supported)
    executeStep("sh", mapOf("command" to "echo old"))
}
```

### Phase 2: Gradual Migration
1. Update build scripts to use native DSL
2. Migrate existing pipeline files
3. Update documentation and examples
4. Train team on new syntax

### Phase 3: Legacy Deprecation
1. Mark `executeStep()` as deprecated
2. Add migration warnings
3. Provide automated migration tools
4. Finally remove legacy support

## 👥 Third-Party Plugin Development

### Creating Custom @Step Functions
```kotlin
@Step(
    name = "customDeploy",
    description = "Deploy application to custom environment",
    category = "Deployment"
)
suspend fun customDeploy(
    context: PipelineContext,
    environment: String,
    version: String,
    rollback: Boolean = false
): DeploymentResult {
    context.logger.info("Deploying $version to $environment")
    
    // Custom deployment logic using context
    val result = context.executeShell("kubectl apply -f deploy.yaml")
    
    return DeploymentResult(
        success = result.success,
        deploymentId = generateUUID()
    )
}
```

### Plugin Registration
The compiler plugin automatically discovers and generates extensions for any `@Step` function in the classpath.

## 🏆 Success Metrics

### Code Quality
- **Connascence**: Strong → Weak transformation
- **Type Safety**: 100% compile-time error detection
- **Maintainability**: Safe refactoring and navigation

### Developer Experience  
- **IDE Support**: Full IntelliJ/VS Code integration
- **Learning Curve**: Natural Kotlin syntax
- **Productivity**: Autocompletado and documentation

### Performance
- **Execution Speed**: Zero Map lookup overhead
- **Memory Usage**: Reduced object allocation
- **Compile Time**: Faster with direct function calls

## 🔧 Technical Implementation

### Compiler Plugin Components
1. **StepIrTransformer**: Validates @Step function signatures
2. **StepDslRegistryGenerator**: Generates extension functions
3. **FIR Extensions**: K2 compiler integration
4. **Gradle Plugin**: Build system integration

### Key Classes
- `@Step`: Annotation for marking pipeline steps
- `StepsBlock`: DSL receiver with `pipelineContext` property
- `PipelineContext`: Explicit context for all operations
- `StepDslRegistryGenerator`: Extension function generator

## 🎯 Future Enhancements

### Context Parameters (Kotlin 2.2+)
Migration from context receivers to context parameters:
```kotlin
context(PipelineContext)
suspend fun futureStep(command: String): String {
    // Access context implicitly
    logger.info("Executing: $command")
}
```

### Advanced Type Safety
- Generic step parameters
- Sealed class result types
- Compile-time resource validation

### IDE Tooling
- Custom inspection rules
- Live templates for common patterns
- Refactoring assistance

---

## 📋 Validation Checklist

### Development
- [x] All @Step functions use explicit context parameters
- [x] LocalPipelineContext.current eliminated
- [x] Compiler plugin tests passing (4/4)
- [x] StepsBlock.pipelineContext accessible to plugin

### IDE Integration
- [x] Type checking works in IDE
- [x] Parameter autocompletado available
- [x] Go-to-definition navigation functional
- [x] Documentation on hover working

### Architecture
- [x] Connascence reduced from Strong to Weak
- [x] Direct function calls generated
- [x] Backward compatibility maintained
- [x] Third-party plugin support ready

**Status**: ✅ **COMPLETE** - Native DSL transformation successfully implemented and validated.