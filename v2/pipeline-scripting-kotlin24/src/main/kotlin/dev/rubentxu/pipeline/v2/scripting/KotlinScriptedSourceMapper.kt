package dev.rubentxu.pipeline.v2.scripting

import org.jetbrains.kotlin.cli.jvm.compiler.EnvironmentConfigFiles
import org.jetbrains.kotlin.cli.jvm.compiler.KotlinCoreEnvironment
import org.jetbrains.kotlin.com.intellij.openapi.util.Disposer
import org.jetbrains.kotlin.com.intellij.psi.PsiErrorElement
import org.jetbrains.kotlin.config.CompilerConfiguration
import org.jetbrains.kotlin.psi.KtCallExpression
import org.jetbrains.kotlin.psi.KtDotQualifiedExpression
import org.jetbrains.kotlin.psi.KtPsiFactory
import org.jetbrains.kotlin.psi.KtTreeVisitorVoid

/**
 * Kotlin-compiler adapter for [ScriptedSourceMapper]. Compiler PSI stays in
 * this outer adapter; callers receive only immutable scripting-port values.
 *
 * Sources are parsed as Kotlin *scripts* (`.kts`), so top-level statements —
 * `if`, loops, property declarations followed by calls — are valid, exactly as
 * in a `.pipeline.kts` body.
 */
class KotlinScriptedSourceMapper : ScriptedSourceMapper {
    override fun map(source: ScriptedSource): ScriptedSourceMapping = synchronized(parserLock) {
        val disposable = Disposer.newDisposable()
        try {
            val environment = KotlinCoreEnvironment.createForProduction(
                disposable,
                CompilerConfiguration(),
                EnvironmentConfigFiles.JVM_CONFIG_FILES,
            )
            val file = KtPsiFactory(environment.project, false).createFile(
                SCRIPT_PARSE_FILE_NAME,
                source.text,
            )
            val calls = mutableListOf<ScriptedMappedCall>()
            val diagnostics = mutableListOf<ScriptedSourceDiagnostic>()

            file.accept(object : KtTreeVisitorVoid() {
                override fun visitCallExpression(expression: KtCallExpression) {
                    val isDotQualified = expression.parent is KtDotQualifiedExpression
                    when {
                        // Unqualified generator-level `sh(...)`: a shell step call.
                        expression.calleeExpression?.text == "sh" && !isDotQualified ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.Shell,
                                source.locationAt(expression.textRange.startOffset),
                            )
                        // Unqualified runtime-returning `isUnix()`: platform query step
                        // (LFC-2R / R3). Argument-less by contract; qualified/receiver
                        // forms are NOT generator calls.
                        expression.calleeExpression?.text == "isUnix" &&
                            !isDotQualified &&
                            expression.valueArguments.isEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.IsUnix,
                                source.locationAt(expression.textRange.startOffset),
                            )
                    }
                    super.visitCallExpression(expression)
                }

                override fun visitErrorElement(element: PsiErrorElement) {
                    diagnostics += source.diagnosticAt(
                        offset = element.textRange.startOffset,
                        message = element.errorDescription,
                    )
                    super.visitErrorElement(element)
                }
            })

            if (diagnostics.isEmpty()) {
                ScriptedSourceMapping.Mapped(calls)
            } else {
                ScriptedSourceMapping.InvalidSyntax(diagnostics)
            }
        } finally {
            Disposer.dispose(disposable)
        }
    }

    private fun ScriptedSource.locationAt(offset: Int): ScriptedSourceLocation =
        ScriptedSourceLocation(sourceId, lineAt(offset), columnAt(offset))

    private fun ScriptedSource.diagnosticAt(offset: Int, message: String): ScriptedSourceDiagnostic =
        ScriptedSourceDiagnostic(lineAt(offset), columnAt(offset), message)

    private fun ScriptedSource.lineAt(offset: Int): Int = text.substring(0, offset).count { it == '\n' } + 1

    private fun ScriptedSource.columnAt(offset: Int): Int {
        val before = text.substring(0, offset)
        return before.length - before.lastIndexOf('\n')
    }

    private companion object {
        /** Kotlin compiler PSI application state is process-global. */
        val parserLock = Any()

        /**
         * Fixed parse-side file name: the `.kts` suffix makes the PSI parser
         * treat the text as a Kotlin script. It carries no semantic weight —
         * reported locations always use the caller's [ScriptedSourceId].
         */
        const val SCRIPT_PARSE_FILE_NAME = "scripted-source.kts"
    }
}
