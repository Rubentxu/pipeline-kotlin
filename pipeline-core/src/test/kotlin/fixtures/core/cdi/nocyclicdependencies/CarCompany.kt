package fixtures.core.cdi.nocyclicdependencies


import fixtures.core.cdi.nocyclicdependencies.interfaces.ICar
import fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany
import fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman
import fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.IConfigClient


@PipelineComponent(name = "carCompany")
class CarCompany(val car: fixtures.core.cdi.nocyclicdependencies.interfaces.ICar) : fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany,
    fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle {


    override fun startWork(human: fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman) {
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

    override fun configure(configClient: IConfigClient) {

    }
}
