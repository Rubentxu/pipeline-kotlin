package fixtures.core.cdi.cyclicdependencies

import fixtures.core.cdi.cyclicdependencies.interfaces.ICarCD
import fixtures.core.cdi.cyclicdependencies.interfaces.IAnimalCD
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent

@PipelineComponent
class AnimalCarCD(private val animal: fixtures.core.cdi.cyclicdependencies.interfaces.IAnimalCD) :
    fixtures.core.cdi.cyclicdependencies.interfaces.ICarCD {

    override fun honk() {
        println("Honking the animal car...")
    }

    override fun startEngine() {
        println("Starting the animal car's engine...")
    }

    override fun move() {
        println("Moving the animal car...")
    }
}