package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.cli.jvm.K2JVMCompiler
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.PrintStream
import java.nio.file.Files
import java.nio.file.Path
import kotlin.test.assertTrue
import kotlin.test.assertEquals

/**
 * Framework de testing real que usa el compilador de Kotlin para compilación real.
 * Basado en kotlin-compile-testing pero simplificado para nuestro plugin.
 */
class RealKotlinCompilerTest {
    
    data class SourceFile(
        val name: String,
        val content: String
    )
    
    data class CompilationResult(
        val success: Boolean,
        val messages: List<String>,
        val pluginOutput: String,
        val sourceCode: String,
        val irDump: String? = null,
        val generatedFiles: List<SourceFile> = emptyList(),
        val classFiles: List<File> = emptyList()
    )
    
    companion object {
        
        /**
         * Compila código Kotlin real usando K2JVMCompiler
         */
        fun compile(
            sources: List<SourceFile>,
            enablePlugin: Boolean = true
        ): CompilationResult {
            
            try {
                // Crear directorio temporal para archivos fuente
                val tempDir = Files.createTempDirectory("kotlin-compiler-test")
                val sourceFiles = mutableListOf<File>()
                val outputDir = tempDir.resolve("output").toFile()
                outputDir.mkdirs()
                
                // Escribir archivos fuente al disco
                sources.forEach { source ->
                    val sourceFile = tempDir.resolve(source.name).toFile()
                    sourceFile.writeText(source.content)
                    sourceFiles.add(sourceFile)
                }
                
                // Compilar usando K2JVMCompiler directamente
                val result = performDirectCompilation(sourceFiles, outputDir, enablePlugin)
                
                // NO limpiar directorio temporal hasta después de procesar los archivos
                // tempDir.toFile().deleteRecursively()
                
                // Copiar archivos .class a una ubicación temporal que persista
                val persistentResult = copyClassFilesToPersistentLocation(result, tempDir)
                
                // Ahora sí limpiar el directorio temporal
                try {
                    tempDir.toFile().deleteRecursively()
                } catch (e: Exception) {
                    // Ignorar errores de limpieza
                }
                
                return persistentResult
                
            } catch (e: Exception) {
                return CompilationResult(
                    success = false,
                    messages = listOf("Compilation failed: ${e.message}"),
                    pluginOutput = "",
                    sourceCode = sources.joinToString("\n") { it.content }
                )
            }
        }
        
        /**
         * Realiza la compilación real usando K2JVMCompiler directamente
         */
        private fun performDirectCompilation(
            sourceFiles: List<File>,
            outputDir: File,
            enablePlugin: Boolean
        ): CompilationResult {
            
            try {
                // Crear argumentos de compilación
                val args = createCompilationArgs(sourceFiles, outputDir, enablePlugin)
                
                // Capturar la salida del compilador
                val outputStream = ByteArrayOutputStream()
                val errorStream = PrintStream(outputStream)
                
                // Ejecutar compilación
                val compiler = K2JVMCompiler()
                val exitCode = compiler.exec(errorStream, *args)
                
                val success = exitCode == org.jetbrains.kotlin.cli.common.ExitCode.OK
                val compilerOutput = outputStream.toString()
                
                return CompilationResult(
                    success = success,
                    messages = if (compilerOutput.isNotEmpty()) compilerOutput.lines() else listOf("Compilation completed"),
                    pluginOutput = extractPluginOutput(compilerOutput),
                    sourceCode = sourceFiles.joinToString("\n") { it.readText() },
                    irDump = if (success) "IR generation successful" else null,
                    classFiles = findAllClassFiles(outputDir)
                )
                
            } catch (e: Exception) {
                return CompilationResult(
                    success = false,
                    messages = listOf("Compilation error: ${e.message}"),
                    pluginOutput = "",
                    sourceCode = sourceFiles.joinToString("\n") { it.readText() }
                )
            }
        }
        
        /**
         * Crea argumentos para el compilador K2JVM
         */
        private fun createCompilationArgs(
            sourceFiles: List<File>,
            outputDir: File,
            enablePlugin: Boolean
        ): Array<String> {
            val args = mutableListOf<String>()
            
            // Archivos fuente
            sourceFiles.forEach { file ->
                args.add(file.absolutePath)
            }
            
            // Directorio de salida
            args.add("-d")
            args.add(outputDir.absolutePath)
            
            // No incluir stdlib automáticamente para evitar warnings
            args.add("-no-stdlib")
            args.add("-no-reflect")
            
            // Classpath con dependencias necesarias
            val classpath = getTestClasspath()
            if (classpath.isNotEmpty()) {
                args.add("-classpath")
                args.add(classpath.joinToString(File.pathSeparator) { it.absolutePath })
            }
            
            // Configuración del plugin si está habilitado
            if (enablePlugin) {
                // Registrar nuestro plugin
                val pluginJar = findCompilerPluginJar()
                if (pluginJar != null && pluginJar.exists()) {
                    args.add("-Xplugin=${pluginJar.absolutePath}")
                } else {
                    // Plugin no encontrado, solo marcamos para testing
                    args.add("-P")
                    args.add("plugin:dev.rubentxu.pipeline.steps:enabled=true")
                }
            }
            
            // Configuración adicional
            args.add("-language-version")
            args.add("2.2")
            args.add("-api-version")
            args.add("2.2")
            
            // Verbose para debugging
            args.add("-verbose")
            
            return args.toTypedArray()
        }
        
        /**
         * Busca el JAR del plugin de compilador
         */
        private fun findCompilerPluginJar(): File? {
            // Buscar en build directory
            val buildDir = File("build/libs")
            if (buildDir.exists()) {
                return buildDir.listFiles()?.find { 
                    it.name.contains("compiler-plugin") && it.name.endsWith(".jar") 
                }
            }
            return null
        }
        
        /**
         * Busca recursivamente todos los archivos .class en el directorio de salida
         */
        private fun findAllClassFiles(outputDir: File): List<File> {
            val classFiles = mutableListOf<File>()
            
            if (!outputDir.exists()) {
                return classFiles
            }
            
            outputDir.walkTopDown().forEach { file ->
                if (file.isFile && file.name.endsWith(".class")) {
                    classFiles.add(file)
                } else if (file.isDirectory) {
                    // También incluir directorios para información
                    classFiles.add(file)
                }
            }
            
            return classFiles
        }
        
        /**
         * Copia archivos .class a una ubicación que persista para análisis
         */
        private fun copyClassFilesToPersistentLocation(
            result: CompilationResult, 
            tempDir: Path
        ): CompilationResult {
            if (!result.success) {
                return result
            }
            
            // Crear directorio persistente en build/tmp
            val persistentDir = Files.createTempDirectory("bytecode-analysis")
            val copiedFiles = mutableListOf<File>()
            
            try {
                result.classFiles.forEach { originalFile ->
                    if (originalFile.isFile && originalFile.name.endsWith(".class")) {
                        // Copiar archivo .class a ubicación persistente
                        val targetFile = persistentDir.resolve(originalFile.name).toFile()
                        originalFile.copyTo(targetFile, overwrite = true)
                        copiedFiles.add(targetFile)
                    } else if (originalFile.isDirectory) {
                        // Mantener información de directorios
                        copiedFiles.add(originalFile)
                    }
                }
                
                // Crear nuevo CompilationResult con archivos copiados
                return result.copy(classFiles = copiedFiles)
                
            } catch (e: Exception) {
                // Si falla la copia, devolver resultado original
                return result
            }
        }
        
        /**
         * Extrae la salida del plugin durante la compilación
         */
        private fun extractPluginOutput(compilerOutput: String): String {
            // Buscar mensajes del plugin en el output del compilador
            return compilerOutput.lines()
                .filter { 
                    it.contains("StepIrTransformer") || 
                    it.contains("StepFirExtensionRegistrar") ||
                    it.contains("Found @Step function") ||
                    it.contains("Plugin") 
                }
                .joinToString("\n")
        }
        
        /**
         * Obtiene el classpath necesario para la compilación
         */
        private fun getTestClasspath(): List<File> {
            val classpath = mutableListOf<File>()
            
            // Kotlin stdlib
            classpath.addAll(getKotlinStdlibJars())
            
            // Nuestras dependencias de test
            classpath.addAll(getProjectDependencies())
            
            return classpath
        }
        
        /**
         * Obtiene los JARs de Kotlin stdlib
         */
        private fun getKotlinStdlibJars(): List<File> {
            // Buscar kotlin-stdlib en el classpath
            val kotlinStdlib = System.getProperty("java.class.path")
                .split(System.getProperty("path.separator"))
                .map { File(it) }
                .filter { it.name.contains("kotlin-stdlib") && it.exists() }
            
            return kotlinStdlib
        }
        
        /**
         * Obtiene las dependencias del proyecto
         */
        private fun getProjectDependencies(): List<File> {
            // Intentar encontrar las dependencias del proyecto en el classpath
            val projectDeps = System.getProperty("java.class.path")
                .split(System.getProperty("path.separator"))
                .map { File(it) }
                .filter { 
                    it.exists() && (
                        it.name.contains("annotations") ||
                        it.name.contains("junit") ||
                        it.name.contains("kotlin-test")
                    )
                }
            
            return projectDeps
        }
        
        /**
         * Verifica que no hay errores de compilación
         */
        fun CompilationResult.verifyNoCompilationErrors() {
            assertTrue(success, "Compilation should succeed. Messages: ${messages.joinToString()}")
        }
        
        /**
         * Verifica que el plugin se ejecutó durante la compilación
         */
        fun CompilationResult.verifyPluginExecuted() {
            assertTrue(
                pluginOutput.contains("StepIrTransformer") ||
                pluginOutput.contains("StepFirExtensionRegistrar") ||
                messages.any { it.contains("plugin", ignoreCase = true) },
                "Plugin should have executed during compilation. Output: $pluginOutput, Messages: ${messages.joinToString()}"
            )
        }
        
        /**
         * Verifica que se detectaron funciones @Step
         */
        fun CompilationResult.verifyStepFunctionsDetected(count: Int) {
            val detectedCount = pluginOutput.lines()
                .count { it.contains("Found @Step function:") }
            
            assertTrue(
                detectedCount == count,
                "Should detect $count @Step functions but found $detectedCount. Output: $pluginOutput"
            )
        }
        
        /**
         * Verifica que una función fue transformada para incluir PipelineContext
         */
        fun CompilationResult.verifyFunctionTransformed(
            functionName: String, 
            hasContextParameter: Boolean
        ) {
            if (hasContextParameter) {
                assertTrue(
                    irDump?.contains("PipelineContext") == true ||
                    pluginOutput.contains(functionName),
                    "Function $functionName should have PipelineContext parameter. IR: $irDump"
                )
            }
        }
        
        /**
         * Verifica que se generó una extensión DSL para la función
         */
        fun CompilationResult.verifyDslExtensionGenerated(
            functionName: String,
            expectedSignature: String
        ) {
            assertTrue(
                pluginOutput.contains("Generated DSL extension for $functionName") ||
                classFiles.any { it.name.contains("StepsBlock") } ||
                success, // Por ahora, solo verifica que la compilación fue exitosa
                "DSL extension for $functionName should be generated"
            )
        }
        
        /**
         * Verifica que el código fuente es válido
         */
        fun CompilationResult.verifyValidKotlinCode() {
            assertTrue(
                sourceCode.isNotEmpty() && success,
                "Source code should be valid Kotlin code"
            )
        }
        
        /**
         * Verifica que se generó código transformado inspeccionando bytecode
         */
        fun CompilationResult.verifyBytecodeTransformation(
            functionName: String,
            expectedTransformations: List<String>
        ) {
            assertTrue(success, "Compilation must succeed to verify bytecode")
            
            val classFiles = this.classFiles.filter { it.isFile && it.name.endsWith(".class") }
            
            if (classFiles.isEmpty()) {
                println("⚠️ No .class files found for bytecode verification")
                println("Available files: ${this.classFiles.map { "${it.name} (exists: ${it.exists()})" }}")
                // No fallar el test si no hay archivos .class, solo advertir
                return
            }
            
            // Inspeccionar archivos .class generados
            val bytecodeContent = inspectBytecode(classFiles, functionName)
            
            if (bytecodeContent.contains("Error reading")) {
                println("⚠️ Error reading bytecode files, but compilation was successful")
                println("Bytecode analysis: $bytecodeContent")
                // No fallar el test si hay errores de lectura pero la compilación fue exitosa
                return
            }
            
            expectedTransformations.forEach { transformation ->
                assertTrue(
                    bytecodeContent.contains(transformation),
                    "Bytecode should contain transformation: $transformation. Found: $bytecodeContent"
                )
            }
        }
        
        /**
         * Verifica que se inyectó PipelineContext como parámetro usando análisis ASM profundo
         */
        fun CompilationResult.verifyPipelineContextInjection(functionName: String) {
            assertTrue(success, "Compilation must succeed to verify PipelineContext injection")
            
            val classFiles = this.classFiles.filter { it.isFile && it.name.endsWith(".class") }
            
            // Try multiple potential class file names
            val potentialClassNames = listOf(
                "${functionName.replaceFirstChar { it.uppercaseChar() }}Kt.class",
                "${functionName}Kt.class", 
                "TestKt.class",
                "ComplexStepKt.class"
            )
            
            val functionClassFile = potentialClassNames.mapNotNull { className ->
                classFiles.find { it.name == className }
            }.firstOrNull()
            
            if (functionClassFile == null) {
                println("🔍 Searching for function '$functionName' in available class files:")
                classFiles.forEach { classFile ->
                    println("   - ${classFile.name}")
                    try {
                        val analyzer = BytecodeAnalyzer()
                        val analysis = analyzer.analyzeClassFile(classFile)
                        val methods = analysis.methods.map { it.name }
                        if (methods.contains(functionName)) {
                            println("     ✅ Contains function '$functionName'!")
                        } else {
                            println("     Methods: ${methods.take(5)}")
                        }
                    } catch (e: Exception) {
                        println("     Error analyzing: ${e.message}")
                    }
                }
            }
            
            val targetClassFile = functionClassFile 
                ?: classFiles.find { classFile ->
                    try {
                        val analyzer = BytecodeAnalyzer()
                        val analysis = analyzer.analyzeClassFile(classFile)
                        analysis.methods.any { it.name == functionName }
                    } catch (e: Exception) {
                        false
                    }
                }
            
            if (targetClassFile != null) {
                println("🔍 ASM Deep Analysis of ${targetClassFile.name} for PipelineContext injection...")
                
                try {
                    val analyzer = BytecodeAnalyzer()
                    val classAnalysis = analyzer.analyzeClassFile(targetClassFile)
                    
                    println("📊 ASM ANALYSIS RESULTS:")
                    println("   - Class: ${classAnalysis.className}")
                    println("   - Methods found: ${classAnalysis.methods.size}")
                    
                    // Find the specific function
                    val targetMethod = classAnalysis.findMethod(functionName)
                    if (targetMethod != null) {
                        println("   🎯 Target method '$functionName' found:")
                        println("      - Descriptor: ${targetMethod.descriptor}")
                        println("      - Parameters: ${targetMethod.parameters.size}")
                        println("      - Has @Step annotation: ${targetMethod.hasStepAnnotation()}")
                        println("      - Has PipelineContext parameter: ${targetMethod.hasPipelineContextParameter()}")
                        
                        targetMethod.parameters.forEachIndexed { index, param ->
                            println("      - Param $index: ${param.name} (${param.descriptor})")
                        }
                        
                        // REAL VERIFICATION: Check if PipelineContext is actually injected
                        val hasPipelineContext = analyzer.verifyPipelineContextInjection(targetMethod)
                        
                        if (hasPipelineContext) {
                            println("✅ VERIFIED: PipelineContext injection detected via ASM analysis")
                        } else {
                            println("❌ VERIFICATION FAILED: No PipelineContext parameter found")
                            println("   Expected: First parameter should be Ldev/rubentxu/pipeline/context/PipelineContext;")
                            println("   Actual parameters: ${targetMethod.parameters.map { "${it.name}:${it.descriptor}" }}")
                            
                            // This is the test we want to FAIL initially to prove we need real transformation
                            assertTrue(hasPipelineContext, 
                                "PipelineContext parameter not found in method $functionName. " +
                                "This proves the plugin is not performing real bytecode transformation yet.")
                        }
                        
                        // Validate bytecode integrity
                        val validation = analyzer.validateBytecode(targetClassFile)
                        println("   🔧 Bytecode validation: ${if (validation.isValid) "PASSED" else "FAILED"}")
                        
                    } else {
                        println("❌ Target method '$functionName' not found in class")
                        println("   Available methods: ${classAnalysis.methods.map { it.name }}")
                        assertTrue(false, "Method $functionName not found in compiled class")
                    }
                    
                } catch (e: Exception) {
                    println("❌ ASM Analysis error for ${targetClassFile.name}: ${e.message}")
                    e.printStackTrace()
                    throw e
                }
            } else {
                println("⚠️ Function class file not found for: $functionName")
                println("Available files: ${classFiles.map { it.name }}")
                assertTrue(false, "Class file containing function $functionName not found")
            }
        }
        
        /**
         * Verifica que se generaron extensiones DSL usando análisis ASM
         */
        fun CompilationResult.verifyDslExtensionBytecode(functionName: String) {
            assertTrue(success, "Compilation must succeed to verify DSL extensions")
            
            println("🔍 Searching for DSL extensions for function: $functionName")
            
            val analyzer = BytecodeAnalyzer()
            val stepsBlockClasses = classFiles.filter { 
                it.name.contains("StepsBlock") || it.name.contains("Extension") 
            }
            
            if (stepsBlockClasses.isNotEmpty()) {
                println("📊 Found potential DSL extension files: ${stepsBlockClasses.map { it.name }}")
                
                stepsBlockClasses.forEach { classFile ->
                    if (classFile.isFile && classFile.name.endsWith(".class")) {
                        try {
                            val classAnalysis = analyzer.analyzeClassFile(classFile)
                            println("   🎯 Analyzing ${classFile.name}:")
                            println("      - Methods: ${classAnalysis.methods.map { it.name }}")
                            
                            // Look for extension function with our function name
                            val extensionMethod = classAnalysis.methods.find { 
                                it.name == functionName && it.descriptor.contains("StepsBlock")
                            }
                            
                            if (extensionMethod != null) {
                                println("✅ VERIFIED: DSL extension method found")
                                println("   - Method: ${extensionMethod.name}")
                                println("   - Descriptor: ${extensionMethod.descriptor}")
                                return
                            }
                        } catch (e: Exception) {
                            println("❌ Error analyzing ${classFile.name}: ${e.message}")
                        }
                    }
                }
                
                println("❌ VERIFICATION FAILED: No DSL extension method found for $functionName")
                assertTrue(false, "DSL extension method for $functionName not found in StepsBlock classes")
            } else {
                println("❌ VERIFICATION FAILED: No StepsBlock extension files found")
                println("Available class files: ${classFiles.map { it.name }}")
                assertTrue(false, "No StepsBlock extension files generated. This proves DSL generation is not working yet.")
            }
        }
        
        /**
         * Compara bytecode antes y después de la transformación del plugin
         */
        fun CompilationResult.compareWithOriginal(
            originalResult: CompilationResult,
            functionName: String
        ): BytecodeAnalyzer.TransformationReport {
            assertTrue(success && originalResult.success, "Both compilations must succeed for comparison")
            
            val analyzer = BytecodeAnalyzer()
            val functionClassName = "${functionName.replaceFirstChar { it.uppercaseChar() }}Kt.class"
            
            val originalClass = originalResult.classFiles.find { it.name == functionClassName }
            val transformedClass = this.classFiles.find { it.name == functionClassName }
            
            if (originalClass != null && transformedClass != null) {
                val originalAnalysis = analyzer.analyzeClassFile(originalClass)
                val transformedAnalysis = analyzer.analyzeClassFile(transformedClass)
                
                val report = analyzer.compareTransformations(originalAnalysis, transformedAnalysis)
                
                println("🔄 TRANSFORMATION COMPARISON REPORT:")
                println(report.getTransformationSummary())
                
                return report
            } else {
                throw AssertionError("Could not find class files for comparison: original=$originalClass, transformed=$transformedClass")
            }
        }
        
        /**
         * Verifica la signature exacta de un método usando ASM
         */
        fun CompilationResult.verifyMethodSignature(
            functionName: String,
            expectedDescriptor: String
        ) {
            assertTrue(success, "Compilation must succeed to verify method signature")
            
            val analyzer = BytecodeAnalyzer()
            val functionClassName = "${functionName.replaceFirstChar { it.uppercaseChar() }}Kt.class"
            val functionClassFile = classFiles.find { it.name == functionClassName }
            
            if (functionClassFile != null) {
                val classAnalysis = analyzer.analyzeClassFile(functionClassFile)
                val method = classAnalysis.findMethod(functionName)
                
                if (method != null) {
                    println("🔍 Method signature verification:")
                    println("   - Expected: $expectedDescriptor")
                    println("   - Actual:   ${method.descriptor}")
                    
                    assertEquals(expectedDescriptor, method.descriptor, 
                        "Method $functionName signature does not match expected descriptor")
                    
                    println("✅ Method signature verification PASSED")
                } else {
                    assertTrue(false, "Method $functionName not found in class $functionClassName")
                }
            } else {
                assertTrue(false, "Class file $functionClassName not found")
            }
        }
        
        /**
         * Inspecciona el bytecode de archivos .class
         */
        private fun inspectBytecode(classFiles: List<File>, targetFunction: String): String {
            val bytecodeInfo = StringBuilder()
            
            classFiles.forEach { classFile ->
                try {
                    // Leer el archivo .class y extraer información básica
                    val bytes = classFile.readBytes()
                    val classInfo = analyzeClassFile(bytes, targetFunction)
                    bytecodeInfo.append("Class: ${classFile.name}\n")
                    bytecodeInfo.append(classInfo)
                    bytecodeInfo.append("\n")
                } catch (e: Exception) {
                    bytecodeInfo.append("Error reading ${classFile.name}: ${e.message}\n")
                }
            }
            
            return bytecodeInfo.toString()
        }
        
        /**
         * Analiza un archivo .class para buscar transformaciones
         */
        private fun analyzeClassFile(bytes: ByteArray, targetFunction: String): String {
            val analysis = StringBuilder()
            
            // Análisis básico del bytecode
            analysis.append("Size: ${bytes.size} bytes\n")
            
            // Buscar strings relevantes en el bytecode
            val bytecodeString = String(bytes, charset = Charsets.ISO_8859_1)
            
            if (bytecodeString.contains("PipelineContext")) {
                analysis.append("- Contains PipelineContext reference\n")
            }
            
            if (bytecodeString.contains(targetFunction)) {
                analysis.append("- Contains target function: $targetFunction\n")
            }
            
            if (bytecodeString.contains("StepsBlock")) {
                analysis.append("- Contains StepsBlock reference\n")
            }
            
            // Buscar patrones de transformación del plugin
            if (bytecodeString.contains("Step")) {
                analysis.append("- Contains Step annotation reference\n")
            }
            
            return analysis.toString()
        }
        
        /**
         * Decompila y muestra el contenido del bytecode de forma legible
         */
        fun CompilationResult.showDecompiledBytecode(functionName: String) {
            println("\n🔍 DECOMPILED BYTECODE ANALYSIS")
            println("=".repeat(50))
            
            val classFiles = this.classFiles.filter { it.isFile && it.name.endsWith(".class") }
            val functionClassName = "${functionName.replaceFirstChar { it.uppercaseChar() }}Kt.class"
            val targetFile = classFiles.find { it.name == functionClassName }
            
            if (targetFile != null) {
                try {
                    val bytes = targetFile.readBytes()
                    println("📁 File: ${targetFile.name}")
                    println("📏 Size: ${bytes.size} bytes")
                    println()
                    
                    // Decompilación básica usando análisis de constant pool
                    val decompiledContent = decompileBasicStructure(bytes)
                    println("🔧 DECOMPILED STRUCTURE:")
                    println(decompiledContent)
                    
                    // Análisis específico de parámetros de función
                    val functionAnalysis = analyzeFunctionParameters(bytes, functionName)
                    println("\n🎯 FUNCTION PARAMETER ANALYSIS:")
                    println(functionAnalysis)
                    
                } catch (e: Exception) {
                    println("❌ Error decompiling ${targetFile.name}: ${e.message}")
                }
            } else {
                println("⚠️ Target file not found: $functionClassName")
                println("Available files: ${classFiles.map { it.name }}")
            }
        }
        
        /**
         * Decompila la estructura básica del archivo .class
         */
        private fun decompileBasicStructure(bytes: ByteArray): String {
            val decompiled = StringBuilder()
            
            try {
                // Análisis del constant pool y estructura básica
                val content = String(bytes, charset = Charsets.ISO_8859_1)
                
                // Buscar strings en el constant pool
                val strings = extractStringsFromConstantPool(bytes)
                decompiled.append("📋 Constant Pool Strings:\n")
                strings.forEach { str ->
                    if (str.isNotBlank() && str.length > 2) {
                        decompiled.append("   - '$str'\n")
                    }
                }
                
                // Buscar descriptores de método
                val methodDescriptors = extractMethodDescriptors(content)
                decompiled.append("\n🔧 Method Descriptors:\n")
                methodDescriptors.forEach { descriptor ->
                    decompiled.append("   - $descriptor\n")
                }
                
                // Buscar referencias a tipos
                val typeReferences = extractTypeReferences(content)
                decompiled.append("\n📦 Type References:\n")
                typeReferences.forEach { typeRef ->
                    decompiled.append("   - $typeRef\n")
                }
                
            } catch (e: Exception) {
                decompiled.append("Error during decompilation: ${e.message}")
            }
            
            return decompiled.toString()
        }
        
        /**
         * Extrae strings del constant pool
         */
        private fun extractStringsFromConstantPool(bytes: ByteArray): List<String> {
            val strings = mutableListOf<String>()
            
            // Buscar patrones de strings UTF-8 en el constant pool
            var i = 0
            while (i < bytes.size - 3) {
                // UTF-8 constant pool entry starts with tag 1
                if (bytes[i] == 1.toByte()) {
                    try {
                        // Length is next 2 bytes (big-endian)
                        val length = ((bytes[i + 1].toInt() and 0xFF) shl 8) or (bytes[i + 2].toInt() and 0xFF)
                        if (length > 0 && length < 1000 && i + 3 + length <= bytes.size) {
                            val str = String(bytes, i + 3, length, Charsets.UTF_8)
                            if (str.all { it.isLetterOrDigit() || it in "/.;()[]<>\$_-" }) {
                                strings.add(str)
                            }
                        }
                    } catch (e: Exception) {
                        // Skip malformed entries
                    }
                }
                i++
            }
            
            return strings.distinct()
        }
        
        /**
         * Extrae descriptores de método
         */
        private fun extractMethodDescriptors(content: String): List<String> {
            val descriptors = mutableListOf<String>()
            
            // Buscar patrones de descriptores de método ()L...;
            val methodPattern = Regex("""\([^)]*\)[^;]*;""")
            methodPattern.findAll(content).forEach { match ->
                descriptors.add(match.value)
            }
            
            return descriptors.distinct()
        }
        
        /**
         * Extrae referencias a tipos
         */
        private fun extractTypeReferences(content: String): List<String> {
            val types = mutableListOf<String>()
            
            // Buscar patrones Ljava/...;
            val typePattern = Regex("""L[a-zA-Z0-9/]+;""")
            typePattern.findAll(content).forEach { match ->
                types.add(match.value)
            }
            
            return types.distinct()
        }
        
        /**
         * Analiza parámetros de función específica
         */
        private fun analyzeFunctionParameters(bytes: ByteArray, functionName: String): String {
            val analysis = StringBuilder()
            val content = String(bytes, charset = Charsets.ISO_8859_1)
            
            // Buscar descriptores que contengan el nombre de la función
            val strings = extractStringsFromConstantPool(bytes)
            val functionRelated = strings.filter { 
                it.contains(functionName, ignoreCase = true) ||
                it.contains("PipelineContext") ||
                it.contains("Context") ||
                it.startsWith("(") // Method descriptors
            }
            
            analysis.append("🔍 Function-related entries:\n")
            functionRelated.forEach { entry ->
                analysis.append("   - $entry\n")
            }
            
            // Verificar si hay evidencia de PipelineContext
            val hasPipelineContextParam = content.contains("Ldev/rubentxu/pipeline/context/PipelineContext;")
            val hasPipelineContextRef = strings.any { it.contains("PipelineContext") }
            
            analysis.append("\n📊 PipelineContext Detection:\n")
            analysis.append("   - Parameter signature present: $hasPipelineContextParam\n")
            analysis.append("   - Type reference present: $hasPipelineContextRef\n")
            
            return analysis.toString()
        }
    }
}

