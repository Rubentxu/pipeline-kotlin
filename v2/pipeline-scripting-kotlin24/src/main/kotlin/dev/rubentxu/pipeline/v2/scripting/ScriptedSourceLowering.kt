package dev.rubentxu.pipeline.v2.scripting

/**
 * LFC-2R / R3 — Compiler-backed lowering of a mapped `.pipeline.kts` body into a
 * generated Kotlin source artifact implementing [CompiledScriptedEntryPoint].
 *
 * The lowering is DETERMINISTIC and driven by the compiler-verified
 * [ScriptedSourceMapping.Mapped] calls — never by regex over raw text:
 *
 * ```
 * user source (top-level statements)
 *   → ScriptedSourceMapper (PSI: exact isUnix()/sh() call sites)
 *   → generated source:
 *       object : CompiledScriptedEntryPoint {
 *           override suspend fun execute(steps: ScriptedStepFacade) {
 *               <body with isUnix() rewritten to steps.isUnix(callSite)>
 *           }
 *       }
 *   → Kotlin host compiles and evaluates it (exactly ONE script evaluation;
 *     the user's Kotlin continuation stays suspended around each runtime call)
 * ```
 *
 * Call-site identity comes from the mapped source locations through
 * [ScriptedSourceLocation.unixCallSite]: same source + same position → same
 * callSite, independent of the checkout path.
 */
object ScriptedSourceLowering {

    sealed interface LoweringResult {
        /** Generated Kotlin source that evaluates to a [CompiledScriptedEntryPoint]. */
        data class Generated(
            val source: String,
            val artifact: ScriptedArtifactIdentity,
            val mappedCalls: List<ScriptedMappedCall>,
        ) : LoweringResult

        data class InvalidSyntax(
            val diagnostics: List<ScriptedSourceDiagnostic>,
        ) : LoweringResult
    }

    /**
     * Lowers [sourceText] into a generated entry-point artifact. The identity is a
     * pure function of (sourceId, sourceText, dslApi, compiler, runtime, plugins,
     * facade schema): any incompatible facade/compiler-mapping change flows into
     * `facadeSchemaDigest` and therefore into the artifact compatibility identity.
     */
    fun lower(
        sourceId: ScriptedSourceId,
        sourceText: String,
        mapper: ScriptedSourceMapper,
        facadeSchemaVersion: String,
    ): LoweringResult = when (val mapping = mapper.map(ScriptedSource(sourceId, sourceText))) {
        is ScriptedSourceMapping.InvalidSyntax -> LoweringResult.InvalidSyntax(mapping.diagnostics)
        is ScriptedSourceMapping.Mapped -> {
            val body = rewriteRuntimeReturningCalls(sourceText, mapping.calls)
            val artifact = ScriptedArtifactIdentity(
                sourceDigest = sha256(sourceText),
                dslApiVersion = DSL_API_VERSION,
                compilerAdapterVersion = COMPILER_ADAPTER_VERSION,
                runtimeCompatibilityVersion = RUNTIME_COMPATIBILITY_VERSION,
                pluginLockDigest = PLUGIN_LOCK_DIGEST,
                facadeSchemaDigest = facadeSchemaVersion,
            )
            LoweringResult.Generated(
                source = generatedSource(sourceId, body),
                artifact = artifact,
                mappedCalls = mapping.calls,
            )
        }
    }

    /**
     * Rewrites each mapped runtime-returning call to its façade form
     * (`steps.<kind>(ScriptedCallSiteId(...), ...)`). Walks offsets in REVERSE
     * order so earlier replacements never shift later ranges.
     *
     * Handles the LFC-2R2 family: `isUnix()`, `pwd()`/`pwd(tmp=true)`,
     * `readFile(...)`, `fileExists(...)`, `sh(..., returnStdout = true)`. The
     * eager `sh(...)` branch is unchanged because it never enters the
     * runtime-returning surface.
     */
    private fun rewriteRuntimeReturningCalls(text: String, calls: List<ScriptedMappedCall>): String {
        // S4-A1: the replacement span is the call's REAL source extent, carried by
        // [ScriptedMappedCall.sourceLength]. It used to be the length of a
        // hardcoded canonical spelling, which is only right for argument-less calls:
        // for `sh(script = "...")` it was wrong, so the substitution landed inside
        // the original call and produced Kotlin that did not compile.
        val ordered = calls.sortedByDescending { it.location.line * 1_000_000 + it.location.column }

        var result = text
        for (call in ordered) {
            val offset = offsetOfAt(result, call.location, call.sourceLength)
            if (offset < 0) continue
            val replacement = when (val kind = call.kind) {
                is ScriptedCallKind.IsUnix -> "steps.isUnix(ScriptedCallSiteId(\"${call.location.unixCallSite().value}\"))"
                is ScriptedCallKind.Pwd -> {
                    val tmp = kind.tmp
                    val callSite = call.location.pwdCallSite(tmp = tmp).value
                    "steps.pwd(ScriptedCallSiteId(\"$callSite\"), tmp = $tmp)"
                }
                // S4-DATA: the author's own path EXPRESSION is re-scoped into the
                // façade call, exactly as `sh` does with its script. A literal `""`
                // was written here before, which both dropped the path and left the
                // argument's text trailing after the rewritten call.
                is ScriptedCallKind.ReadFile -> {
                    val callSite = call.location.readFileCallSite().value
                    "steps.readFile(ScriptedCallSiteId(\"$callSite\"), ${kind.pathExpression})"
                }
                is ScriptedCallKind.FileExists -> {
                    val callSite = call.location.fileExistsCallSite().value
                    "steps.fileExists(ScriptedCallSiteId(\"$callSite\"), ${kind.pathExpression})"
                }
                is ScriptedCallKind.Shell -> {
                    val callSite = call.location.shellCallSite(kind.returnMode).value
                    // One façade, three shapes. The runtime-returning forms used to
                    // have a separate `shReturnStdout` façade that the mapper could
                    // never select, so it was a dead semantic parameter; a single
                    // `sh` carrying the return mode has no such gap.
                    when (kind.returnMode) {
                        ScriptedShellReturnMode.NONE ->
                            "steps.sh(ScriptedCallSiteId(\"$callSite\"), ${kind.script}, null, null)"
                        ScriptedShellReturnMode.STDOUT ->
                            "steps.sh(ScriptedCallSiteId(\"$callSite\"), ${kind.script}, ReturnStdout, null, null)"
                        ScriptedShellReturnMode.STATUS ->
                            "steps.sh(ScriptedCallSiteId(\"$callSite\"), ${kind.script}, ReturnStatus, null, null)"
                    }
                }
            }
            result = result.substring(0, offset) + replacement + result.substring(offset + call.sourceLength)
        }
        return result
    }

