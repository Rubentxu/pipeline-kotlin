package dev.rubentxu.pipeline.v2.events.identity

import dev.rubentxu.pipeline.v2.domain.identity.ResourceRefs
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test

/**
 * EVT-1 R2: EventRef uniqueness is (source, eventId); blank EventId rejected.
 */
class EventRefLawsTest {
    @Test
    fun `R2 - EventRef uniqueness is source plus eventId`() {
        val source = ResourceRefs.run("01987654-3210-fedc-ba98-76543210fedc")
        val other = ResourceRefs.run("another-run")
        val e1 = EventRef(source, EventId("evt-1"))
        val e2 = EventRef(source, EventId("evt-2"))
        val e3 = EventRef(other, EventId("evt-1"))
        val e1bis = EventRef(source, EventId("evt-1"))
        assertNotEquals(e1, e2)
        assertNotEquals(e1, e3)
        assertEquals(e1, e1bis)
    }

    @Test
    fun `R2 - blank EventId is rejected`() {
        assertThrows(IllegalArgumentException::class.java) { EventId(" ") }
    }
}
