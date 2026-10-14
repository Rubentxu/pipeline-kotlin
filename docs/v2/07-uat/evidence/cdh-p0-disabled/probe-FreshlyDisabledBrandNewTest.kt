package dev.rubentxu.pipeline.v2.probe

import org.junit.jupiter.api.Disabled
import org.junit.jupiter.api.Test

/**
 * A BRAND NEW test that someone disables in passing.
 * It has nothing to do with any archived cycle.
 */
@Disabled("disabled for now")
class FreshlyDisabledBrandNewTest {
    @Test
    fun `a brand new contract nobody has triaged`() {
        // never written
    }
}
