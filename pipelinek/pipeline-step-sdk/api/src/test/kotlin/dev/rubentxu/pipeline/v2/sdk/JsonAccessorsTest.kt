package dev.rubentxu.pipeline.v2.sdk

import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.boolOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.doubleOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.intOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.longOrNull
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredArray
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredBoolean
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredDouble
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredInt
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredLong
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredObject
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.requiredString
import dev.rubentxu.pipeline.v2.sdk.JsonAccessors.stringOrNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * The difference between a field a step declared and a field it did not.
 *
 * The step codecs read their payload through these accessors, and there are
 * dozens of them. The contract they rely on is the difference between the two
 * families: a REQUIRED field that is missing must fail the step, and an
 * OPTIONAL one must read as absent. A required accessor that returned null
 * instead of failing would hand a step a payload with a hole in it, and the
 * step would carry on with a value nobody put there — the kind of failure
 * that surfaces three pipelines later, in a number that is quietly wrong.
 *
 * The other half is the kind of the value. An optional accessor that returns
 * null for a field of the wrong kind is forgiving by design, because a codec
 * that reads a field nobody declared should see nothing rather than a wrong
 * zero.
 */
class JsonAccessorsTest {

    private val payload: JsonObject = buildJsonObject {
        put("name", "build")
        put("retries", 3)
        put("ratio", 0.5)
        put("enabled", true)
        put("nested", buildJsonObject { put("k", "v") })
        put("items", buildJsonObject { })
    }

    // --- required fields fail loudly ------------------------------------------------

    @Test
    fun `a required string that is missing fails the step`() {
        val failure = assertThrows(NoSuchElementException::class.java) {
            payload.requiredString("absent")
        }

        assertTrue(
            failure.message?.contains("absent") == true,
            "the refusal did not name the field: ${failure.message}",
        )
    }

    @Test
    fun `a required number that is missing fails the step`() {
        assertThrows(NoSuchElementException::class.java) { payload.requiredLong("absent") }
        assertThrows(NoSuchElementException::class.java) { payload.requiredInt("absent") }
        assertThrows(NoSuchElementException::class.java) { payload.requiredDouble("absent") }
        assertThrows(NoSuchElementException::class.java) { payload.requiredBoolean("absent") }
    }

    @Test
    fun `a required object that is not an object fails the step`() {
        // The field is there, and it is a string. Reading it as an object has to
        // fail: an empty object would look like a step that received no
        // configuration, which is a different claim from the one the payload
        // makes.
        assertThrows(IllegalArgumentException::class.java) { payload.requiredObject("name") }
    }

    @Test
    fun `a required array that is not an array fails the step`() {
        assertThrows(ClassCastException::class.java) { payload.requiredArray("name") }
    }

    // --- optional fields read as absent ---------------------------------------------

    @Test
    fun `an optional field that is missing reads as absent`() {
        assertNull(payload.stringOrNull("absent"))
        assertNull(payload.longOrNull("absent"))
        assertNull(payload.intOrNull("absent"))
        assertNull(payload.doubleOrNull("absent"))
        assertNull(payload.boolOrNull("absent"))
    }

    @Test
    fun `an optional field of the wrong kind reads as absent`() {
        // A field of another kind is not a value of this kind, and returning
        // null says exactly that. Coercing it would invent a number nobody
        // wrote. The text accessor is the exception, and deliberately: it reads
        // the CONTENT of a primitive, so a number read as text is its own
        // digits rather than null.
        assertNull(payload.longOrNull("name"))
        assertNull(payload.intOrNull("name"))
        assertNull(payload.doubleOrNull("name"))
        assertNull(payload.boolOrNull("name"))
        assertEquals("3", payload.stringOrNull("retries"))
    }

    // --- the values that are there --------------------------------------------------

    @Test
    fun `the values that are declared are read back`() {
        assertEquals("build", payload.requiredString("name"))
        assertEquals(3L, payload.requiredLong("retries"))
        assertEquals(3, payload.requiredInt("retries"))
        assertEquals(0.5, payload.requiredDouble("ratio"))
        assertEquals(true, payload.requiredBoolean("enabled"))
        assertEquals("v", payload.requiredObject("nested").requiredString("k"))
    }
}
