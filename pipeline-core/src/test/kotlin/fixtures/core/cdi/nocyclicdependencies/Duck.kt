package fixtures.core.cdi.nocyclicdependencies

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal

@PipelineComponent(priority = ConfigurationPriority.HIGH)
class Duck : fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal {

    override fun breath() {
        println("Duck breathing...")
    }

    override fun eat() {
        println("Duck eating...")
    }

}