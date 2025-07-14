package dev.rubentxu.pipeline.steps.plugin.fir

import org.jetbrains.kotlin.diagnostics.DiagnosticReporter
import org.jetbrains.kotlin.diagnostics.reportOn
import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.analysis.checkers.context.CheckerContext
import org.jetbrains.kotlin.fir.analysis.checkers.declaration.FirSimpleFunctionChecker
import org.jetbrains.kotlin.fir.analysis.extensions.FirAdditionalCheckersExtension
import org.jetbrains.kotlin.fir.declarations.FirSimpleFunction
import org.jetbrains.kotlin.fir.declarations.utils.hasAnnotation
import org.jetbrains.kotlin.fir.types.classId
import org.jetbrains.kotlin.fir.types.coneType
import org.jetbrains.kotlin.name.ClassId
import org.jetbrains.kotlin.name.FqName

class StepAdditionalCheckersExtension(session: FirSession) : FirAdditionalCheckersExtension(session) {
    override val declarationCheckers = object : DeclarationCheckers() {
        override val simpleFunctionCheckers: Set<FirSimpleFunctionChecker>
            get() = setOf(StepFunctionChecker)
    }

    object StepFunctionChecker : FirSimpleFunctionChecker() {
        private val STEP_ANNOTATION_FQ_NAME = FqName("dev.rubentxu.pipeline.steps.annotations.Step")
        private val PIPELINE_CONTEXT_CLASS_ID = ClassId.topLevel(FqName("dev.rubentxu.pipeline.context.PipelineContext"))

        override fun check(declaration: FirSimpleFunction, context: CheckerContext, reporter: DiagnosticReporter) {
            if (!declaration.hasAnnotation(STEP_ANNOTATION_FQ_NAME, context.session)) return

            for (parameter in declaration.valueParameters) {
                if (parameter.returnTypeRef.coneType.classId == PIPELINE_CONTEXT_CLASS_ID) {
                    reporter.reportOn(parameter.source, StepDiagnostics.MANUAL_PIPELINE_CONTEXT_PARAMETER, "", context)
                }
            }
        }
    }
}
