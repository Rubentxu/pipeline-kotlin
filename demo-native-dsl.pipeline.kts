#!/usr/bin/env kotlinc -script

/**
 * Demo Pipeline: DSL Nativo vs Legacy
 * 
 * Este script demuestra la transformación completa del DSL desde el patrón legacy
 * executeStep("nombre", mapOf(...)) hacia llamadas nativas como sh("comando").
 * 
 * BENEFICIOS DEMOSTRADOS:
 * 1. 🎯 Type Safety: Errores detectados en compile-time
 * 2. 🚀 IDE Support: Autocompletado, navegación, refactoring
 * 3. 📈 Performance: Llamadas directas sin overhead de Map
 * 4. 🔧 Developer Experience: Sintaxis natural de Kotlin
 */

pipeline {
    stage("demo-native-dsl") {
        steps {
            // ========================================
            // 🆕 NUEVA SINTAXIS NATIVA (Type-Safe)
            // ========================================
            
            echo("🚀 Iniciando demo de DSL nativo")
            
            // Comandos shell con type safety
            sh("echo 'Compilando aplicación...'")
            val version = sh("date +%Y%m%d", returnStdout = true).trim()
            echo("Version generada: $version")
            
            // Operaciones de archivos type-safe
            writeFile("version.txt", version)
            writeFile("build.gradle", """
                plugins {
                    id 'java'
                }
                version = '$version'
                """.trimIndent())
            
            // Verificación de archivos
            val hasVersion = fileExists("version.txt")
            val hasBuild = fileExists("build.gradle")
            echo("Archivos creados: version=$hasVersion, build=$hasBuild")
            
            // Lectura de archivos
            val versionContent = readFile("version.txt")
            echo("Contenido version.txt: $versionContent")
            
            // Utilidades con parámetros tipados
            val uuid = generateUUID()
            val timestamp = timestamp("ISO")
            echo("UUID: $uuid")
            echo("Timestamp: $timestamp")
            
            // Gestión de directorios
            mkdir("dist")
            mkdir("temp/nested")
            
            // Variables de entorno
            val home = getEnv("HOME", "/tmp")
            val user = getEnv("USER", "unknown")
            echo("Usuario: $user en directorio: $home")
            
            // Listado de archivos con opciones tipadas
            val files = listFiles(".", recursive = false, includeHidden = false)
            echo("Archivos en directorio actual:")
            files.forEach { file -> echo("  - $file") }
            
            // Operaciones con retry y sleep
            retry(maxRetries = 3) {
                sh("echo 'Operación que podría fallar'")
                sleep(100L) // Pausa breve
            }
            
            // Script execution con argumentos tipados
            writeFile("test-script.sh", """
                #!/bin/bash
                echo "Script ejecutado con args: $@"
                echo "Arg 1: $1"
                echo "Arg 2: $2"
                """.trimIndent())
            
            script("test-script.sh", args = listOf("hello", "world"))
            
            // Operaciones de copia y eliminación
            copyFile("version.txt", "dist/version.txt")
            copyFile("build.gradle", "dist/build.gradle")
            
            // Cleanup con type safety
            deleteFile("test-script.sh")
            deleteFile("temp", recursive = true)
            
            echo("✅ Demo de DSL nativo completado exitosamente")
            
            // ========================================
            // 📜 SINTAXIS LEGACY (Para comparación)
            // ========================================
            
            echo("📜 Comparando con sintaxis legacy...")
            
            // Legacy: Sin type safety, propenso a errores
            executeStep("echo", mapOf("message" to "Esto es sintaxis legacy"))
            executeStep("sh", mapOf(
                "command" to "echo 'Sin autocompletado del IDE'",
                "returnStdout" to false
            ))
            
            // Legacy: Errores solo detectables en runtime
            // executeStep("sh", mapOf("commnad" to "typo en key")) // Error en runtime!
            // executeStep("echo", mapOf()) // Error: falta 'message' parameter
            
            echo("🔄 Ambas sintaxis coexisten durante la transición")
        }
    }
    
    stage("demo-type-safety") {
        steps {
            echo("🛡️ Demostrando Type Safety")
            
            // ✅ CORRECTO: Type-safe calls
            sh("ls -la")
            echo("Mensaje válido")
            sleep(500L)
            writeFile("test.txt", "contenido")
            val exists = fileExists("test.txt")
            
            // ❌ INCORRECTO: Estos causarían errores de compilación
            // sh(123)                    // Error: String esperado, no Int
            // echo()                     // Error: parámetro 'message' requerido
            // sleep("not-a-number")      // Error: Long esperado, no String
            // writeFile("file")          // Error: parámetro 'text' requerido
            // fileExists()               // Error: parámetro 'file' requerido
            
            echo("✅ Type safety verificado - errores detectados en compile-time")
            
            // Cleanup
            deleteFile("test.txt")
        }
    }
    
    stage("demo-ide-features") {
        steps {
            echo("🎯 Demostrando características IDE")
            
            // El IDE debería proporcionar:
            // 1. Autocompletado al escribir 'sh(' - muestra parámetros disponibles
            // 2. Documentación hover al pasar mouse sobre funciones
            // 3. Go-to-definition (Ctrl+Click) navega al @Step function
            // 4. Refactoring safe al renombrar parámetros
            // 5. Error highlighting para tipos incorrectos
            
            sh("echo 'IDE features:'")
            echo("- ✅ Autocompletado de parámetros")
            echo("- ✅ Documentación on-hover") 
            echo("- ✅ Go-to-definition navigation")
            echo("- ✅ Type error highlighting")
            echo("- ✅ Safe refactoring support")
            
            val info = """
            |DSL Transformation Summary:
            |==========================
            |Before: executeStep("sh", mapOf("command" -> cmd))
            |After:  sh(command = cmd)
            |
            |Benefits:
            |• Connascence: Position/Content → Name/Type  
            |• Performance: No Map overhead, direct calls
            |• IDE Support: Full IntelliJ/VS Code integration
            |• Type Safety: Compile-time error detection
            |• Developer UX: Natural Kotlin syntax
            """.trimMargin()
            
            writeFile("transformation-summary.txt", info)
            echo("📊 Resumen de transformación guardado en transformation-summary.txt")
            
            val summary = readFile("transformation-summary.txt")
            echo("Contenido del resumen:")
            echo(summary)
            
            deleteFile("transformation-summary.txt")
        }
    }
}

