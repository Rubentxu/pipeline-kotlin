package dev.rubentxu.pipeline.steps.plugin.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName
import org.jetbrains.kotlin.name.Name

/**
 * Simplified FIR extension for @Step functions with Context Parameters support.
 * 
 * This extension serves as a placeholder for future Context Parameters integration.
 * Current implementation is minimal to avoid API compatibility issues.
 */
class StepContextParameterExtension(session: FirSession) : FirDeclarationGenerationExtension(session) {

    companion object {
        // @Step annotation FQN
        val STEP_ANNOTATION_FQN = FqName("dev.rubentxu.pipeline.steps.annotations.Step")
        
        // PipelineContext FQN for context parameters
        val PIPELINE_CONTEXT_FQN = FqName("dev.rubentxu.pipeline.context.PipelineContext")
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(PIPELINE_CONTEXT_FQN)
    }

    /**
     * For this simplified implementation, we don't generate new callable names.
     * Context parameter support will be added when the API stabilizes.
     */
    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext
    ): Set<Name> {
        return emptySet()
    }

    /**
     * Placeholder for context parameter wrapper generation.
     * This will be implemented when Context Parameters API is stable.
     */
    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        return emptyList()
    }
}