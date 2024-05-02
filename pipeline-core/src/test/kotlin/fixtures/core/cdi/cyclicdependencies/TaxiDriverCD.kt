package fixtures.core.cdi.cyclicdependencies

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier
import fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD
import fixtures.core.cdi.cyclicdependencies.interfaces.IVehicleCD

@PipelineComponent(name = "cabby")
class TaxiDriverCD(@Qualifier("carCompany") private val vehicle: fixtures.core.cdi.cyclicdependencies.interfaces.IVehicleCD) :
    fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD {

    override fun breath() {
        println("I am a taxi driver and I am breathing")
    }

    override fun eat() {
        println("I am a taxi driver and I am eating")
    }

    override fun speak() {
        println("I am a taxi driver")
    }

    override fun work() {
        vehicle.startEngine()
        vehicle.move()
    }

}