package fixtures.core.cdi.nocyclicdependencies.interfaces

import dev.rubentxu.pipeline.core.interfaces.Configurable


interface IVehicle: Configurable {

    fun startEngine()

    fun move()

}
