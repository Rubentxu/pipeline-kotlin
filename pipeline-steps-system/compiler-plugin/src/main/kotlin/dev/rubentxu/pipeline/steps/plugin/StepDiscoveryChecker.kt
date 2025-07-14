package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.FirValueParameter
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.*
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.fir.types.isNullable
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * FIR checker for discovering @Step functions and collecting their metadata.
 * 
 * This checker finds all functions annotated with @Step, extracts their metadata,
 * and stores it in the StepMetadataService for use in later compiler phases.
 */
class StepDiscoveryChecker(private val session: FirSession) : FirSimpleFunctionChecker() {

    companion object {
        private val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.steps.annotations.Step"))
    }

    override fun check(
        declaration: FirSimpleFunction,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        if (declaration.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session)) {
            discoverStepFunction(declaration, context)
        }
    }

    private fun discoverStepFunction(
        function: FirSimpleFunction,
        context: CheckerContext
    ) {
        val metadata = extractStepMetadata(function, context)
        session.stepMetadataService.addDiscoveredFunction(metadata)
    }

    private fun extractStepMetadata(
        function: FirSimpleFunction,
        context: CheckerContext
    ): StepFunctionMetadata {
        val stepAnnotation = function.annotations.find { 
            it.annotationTypeRef.coneType.classId == STEP_ANNOTATION_CLASS_ID 
        }
        
        val name = extractAnnotationStringParameter(stepAnnotation, "name") ?: function.name.asString()
        val description = extractAnnotationStringParameter(stepAnnotation, "description") ?: ""
        val category = extractAnnotationEnumParameter(stepAnnotation, "category") ?: "GENERAL"
        val securityLevel = extractAnnotationEnumParameter(stepAnnotation, "securityLevel") ?: "RESTRICTED"
        
        val parameters = function.valueParameters.map { param ->
            extractParameterMetadata(param)
        }
        
        val packageName = context.containingFile?.packageFqName?.asString() ?: ""
        val isTopLevel = function.containingClass() == null
        
        return StepFunctionMetadata(
            name = name,
            description = description,
            category = category,
            securityLevel = securityLevel,
            parameters = parameters,
            returnType = function.returnTypeRef.coneType.toString(),
            packageName = packageName,
            isTopLevel = isTopLevel
        )
    }

    private fun extractParameterMetadata(
        parameter: FirValueParameter
    ): StepParameterMetadata {
        return StepParameterMetadata(
            name = parameter.name.asString(),
            type = parameter.returnTypeRef.coneType.toString(),
            hasDefault = parameter.defaultValue != null,
            isNullable = parameter.returnTypeRef.coneType.isNullable
        )
    }

    private fun extractAnnotationStringParameter(
        annotation: FirAnnotation?,
        parameterName: String
    ): String? {
        if (annotation == null) return null
        
        val argument = annotation.argumentMapping.mapping[Name.identifier(parameterName)]
        return when (argument) {
            is FirLiteralExpression -> argument.value as? String
            is FirConstExpression<*> -> argument.value as? String
            else -> null
        }
    }

    private fun extractAnnotationEnumParameter(
        annotation: FirAnnotation?,
        parameterName: String
    ): String? {
        if (annotation == null) return null
        
        val argument = annotation.argumentMapping.mapping[Name.identifier(parameterName)]
        return when (argument) {
            is FirGetEnumValueExpression -> argument.enumEntryName.asString()
            is FirPropertyAccessExpression -> argument.calleeReference.name.asString()
            is FirQualifiedAccessExpression -> (argument.calleeReference.resolved?.fir as? FirSimpleFunction)?.name?.asString()
            else -> null
        }
    }
}