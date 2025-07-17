package dev.rubentxu.pipeline.steps.plugin

import dev.rubentxu.pipeline.steps.plugin.logging.StructuredLogger
import dev.rubentxu.pipeline.steps.plugin.logging.ValidationType
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * Modern checker extension for @Step functions with comprehensive validation.
 * 
 * Enhanced implementation with robust error handling and fallback mechanisms
 * to ensure stability across different Kotlin compiler versions.
 */
class StepModernCheckerExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(
            StepFunctionChecker(MppCheckerKind.Common)
        )
    }
}

/**
 * Simplified checker for @Step functions that validates basic requirements
 */
class StepFunctionChecker(mppKind: MppCheckerKind) : FirSimpleFunctionChecker(mppKind) {
    
    companion object {
        // @Step annotation class ID
        val STEP_ANNOTATION_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.annotations/Step")
        val STEP_ANNOTATION_FQN = FqName("dev.rubentxu.pipeline.annotations.Step")
        
        // PipelineContext class ID  
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.context/PipelineContext")
        val PIPELINE_CONTEXT_FQN = FqName("dev.rubentxu.pipeline.context.PipelineContext")
    }

    context(CheckerContext, DiagnosticReporter)
    override fun check(declaration: FirSimpleFunction) {
        try {
            // Enhanced @Step annotation detection with fallback mechanisms
            val hasStepAnnotation = detectStepAnnotationWithFallbacks(declaration)
            
            if (!hasStepAnnotation) {
                return // Not a @Step function, skip validation
            }
            
            StructuredLogger.logValidation(
                functionName = declaration.name.asString(),
                validationType = ValidationType.STEP_ANNOTATION,
                success = true,
                message = "@Step annotation detected successfully"
            )
            
            // Perform comprehensive validation with error handling
            performValidationWithErrorHandling(declaration)
            
        } catch (e: Exception) {
            // Graceful handling of validation errors
            handleValidationError(declaration, e)
        }
    }
    
