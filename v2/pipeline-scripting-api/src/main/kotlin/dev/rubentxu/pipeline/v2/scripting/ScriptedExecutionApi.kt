package dev.rubentxu.pipeline.v2.scripting

/** Stable source identity emitted for one effectful scripted call. */
@JvmInline
value class ScriptedCallSiteId(val value: String) {
    init {
        require(value.isNotBlank()) { "Scripted call-site id must not be blank" }
    }
}

/** Stable dynamic-scope segment emitted for one generated loop or block. */
@JvmInline
value class ScriptedDynamicScopeId(val value: String) {
    init {
        require(value.isNotBlank()) { "Scripted dynamic scope id must not be blank" }
    }
}

/** Stable generator input identifying a source file independently of runtime paths. */
@JvmInline
value class ScriptedSourceId(val value: String) {
    init {
        require(value.isNotBlank()) { "Scripted source id must not be blank" }
    }
}

/** Typed name of a generated lexical block such as credentials or retry. */
@JvmInline
value class ScriptedBlockName(val value: String) {
    init {
        require(value.isNotBlank()) { "Scripted block name must not be blank" }
    }
}

/**
 * Immutable compiler/source-map location from which generated identities are
 * derived. The generator supplies repository-stable [sourceId], never a
 * machine-specific runtime path or stack trace.
 */
data class ScriptedSourceLocation(
    val sourceId: ScriptedSourceId,
    val line: Int,
    val column: Int,
) {
    init {
        require(line > 0) { "Scripted source line must be positive" }
        require(column > 0) { "Scripted source column must be positive" }
    }

    /** Stable source identity emitted for a generated `sh` call. */
    fun shellCallSite(): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:sh",
    )

    /**
     * Stable source identity emitted for a generated runtime-returning platform
     * query call. Deliberately DISTINCT from [shellCallSite]: two different steps
     * transformed at the same source position must never collide on one durable
     * call-site identity.
     */
    fun unixCallSite(): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:isUnix",
    )

    /**
     * Stable source identity emitted for a generated runtime-returning workspace
     * query call (`pwd()` / `pwd(tmp=true)`). Deliberately DISTINCT from
     * [shellCallSite] and [unixCallSite]: three different steps transformed at
     * the same source position must never collide on one durable call-site
     * identity.
     */
    fun pwdCallSite(tmp: Boolean = false): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:pwd${if (tmp) ":tmp" else ""}",
    )

    /** Stable dynamic scope for a generated loop iteration. */
    fun loopScope(iteration: Int): ScriptedDynamicScopeId {
        require(iteration >= 0) { "Scripted loop iteration must not be negative" }
        return ScriptedDynamicScopeId("loop:${sourceId.value}:$line:$column[$iteration]")
    }

    /** Stable dynamic scope for a generated lexical block. */
    fun blockScope(blockName: ScriptedBlockName): ScriptedDynamicScopeId = ScriptedDynamicScopeId(
        "block:${blockName.value}@${sourceId.value}:$line:$column",
    )

    /**
     * Stable source identity emitted for a generated runtime-returning file-read
     * call (LFC-2R2). Deliberately DISTINCT from [shellCallSite], [unixCallSite],
     * and [pwdCallSite]: each call-site kind keeps its own collision-free bucket
     * so the same source position never produces two competing identities.
     */
    fun readFileCallSite(): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:readFile",
    )

    /**
     * Stable source identity emitted for a generated runtime-returning
     * file-existence check (LFC-2R2). Deliberately DISTINCT from
     * [readFileCallSite], [shellCallSite], [unixCallSite], and
     * [pwdCallSite]: one identity per kind, no collision across kinds.
     */
    fun fileExistsCallSite(): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:fileExists",
    )

    /**
     * Stable source identity emitted for a generated runtime-returning
     * `sh(...)` invocation with `returnStdout = true` (LFC-2R2).
     * Deliberately DISTINCT from [shellCallSite] (eager `sh`): the same
     * source position with different `returnStdout` arity must never
     * collide on one durable identity.
     */
    fun shReturnStdoutCallSite(): ScriptedCallSiteId = ScriptedCallSiteId(
        "${sourceId.value}:$line:$column:sh:ro",
    )
}

/** Source text supplied to a compiler-backed scripted source mapper. */
data class ScriptedSource(
    val sourceId: ScriptedSourceId,
    val text: String,
)

