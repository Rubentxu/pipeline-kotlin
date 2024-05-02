package fixtures.core.cdi.cyclicdependencies

import fixtures.core.cdi.cyclicdependencies.interfaces.ICarCD
import fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD
import fixtures.core.cdi.cyclicdependencies.interfaces.IVehicleCD
import fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent

@PipelineComponent(name = "carCompany")
class CarCompanyCD(private val car: fixtures.core.cdi.cyclicdependencies.interfaces.ICarCD) : fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD,
    fixtures.core.cdi.cyclicdependencies.interfaces.IVehicleCD {

    override fun startWork(human: fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD) {
        human.work()
        car.startEngine()
        car.move()
    }

    override fun startEngine() {
        println("Starting engine...")
    }

    override fun move() {
        println("Moving...")
    }
}