package fixtures.core.cdi.legacy


import fixtures.core.cdi.legacy.interfaces.IExampleC
import fixtures.core.cdi.legacy.interfaces.IExampleE
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent

@PipelineComponent(name = "ejemploE")
class ExampleE(private val exampleD: fixtures.core.cdi.legacy.interfaces.IExampleC) :
    fixtures.core.cdi.legacy.interfaces.IExampleE