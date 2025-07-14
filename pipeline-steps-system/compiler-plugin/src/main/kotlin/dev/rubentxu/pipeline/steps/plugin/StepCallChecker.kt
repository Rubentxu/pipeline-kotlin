package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.expression.FirFunctionCallChecker
import org.jetbrains.kotlin.fir.declarations.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirFunctionCall
import org.jetbrains.kotlin.fir.resolve.calls.FirResolvedCallInfo
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.diagnostics.DiagnosticReporter

/**
 * FIR checker for validating calls to @Step functions.
 * 
 * This checker validates that:
 * - @Step functions are called from valid contexts
 * - Correct number of arguments are provided
 * - Call context follows @Step conventions
 * - No recursive @Step calls that could cause issues
 */
class StepCallChecker(private val session: FirSession) : FirFunctionCallChecker() {
    
    companion object {
        private val STEP_ANNOTATION_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.steps.annotations.Step"))
        private val STEPS_BLOCK_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.dsl.StepsBlock"))
    }
    
    override fun check(
        expression: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Get the resolved function symbol
        val resolvedSymbol = expression.calleeReference.toResolvedCallableSymbol()
        val calledFunction = resolvedSymbol?.fir
        
        // Check if the called function has @Step annotation
        if (calledFunction?.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session) == true) {
            validateStepFunctionCall(expression, context, reporter)
        }
    }
    
    private fun validateStepFunctionCall(
        call: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // 1. Validate call context
        validateCallContext(call, context, reporter)
        
        // 2. Validate argument count (accounting for automatic PipelineContext injection)
        validateArgumentCount(call, context, reporter)
        
        // 3. Validate no recursive calls
        validateNoRecursiveCalls(call, context, reporter)
        
        // 4. Validate security context
        validateSecurityContext(call, context, reporter)
    }
    
    private fun validateCallContext(
        call: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Check if we're in a valid context for @Step function calls
        val isInValidContext = isInStepsBlockContext(context) || isInStepFunctionContext(context)
        
        if (!isInValidContext) {
            reporter.reportOn(
                call.source,
                StepDiagnostics.STEP_CALL_OUTSIDE_CONTEXT,
                context
            )
        }
    }
    
    private fun validateArgumentCount(
        call: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        val resolvedSymbol = call.calleeReference.toResolvedCallableSymbol()
        val calledFunction = resolvedSymbol?.fir as? org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
        
        if (calledFunction != null) {
            val providedArguments = call.arguments.size
            val expectedParameters = calledFunction.valueParameters.size
            
            // Account for automatic PipelineContext injection
            // The call site should provide one less argument than the function declares
            // because PipelineContext will be injected automatically
            val expectedCallArguments = expectedParameters - if (hasPipelineContextParameter(calledFunction)) 0 else 1
            
            if (providedArguments != expectedCallArguments) {
                reporter.reportOn(
                    call.source,
                    StepDiagnostics.STEP_CALL_ARGUMENT_MISMATCH,
                    context
                )
            }
        }
    }
    
    private fun validateNoRecursiveCalls(
        call: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Check if we're calling the same @Step function recursively
        val calledFunctionName = call.calleeReference.name.asString()
        val currentFunction = findCurrentFunction(context)
        
        if (currentFunction != null && 
            currentFunction.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session) &&
            currentFunction.name.asString() == calledFunctionName) {
            
            // This is a recursive call to a @Step function
            // While not necessarily an error, it could cause context injection issues
            reporter.reportOn(
                call.source,
                StepDiagnostics.STEP_FUNCTION_PERFORMANCE_WARNING,
                context
            )
        }
    }
    
    private fun validateSecurityContext(
        call: FirFunctionCall,
        context: CheckerContext,
        reporter: DiagnosticReporter
    ) {
        // Extract security levels of both caller and callee
        val callerSecurityLevel = getCurrentFunctionSecurityLevel(context)
        val calleeSecurityLevel = getCalleeSecurityLevel(call)
        
        // Validate that a restricted function doesn't call a trusted function
        if (callerSecurityLevel == "RESTRICTED" && calleeSecurityLevel == "TRUSTED") {
            reporter.reportOn(
                call.source,
                StepDiagnostics.STEP_CALL_CONTEXT_VIOLATION,
                context
            )
        }
    }
    
    private fun isInStepsBlockContext(context: CheckerContext): Boolean {
        // Check if we're inside a StepsBlock lambda or similar context
        // This would require examining the current scope and looking for StepsBlock receivers
        
        // For now, return true as a placeholder - in practice this would examine
        // the context stack to find StepsBlock receivers
        return true
    }
    
    private fun isInStepFunctionContext(context: CheckerContext): Boolean {
        // Check if we're inside another @Step function
        val currentFunction = findCurrentFunction(context)
        return currentFunction?.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session) == true
    }
    
    private fun findCurrentFunction(context: CheckerContext): org.jetbrains.kotlin.fir.declarations.FirSimpleFunction? {
        // Traverse the context to find the current function
        // This is a simplified implementation
        return context.containingDeclarations.filterIsInstance<org.jetbrains.kotlin.fir.declarations.FirSimpleFunction>().lastOrNull()
    }
    
    private fun hasPipelineContextParameter(function: org.jetbrains.kotlin.fir.declarations.FirSimpleFunction): Boolean {
        // Check if the function already has a PipelineContext parameter
        return function.valueParameters.any { param ->
            param.returnTypeRef.coneType.toString().contains("PipelineContext")
        }
    }
    
    private fun getCurrentFunctionSecurityLevel(context: CheckerContext): String? {
        // Extract security level from current function's @Step annotation
        val currentFunction = findCurrentFunction(context)
        if (currentFunction?.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session) == true) {
            val stepAnnotation = currentFunction.annotations.find { 
                it.annotationTypeRef.coneType.classId == STEP_ANNOTATION_CLASS_ID 
            }
            return extractSecurityLevelFromAnnotation(stepAnnotation)
        }
        return null
    }
    
    private fun getCalleeSecurityLevel(call: FirFunctionCall): String? {
        // Extract security level from called function's @Step annotation
        val resolvedSymbol = call.calleeReference.toResolvedCallableSymbol()
        val calledFunction = resolvedSymbol?.fir as? org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
        
        if (calledFunction?.hasAnnotation(STEP_ANNOTATION_CLASS_ID, session) == true) {
            val stepAnnotation = calledFunction.annotations.find { 
                it.annotationTypeRef.coneType.classId == STEP_ANNOTATION_CLASS_ID 
            }
            return extractSecurityLevelFromAnnotation(stepAnnotation)
        }
        return null
    }
    
    private fun extractSecurityLevelFromAnnotation(annotation: org.jetbrains.kotlin.fir.expressions.FirAnnotation?): String? {
        // Extract securityLevel parameter from @Step annotation
        // This is a simplified implementation
        annotation?.argumentMapping?.mapping?.get(org.jetbrains.kotlin.name.Name.identifier("securityLevel"))?.let { argument ->
            // Extract enum value - simplified
            return when (argument) {
                is org.jetbrains.kotlin.fir.expressions.FirGetEnumValueExpression -> argument.enumEntryName.asString()
                else -> "RESTRICTED" // default
            }
        }
        return "RESTRICTED" // default security level
    }
}