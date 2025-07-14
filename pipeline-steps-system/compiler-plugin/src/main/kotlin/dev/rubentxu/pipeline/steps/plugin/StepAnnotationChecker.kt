package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirBasicDeclarationChecker
import org.jetbrains.kotlin.fir.declarations.*
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter

/**
 * FIR checker for validating @Step annotation usage.
 * 
 * This checker validates that:
 * - @Step annotation is only used on functions
 * - Annotation parameters are valid
 * - Category and security level values are valid
 * - Annotation usage follows best practices
 */
class StepAnnotationChecker(private val session: FirSession) : FirBasicDeclarationChecker() {
    
    companion object {
        private val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.steps.annotations.Step"))
        
        // Valid categories for @Step annotation
        private val VALID_CATEGORIES = setOf(
            "GENERAL", "SCM", "BUILD", "TEST", "DEPLOY", "SECURITY", "UTIL", "NOTIFICATION"
        )
        
        // Valid security levels for @Step annotation
        private val VALID_SECURITY_LEVELS = setOf(
            "TRUSTED", "RESTRICTED", "ISOLATED"
        )
    }
    
    override fun check(
        declaration: FirDeclaration,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Find @Step annotations on this declaration
        declaration.annotations.forEach { annotation ->
            if (isStepAnnotation(annotation)) {
                validateStepAnnotation(annotation, declaration, context, reporter)
            }
        }
    }
    
    private fun validateStepAnnotation(
        annotation: FirAnnotation,
        declaration: FirDeclaration,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // 1. Validate that @Step is only used on functions
        validateAnnotationTarget(annotation, declaration, context, reporter)
        
        // 2. Validate annotation parameters
        validateAnnotationParameters(annotation, context, reporter)
    }
    
    private fun validateAnnotationTarget(
        annotation: FirAnnotation,
        declaration: FirDeclaration,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Check if @Step is used on a non-function declaration
        if (declaration !is FirSimpleFunction) {
            reporter.reportOn(
                annotation.source ?: declaration.source,
                StepDiagnostics.STEP_ANNOTATION_ON_NON_FUNCTION,
                context
            )
        }
    }
    
    private fun validateAnnotationParameters(
        annotation: FirAnnotation,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Get annotation arguments
        val argumentMapping = annotation.argumentMapping
        
        // Validate name parameter
        validateNameParameter(argumentMapping, annotation, context, reporter)
        
        // Validate description parameter
        validateDescriptionParameter(argumentMapping, annotation, context, reporter)
        
        // Validate category parameter
        validateCategoryParameter(argumentMapping, annotation, context, reporter)
        
        // Validate securityLevel parameter
        validateSecurityLevelParameter(argumentMapping, annotation, context, reporter)
    }
    
    private fun validateNameParameter(
        argumentMapping: FirAnnotationArgumentMapping,
        annotation: FirAnnotation,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val nameParam = argumentMapping.mapping[Name.identifier("name")]
        nameParam?.let { argument ->
            when (argument) {
                is FirLiteralExpression -> {
                    val nameValue = argument.value as? String
                    if (nameValue != null) {
                        // Validate name follows conventions
                        if (nameValue.isNotBlank() && !nameValue.matches(Regex("^[a-zA-Z][a-zA-Z0-9_]*$"))) {
                            reporter.reportOn(
                                argument.source ?: annotation.source,
                                StepDiagnostics.STEP_INVALID_ANNOTATION_PARAMETER,
                                "name must be a valid identifier",
                                context
                            )
                        }
                    }
                }
                else -> {
                    reporter.reportOn(
                        argument.source ?: annotation.source,
                        StepDiagnostics.STEP_INVALID_ANNOTATION_PARAMETER,
                        "name must be a string literal",
                        context
                    )
                }
            }
        }
    }
    
    private fun validateDescriptionParameter(
        argumentMapping: FirAnnotationArgumentMapping,
        annotation: FirAnnotation,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val descriptionParam = argumentMapping.mapping[Name.identifier("description")]
        descriptionParam?.let { argument ->
            when (argument) {
                is FirLiteralExpression -> {
                    val descriptionValue = argument.value as? String
                    if (descriptionValue != null && descriptionValue.length > 200) {
                        reporter.reportOn(
                            argument.source ?: annotation.source,
                            StepDiagnostics.STEP_INVALID_ANNOTATION_PARAMETER,
                            "description should be concise (max 200 characters)",
                            context
                        )
                    }
                }
                else -> {
                    reporter.reportOn(
                        argument.source ?: annotation.source,
                        StepDiagnostics.STEP_INVALID_ANNOTATION_PARAMETER,
                        "description must be a string literal",
                        context
                    )
                }
            }
        }
    }
    
    private fun validateCategoryParameter(
        argumentMapping: FirAnnotationArgumentMapping,
        annotation: FirAnnotation,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val categoryParam = argumentMapping.mapping[Name.identifier("category")]
        categoryParam?.let { argument ->
            // Extract category value - this would need proper enum handling
            val categoryValue = extractEnumValue(argument)
            if (categoryValue != null && categoryValue !in VALID_CATEGORIES) {
                reporter.reportOn(
                    argument.source ?: annotation.source,
                    StepDiagnostics.STEP_INVALID_CATEGORY,
                    categoryValue,
                    context
                )
            }
        }
    }
    
    private fun validateSecurityLevelParameter(
        argumentMapping: FirAnnotationArgumentMapping,
        annotation: FirAnnotation,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val securityLevelParam = argumentMapping.mapping[Name.identifier("securityLevel")]
        securityLevelParam?.let { argument ->
            // Extract security level value - this would need proper enum handling
            val securityLevelValue = extractEnumValue(argument)
            if (securityLevelValue != null && securityLevelValue !in VALID_SECURITY_LEVELS) {
                reporter.reportOn(
                    argument.source ?: annotation.source,
                    StepDiagnostics.STEP_INVALID_SECURITY_LEVEL,
                    securityLevelValue,
                    context
                )
            }
        }
    }
    
    private fun isStepAnnotation(annotation: FirAnnotation): Boolean {
        return annotation.annotationTypeRef.coneType.classId == STEP_ANNOTATION_CLASS_ID
    }
    
    private fun extractEnumValue(expression: FirExpression): String? {
        // Simplified enum value extraction
        // In practice, this would need more sophisticated handling for enum constants
        return when (expression) {
            is FirGetEnumValueExpression -> expression.enumEntryName.asString()
            is FirPropertyAccessExpression -> {
                val propertyName = expression.calleeReference.name.asString()
                propertyName
            }
            else -> null
        }
    }
}