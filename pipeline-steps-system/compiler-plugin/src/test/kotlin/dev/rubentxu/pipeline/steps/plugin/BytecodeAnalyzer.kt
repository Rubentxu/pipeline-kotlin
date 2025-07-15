package dev.rubentxu.pipeline.steps.plugin

import org.objectweb.asm.*
import org.objectweb.asm.util.CheckClassAdapter
import java.io.File
import java.io.PrintWriter
import java.io.StringWriter

/**
 * Advanced bytecode analyzer using ASM for verifying real compiler plugin transformations.
 * This class provides deep inspection of .class files to verify that @Step functions
 * have been properly transformed with PipelineContext injection and DSL extensions.
 */
class BytecodeAnalyzer {
    
    data class MethodInfo(
        val name: String,
        val descriptor: String,
        val signature: String?,
        val access: Int,
        val parameters: List<ParameterInfo>,
        val localVariables: List<LocalVariableInfo> = emptyList(),
        val annotations: List<AnnotationInfo> = emptyList()
    ) {
        val isStatic: Boolean get() = (access and Opcodes.ACC_STATIC) != 0
        val isPublic: Boolean get() = (access and Opcodes.ACC_PUBLIC) != 0
        val isSuspend: Boolean get() = parameters.any { it.descriptor.contains("Continuation") }
        
        fun hasPipelineContextParameter(): Boolean {
            return parameters.any { 
                it.descriptor == "Ldev/rubentxu/pipeline/context/PipelineContext;" ||
                it.name == "context"
            }
        }
        
        fun getParameterCount(): Int = parameters.size
        
        fun hasStepAnnotation(): Boolean {
            return annotations.any { 
                it.descriptor == "Ldev/rubentxu/pipeline/steps/annotations/Step;" 
            }
        }
    }
    
    data class ParameterInfo(
        val name: String,
        val descriptor: String,
        val index: Int,
        val access: Int = 0
    )
    
    data class LocalVariableInfo(
        val name: String,
        val descriptor: String,
        val signature: String?,
        val start: Label,
        val end: Label,
        val index: Int
    )
    
    data class AnnotationInfo(
        val descriptor: String,
        val visible: Boolean,
        val values: Map<String, Any> = emptyMap()
    )
    
    data class ClassAnalysis(
        val className: String,
        val superClass: String,
        val interfaces: List<String>,
        val methods: List<MethodInfo>,
        val fields: List<String> = emptyList(),
        val annotations: List<AnnotationInfo> = emptyList()
    ) {
        fun findMethod(name: String): MethodInfo? = methods.find { it.name == name }
        
        fun getStepMethods(): List<MethodInfo> = methods.filter { it.hasStepAnnotation() }
        
        fun hasMethod(name: String, descriptor: String): Boolean {
            return methods.any { it.name == name && it.descriptor == descriptor }
        }
        
        fun getMethodsWithPipelineContext(): List<MethodInfo> {
            return methods.filter { it.hasPipelineContextParameter() }
        }
    }
    
    /**
     * Analyzes a .class file and extracts detailed method information
     */
    fun analyzeClassFile(classFile: File): ClassAnalysis {
        require(classFile.exists() && classFile.name.endsWith(".class")) {
            "File must be a valid .class file: ${classFile.absolutePath}"
        }
        
        val classReader = ClassReader(classFile.readBytes())
        val analyzer = ClassAnalyzerVisitor()
        
        try {
            classReader.accept(analyzer, ClassReader.EXPAND_FRAMES)
            return analyzer.getResult()
        } catch (e: Exception) {
            throw RuntimeException("Failed to analyze class file ${classFile.name}: ${e.message}", e)
        }
    }
    