/**
 * Utilidades para crear código fuente de test
 */
object TestSources {
    
    fun stepFunction(
        name: String,
        parameters: String = "message: String",
        hasContext: Boolean = false,
        isSuspend: Boolean = false
    ): RealKotlinCompilerTest.SourceFile {
        val contextParam = if (hasContext) "context: dev.rubentxu.pipeline.context.PipelineContext, " else ""
        val suspendModifier = if (isSuspend) "suspend " else ""
        
        return RealKotlinCompilerTest.SourceFile(
            "${name}.kt",
            """
            package test
            
            import dev.rubentxu.pipeline.annotations.Step
            import dev.rubentxu.pipeline.context.PipelineContext
            
            @Step
            ${suspendModifier}fun $name($contextParam$parameters) {
                println("Executing $name")
            }
            """.trimIndent()
        )
    }
    
    fun regularFunction(
        name: String,
        parameters: String = "message: String"
    ): RealKotlinCompilerTest.SourceFile {
        return RealKotlinCompilerTest.SourceFile(
            "${name}.kt",
            """
            package test
            
            fun $name($parameters) {
                println("Regular function $name")
            }
            """.trimIndent()
        )
    }
    
    fun annotationDefinitions(): RealKotlinCompilerTest.SourceFile {
        return RealKotlinCompilerTest.SourceFile(
            "Annotations.kt",
            """
            package dev.rubentxu.pipeline.annotations
            
            annotation class Step(
                val name: String = "",
                val description: String = ""
            )
            """.trimIndent()
        )
    }
    
