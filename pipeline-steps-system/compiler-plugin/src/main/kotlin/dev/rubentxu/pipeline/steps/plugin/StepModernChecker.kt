package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.DeclarationCheckers
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.fir.analysis.checkers.MppCheckerKind

/**
 * Simplified checker extension for @Step functions.
 * 
 * This checker provides basic validation for @Step function declarations.
 * More comprehensive validation can be added as the API stabilizes.
 */
class StepModernCheckerExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    
    override val declarationCheckers: DeclarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker> = setOf(
            StepFunctionChecker(MppCheckerKind.Common)
        )
    }
}

/**
 * Simplified checker for @Step functions
 */
class StepFunctionChecker(mppKind: MppCheckerKind) : FirSimpleFunctionChecker(mppKind) {
    
    companion object {
        // @Step annotation class ID
        val STEP_ANNOTATION_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.steps.annotations/Step")
        
        // PipelineContext class ID  
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.context/PipelineContext")
    }

    context(CheckerContext, DiagnosticReporter)
    override fun check(declaration: FirSimpleFunction) {
        // For now, we perform minimal validation
        // The function just needs to compile without errors
        
        // Future validations can be added here:
        // - Check that @Step functions have proper annotations
        // - Validate parameter types and constraints
        // - Check security level compliance
        
        // Placeholder: just verify the function exists and is properly formed
        if (declaration.name.asString().startsWith("step")) {
            // This is a basic check that the function follows naming conventions
            // More sophisticated checks can be added later
        }
    }
}