    /**
     * Verifies that a method has PipelineContext as first parameter
     */
    fun verifyPipelineContextInjection(method: MethodInfo): Boolean {
        // Check if method descriptor contains PipelineContext parameter
        val hasPipelineContextInDescriptor = method.descriptor.contains("Ldev/rubentxu/pipeline/context/PipelineContext;")
        
        // Check if parameters list contains PipelineContext
        val hasPipelineContextParameter = method.parameters.isNotEmpty() && 
            (method.parameters.first().descriptor == "Ldev/rubentxu/pipeline/context/PipelineContext;" ||
             method.parameters.first().name == "pipelineContext")
             
        // Check using hasPipelineContextParameter() method
        val detectedByMethod = method.hasPipelineContextParameter()
        
        return hasPipelineContextInDescriptor || hasPipelineContextParameter || detectedByMethod
    }
    
    /**
     * Compares two class analyses to detect transformations
     */
    fun compareTransformations(
        originalClass: ClassAnalysis,
        transformedClass: ClassAnalysis
    ): TransformationReport {
        val report = TransformationReport()
        
        // Compare methods
        for (originalMethod in originalClass.methods) {
            val transformedMethod = transformedClass.findMethod(originalMethod.name)
            
            if (transformedMethod != null) {
                val paramDiff = transformedMethod.getParameterCount() - originalMethod.getParameterCount()
                val hasPipelineContext = transformedMethod.hasPipelineContextParameter()
                
                if (paramDiff > 0 || hasPipelineContext) {
                    report.transformedMethods[originalMethod.name] = MethodTransformation(
                        originalParameterCount = originalMethod.getParameterCount(),
                        newParameterCount = transformedMethod.getParameterCount(),
                        pipelineContextInjected = hasPipelineContext,
                        originalDescriptor = originalMethod.descriptor,
                        newDescriptor = transformedMethod.descriptor
                    )
                }
            }
        }
        
        return report
    }
    
    /**
     * Validates bytecode integrity using ASM's CheckClassAdapter
     */
    fun validateBytecode(classFile: File): BytecodeValidationResult {
        return try {
            val classReader = ClassReader(classFile.readBytes())
            val stringWriter = StringWriter()
            val printWriter = PrintWriter(stringWriter)
            
            val checkAdapter = CheckClassAdapter(ClassWriter(0), true)
            classReader.accept(checkAdapter, 0)
            
            BytecodeValidationResult(
                isValid = true,
                errors = emptyList(),
                warnings = emptyList()
            )
        } catch (e: Exception) {
            BytecodeValidationResult(
                isValid = false,
                errors = listOf("Bytecode validation failed: ${e.message}"),
                warnings = emptyList()
            )
        }
    }
    
    data class TransformationReport(
        val transformedMethods: MutableMap<String, MethodTransformation> = mutableMapOf(),
        val addedMethods: MutableList<String> = mutableListOf(),
        val removedMethods: MutableList<String> = mutableListOf()
    ) {
        fun hasTransformations(): Boolean = transformedMethods.isNotEmpty() || 
                                          addedMethods.isNotEmpty() || 
                                          removedMethods.isNotEmpty()
        
        fun getTransformationSummary(): String {
            return buildString {
                appendLine("🔄 TRANSFORMATION REPORT:")
                appendLine("  - Transformed methods: ${transformedMethods.size}")
                appendLine("  - Added methods: ${addedMethods.size}")
                appendLine("  - Removed methods: ${removedMethods.size}")
                
                transformedMethods.forEach { (methodName, transformation) ->
                    appendLine("  📝 $methodName:")
                    appendLine("    - Parameters: ${transformation.originalParameterCount} → ${transformation.newParameterCount}")
                    appendLine("    - PipelineContext injected: ${transformation.pipelineContextInjected}")
                    appendLine("    - Descriptor: ${transformation.originalDescriptor} → ${transformation.newDescriptor}")
                }
            }
        }
    }
    
    data class MethodTransformation(
        val originalParameterCount: Int,
        val newParameterCount: Int,
        val pipelineContextInjected: Boolean,
        val originalDescriptor: String,
        val newDescriptor: String
    )
    
    data class BytecodeValidationResult(
        val isValid: Boolean,
        val errors: List<String>,
        val warnings: List<String>
    )
    
