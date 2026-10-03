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
                    val location = source.locationAt(expression.textRange.startOffset)
                    when {
                        // Unqualified generator-level `sh(...)`: a shell step call.
                        //
                        // S4-A1. ONE arm, not two. The eager arm used to match on
                        // the callee name alone and was tested first, so the
                        // runtime-returning arm below it could never fire; and the
                        // eager arm carried no script, so the lowering had nothing
                        // to rewrite with and declined, leaving a bare `sh` in the
                        // generated Kotlin that failed to compile. The three legal
                        // shapes are now distinguished by ARGUMENT FORM here, once.
                        expression.calleeExpression?.text == "sh" && !isDotQualified -> {
                            val requested = expression.shellReturnFlags()
                            val script = expression.scriptTextOrNull()
                            when {
                                requested.stdout && requested.status ->
                                    diagnostics += source.diagnosticAt(
                                        offset = expression.textRange.startOffset,
                                        message = "sh(...) cannot request both returnStdout and " +
                                            "returnStatus; the three shapes are mutually exclusive",
                                    )
                                script == null ->
                                    diagnostics += source.diagnosticAt(
                                        offset = expression.textRange.startOffset,
                                        message = "sh(...) requires a script argument",
                                    )
                                else -> calls += ScriptedMappedCall(
                                    ScriptedCallKind.Shell(
                                        script = script,
                                        returnMode = when {
                                            requested.stdout -> ScriptedShellReturnMode.STDOUT
                                            requested.status -> ScriptedShellReturnMode.STATUS
                                            else -> ScriptedShellReturnMode.NONE
                                        },
                                    ),
                                    location,
                                    expression.textRange.length,
                                )
                            }
                        }
                        // Unqualified runtime-returning `isUnix()`: platform query step
                        // (LFC-2R / R3). Argument-less by contract; qualified/receiver
                        // forms are NOT generator calls.
                        expression.calleeExpression?.text == "isUnix" &&
                            !isDotQualified &&
                            expression.valueArguments.isEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.IsUnix,
                                location,
                                expression.textRange.length,
                            )
                        // Unqualified runtime-returning `pwd()` / `pwd(tmp=true)`:
                        // workspace query step (WU-LPR-402). Argument-less → core.pwd,
                        // `tmp = true` → core.pwd.tmp.
                        expression.calleeExpression?.text == "pwd" &&
                            !isDotQualified ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.Pwd(tmp = expression.hasNamedArgTrue("tmp")),
                                location,
                                expression.textRange.length,
                            )
                        // Unqualified runtime-returning `readFile(...)`: workspace file-read
                        // step (LFC-2R2). The argument is the path; encoding defaults at the
                        // façade.
                        expression.calleeExpression?.text == "readFile" &&
                            !isDotQualified &&
                            expression.valueArguments.isNotEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.ReadFile,
                                location,
                                expression.textRange.length,
                            )
                        // Unqualified runtime-returning `fileExists(...)`: workspace
                        // file-existence check (LFC-2R2). The argument is the path.
                        expression.calleeExpression?.text == "fileExists" &&
                            !isDotQualified &&
                            expression.valueArguments.isNotEmpty() ->
                            calls += ScriptedMappedCall(
                                ScriptedCallKind.FileExists,
                                location,
                                expression.textRange.length,
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
     * Which runtime-returning shape a `sh(...)` call asks for.
     *
     * Both spellings of each flag are recognised: `returnStdout = true` and the
     * marker form `returnStdout = ReturnStdout` that the public DSL actually uses.
     * Checking only the boolean spelling would have classified the documented
     * spelling as NONE and quietly run a command whose value the user expected to
     * read.
     *
     * Both flags are reported rather than resolved, because asking for both is an
     * invalid program the caller must reject, not a shape to invent meaning for.
     */
    private fun KtCallExpression.shellReturnFlags(): ShellReturnFlags {
        fun requested(name: String, marker: String): Boolean = valueArguments.any { arg ->
            arg.getArgumentName()?.asName?.asString() == name &&
                arg.getArgumentExpression()?.text?.trim()?.let {
                    it.equals("true", ignoreCase = true) ||
                        it.equals("1") ||
                        it.substringAfterLast('.') == marker
                } == true
        }
        return ShellReturnFlags(
            stdout = requested("returnStdout", "ReturnStdout"),
            status = requested("returnStatus", "ReturnStatus"),
        )
    }

    /** The two independently observable return-mode requests of a `sh(...)` call. */
    private data class ShellReturnFlags(val stdout: Boolean, val status: Boolean)

    /**
     * The `script` argument of a `sh(...)` call, in either legal spelling.
     *
     * Both `sh("echo hi")` and `sh(script = "echo hi")` are ordinary Kotlin, so
     * both must be recognised. The argument is carried as PSI TEXT rather than a
     * literal, which is what lets `sh(command)` pass a variable through: the
     * generated call is then `steps.sh(callSite, command, null, null)`, valid
     * Kotlin that resolves the same value at run time.
     *
     * S4-A1: returns `null` when there is no `script` argument at all, so the
     * caller can reject the call. The previous version searched only positional
     * arguments and substituted an empty string for anything else, so the
     * NAMED form silently became a shell command running nothing and reported
     * success — the silent placeholder the Semantic Constitution forbids. A test
     * fixture using `sh(script = ...)` had been passing on that behaviour.
     */
    private fun KtCallExpression.scriptTextOrNull(): String? =
        valueArguments
            .firstOrNull { arg ->
                val name = arg.getArgumentName()?.asName?.asString()
                name == null || name == "script"
            }
            ?.getArgumentExpression()
            ?.text
            ?.takeIf { it.isNotEmpty() }

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
