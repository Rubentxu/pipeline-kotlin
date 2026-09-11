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
            val body = rewriteIsUnixCalls(sourceText, mapping.calls)
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
     * Rewrites each mapped `isUnix()` occurrence to
     * `steps.isUnix(ScriptedCallSiteId("<src>:<line>:<col>:isUnix"))`.
     *
     * Positions come from the PSI mapping (0-based text offsets computed by the
     * mapper into 1-based line/column); rewriting walks the offsets in REVERSE
     * order so earlier replacements never shift later ranges.
     */
    private fun rewriteIsUnixCalls(text: String, calls: List<ScriptedMappedCall>): String {
        val isUnixCalls = calls
            .filter { it.kind == ScriptedCallKind.IsUnix }
            .sortedByDescending { it.location.line * 1_000_000 + it.location.column }
        var result = text
        for (call in isUnixCalls) {
            val offset = offsetOf(result, call.location)
            if (offset < 0) continue
            val callSite = call.location.unixCallSite().value
            result = result.substring(0, offset) +
                "steps.isUnix(ScriptedCallSiteId(\"$callSite\"))" +
                result.substring(offset + "isUnix()".length)
        }
        return result
    }

    /** Reverse mapping of the mapper's 1-based line/column to a text offset. */
    private fun offsetOf(text: String, location: ScriptedSourceLocation): Int {
        var offset = 0
        var line = 1
        while (line < location.line && offset < text.length) {
            if (text[offset] == '\n') line++
            offset++
        }
        if (line != location.line) return -1
        val lineStart = offset
        val columnStart = lineStart + location.column - 1
        if (columnStart + "isUnix()".length > text.length) return -1
        return if (text.regionMatches(columnStart, "isUnix()", 0, "isUnix()".length)) columnStart else -1
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
     */
    const val FACADE_SCHEMA_VERSION = "facade-r3-isUnix-v1"
}
