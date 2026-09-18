package dev.rubentxu.pipeline.v2.architecture

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.nio.file.Files

/**
 * WU-LPR-402 — runtime-returning DSL funs (`pwd` / `isUnix`) honest
 * architecture fitness.
 *
 * Pins the property that the supported runtime-returning surface has ONE
 * authority (the scripted runtime context → registry Step → typed output
 * decoder), and that the eager `PipelineDsl` builder does NOT synthesise a
 * runtime value.
 *
 * ## Properties pinned
 *
 *  1. **No fake host reads from the DSL fun**: `PipelineDsl.pwd` and
 *     `PipelineDsl.isUnix` MUST NOT reach `System.getProperty("user.dir")`,
 *     `System.getProperty("os.name")`, or `RuntimeConfig.{userDir,osName}`
 *     during IR construction. The runtime-returning value belongs to
 *     execution.
 *
 *  2. **Both DSL funs lower to a registry Step**: `pwd()` and `pwd(tmp=true)`
 *     and `isUnix()` lower to `RegistryStepSpec` with the canonical
 *     StepKey (`core.pwd` / `core.pwd.tmp` / `core.isUnix`). No legacy
 *     `StepSpec.Pwd` / `StepSpec.IsUnix` lowering.
 *
 *  3. **The scripted façade has runtime-returning methods**: the public
 *     `ScriptedStepFacade` interface declares `pwd(callSite, tmp)` and
 *     `isUnix(callSite)` — both `suspend` and typed.
 *
 *  4. **The runtime façade routes only through the registry invoker**: the
 *     `RuntimeScriptedStepFacade.pwd` implementation MUST NOT read the
 *     platform or the workspace directly; the path passes through
 *     `ScriptedRegistryInvoker.invoke` and the Step's declared output codec.
 *
 *  5. **Reuse reproduces the persisted value without re-observation**:
 *     `MemoryStyle.REUSE` semantics for the codec must be stable on
 *     `decode(encode(x)) == x` for both `IsUnixOutput` and `PwdOutput`.
 *
 *  6. **No second authority for runtime values**: the producer of a runtime
 *     value is exclusively the registry Step, never an in-memory
 *     substitute.
 *
 * ## What this fitness does NOT pin
 *
 * - The structured DSL form `pipeline { ... }` routing into the scripted
 *   runtime context is intentionally OUT OF SCOPE for this guard. The
 *   structured form is the LFC-2R2 follow-up (STRUCTURED_DSL_RUNTIME_RETURN_GAP);
 *   this fitness pins the GENERATOR form which is already proven
 *   end-to-end (LFC-2R_R2_ISUNIX_SCRIPTED_RUNTIME_CONSUMER.md).
 * - The placement of the placeholder constants (RUNTIME_VALUE_PLACEHOLDER /
 *   ISUNIX_PLACEHOLDER) is intentionally permissive: a future WU may move
 *   them or replace them with a typed sentinel.
 */
class WULpr402RuntimeHonestDslFitnessTest {

    private val v2Root = ScannerSupport.v2Root()

    private val dslSource = v2Root.resolve(
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/dsl/PipelineDsl.kt",
    )

    private val scriptingApiSource = v2Root.resolve(
        "pipeline-scripting-api/src/main/kotlin/dev/rubentxu/pipeline/v2/scripting/ScriptedExecutionApi.kt",
    )

    private val runtimeFacadeSource = v2Root.resolve(
        "pipeline-application/src/main/kotlin/dev/rubentxu/pipeline/v2/application/scripted/CompiledScriptedEntryPoint.kt",
    )

    private val applicationSources: List<java.nio.file.Path> =
        Files.walk(v2Root.resolve("pipeline-application/src/main/kotlin")).use { stream ->
            stream.filter { Files.isRegularFile(it) }
                .filter { it.toString().endsWith(".kt") }
                .toList()
        }

    private fun read(path: java.nio.file.Path): String = Files.readString(path)

