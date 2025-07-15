package dev.rubentxu.pipeline.steps.plugin.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.extensions.FirDeclarationGenerationExtension
import org.jetbrains.kotlin.fir.extensions.MemberGenerationContext
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirNamedFunctionSymbol
import org.jetbrains.kotlin.name.CallableId
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.Name

/**
 * Simplified FIR extension for @Step functions.
 *
 * This extension serves as a placeholder for detecting @Step functions.
 * The actual transformation work is done in the IR phase for better API stability.
 */
class StepContextParameterExtension(session: FirSession) : FirDeclarationGenerationExtension(session) {

    companion object {
        // @Step annotation class ID
        val STEP_ANNOTATION_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.steps.annotations/Step")

        // PipelineContext class ID
        val PIPELINE_CONTEXT_CLASS_ID = ClassId.fromString("dev.rubentxu.pipeline.context/PipelineContext")
    }

    /**
     * For this simplified implementation, we don't generate new functions at FIR level.
     * The transformation happens in IR phase.
     */
    override fun getCallableNamesForClass(
        classSymbol: FirClassSymbol<*>,
        context: MemberGenerationContext
    ): Set<Name> {
        return emptySet()
    }

    override fun generateFunctions(
        callableId: CallableId,
        context: MemberGenerationContext?
    ): List<FirNamedFunctionSymbol> {
        return emptyList()
    }
}