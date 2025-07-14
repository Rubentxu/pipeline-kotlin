package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.types.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter

/**
 * FIR checker for validating @Step function declarations.
 * 
 * This checker validates that @Step functions follow the required conventions:
 * - Don't manually declare PipelineContext parameter
 * - Use supported parameter types
 * - Have valid return types
 * - Follow naming conventions
 * - Have appropriate visibility
 */
class StepFunctionChecker(private val session: FirSession) : FirSimpleFunctionChecker() {
    
    companion object {
        private val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.steps.annotations.Step"))
        private val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.context.PipelineContext"))
        
        // Supported parameter types for @Step functions
        private val SUPPORTED_PARAMETER_TYPES = setOf(
            "kotlin.String",
            "kotlin.Int", 
            "kotlin.Boolean",
            "kotlin.collections.List",
            "kotlin.collections.Map",
            "java.io.File",
            "kotlin.Array"
        )
        
        // Supported return types for @Step functions
        private val SUPPORTED_RETURN_TYPES = setOf(
            "kotlin.Unit",
            "kotlin.String",
            "dev.rubentxu.pipeline.steps.StepResult",
            "dev.rubentxu.pipeline.data.PipelineData"
        )
    }
    
    override fun check(
        declaration: FirSimpleFunction, 
        context: CheckerContext, 
        reporter: DiagnosticReporter
    ) {
        // Check if function has @Step annotation
        if (declaration.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session)) {
            validateStepFunction(declaration, context, reporter)
        }
    }
    
    private fun validateStepFunction(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // 1. Validate function name
        validateFunctionName(function, context, reporter)
        
        // 2. Validate that function doesn't manually declare PipelineContext
        validateNoPipelineContextParameter(function, context, reporter)
        
        // 3. Validate parameter types
        validateParameterTypes(function, context, reporter)
        
        // 4. Validate return type
        validateReturnType(function, context, reporter)
        
        // 5. Validate function visibility
        validateVisibility(function, context, reporter)
        
        // 6. Validate security considerations
        validateSecurityConstraints(function, context, reporter)
    }
    
    private fun validateFunctionName(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val functionName = function.name.asString()
        
        // Check for blank name
        if (functionName.isBlank()) {
            reporter.reportOn(
                function.source,
                StepDiagnostics.STEP_FUNCTION_BLANK_NAME,
                context
            )
            return
        }
        
        // Check naming conventions (camelCase, starts with lowercase)
        if (!functionName.matches(Regex("^[a-z][a-zA-Z0-9]*$"))) {
            reporter.reportOn(
                function.source,
                StepDiagnostics.STEP_FUNCTION_NAMING_CONVENTION,
                context
            )
        }
    }
    
    private fun validateNoPipelineContextParameter(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Check if any parameter is PipelineContext type
        function.valueParameters.forEach { parameter ->
            val parameterType = parameter.returnTypeRef.coneType
            if (isPipelineContextType(parameterType)) {
                reporter.reportOn(
                    parameter.source ?: function.source,
                    StepDiagnostics.STEP_FUNCTION_MANUAL_CONTEXT,
                    context
                )
            }
        }
    }
    
    private fun validateParameterTypes(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        function.valueParameters.forEach { parameter ->
            val parameterType = parameter.returnTypeRef.coneType
            val typeString = parameterType.toString()
            
            // Check if parameter type is supported
            val isSupported = SUPPORTED_PARAMETER_TYPES.any { supportedType ->
                typeString.contains(supportedType)
            }
            
            if (!isSupported && !isPipelineContextType(parameterType)) {
                reporter.reportOn(
                    parameter.source ?: function.source,
                    StepDiagnostics.STEP_UNSUPPORTED_PARAMETER_TYPE,
                    parameterType.toKotlinType(),
                    context
                )
            }
        }
    }
    
    private fun validateReturnType(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val returnType = function.returnTypeRef.coneType
        val returnTypeString = returnType.toString()
        
        // Check if return type is supported
        val isSupported = SUPPORTED_RETURN_TYPES.any { supportedType ->
            returnTypeString.contains(supportedType)
        }
        
        if (!isSupported) {
            reporter.reportOn(
                function.source,
                StepDiagnostics.STEP_UNSUPPORTED_RETURN_TYPE,
                returnType.toKotlinType(),
                context
            )
        }
    }
    
    private fun validateVisibility(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // @Step functions should be public or internal for discoverability
        val visibility = function.visibility
        if (visibility.name == "private") {
            reporter.reportOn(
                function.source,
                StepDiagnostics.STEP_FUNCTION_INVALID_VISIBILITY,
                context
            )
        }
    }
    
    private fun validateSecurityConstraints(
        function: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Extract security level from @Step annotation and validate constraints
        val stepAnnotation = function.annotations.find { 
            it.annotationTypeRef.coneType.classId == STEP_ANNOTATION_CLASS_ID 
        }
        
        stepAnnotation?.let { annotation ->
            // Check if function uses potentially dangerous APIs based on security level
            // This would require more sophisticated analysis of function body
            // For now, we can check for suspicious parameter patterns
            
            function.valueParameters.forEach { parameter ->
                val parameterType = parameter.returnTypeRef.coneType.toString()
                if (parameterType.contains("Process") || parameterType.contains("Runtime")) {
                    reporter.reportOn(
                        parameter.source ?: function.source,
                        StepDiagnostics.STEP_FUNCTION_SECURITY_WARNING,
                        "Potentially dangerous API usage detected",
                        context
                    )
                }
            }
        }
    }
    
    private fun isPipelineContextType(coneType: ConeKotlinType): Boolean {
        return coneType.classId == PIPELINE_CONTEXT_CLASS_ID ||
               coneType.toString().contains("PipelineContext")
    }
    
    // Extension function to convert ConeKotlinType to KotlinType (placeholder)
    private fun ConeKotlinType.toKotlinType(): org.jetbrains.kotlin.types.KotlinType {
        // This is a simplified conversion - in practice you'd need proper conversion logic
        // For now we'll use a placeholder to avoid compilation errors
        return org.jetbrains.kotlin.types.TypeUtils.makeNullable(
            org.jetbrains.kotlin.builtins.DefaultBuiltIns.getInstance().anyType
        )
    }
}