// ========================================
// 📋 CHECKLIST DE VALIDACIÓN IDE
// ========================================

/*
Para validar completamente el autocompletado y navegación IDE:

✅ 1. AUTOCOMPLETADO
   - Escribir 'steps { sh(' debería mostrar parámetros (command, returnStdout)
   - Escribir 'echo(' debería mostrar parámetro (message)
   - Autocompletado debe mostrar tipos y valores por defecto

✅ 2. TYPE CHECKING  
   - sh(123) debe mostrar error rojo en IDE
   - echo() sin parámetros debe mostrar error
   - sleep("text") debe mostrar error de tipo

✅ 3. NAVIGATION
   - Ctrl+Click en 'sh' debe navegar a BuiltInSteps.kt:sh()
   - Ctrl+Click en 'echo' debe navegar a BuiltInSteps.kt:echo()
   - F4 o Go-to-Implementation debe funcionar

✅ 4. DOCUMENTATION
   - Hover sobre 'sh' debe mostrar @param documentation
   - Quick documentation (Ctrl+Q) debe funcionar
   - Parameter hints (Ctrl+P) debe mostrar tipos

✅ 5. REFACTORING
   - Rename de parámetros debe ser safe
   - Extract method debe preservar tipos
   - Change signature debe mantener type safety

✅ 6. ERROR HIGHLIGHTING
   - Errores de tipo en tiempo real
   - Missing parameters resaltados
   - Invalid parameter names marcados
*/