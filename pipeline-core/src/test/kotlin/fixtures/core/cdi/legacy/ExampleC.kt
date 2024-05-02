package fixtures.core.cdi.legacy

import dev.rubentxu.pipeline.core.cdi.ConfigurationPriority
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import fixtures.core.cdi.legacy.interfaces.IExampleC

@PipelineComponent(priority = ConfigurationPriority.HIGH, name = "ejemploC")
class ExampleC : fixtures.core.cdi.legacy.interfaces.IExampleC {

}