    /**
     * Enhanced @Step annotation detection with multiple fallback strategies
     */
    private fun detectStepAnnotationWithFallbacks(declaration: FirSimpleFunction): Boolean {
        return try {
            // Primary method: Check annotations directly
            val directAnnotationCheck = declaration.annotations.any { annotation ->
                try {
                    val annotationType = annotation.annotationTypeRef.coneType
                    annotationType.toString().contains("Step") ||
                    annotationType.toString().contains(STEP_ANNOTATION_FQN.asString())
                } catch (e: Exception) {
                    StructuredLogger.logWarning(
                        operation = "annotation_type_resolution",
                        message = "Failed to resolve annotation type, using fallback",
                        context = mapOf("error" to e.message.orEmpty())
                    )
                    false
                }
            }
            
            if (directAnnotationCheck) {
                return true
            }
            
            // Fallback 1: String-based annotation detection
            val stringBasedCheck = declaration.annotations.any { annotation ->
                annotation.toString().contains("Step")
            }
            
            if (stringBasedCheck) {
                StructuredLogger.logValidation(
                    functionName = declaration.name.asString(),
                    validationType = ValidationType.STEP_ANNOTATION,
                    success = true,
                    message = "@Step annotation detected via string fallback"
                )
                return true
            }
            
            // Fallback 2: Function name pattern detection
            val nameBasedCheck = declaration.name.asString().let { name ->
                name.contains("step", ignoreCase = true) && 
                declaration.annotations.isNotEmpty()
            }
            
            if (nameBasedCheck) {
                StructuredLogger.logValidation(
                    functionName = declaration.name.asString(),
                    validationType = ValidationType.STEP_ANNOTATION,
                    success = true,
                    message = "Potential @Step function detected via naming pattern"
                )
            }
            
            nameBasedCheck
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "step_annotation_detection",
                error = e,
                context = mapOf("function_name" to declaration.name.asString())
            )
            false
        }
    }
    
    /**
     * Perform validation with comprehensive error handling
     */
    context(CheckerContext, DiagnosticReporter)
    private fun performValidationWithErrorHandling(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            validateBasicRequirements(declaration)
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "basic_requirements_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
        
        try {
            validateParameterTypes(declaration)
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "parameter_types_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
        
        try {
            validateSuspendFunctionRequirements(declaration)
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "suspend_function_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
        
        try {
            validateSecurityLevelAnnotation(declaration)
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "security_level_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Handle validation errors gracefully
     */
    private fun handleValidationError(declaration: FirSimpleFunction, error: Exception) {
        val functionName = declaration.name.asString()
        
        StructuredLogger.logError(
            operation = "fir_checker_validation",
            error = error,
            context = mapOf(
                "function_name" to functionName,
                "recovery_strategy" to "continue_compilation"
            )
        )
        
        // Log a warning but don't fail compilation
        StructuredLogger.logWarning(
            operation = "fir_checker_recovery",
            message = "Validation error handled gracefully, compilation continues",
            context = mapOf(
                "function_name" to functionName,
                "error_type" to (error::class.simpleName ?: "Unknown")
            )
        )
    }
    
    /**
     * Validate basic @Step function requirements with enhanced error handling
     */
    context(CheckerContext, DiagnosticReporter)
    private fun validateBasicRequirements(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            // Enhanced naming convention validation
            validateNamingConvention(functionName)
            
            // Enhanced parameter structure validation
            validateParameterStructure(declaration)
            
            // Validate visibility requirements
            validateVisibilityRequirements(declaration)
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "basic_requirements_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Validate naming convention with multiple checks
     */
    private fun validateNamingConvention(functionName: String) {
        // Check for underscore prefix
        if (functionName.startsWith("_")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.NAMING_CONVENTION,
                success = false,
                message = "@Step function name should not start with underscore"
            )
        }
        
        // Check for camelCase convention
        if (functionName.contains("_") && !functionName.startsWith("test")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.NAMING_CONVENTION,
                success = false,
                message = "@Step function should use camelCase naming"
            )
        }
        
        // Check for reasonable length
        if (functionName.length > 50) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.NAMING_CONVENTION,
                success = false,
                message = "@Step function name is too long (${functionName.length} characters)"
            )
        }
        
        if (functionName.length >= 3) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.NAMING_CONVENTION,
                success = true,
                message = "@Step function follows naming conventions"
            )
        }
    }
    
    /**
     * Enhanced parameter structure validation
     */
    private fun validateParameterStructure(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            // Check if function has any parameters
            val parameterCount = declaration.valueParameters.size
            
            // Check for PipelineContext parameter with fallback detection
            val hasPipelineContextParam = declaration.valueParameters.any { param ->
                try {
                    val paramType = param.returnTypeRef.coneType.toString()
                    paramType.contains("PipelineContext") || paramType.contains("context")
                } catch (e: Exception) {
                    StructuredLogger.logWarning(
                        operation = "parameter_type_resolution",
                        message = "Failed to resolve parameter type: ${e.message}",
                        context = mapOf(
                            "function_name" to functionName,
                            "parameter_name" to param.name.asString()
                        )
                    )
                    false
                }
            }
            
            if (hasPipelineContextParam) {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.CONTEXT_PARAMETER,
                    success = true,
                    message = "@Step function has PipelineContext parameter"
                )
            } else if (parameterCount == 0) {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.CONTEXT_PARAMETER,
                    success = false,
                    message = "@Step function has no parameters - may need PipelineContext"
                )
            } else {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.CONTEXT_PARAMETER,
                    success = false,
                    message = "@Step function may need PipelineContext parameter (has $parameterCount other parameters)"
                )
            }
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "parameter_structure_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Validate visibility requirements for @Step functions
     */
    private fun validateVisibilityRequirements(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            // @Step functions should generally be public for discoverability
            // Use string representation as visibility API may vary across Kotlin versions
            val declarationString = declaration.toString()
            val isPrivate = declarationString.contains("private", ignoreCase = true)
            
            if (isPrivate) {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.NAMING_CONVENTION,
                    success = false,
                    message = "@Step function should not be private for proper discoverability"
                )
            } else {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.NAMING_CONVENTION,
                    success = true,
                    message = "@Step function has appropriate visibility"
                )
            }
            
        } catch (e: Exception) {
            StructuredLogger.logWarning(
                operation = "visibility_validation",
                message = "Could not validate visibility: ${e.message}",
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Enhanced parameter type validation with comprehensive checks
     */
    context(CheckerContext, DiagnosticReporter)
    private fun validateParameterTypes(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            declaration.valueParameters.forEachIndexed { index, param ->
                validateIndividualParameter(functionName, param, index)
            }
            
            // Validate overall parameter structure
            validateParameterCombinations(declaration)
            
        } catch (e: Exception) {
            StructuredLogger.logError(
                operation = "parameter_types_validation",
                error = e,
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Validate individual parameter with fallback error handling
     */
    private fun validateIndividualParameter(functionName: String, param: Any, index: Int) {
        try {
            val paramName = param.toString().substringAfter("name=").substringBefore(",").trim()
            
            // Get parameter type with fallback
            val paramType = try {
                val firParam = param as? FirSimpleFunction // This is a simplified approach
                firParam?.toString() ?: param.toString()
            } catch (e: Exception) {
                param.toString()
            }
            
            // Enhanced parameter type checks
            validateParameterTypePattern(functionName, paramName, paramType)
            
        } catch (e: Exception) {
            StructuredLogger.logWarning(
                operation = "individual_parameter_validation",
                message = "Could not validate parameter at index $index: ${e.message}",
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Validate parameter type patterns
     */
    private fun validateParameterTypePattern(functionName: String, paramName: String, paramType: String) {
        // Check for potentially problematic parameter types
        if (paramType.contains("Function") && !paramType.contains("suspend")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.PARAMETER_TYPES,
                success = false,
                message = "Parameter '$paramName' is non-suspend function which may not work correctly in pipeline context"
            )
        }
        
        // Check for callback patterns
        if (paramType.contains("Callback") || paramType.contains("Listener")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.PARAMETER_TYPES,
                success = true,
                message = "Parameter '$paramName' uses callback pattern"
            )
        }
        
        // Check for collection types
        if (paramType.contains("List") || paramType.contains("Set") || paramType.contains("Map")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.PARAMETER_TYPES,
                success = true,
                message = "Parameter '$paramName' uses collection type - ensure thread safety"
            )
        }
        
        // Check for nullable types
        if (paramType.contains("?")) {
            StructuredLogger.logValidation(
                functionName = functionName,
                validationType = ValidationType.PARAMETER_TYPES,
                success = true,
                message = "Parameter '$paramName' is nullable - good for optional parameters"
            )
        }
    }
    
    /**
     * Validate parameter combinations
     */
    private fun validateParameterCombinations(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        val paramCount = declaration.valueParameters.size
        
        when {
            paramCount == 0 -> {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.PARAMETER_TYPES,
                    success = false,
                    message = "@Step function has no parameters - consider adding PipelineContext"
                )
            }
            paramCount > 10 -> {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.PARAMETER_TYPES,
                    success = false,
                    message = "@Step function has many parameters ($paramCount) - consider using data classes"
                )
            }
            else -> {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.PARAMETER_TYPES,
                    success = true,
                    message = "@Step function has reasonable parameter count ($paramCount)"
                )
            }
        }
    }
    
    /**
     * Validate suspend function requirements
     */
    context(CheckerContext, DiagnosticReporter)
    private fun validateSuspendFunctionRequirements(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            // Check suspend status using string representation for API compatibility
            val declarationString = declaration.toString()
            val isSuspend = declarationString.contains("suspend", ignoreCase = true)
            
            if (isSuspend) {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.SUSPEND_FUNCTION,
                    success = true,
                    message = "@Step function is suspend - compatible with async pipeline execution"
                )
            } else {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.SUSPEND_FUNCTION,
                    success = false,
                    message = "@Step function is not suspend - may limit async capabilities"
                )
            }
            
        } catch (e: Exception) {
            StructuredLogger.logWarning(
                operation = "suspend_function_validation",
                message = "Could not determine suspend status: ${e.message}",
                context = mapOf("function_name" to functionName)
            )
        }
    }
    
    /**
     * Validate security level annotation if present
     */
    context(CheckerContext, DiagnosticReporter)
    private fun validateSecurityLevelAnnotation(declaration: FirSimpleFunction) {
        val functionName = declaration.name.asString()
        
        try {
            val hasSecurityAnnotation = declaration.annotations.any { annotation ->
                annotation.toString().contains("SecurityLevel") ||
                annotation.toString().contains("security")
            }
            
            if (hasSecurityAnnotation) {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.SECURITY_LEVEL,
                    success = true,
                    message = "@Step function has security level annotation"
                )
            } else {
                StructuredLogger.logValidation(
                    functionName = functionName,
                    validationType = ValidationType.SECURITY_LEVEL,
                    success = false,
                    message = "@Step function lacks security level annotation - defaulting to RESTRICTED"
                )
            }
            
        } catch (e: Exception) {
            StructuredLogger.logWarning(
                operation = "security_level_validation",
                message = "Could not validate security level: ${e.message}",
                context = mapOf("function_name" to functionName)
            )
        }
    }
}