/** Pure port for extracting generated-call locations from scripted Kotlin source. */
fun interface ScriptedSourceMapper {
    fun map(source: ScriptedSource): ScriptedSourceMapping
}

/**
 * Kind of a runtime-effectful scripted call discovered by source mapping
 * (LFC-2R / R3). The ADT grows only with real consumers — never per-Step
 * speculative buckets.
 */
sealed interface ScriptedCallKind {
    data object Shell : ScriptedCallKind
    data object IsUnix : ScriptedCallKind

    /**
     * Runtime-returning workspace query (WU-LPR-402). Mirrors [IsUnix]:
     * the returned path is a durable runtime value materialized before
     * control returns to Kotlin; FRESH observes the canonical workspace
     * through the registry Step, REUSE reproduces the persisted observation
     * without re-observing.
     *
     * `pwd(tmp=true)` is the SAME kind with a tmp flag — the durable StepKey
     * (`core.pwd` vs `core.pwd.tmp`) is decided by the façade based on
     * [tmp], not by a separate ScriptedCallKind. This keeps the call-site
     * mapper ADT closed while preserving the disambiguation at the
     * registry-routing layer.
     */
    data class Pwd(val tmp: Boolean = false) : ScriptedCallKind

    /**
     * Runtime-returning workspace file read (LFC-2R2). Mirrors [IsUnix] and
     * [Pwd]: the returned String is the file content materialised before
     * control returns to Kotlin. FRESH observes through the registry Step
     * (`core.readFile`); REUSE reproduces the persisted observation
     * without re-reading the file.
     */
    data object ReadFile : ScriptedCallKind

    /**
     * Runtime-returning workspace file existence check (LFC-2R2). Mirrors
     * [IsUnix] and [Pwd]: the returned Boolean is a durable runtime value.
     * FRESH observes through the registry Step (`core.fileExists`);
     * REUSE reproduces the persisted observation without re-stat-ing.
     */
    data object FileExists : ScriptedCallKind

    /**
     * Runtime-returning `sh(...)` invocation with `returnStdout = true`
     * (LFC-2R2). Distinct from [Shell] (which is eager / Unit). The
     * rewriter uses [script] to produce the textual rewrite target
     * `steps.shReturnStdout(callSite, script)`; the façade then routes
     * through the registry Step (`core.sh` with `returnStdout = true`).
     */
    data class ShellReturnStdout(val script: String) : ScriptedCallKind
}

/** One mapped runtime-effectful call: its kind and exact source location. */
data class ScriptedMappedCall(
    val kind: ScriptedCallKind,
    val location: ScriptedSourceLocation,
)

/** Closed result of parsing source for generated scripted calls. */
sealed interface ScriptedSourceMapping {
    data class Mapped(
        val calls: List<ScriptedMappedCall>,
    ) : ScriptedSourceMapping {
        /** Back-compat view: the mapped `sh` calls in source order. */
        val shellCalls: List<ScriptedSourceLocation>
            get() = calls.filter { it.kind == ScriptedCallKind.Shell }.map { it.location }
    }

    data class InvalidSyntax(
        val diagnostics: List<ScriptedSourceDiagnostic>,
    ) : ScriptedSourceMapping
}

/** Source-local parser diagnostic that never exposes compiler implementation types. */
data class ScriptedSourceDiagnostic(
    val line: Int,
    val column: Int,
    val message: String,
) {
    init {
        require(line > 0) { "Scripted diagnostic line must be positive" }
        require(column > 0) { "Scripted diagnostic column must be positive" }
        require(message.isNotBlank()) { "Scripted diagnostic message must not be blank" }
    }
}

/** Type marker selecting Jenkins-compatible stdout return semantics. */
data object ReturnStdout

/** Type marker selecting Jenkins-compatible exit-status return semantics. */
data object ReturnStatus

