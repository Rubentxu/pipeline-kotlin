package fixtures.core.cdi.nocyclicdependencies


import fixtures.core.cdi.nocyclicdependencies.interfaces.ICar
import fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.IConfigClient

@PipelineComponent
class AnimalCar(private val animal: fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal) :
    fixtures.core.cdi.nocyclicdependencies.interfaces.ICar {

    override fun honk() {
        println("Honking the animal car...")
    }

    override fun startEngine() {
        println("Starting the animal car's engine...")
    }

    override fun move() {
        println("Moving the animal car...")
    }

    override fun configure(configClient: IConfigClient) {

    }
}