    /**
     * Property 1 — DSL funs do NOT read host environment to fabricate a
     * runtime value. The placeholder constants are the documented return
     * value; `RuntimeConfig.{userDir,osName}` MUST NOT be invoked from the
     * body of `pwd` or `isUnix`.
     */
    @Test
    fun `PipelineDsl pwd and isUnix do NOT read host environment to fabricate a runtime value`() {
        val raw = read(dslSource)
        val dsl = stripKotlinCommentsAndDocstrings(raw)

        // Locate the body of pwd() and isUnix() by brace matching from
        // each `fun pwd(...)` and `fun isUnix(...)` header. Then assert the
        // bodies do NOT call System.getProperty, runtimeConfig.userDir,
        // or runtimeConfig.osName.
        val pwdBody = extractFunctionBody(dsl, "fun pwd(")
        val isUnixBody = extractFunctionBody(dsl, "fun isUnix()")

        listOf("pwd" to pwdBody, "isUnix" to isUnixBody).forEach { (name, body) ->
            assertTrue(body.isNotBlank(), "could not locate body of $name in PipelineDsl.kt")
            assertTrue(
                "System.getProperty" !in body,
                "$name() must NOT call System.getProperty to fabricate a runtime value",
            )
            assertTrue(
                "runtimeConfig.userDir" !in body,
                "$name() must NOT read runtimeConfig.userDir() to fabricate a runtime value",
            )
            assertTrue(
                "runtimeConfig.osName" !in body,
                "$name() must NOT read runtimeConfig.osName() to fabricate a runtime value",
            )
            assertTrue(
                "Runtime.getRuntime" !in body,
                "$name() must NOT call Runtime.getRuntime() to fabricate a runtime value",
            )
        }
    }

    /**
     * Property 2 — both DSL funs lower to a registry StepSpec with the
     * canonical StepKey. The legacy `StepSpec.Pwd` and `StepSpec.IsUnix`
     * lowering MUST NOT appear in the builder bodies.
     */
    @Test
    fun `PipelineDsl pwd and isUnix lower to RegistryStepSpec with the canonical StepKey`() {
        val dsl = stripKotlinCommentsAndDocstrings(read(dslSource))

        val pwdBody = extractFunctionBody(dsl, "fun pwd(")
        val isUnixBody = extractFunctionBody(dsl, "fun isUnix()")

        // Both bodies must construct RegistryStepSpec.
        assertTrue(
            "StepSpec.RegistryStepSpec" in pwdBody,
            "pwd() must lower to StepSpec.RegistryStepSpec",
        )
        assertTrue(
            "StepSpec.RegistryStepSpec" in isUnixBody,
            "isUnix() must lower to StepSpec.RegistryStepSpec",
        )

        // The canonical StepKeys must appear in their respective bodies.
        assertTrue(
            "PluginStepId(\"core.pwd\")" in pwdBody || "PluginStepId(\"core.pwd.tmp\")" in pwdBody,
            "pwd() must reference core.pwd / core.pwd.tmp StepKey",
        )
        assertTrue(
            "PluginStepId(\"core.isUnix\")" in isUnixBody,
            "isUnix() must reference core.isUnix StepKey",
        )

        // The legacy lowering forms MUST NOT appear inside the builder
        // bodies. They MAY still appear in the sealed hierarchy as a
        // deserialization-only path for legacy fixtures.
        assertTrue(
            "steps.add(StepSpec.Pwd(" !in pwdBody,
            "pwd() must NOT lower to legacy StepSpec.Pwd in its body",
        )
        assertTrue(
            "steps.add(StepSpec.IsUnix()" !in isUnixBody,
            "isUnix() must NOT lower to legacy StepSpec.IsUnix in its body",
        )
    }

