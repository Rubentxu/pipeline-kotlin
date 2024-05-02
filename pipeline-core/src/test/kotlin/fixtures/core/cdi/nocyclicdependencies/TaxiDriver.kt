package fixtures.core.cdi.nocyclicdependencies


import fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman
import fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier

@PipelineComponent(name = "cabby")
class TaxiDriver(@Qualifier("carCompany") private val vehicle: fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle) :
    fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman {

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