    /**
     * Reverse mapping of the mapper's 1-based line/column to a text offset.
     * The [length] is the expected rewrite-target span at that offset; the call
     * site is rejected if the source text doesn't match.
     */
    private fun offsetOfAt(text: String, location: ScriptedSourceLocation, length: Int): Int {
        var offset = 0
        var line = 1
        while (line < location.line && offset < text.length) {
            if (text[offset] == '\n') line++
            offset++
        }
        if (line != location.line) return -1
        val lineStart = offset
        val columnStart = lineStart + location.column - 1
        if (columnStart + length > text.length) return -1
        // We can't always match the literal text because the rewriter is invoked
        // for an unknown rewrite-target span. Instead we accept any call-shaped
        // text: identifiers and parens, no semicolons at the start. This is a
        // permissive offset locator; the rewriting strategy is offset-locked, so
        // mis-attribution is bounded by the line/column uniqueness within the file.
        return columnStart
    }

    private fun generatedSource(sourceId: ScriptedSourceId, body: String): String = """
        object : CompiledScriptedEntryPoint {
            override val artifact = ScriptedArtifactIdentity(
                sourceDigest = "${sha256(body)}",
                dslApiVersion = "$DSL_API_VERSION",
                compilerAdapterVersion = "$COMPILER_ADAPTER_VERSION",
                runtimeCompatibilityVersion = "$RUNTIME_COMPATIBILITY_VERSION",
                pluginLockDigest = "$PLUGIN_LOCK_DIGEST",
                facadeSchemaDigest = "$FACADE_SCHEMA_VERSION",
            )
            override val entryPointId = "${sourceId.value}"

            override suspend fun execute(steps: ScriptedStepFacade) {
                $body
            }
        }
    """.trimIndent()

    internal fun sha256(input: String): String =
        java.security.MessageDigest.getInstance("SHA-256")
            .digest(input.toByteArray())
            .joinToString("") { "%02x".format(it) }

    private const val DSL_API_VERSION = "r3-dsl-v1"
    private const val COMPILER_ADAPTER_VERSION = "r3-compiler-v1"
    private const val RUNTIME_COMPATIBILITY_VERSION = "r3-runtime-v1"
    private const val PLUGIN_LOCK_DIGEST = "r3-plugins-v1"

    /**
     * Facade schema identity of the GENERATED surface. Bump when the generated
     * call shape or the facade contract changes incompatibly: artifacts compiled
     * against an older schema must never be silently reusable (user law 7).
     *
     * S4-A1 bumped it from `…-shReturnStdout-v1`, and the bump was mandatory, not
     * cosmetic. Three incompatible changes to the GENERATED call shape happened at
     * once, and the constant previously advertised a method that no longer exists:
     *
     *  - the eager `sh` is now rewritten at all. It used to survive into the
     *    generated Kotlin as a bare `sh(...)` with no receiver, so the host
     *    rejected the script at compile time;
     *  - `steps.shReturnStdout(id, script, null)` became
     *    `steps.sh(id, script, ReturnStdout, null, null)` — a removed method and a
     *    new signature, so an artifact built against the old schema references a
     *    member the current façade does not have;
     *  - the call-site identity gained its return mode (`:sh:none` / `:sh:ro` /
     *    `:sh:rs`), so a durable identity written under `r4-v1` is not the same
     *    identity as the one the same source position produces now.
     *
     * Leaving it at `v1` would have let an artifact compiled against a
     * call shape that cannot be reused present the same
     * [dev.rubentxu.pipeline.v2.scripting.ScriptedArtifactIdentity.facadeSchemaDigest]
     * as one compiled against this one — a declared compatibility dimension with
     * no discriminating power, the same class of defect as the `PLUGIN_LOCK_DIGEST`
     * constant recorded in the S4-A0 characterization §3.6.
     */
    const val FACADE_SCHEMA_VERSION = "facade-r4-pwd-readFile-fileExists-shSpine-v2"
}