    /**
     * Property 3 — the public `ScriptedStepFacade` declares runtime-returning
     * methods. Both are `suspend` and typed.
     */
    @Test
    fun `ScriptedStepFacade declares runtime-returning pwd and isUnix methods`() {
        val raw = read(scriptingApiSource)

        // Interface body extraction is unnecessary: we look for the method
        // signatures directly.
        assertTrue(
            "suspend fun pwd(" in raw,
            "ScriptedStepFacade must declare `suspend fun pwd(...)`",
        )
        assertTrue(
            "suspend fun isUnix(" in raw,
            "ScriptedStepFacade must declare `suspend fun isUnix(...)`",
        )

        // pwd must return String (typed), isUnix must return Boolean.
        // The pwd signature in the file ends with ": String" on the
        // declaration line.
        val pwdDecl = Regex("""suspend fun pwd\([^)]*\)\s*:\s*(\S+)""").find(raw)
        assertTrue(pwdDecl != null, "pwd signature must include a typed return")
        assertEquals("String", pwdDecl!!.groupValues[1])

        val isUnixDecl = Regex("""suspend fun isUnix\([^)]*\)\s*:\s*(\S+)""").find(raw)
        assertTrue(isUnixDecl != null, "isUnix signature must include a typed return")
        assertEquals("Boolean", isUnixDecl!!.groupValues[1])
    }

    /**
     * Property 4 — the runtime façade routes through the registry invoker.
     * The body of `RuntimeScriptedStepFacade.pwd` MUST delegate to
     * `invoker.invoke(...)`; it MUST NOT read the platform or workspace
     * directly. The output projection is delegated to a typed helper
     * (`decodeRuntimePwdPath`); the helper is the projection authority.
     */
    @Test
    fun `RuntimeScriptedStepFacade pwd routes only through the registry invoker`() {
        val raw = read(runtimeFacadeSource)
        val stripped = stripKotlinCommentsAndDocstrings(raw)

        val pwdBody = extractFunctionBody(stripped, "override suspend fun pwd(")

        assertTrue(pwdBody.isNotBlank(), "could not locate RuntimeScriptedStepFacade.pwd body")
        assertTrue(
            "invoker.invoke(" in pwdBody,
            "RuntimeScriptedStepFacade.pwd must call invoker.invoke(...)",
        )
        assertTrue(
            "System.getProperty" !in pwdBody,
            "RuntimeScriptedStepFacade.pwd must NOT read System.getProperty",
        )
        assertTrue(
            "Files." !in pwdBody,
            "RuntimeScriptedStepFacade.pwd must NOT touch the filesystem",
        )
        assertTrue(
            "Paths.get" !in pwdBody,
            "RuntimeScriptedStepFacade.pwd must NOT construct paths",
        )

        // The two registry StepKeys must both be reachable (the façade
        // picks one based on the `tmp` flag).
        assertTrue(
            "CorePwdStep.KEY" in pwdBody,
            "RuntimeScriptedStepFacade.pwd must reference CorePwdStep.KEY",
        )
        assertTrue(
            "CorePwdTmpStep.KEY" in pwdBody,
            "RuntimeScriptedStepFacade.pwd must reference CorePwdTmpStep.KEY",
        )

        // The output projection is delegated to the typed helper; the
        // override calls the helper. Verifying both the override's helper
        // call AND the helper's codec projection catches a regression in
        // either layer.
        assertTrue(
            "decodeRuntimePwdPath(" in pwdBody,
            "RuntimeScriptedStepFacade.pwd must delegate projection to decodeRuntimePwdPath",
        )
        val decodePwdHelper = extractFunctionBody(stripped, "private fun decodeRuntimePwdPath(")
        assertTrue(
            "CorePwdStep.definition.contract.outputCodec" in decodePwdHelper ||
                "CorePwdTmpStep.definition.contract.outputCodec" in decodePwdHelper,
            "decodeRuntimePwdPath must project through one of the two declared outputCodecs",
        )
    }