/** Immutable compatibility identity of one compiled scripted artifact. */
data class ScriptedArtifactIdentity(
    val sourceDigest: String,
    val dslApiVersion: String,
    val compilerAdapterVersion: String,
    val runtimeCompatibilityVersion: String,
    val pluginLockDigest: String,
    val facadeSchemaDigest: String,
) {
    init {
        listOf(
            sourceDigest,
            dslApiVersion,
            compilerAdapterVersion,
            runtimeCompatibilityVersion,
            pluginLockDigest,
            facadeSchemaDigest,
        ).forEach { require(it.isNotBlank()) { "Scripted artifact identity fields must not be blank" } }
    }

    /** Collision-free material consumed by the application replay fingerprint. */
    fun fingerprintMaterial(): String = stableScriptedArtifactKey(listOf(
        sourceDigest,
        dslApiVersion,
        compilerAdapterVersion,
        runtimeCompatibilityVersion,
        pluginLockDigest,
        facadeSchemaDigest,
    ))
}

/** A stable executable entry point from a compatible compiled artifact. */
interface CompiledScriptedEntryPoint {
    val artifact: ScriptedArtifactIdentity
    val entryPointId: String

    suspend fun execute(steps: ScriptedStepFacade)
}

/**
 * Public generated-step façade contract. The scripting API owns this interface
 * so a `.pipeline.kts` artifact never depends on the application runtime.
 */
interface ScriptedStepFacade {
    suspend fun <T> scoped(
        scopeId: ScriptedDynamicScopeId,
        block: suspend ScriptedStepFacade.() -> T,
    ): T

    suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        encoding: String? = null,
        label: String? = null,
    )

    suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        returnStdout: ReturnStdout,
        encoding: String? = null,
        label: String? = null,
    ): String

    suspend fun sh(
        callSite: ScriptedCallSiteId,
        script: String,
        returnStatus: ReturnStatus,
        encoding: String? = null,
        label: String? = null,
    ): Int

    /**
     * Runtime-returning platform query (LFC-2R / R2). Unlike the eager DSL path,
     * the returned [Boolean] is a durable runtime value: FRESH observes the
     * execution target through the registry Step; REUSE reproduces the persisted
     * observation without re-observing. The result materializes BEFORE control
     * returns to Kotlin; a failure NEVER fabricates `false`.
     */
    suspend fun isUnix(callSite: ScriptedCallSiteId): Boolean

    /**
     * Runtime-returning workspace query (WU-LPR-402). Mirrors [isUnix] in shape
     * but returns the canonical absolute workspace path (or, with [tmp] = true,
     * a deterministic temp subdirectory under the workspace root).
     *
     * Like [isUnix], the returned path is a durable runtime value: FRESH
     * observes through the registry Step (`core.pwd` or `core.pwd.tmp`); REUSE
     * reproduces the persisted observation without re-observing. A failure
     * NEVER fabricates a placeholder string.
     *
     * `tmp=true` is supported: the `core.pwd.tmp` registry Step is registered
     * (S2-A6/G3T) and produces a deterministic `tmp-pwd-<sha256(opId)>` path
     * (no timestamp/UUID). REUSE correctly returns the same path on resume
     * without recreating the directory (the adapter skips the
     * `Files.createDirectories` when the persisted observation already names
     * an existing path).
     */
    suspend fun pwd(callSite: ScriptedCallSiteId, tmp: Boolean = false): String

    /**
     * Runtime-returning workspace file read (LFC-2R2). Mirrors [pwd] in shape:
     * FRESH observes the file content through the registry Step (`core.readFile`);
     * REUSE reproduces the persisted observation without re-reading the file.
     * A failure NEVER fabricates an empty string.
     */
    suspend fun readFile(callSite: ScriptedCallSiteId, file: String): String

    /**
     * Runtime-returning workspace file existence check (LFC-2R2). Mirrors
     * [isUnix] in shape (returns Boolean): FRESH observes through the
     * registry Step (`core.fileExists`); REUSE reproduces the persisted
     * observation without re-stat-ing. A failure NEVER fabricates `false`.
     */
    suspend fun fileExists(callSite: ScriptedCallSiteId, file: String): Boolean

    /**
     * Runtime-returning shell invocation with `returnStdout = true` (LFC-2R2).
     * Mirrors the existing [sh] overloads but returns the captured stdout as
     * a String. The route is the same: registry Step (`core.sh`) with
     * `returnStdout = true` and the Step's declared output codec decoded.
     */
    suspend fun shReturnStdout(
        callSite: ScriptedCallSiteId,
        script: String,
        encoding: String? = null,
    ): String
}

private fun stableScriptedArtifactKey(fields: List<String>): String = fields.joinToString(separator = "") { field ->
    "${field.length}:$field"
}
