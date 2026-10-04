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
            val body = rewrite(sourceText, mapping.calls, mapping.loopScopes)
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
     * S4-IDENTITY I2 — one edit pass, every offset resolved against the ORIGINAL text.
     *
     * This replaces an offset-locked rewriter that resolved each call's position
     * against the *already-rewritten* buffer. That worked only because the loop
     * wrappers did not exist: a wrapper spanning a body changes the offsets of
     * everything inside it, so per-call resolution against a mutating buffer and
     * structural scoping cannot coexist. Resolving every edit up front, against
     * immutable source text, and applying them back-to-front makes the two
     * composable and removes the "mis-attribution is bounded by line/column
     * uniqueness" caveat entirely.
     *
     * Two kinds of edit:
     *  - call rewrites, over [ScriptedMappedCall.sourceLength] — the call's real
     *    source extent, not the length of a canonical spelling;
     *  - loop-scope insertions, two pure insertions per loop (open after `{`, close
     *    before `}`), which is what makes nested loops fall out correctly: an
     *    insertion never invalidates an offset, and back-to-front application keeps
     *    every earlier offset exact.
     */
    private fun rewrite(
        text: String,
        calls: List<ScriptedMappedCall>,
        loopScopes: List<ScriptedLoopScope>,
    ): String {
        val edits = mutableListOf<TextEdit>()

        for (scope in loopScopes) {
            val scopeId = scope.scopeId.value
            edits += TextEdit(
                offset = scope.bodyStartOffset + 1,
                length = 0,
                replacement = "\nsteps.scoped(ScriptedDynamicScopeId(\"$scopeId\")) {",
            )
            edits += TextEdit(scope.bodyEndOffset, 0, "\n}")
        }

        for (call in calls) {
            val start = offsetOf(text, call.location)
            if (start < 0 || start + call.sourceLength > text.length) continue
            edits += TextEdit(start, call.sourceLength, facadeCall(call))
        }

        // Back-to-front. Ties on the same offset resolve by construction order, so
        // a stable sort makes the output deterministic rather than incidental.
        var result = text
        edits.sortedWith(compareByDescending<TextEdit> { it.offset }.thenBy { it.order })
            .forEach { edit ->
                result = result.substring(0, edit.offset) +
                    edit.replacement +
                    result.substring(edit.offset + edit.length)
            }
        return result
    }

    /**
     * One text substitution, resolved against the original source.
     *
     * [order] exists only to make same-offset edits deterministic; it is not part of
     * the substitution's meaning.
     */
    private class TextEdit(
        val offset: Int,
        val length: Int,
        val replacement: String,
        val order: Int = 0,
    )

    /** The façade call text for one mapped call. */
    private fun facadeCall(call: ScriptedMappedCall): String = when (val kind = call.kind) {
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

    /**
     * The mapper's 1-based line/column to a character offset in [text].
     *
     * Resolved once, against the untouched source. `-1` when the location does not
     * exist in this text, in which case the call is left alone rather than rewritten
     * at a guessed offset — the same refusal `offsetOfAt` made, now without the
     * buffer it used to consult.
     */
    private fun offsetOf(text: String, location: ScriptedSourceLocation): Int {
        var offset = 0
        var line = 1
        while (line < location.line && offset < text.length) {
            if (text[offset] == '\n') line++
            offset++
        }
        if (line != location.line) return -1
        val start = offset + location.column - 1
        return if (start in 0..text.length) start else -1
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
    // ADR-0103 D6. Bumped r3 -> r4 by R1-E, in the SAME cut that stopped hashing scripted
    // operations under a hardcoded memoized policy and started hashing the declared one. The
    // fingerprint of every scripted operation whose Step declares anything other than the
    // memoized default therefore changes: the shell Step and the error Step among them. Those
    // existing `scripted.*` rows now diverge and the divergence gate answers
    // REPLAY_COMPATIBILITY, which is the intended outcome — an explicit incompatibility rather
    // than a silent re-execution of an effect believed to be fresh.
    //
    // No row is rewritten, rehashed or migrated. This constant is the dimension that already
    // exists to declare a durable model change, which is why D6 chose it over a parallel schema
    // version. Bumping it separately from the behaviour change would let an artifact built
    // before the change be reused by a runtime that no longer shares its identity law.
    //
    // Naming the type in this comment would register a phantom consumer module in
    // SharedModelCompositionFitnessTest, whose `consumersOf` is a raw text search rather than a
    // symbol resolution. The dependency this bump does not create must not be recorded as though
    // it had.
    private const val RUNTIME_COMPATIBILITY_VERSION = "r4-runtime-v1"
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