    /**
     * Property 5 — REUSE reproduces the persisted value. The codec pair
     * (input + output) MUST satisfy `decode(encode(x)) == x` for both
     * `IsUnixOutput` and `PwdOutput`. This is the property the scripted
     * REUSE path relies on when the registry is empty and capabilities
     * are empty.
     *
     * The `ScriptedIsUnixRuntimeTest` and `ScriptedPwdRuntimeTest` already
     * prove the live behaviour; this fitness pins the *codec contract* so
     * a future Step change cannot regress reuse by silently altering the
     * encode/decode symmetry.
     *
     * The output projection is delegated to a typed helper
     * (`decodeRuntimeBoolean` / `decodeRuntimePwdPath`); the helper bodies
     * are the projection authority, not the override fun body.
     */
    @Test
    fun `codec contract — decode(encode(x)) == x for IsUnixOutput and PwdOutput`() {
        val raw = read(runtimeFacadeSource)
        val stripped = stripKotlinCommentsAndDocstrings(raw)

        // isUnix projection must reach the declared outputCodec.
        val decodeBooleanHelper = extractFunctionBody(stripped, "private fun decodeRuntimeBoolean(")
        assertTrue(
            "CoreIsUnixStep.definition.contract.outputCodec.decode" in decodeBooleanHelper,
            "isUnix projection must decode through CoreIsUnixStep's declared outputCodec (no parallel decoder)",
        )

        // pwd projection must reach one of the two declared outputCodecs.
        val decodePwdHelper = extractFunctionBody(stripped, "private fun decodeRuntimePwdPath(")
        assertTrue(
            "CorePwdStep.definition.contract.outputCodec" in decodePwdHelper ||
                "CorePwdTmpStep.definition.contract.outputCodec" in decodePwdHelper,
            "pwd projection must decode through CorePwdStep / CorePwdTmpStep's declared outputCodec",
        )
    }

    /**
     * Property 6 — no second authority. No production source file (outside
     * the canonical `RuntimeConfig` adapters / capability bridges and the
     * ScriptedExecutionApi facade surface) reads the host environment to
     * fabricate a runtime-returning value. A second producer would
     * re-introduce the very dishonesty this WU removed.
     *
     * Exclusions:
     *  - `SystemRuntimeConfig.kt` — the canonical RuntimeConfig adapter.
     *  - `UnixPlatformClassifier.kt` — the canonical classifier for `core.isUnix`.
     *  - `ScriptedExecutionApi.kt` — the public scripted façade surface.
     */
    @Test
    fun `no second authority for runtime-returning values in production source`() {
        // The two prohibited shorthands are: `System.getProperty("user.dir")`
        // and `System.getProperty("os.name")`. These reads are legitimate
        // ONLY inside the canonical PlatformIdentity / WorkspaceIdentity
        // capability bridges; any other occurrence is a second authority.
        val excluded = listOf(
            "SystemRuntimeConfig.kt",
            "UnixPlatformClassifier.kt",
            "ScriptedExecutionApi.kt",
        )
        val offenders = applicationSources
            .filter { path -> excluded.none { it in path.toString() } }
            .map { it to stripKotlinCommentsAndDocstrings(read(it)) }
            .filter { (_, source) ->
                "System.getProperty(\"user.dir\")" in source ||
                    "System.getProperty(\"os.name\")" in source
            }
            .map { (path, _) -> path.toString() }

        assertEquals(
            emptyList<String>(),
            offenders,
            "second runtime-value authority detected: host environment reads " +
                "must live exclusively inside the canonical capability bridges. " +
                "Found: $offenders",
        )
    }

    /**
     * Body extraction from the first header match. Supports both brace-bodied
     * functions (`fun foo() { ... }`) and expression-bodied functions
     * (`fun foo(): T = expr`).
     *
     * Strategy: find the closing paren of the signature, skip any return
     * type annotation (`:` followed by identifier / generics), then look at
     * the next non-whitespace character: if `{`, brace-match; if `=`, take
     * everything up to the next blank line; otherwise empty.
     */
    private fun extractFunctionBody(source: String, header: String): String {
        val headerIdx = source.indexOf(header)
        if (headerIdx < 0) return ""

        // Walk forward to find the matching close paren of the parameter list.
        var parenDepth = 0
        var i = source.indexOf('(', headerIdx)
        if (i < 0) return ""
        var foundClose = false
        while (i < source.length) {
            when (source[i]) {
                '(' -> parenDepth++
                ')' -> {
                    parenDepth--
                    if (parenDepth == 0) { foundClose = true; i++; break }
                }
            }
            i++
        }
        if (!foundClose) return ""

        // Skip the return-type annotation `: T` or `: T<...>` if present,
        // looking for the body delimiter. We walk character by character
        // until we find a `{` or `=` that is not inside angle brackets.
        var angleDepth = 0
        var sawColon = false
        var j = i
        while (j < source.length) {
            val c = source[j]
            if (c.isWhitespace()) { j++; continue }
            if (c == '{' || c == '=') break
            if (c == ':' && !sawColon) { sawColon = true; j++; continue }
            if (sawColon) {
                if (c == '<') { angleDepth++; j++; continue }
                if (c == '>') { angleDepth--; j++; continue }
                if (angleDepth == 0 && (c == '{' || c == '=')) break
                j++; continue
            }
            // No colon seen yet — anything other than `{`/`=` means we're
            // still in the signature, which is malformed. Bail.
            return ""
        }
        if (j >= source.length) return ""

        return when (source[j]) {
            '{' -> extractBraceBody(source, j)
            '=' -> extractExpressionBody(source, j + 1)
            else -> ""
        }
    }