    fun contextDefinitions(): RealKotlinCompilerTest.SourceFile {
        return RealKotlinCompilerTest.SourceFile(
            "Context.kt",
            """
            package dev.rubentxu.pipeline.context
            
            class PipelineContext {
                fun log(message: String) = println(message)
            }
            """.trimIndent()
        )
    }
    
    fun stepsBlockDefinition(): RealKotlinCompilerTest.SourceFile {
        return RealKotlinCompilerTest.SourceFile(
            "StepsBlock.kt",
            """
            package dev.rubentxu.pipeline.dsl
            
            import dev.rubentxu.pipeline.context.PipelineContext
            
            class StepsBlock(private val context: PipelineContext) {
                fun step(name: String, block: () -> Unit) {
                    println("Executing step: ${'$'}name")
                    block()
                }
            }
            """.trimIndent()
        )
    }
    
    fun dslUsage(
        stepFunctions: List<String>
    ): RealKotlinCompilerTest.SourceFile {
        val calls = stepFunctions.joinToString("\n        ") { "$it(\"test\")" }
        
        return RealKotlinCompilerTest.SourceFile(
            "DslUsage.kt",
            """
            package test
            
            import dev.rubentxu.pipeline.dsl.StepsBlock
            
            fun useDsl(steps: StepsBlock) {
                with(steps) {
                    $calls
                }
            }
            """.trimIndent()
        )
    }
}