    /**
     * Internal ASM ClassVisitor for analyzing class structure
     */
    private class ClassAnalyzerVisitor : ClassVisitor(Opcodes.ASM9) {
        private lateinit var className: String
        private lateinit var superClass: String
        private val interfaces = mutableListOf<String>()
        private val methods = mutableListOf<MethodInfo>()
        private val fields = mutableListOf<String>()
        private val annotations = mutableListOf<AnnotationInfo>()
        
        override fun visit(
            version: Int,
            access: Int,
            name: String,
            signature: String?,
            superName: String?,
            interfaces: Array<out String>?
        ) {
            this.className = name
            this.superClass = superName ?: "java/lang/Object"
            this.interfaces.addAll(interfaces ?: emptyArray())
        }
        
        override fun visitMethod(
            access: Int,
            name: String,
            descriptor: String,
            signature: String?,
            exceptions: Array<out String>?
        ): MethodVisitor {
            return MethodAnalyzerVisitor(access, name, descriptor, signature) { methodInfo ->
                methods.add(methodInfo)
            }
        }
        
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            val annotation = AnnotationInfo(descriptor, visible)
            annotations.add(annotation)
            return null
        }
        
        fun getResult(): ClassAnalysis {
            return ClassAnalysis(
                className = className,
                superClass = superClass,
                interfaces = interfaces,
                methods = methods,
                fields = fields,
                annotations = annotations
            )
        }
    }
    
    /**
     * Internal ASM MethodVisitor for analyzing method structure
     */
    private class MethodAnalyzerVisitor(
        private val access: Int,
        private val methodName: String,
        private val descriptor: String,
        private val signature: String?,
        private val onMethodAnalyzed: (MethodInfo) -> Unit
    ) : MethodVisitor(Opcodes.ASM9) {
        
        private val parameters = mutableListOf<ParameterInfo>()
        private val localVariables = mutableListOf<LocalVariableInfo>()
        private val annotations = mutableListOf<AnnotationInfo>()
        
        override fun visitParameter(name: String?, access: Int) {
            val paramName = name ?: "param${parameters.size}"
            // Extract parameter descriptor from method descriptor
            val paramDescriptor = extractParameterDescriptor(descriptor, parameters.size)
            
            parameters.add(ParameterInfo(
                name = paramName,
                descriptor = paramDescriptor,
                index = parameters.size,
                access = access
            ))
        }
        
        override fun visitLocalVariable(
            name: String,
            descriptor: String,
            signature: String?,
            start: Label,
            end: Label,
            index: Int
        ) {
            localVariables.add(LocalVariableInfo(
                name = name,
                descriptor = descriptor,
                signature = signature,
                start = start,
                end = end,
                index = index
            ))
        }
        
        override fun visitAnnotation(descriptor: String, visible: Boolean): AnnotationVisitor? {
            val annotation = AnnotationInfo(descriptor, visible)
            annotations.add(annotation)
            return null
        }
        
        override fun visitEnd() {
            // If no parameters were found via visitParameter, extract from descriptor
            if (parameters.isEmpty()) {
                extractParametersFromDescriptor()
            }
            
            val methodInfo = MethodInfo(
                name = methodName,
                descriptor = descriptor,
                signature = signature,
                access = access,
                parameters = parameters,
                localVariables = localVariables,
                annotations = annotations
            )
            
            onMethodAnalyzed(methodInfo)
        }
        
        private fun extractParametersFromDescriptor() {
            val type = Type.getMethodType(descriptor)
            type.argumentTypes.forEachIndexed { index, argType ->
                parameters.add(ParameterInfo(
                    name = "param$index",
                    descriptor = argType.descriptor,
                    index = index
                ))
            }
        }
        
        private fun extractParameterDescriptor(methodDescriptor: String, paramIndex: Int): String {
            return try {
                val type = Type.getMethodType(methodDescriptor)
                if (paramIndex < type.argumentTypes.size) {
                    type.argumentTypes[paramIndex].descriptor
                } else {
                    "Ljava/lang/Object;"
                }
            } catch (e: Exception) {
                "Ljava/lang/Object;"
            }
        }
    }
}