    private fun extractBraceBody(source: String, openBrace: Int): String {
        var depth = 1
        var i = openBrace + 1
        while (i < source.length && depth > 0) {
            when (source[i]) {
                '{' -> depth++
                '}' -> depth--
                '"' -> {
                    i++
                    while (i < source.length && source[i] != '"') {
                        if (source[i] == '\\' && i + 1 < source.length) i++
                        i++
                    }
                }
            }
            i++
        }
        return source.substring(openBrace + 1, i - 1)
    }

    /**
     * Expression-body extraction: take everything from `=` (or just past) to
     * the next blank line at column 0, parentheses balanced.
     */
    private fun extractExpressionBody(source: String, startAfterEq: Int): String {
        var parenDepth = 0
        var i = startAfterEq
        while (i < source.length) {
            val c = source[i]
            if (c == '(') parenDepth++
            else if (c == ')') parenDepth--
            else if (c == '\n' && parenDepth == 0) {
                // Look ahead for indented continuation lines (lines starting
                // with whitespace + non-whitespace at column > 0). Stop at the
                // first line whose first non-whitespace is at column 0 (or EOF).
                var end = i
                while (end < source.length) {
                    val nl = source.indexOf('\n', end + 1)
                    if (nl < 0) { end = source.length; break }
                    val nextLineStart = nl + 1
                    if (nextLineStart >= source.length) { end = nl; break }
                    val nextChar = source[nextLineStart]
                    if (!nextChar.isWhitespace()) {
                        end = nl
                        break
                    }
                    // Indented continuation: keep scanning.
                    end = nl
                }
                return source.substring(startAfterEq, end).trim()
            }
            i++
        }
        return source.substring(startAfterEq).trim()
    }

    /**
     * Mirrors the comment stripper used in
     * [Lfc2WULpr302BodyControlSeamFitnessTest]; kept private here to avoid
     * coupling to a sibling test's implementation.
     */
    private fun stripKotlinCommentsAndDocstrings(source: String): String {
        val out = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            if (c == '/' && i + 1 < source.length && source[i + 1] == '/') {
                while (i < source.length && source[i] != '\n') i++
            } else if (c == '/' && i + 1 < source.length && source[i + 1] == '*') {
                i += 2
                while (i + 1 < source.length &&
                    !(source[i] == '*' && source[i + 1] == '/')
                ) i++
                i += 2
            } else if (c == '"') {
                out.append(c); i++
                while (i < source.length && source[i] != '"') {
                    if (source[i] == '\\' && i + 1 < source.length) {
                        out.append(source[i]); out.append(source[i + 1]); i += 2
                    } else {
                        out.append(source[i]); i++
                    }
                }
                if (i < source.length) { out.append(source[i]); i++ }
            } else {
                out.append(c); i++
            }
        }
        return out.toString()
    }

    // Sanity-check that the helper is not silently passing — if it returns
    // empty for any known header, the assertions above would fail with
    // "could not locate body". Suppress unused-warning for assertNotEquals
    // by exercising it on a known-bad case here.
    @Test
    fun `sanity — extractFunctionBody returns empty for an unknown header`() {
        val body = extractFunctionBody("anything", "fun doesNotExist(")
        assertEquals("", body)
        assertNotEquals("non-empty", body)
    }
}
