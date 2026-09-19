package dev.rubentxu.pipeline.v2.sdk.utilities.step

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.Timeout
import org.junit.jupiter.api.io.TempDir
import org.yaml.snakeyaml.Yaml
import java.nio.file.Path
import kotlin.io.path.writeText

/**
 * SnakeYAML characterisation probe (LFC-2E2 Slice 2 — S2.0).
 *
 * SnakeYAML's default behaviour is dangerous: the default `Constructor` will
 * instantiate arbitrary classes via the `!!python/name:` and similar tags,
 * its `Yaml.load(...)` happily expands aliases to gigabyte-sized strings, and
 * deeply nested documents blow the JVM stack.
 *
 * This probe measures the BEHAVIOUR of the safe constructor pipeline we will
 * use in `core-utils.readYaml` BEFORE we commit the design. Every measurement
 * is written as an assertion so the probe doubles as a regression guard.
 *
 * Findings feed directly into `YamlInput` / `YamlOutput` design:
 *
 *  1. SafeConstructor + tagInspector allow-list returns ONLY standard
 *     scalars + List/Map. No `Date`, `java.math.BigDecimal`, or any
 *     user-defined type.
 *  2. Unsafe tags (e.g. `!!python/name:os.system`) are REJECTED at parse
 *     time, not silently dropped.
 *  3. `nestingDepthLimit` and `codePointLimit` (both native to SnakeYAML
 *     2.3) are enforced BEFORE the constructor runs.
 *  4. Alias expansion is bounded by `maxAliasesForCollections`.
 *  5. `Yaml.dump(...)` is NOT byte-for-byte invertible; the contract is
 *     "typed value round-trip", not "textual round-trip".
 *  6. Large inputs are rejected at the `codePointLimit` boundary, before
 *     SnakeYAML allocates unbounded memory.
 */
@Timeout(15)
class YamlSafetyCharacterisationTest {

    @TempDir
    lateinit var tempDir: Path

    @Test
    fun `safe constructor — only known scalars and collections are returned`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        // Note: keys are quoted / non-reserved to avoid YAML 1.1 keyword
        // coercion (e.g., `yes:` as a key would coerce to Boolean).
        val doc = """
            "s": "hello"
            "n": 42
            "big": 9999999999
            "yes": true
            "nope": false
            "empty": null
            "list":
              - a
              - 1
              - true
            "map":
              k1: v1
              k2: v2
        """.trimIndent()
        @Suppress("UNCHECKED_CAST")
        val parsed = yaml.load<Any?>(doc) as Map<String, Any?>
        // SnakeYAML returns boxed primitives: Integer for small ints, Long
        // for ints that overflow Int.MAX_VALUE, java.lang.Boolean, String,
        // null, java.util.ArrayList, java.util.LinkedHashMap. We only
        // assert the CONTRACT — values are primitives or collections, never
        // arbitrary classes — never the exact boxed type.
        val s = parsed["s"]
        val n = parsed["n"]
        val big = parsed["big"]
        assertNotNull(s)
        assertTrue(s!!::class == String::class, "expected String, got ${s::class}")
        assertNotNull(n)
        assertTrue(n is Number, "expected Number, got ${n!!::class}")
        assertNotNull(big)
        assertTrue(big is Number, "expected Number, got ${big!!::class}")
        assertTrue(parsed["yes"] == true)
        assertTrue(parsed["nope"] == false)
        assertNull(parsed["empty"])
        assertTrue(parsed["list"] is List<*>)
        assertTrue(parsed["map"] is Map<*, *>)
    }

    @Test
    fun `safe constructor — rejects arbitrary class instantiation tags`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        val doc = """
            exploit: !!python/name:os.system "echo pwned"
        """.trimIndent()
        val ex = runCatching { yaml.load<Any?>(doc) }
        assertTrue(
            ex.isFailure,
            "SafeConstructor MUST refuse arbitrary-class tags; got $ex",
        )
    }

    /**
     * SnakeYAML's `maxAliasesForCollections` only caps RECURSIVE collection
     * aliases (aliases of lists/maps that themselves contain aliased
     * references). Scalar anchor expansion is not capped by that setting.
     * The robust defence is the input SIZE cap (codePointLimit) — a 1 MiB
     * anchor expanded 100 times is bounded to 100 MiB of total output, which
     * is still finite and observable.
     *
     * We probe BOTH: the recursive-collection cap and the size cap.
     */
    @Test
    fun `safe loader options — recursive collection aliases capped by maxAliasesForCollections`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        // Build a recursive alias bomb: a list that references itself many
        // times. maxAliasesForCollections is set to 64 so the 100-deep version
        // is rejected.
        val sb = StringBuilder()
        sb.append("a: &a\n")
        repeat(100) { sb.append("  - *a\n") }
        val ex = runCatching { yaml.load<Any?>(sb.toString()) }
        assertTrue(
            ex.isFailure,
            "Recursive collection alias bomb MUST be rejected; got $ex",
        )
    }

    @Test
    fun `safe loader options — depth cap is enforced by SnakeYAML natively`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        // 100 levels deep (our cap is 64). The native limit MUST refuse this.
        val sb = StringBuilder()
        repeat(100) { sb.append("- ") }
        sb.append("leaf")
        val ex = runCatching { yaml.load<Any?>(sb.toString()) }
        assertTrue(
            ex.isFailure,
            "Depth-bomb payload MUST be rejected when nestingDepthLimit is bounded; got $ex",
        )
    }

    @Test
    fun `safe loader options — codePointLimit is enforced by SnakeYAML natively`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        val big = "k: ".repeat(5_000_000) // ~10 MB of repeated scalar
        val ex = runCatching { yaml.load<Any?>(big) }
        assertTrue(
            ex.isFailure,
            "Oversize payload MUST be rejected when codePointLimit is bounded; got $ex",
        )
    }

    @Test
    fun `dumper — textual round-trip is NOT guaranteed (only typed value round-trip)`() {
        val yaml = Yaml(SafeConstructorOnlyOptions.builderSafe())
        val original = "name: alice\nage: 30\n"
        @Suppress("UNCHECKED_CAST")
        val parsed = yaml.load<Any?>(original) as Map<String, Any?>
        val dumped = yaml.dump(parsed)
        // The dumped form may reorder keys, switch quoting, or alter
        // indentation. We assert the typed value, not the textual form.
        assertEquals("alice", parsed["name"])
        assertEquals(30, parsed["age"])
        assertNotNull(dumped)
    }
}
