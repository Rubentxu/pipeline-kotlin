import dev.rubentxu.pipeline.error.*
import dev.rubentxu.pipeline.logger.DefaultLogger
import kotlinx.coroutines.runBlocking

fun main() = runBlocking {
    println("🔍 Testing Enhanced Error Reporting System")
    println("=" * 50)
    
    val logger = DefaultLogger()
    val enhancedEngine = EnhancedScriptEngine(logger)
    
    // Test 1: Script with syntax error
    println("\n📋 Test 1: Syntax Error Detection")
    val syntaxErrorScript = """
        pipeline {
            stages {
                stage("Build") {
                    steps {
                        echo("Hello World"
                    }
                }
            }
        
    """.trimIndent()
    
    val result1 = enhancedEngine.compileEnhanced(syntaxErrorScript, "syntax-error-test.kts")
    
    if (result1.enhancedErrors.isNotEmpty()) {
        println("✅ Syntax error detected:")
        result1.enhancedErrors.forEach { error ->
            println("  " + error.format(includeContext = true))
        }
    }
    
    // Test 2: Script with semantic error (unresolved reference)
    println("\n📋 Test 2: Semantic Error Detection")
    val semanticErrorScript = """
        pipeline {
            stages {
                stage("Build") {
                    steps {
                        unknownFunction("test")
                    }
                }
            }
        }
    """.trimIndent()
    
    val result2 = enhancedEngine.compileEnhanced(semanticErrorScript, "semantic-error-test.kts")
    
    if (result2.enhancedErrors.isNotEmpty()) {
        println("✅ Semantic error detected:")
        result2.enhancedErrors.forEach { error ->
            println("  " + error.format(includeContext = true))
        }
    }
    
    // Test 3: Valid script
    println("\n📋 Test 3: Valid Script Compilation")
    val validScript = """
        println("Hello, Enhanced Error System!")
    """.trimIndent()
    
    val result3 = enhancedEngine.compileEnhanced(validScript, "valid-script.kts")
    
    when {
        result3.enhancedErrors.isEmpty() -> println("✅ Valid script compiled successfully!")
        else -> {
            println("❌ Unexpected errors:")
            result3.enhancedErrors.forEach { error ->
                println("  " + error.formatCompact())
            }
        }
    }
    
    // Test 4: Test ErrorSuggestionEngine directly
    println("\n📋 Test 4: Error Suggestion Engine")
    val suggestions = ErrorSuggestionEngine.generateSuggestions(
        "unresolved reference: sh",
        "UNRESOLVED_REFERENCE"
    )
    
    if (suggestions.isNotEmpty()) {
        println("✅ Generated suggestions for 'unresolved reference: sh':")
        suggestions.forEach { suggestion ->
            println("  ${suggestion.type.symbol} ${suggestion.description}")
            suggestion.fixText?.let { fix ->
                println("    Fix: $fix")
            }
        }
    }
    
    // Test 5: Source Map functionality
    println("\n📋 Test 5: Source Map Generation")
    val sourceMap = result3.sourceMap
    if (sourceMap != null) {
        println("✅ Source map created for: ${sourceMap.originalFile}")
        println("  Mappings: ${sourceMap.mappings.size}")
        
        // Test mapping a position
        val mappedPosition = sourceMap.mapToOriginal(1, 0)
        mappedPosition?.let { pos ->
            println("  Mapped position (1,0) to: ${pos.line}:${pos.column}")
        }
    }
    
    println("\n🎉 Enhanced Error Reporting System test completed!")
    println("=" * 50)
}