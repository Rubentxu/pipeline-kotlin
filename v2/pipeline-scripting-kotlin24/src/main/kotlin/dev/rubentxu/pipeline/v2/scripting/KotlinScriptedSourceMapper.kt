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
import org.jetbrains.kotlin.psi.KtValueArgument
import org.jetbrains.kotlin.psi.KtValueArgumentName

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
                        // Unqualified runtime-returning `pwd()` / `pwd(tmp=true)`:
                        // workspace query step (WU-LPR-402). Argument-less → core.pwd,
                        // `tmp = true` → core.pwd.tmp.
                        expression.calleeExpression?.text == "pwd" &&
                            !isDotQualified ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.Pwd(tmp = expression.hasNamedArgTrue("tmp")),
                                source.locationAt(expression.textRange.startOffset),
                            )
                        // Unqualified runtime-returning `readFile(...)`: workspace file-read
                        // step (LFC-2R2). The argument is the path; encoding defaults at the
                        // façade.
                        expression.calleeExpression?.text == "readFile" &&
                            !isDotQualified &&
                            expression.valueArguments.isNotEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.ReadFile,
                                source.locationAt(expression.textRange.startOffset),
                            )
                        // Unqualified runtime-returning `fileExists(...)`: workspace
                        // file-existence check (LFC-2R2). The argument is the path.
                        expression.calleeExpression?.text == "fileExists" &&
                            !isDotQualified &&
                            expression.valueArguments.isNotEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.FileExists,
                                source.locationAt(expression.textRange.startOffset),
                            )
                        // Unqualified runtime-returning `sh(..., returnStdout = true)`:
                        // captures stdout as a typed value (LFC-2R2). Distinct from
                        // the eager `sh(...)` branch above — both compile-time legal.
                        expression.calleeExpression?.text == "sh" &&
                            !isDotQualified &&
                            expression.hasNamedArgTrue("returnStdout") ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.ShellReturnStdout(script = expression.scriptText()),
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

    /**
     * Returns true iff [name] is a named argument in the call with a literal `true`
     * value. Recognises the canonical `name = true` and the shorthand `name = 1`
     * that Kotlin allows for boolean parameters.
     */
    private fun KtCallExpression.hasNamedArgTrue(name: String): Boolean =
        valueArguments.any { arg ->
            arg.getArgumentName()?.asName?.asString() == name &&
                arg.getArgumentExpression()?.text?.trim()?.equals("true", ignoreCase = true) == true
        }

    /**
     * Returns the textual form of the FIRST positional argument (assumed to be the
     * `script` parameter for `sh(...)`). Used by [ScriptedCallKind.ShellReturnStdout]
     * to carry the script text into the rewrite target.
     */
    private fun KtCallExpression.scriptText(): String =
        valueArguments.firstOrNull { it.getArgumentName()?.asName?.asString() == null }
            ?.getArgumentExpression()
            ?.text
            ?: ""

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
