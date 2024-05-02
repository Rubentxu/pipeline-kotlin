package fixtures.core.cdi.nocyclicdependencies

import fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman
import fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle
import fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier

@PipelineComponent(name = "trucker")
class TruckDriver(
    @Qualifier("carCompany") private val vehicle: fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle,
    @Qualifier("carCompany") private val company: fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany
) : fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman {

    override fun breath() {
        println("I am a truck driver and I am breathing")
    }

    override fun eat() {
        println("I am a truck driver and I am eating")
    }

    override fun speak() {
        println("I am a truck driver")
    }

    override fun work() {
        company.startWork(this)
        vehicle.startEngine()
        vehicle.move()
    }

}