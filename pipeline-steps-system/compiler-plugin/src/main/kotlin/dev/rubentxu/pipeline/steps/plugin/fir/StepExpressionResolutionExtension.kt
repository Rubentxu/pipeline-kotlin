package dev.rubentxu.pipeline.steps.plugin.fir

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.hasAnnotation
import org.jetbrains.kotlin.fir.expressions.FirResolvable
import org.jetbrains.kotlin.fir.extensions.FirExpressionResolutionExtension
import org.jetbrains.kotlin.fir.resolve.ScopeSession
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitDispatchReceiverValue
import org.jetbrains.kotlin.fir.resolve.calls.ImplicitReceiverValue
import org.jetbrains.kotlin.fir.resolve.lookupSuperTypes
import org.jetbrains.kotlin.fir.scopes.getTowerDataElements
import org.jetbrains.kotlin.fir.symbols.impl.FirClassSymbol
import org.jetbrains.kotlin.fir.symbols.impl.FirRegularClassSymbol
import org.jetbrains.kotlin.fir.types.constructClassType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

/**
 * A FIR extension that resolves expressions within @Step functions.
 * Its primary role is to provide an implicit `PipelineContext` receiver
 * to all functions annotated with `@Step`.
 */
class StepExpressionResolutionExtension(session: FirSession) : FirExpressionResolutionExtension(session) {

    companion object {
        private val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.steps.annotations.Step")
        private val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.context.PipelineContext"))
    }

    override fun addNewImplicitReceivers(call: FirResolvable, scopeSession: ScopeSession): List<ImplicitReceiverValue<*>> {
        val towerDataElements = scopeSession.getTowerDataElements()

        val inStepFunction = towerDataElements.any { 
            val function = it.scope?.owner as? FirSimpleFunction
            function?.hasAnnotation(STEP_ANNOTATION_FQ_NAME, session) == true
        }

        if (!inStepFunction) {
            return emptyList()
        }

        val contextSymbol = session.symbolProvider.getClassLikeSymbolByClassId(PIPELINE_CONTEXT_CLASS_ID) as? FirRegularClassSymbol
            ?: return emptyList() // PipelineContext not found in classpath

        val contextType = contextSymbol.constructType(emptyArray(), isNullable = false)

        return listOf(ImplicitDispatchReceiverValue(contextSymbol, contextType, session, scopeSession))
    }
}
