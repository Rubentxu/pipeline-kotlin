package demo

import kotlin.test.Test
import kotlin.test.assertEquals

class AppTest {
    @Test fun `add works`() { assertEquals(5, add(2, 3)) }
    @Test fun `add is commutative`() { assertEquals(add(1, 2), add(2, 1)) }

    // WU-LPR-062 failure path: -Pdemo.broken=true injects a deliberate failure
    // so the pipeline failure route can be exercised end to end.
    @Test fun `deliberate break switch`() {
        val broken = System.getProperty("demo.broken") == "true"
        if (broken) throw AssertionError("demo.broken=true requested a failing build")
    }
}
