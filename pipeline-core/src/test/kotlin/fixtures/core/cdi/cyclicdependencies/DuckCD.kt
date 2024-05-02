package fixtures.core.cdi.cyclicdependencies

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import fixtures.core.cdi.cyclicdependencies.interfaces.IAnimalCD

@PipelineComponent(priority = ConfigurationPriority.HIGH)
class DuckCD : fixtures.core.cdi.cyclicdependencies.interfaces.IAnimalCD {

    override fun breath() {
        println("DuckCD breathing...")
    }

    override fun eat() {
        println("DuckCD eating...")
    }

}