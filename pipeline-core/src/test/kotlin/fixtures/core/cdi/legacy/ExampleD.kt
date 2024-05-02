package fixtures.core.cdi.legacy


import fixtures.core.cdi.legacy.interfaces.IExampleC
import fixtures.core.cdi.legacy.interfaces.IExampleH
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent

@PipelineComponent
class ExampleD(private val exampleH: fixtures.core.cdi.legacy.interfaces.IExampleH) :
    fixtures.core.cdi.legacy.interfaces.IExampleC