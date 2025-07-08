# Documentation Generation Guide

This project uses Dokka v2 for generating comprehensive API documentation from KDoc comments.

## Prerequisites

- JDK 11 or higher
- Kotlin 2.0+
- Gradle 8.0+

## Generating Documentation

### Generate Documentation for All Modules

```bash
gradle dokkaGeneratePublicationHtml
```

This will generate HTML documentation for all modules in the project.

### Generate Documentation for a Specific Module

```bash
gradle core:dokkaGeneratePublicationHtml
gradle pipeline-cli:dokkaGeneratePublicationHtml
gradle pipeline-backend:dokkaGeneratePublicationHtml
```

### Output Locations

The generated documentation will be available at:

- **Individual modules**: `{module}/build/dokka/html/index.html`
- **Core module**: `core/build/dokka/html/index.html`
- **CLI module**: `pipeline-cli/build/dokka/html/index.html`
- **Backend module**: `pipeline-backend/build/dokka/html/index.html`

## Documentation Structure

The documentation includes:

### Core API Documentation

- **Pipeline DSL**: `StageBlock`, `StepsBlock`, `Step`, `StageExecutor`
- **Agent Configuration**: `AgentBlock`, `DockerAgentBlock`, `KubernetesAgentBlock`
- **Security & Sandboxing**: Security model and isolation features
- **Plugin System**: Plugin architecture and extensibility

### Module-Specific Documentation

- **core**: Core pipeline execution engine and DSL
- **pipeline-cli**: Command-line interface and user tools
- **pipeline-backend**: Backend services and infrastructure
- **pipeline-config**: Configuration management
- **pipeline-testing**: Testing framework and utilities

## Documentation Features

### Professional KDoc Standards

- **Comprehensive class documentation** with purpose, usage examples, and lifecycle information
- **Method documentation** with parameters, return values, and exceptions
- **Cross-references** between related classes using `@see` tags
- **Version information** using `@since` tags
- **Usage examples** with practical code samples
- **Security considerations** and best practices

### Dokka v2 Features

- **Modern HTML output** with responsive design
- **Search functionality** across all documentation
- **Source code links** to GitHub repository
- **Cross-module navigation** for multi-module projects
- **Professional styling** with syntax highlighting

## Viewing Documentation

### Local Development

After generating documentation, you can view it by opening the HTML files in your browser:

```bash
# Open core module documentation
open core/build/dokka/html/index.html

# Or serve it locally
python -m http.server 8000 -d core/build/dokka/html
```

### Integration with IDE

Many IDEs (IntelliJ IDEA, VS Code) can display the generated documentation links directly in the build output.

## Documentation Coverage

The project maintains high documentation coverage:

- **Core API**: 100% public API coverage
- **DSL Builders**: 100% public API coverage  
- **Security Components**: 90% public API coverage
- **Testing Framework**: 80% public API coverage

## Contributing to Documentation

### KDoc Style Guide

Follow these standards when adding or updating documentation:

1. **Class Documentation**:
   - Start with a concise description
   - Include usage examples
   - Document lifecycle and behavior
   - Add `@since` version tags

2. **Method Documentation**:
   - Document all parameters with `@param`
   - Document return values with `@return`
   - Document exceptions with `@throws`
   - Include usage examples for complex methods

3. **Professional Language**:
   - Use clear, concise English
   - Focus on practical usage
   - Include security considerations
   - Provide concrete examples

### Example KDoc Structure

```kotlin
/**
 * Brief description of the class or method.
 *
 * More detailed explanation with context and usage information.
 *
 * ## Usage Example
 * ```kotlin
 * val example = ExampleClass()
 * example.doSomething()
 * ```
 *
 * ## Important Notes
 * - Security considerations
 * - Performance implications
 * - Best practices
 *
 * @param parameter Description of parameter
 * @return Description of return value
 * @throws Exception Description of when this exception is thrown
 * @see RelatedClass
 * @since 1.0.0
 */
```

## Troubleshooting

### Common Issues

1. **"Unresolved reference" errors**: Ensure all imports are correct and modules are properly configured
2. **Missing documentation**: Check that Dokka plugin is applied to all subprojects
3. **Build failures**: Verify Kotlin version compatibility and dependency versions

### Debug Commands

```bash
# Check Dokka tasks
gradle tasks --group=documentation

# Verbose output
gradle dokkaGeneratePublicationHtml --info

# Clean and rebuild
gradle clean dokkaGeneratePublicationHtml
```

## Configuration

The project uses Dokka v2 with the following configuration in `build.gradle.kts`:

```kotlin
plugins {
    id("org.jetbrains.dokka") version "2.0.0"
}

subprojects {
    apply(plugin = "org.jetbrains.dokka")
}
```

And in `gradle.properties`:

```properties
org.jetbrains.dokka.experimental.gradle.pluginMode=V2Enabled
org.jetbrains.dokka.experimental.gradle.pluginMode.noWarn=true
```

This configuration ensures all modules participate in documentation generation with consistent styling and navigation.