package dev.rubentxu.pipeline.v2.application

import dev.rubentxu.pipeline.v2.events.durable.FencingToken
import dev.rubentxu.pipeline.v2.events.durable.LeaseAcquisition
import dev.rubentxu.pipeline.v2.events.durable.LeaseLiveness
import dev.rubentxu.pipeline.v2.events.durable.LeaseRecord
import dev.rubentxu.pipeline.v2.events.durable.LeaseRequest
import dev.rubentxu.pipeline.v2.events.durable.RunLeasePort
import dev.rubentxu.pipeline.v2.events.durable.RunOwnerId
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test

/**
 * La propiedad del store del lease empieza en la adquisición.
 *
 * ## La garantía
 * El store del lease tiene dueño DESDE su construcción, no desde que el
 * acquire responde bien. Las tres salidas de `acquire` son distintas y la
 * garantía es la misma en las tres: **la rama que lanza** (el I/O del lock
 * puede fallar), **la rama que rechaza** (`AlreadyOwned`, `Unverifiable`) y
 * **la rama que gana** dejan el store bajo propiedad del scope. Antes de esta
 * regla, la rama que lanza se lo llevaba sin dueño — nadie ejecutaba close ni
 * release — y las ramas de rechazo lo cerraban a mano con un `runCatching`
 * que tragaba el fallo del cierre, el mismo patrón que
 * [ResourceScope] dejó de permitir.
 *
 * ## Por qué esto no lo puede observar la capa ejecutable
 * El hueco vive entre la construcción del store y la respuesta del acquire:
 * un proceso real que lo atraviese no deja huella en su código de salida — el
 * fichero de lock y su record simplemente quedan ahí, y el siguiente lector
 * cree que el run sigue vivo. La unidad de la garantía es la función de
 * adquisición con un doble del puerto que cuenta quién la toca y cuándo.
 */
class DurableLeaseOwnershipTest {

    /**
     * El doble del puerto: cuenta cada toque y no toca ficheros. El outcome del
     * acquire lo decide la fila, y [events] es el orden observado de toques.
     *
     * Modela el contrato del puerto: liberar un lease que nadie retiene es un
     * no-op, así que el `release` sin lease no se graba. Sin esa fidelidad, el
     * orden observable de las ramas que nunca adquirieron mentiría.
     */
    private class FakeLeaseStore(
        private val acquireOutcome: () -> LeaseAcquisition,
    ) : RunLeasePort, AutoCloseable {
        val events = mutableListOf<String>()
        var releasedOwner: RunOwnerId? = null
        var closeCount = 0
        private var leaseHeld = false

        override fun acquire(request: LeaseRequest): LeaseAcquisition {
            events += "acquire"
            val outcome = acquireOutcome()
            when (outcome) {
                is LeaseAcquisition.Acquired,
                is LeaseAcquisition.TakenOver,
                is LeaseAcquisition.Reentered,
                -> leaseHeld = true
                is LeaseAcquisition.AlreadyOwned,
                is LeaseAcquisition.Unverifiable,
                -> Unit
            }
            return outcome
        }

        override fun release(ownerId: RunOwnerId) {
            if (!leaseHeld) return
            events += "release"
            releasedOwner = ownerId
        }

        override fun liveness(runId: String): LeaseLiveness = LeaseLiveness.Free

        override fun isKnown(runId: String): Boolean = false

        override fun observe(runId: String): LeaseRecord? = null

        override fun close() {
            events += "close"
            closeCount += 1
        }
    }

    private val owner: RunOwnerId = RunOwnerId.of("test-owner")!!

    /**
     * La fila del hueco: acquire lanza y, si el store no entró al scope antes,
     * el cierre del scope no tiene nada que devolver.
     */
    @Test
    @DisplayName("un acquire que lanza deja el store con dueño: el scope lo cierra al deshacerse")
    fun anAcquireThatThrowsLeavesTheStoreOwned() {
        val scope = ResourceScope()
        val store = FakeLeaseStore { error("el lock no se pudo abrir") }

        val thrown = assertThrows(IllegalStateException::class.java) {
            acquireDurableLease(scope, "run-1", owner, store)
        }
        assertEquals("el lock no se pudo abrir", thrown.message, "el fallo del acquire viaja intacto")
        scope.close()

        assertEquals(
            listOf("acquire", "close"),
            store.events,
            "el store entra al scope ANTES del acquire: la rama que lanza también lo deja con dueño",
        )
    }

    /**
     * El cierre a mano de las ramas de rechazo desaparece: el store ya es del
     * scope, y cerrarlo dos veces — una a mano con el fallo tragado y otra en
     * el scope — era la mitad silenciosa del patrón viejo.
     */
    @Test
    @DisplayName("un rechazo tipado no cierra a mano: el close ocurre cuando el scope se deshace")
    fun aTypedRefusalDoesNotCloseByHand() {
        val scope = ResourceScope()
        val holder = RunOwnerId.of("another-process")!!
        val store = FakeLeaseStore {
            LeaseAcquisition.AlreadyOwned(
                record = LeaseRecord("run-1", holder, FencingToken.of(3L), ownerAlive = true),
                heldBy = holder,
                fencingToken = FencingToken.of(3L),
            )
        }

        val refused = assertThrows(CommandRefused::class.java) {
            acquireDurableLease(scope, "run-1", owner, store)
        }
        assertEquals(2, refused.exitCode, "el rechazo de admisión del lease sale 2")
        assertEquals(
            0,
            store.closeCount,
            "ningún cierre a mano: el store sigue con dueño mientras el rechazo viaja",
        )
        scope.close()

        assertEquals(
            listOf("acquire", "close"),
            store.events,
            "el scope devuelve el store en el deshacerse del rechazo",
        )
    }

    /**
     * La rama que gana transfiere la propiedad igual que las otras dos, y el
     * epoch que viaja al Saga es el token del acquire.
     */
    @Test
    @DisplayName("una adquisición exitosa libera al dueño que adquirió, antes de cerrar")
    fun aSuccessfulAcquisitionReleasesItsOwnOwnerBeforeClosing() {
        val scope = ResourceScope()
        val store = FakeLeaseStore {
            LeaseAcquisition.Acquired(
                record = LeaseRecord("run-1", owner, FencingToken.of(7L)),
                fencingToken = FencingToken.of(7L),
            )
        }

        val lease = acquireDurableLease(scope, "run-1", owner, store)
        assertEquals(7L, lease.leaseEpoch, "el token del acquire es el epoch del fence del Saga")
        assertEquals(owner, lease.runOwner, "el dueño del lease es el dueño que pidió el acquire")
        assertEquals(
            0,
            store.closeCount,
            "el store sigue con dueño mientras el run vive",
        )
        scope.close()

        assertEquals(
            listOf("acquire", "release", "close"),
            store.events,
            "el orden del scope libera el lease antes de cerrar el store",
        )
        assertEquals(
            owner,
            store.releasedOwner,
            "el release va con el dueño que adquirió, no con otro",
        )
    }
}
