package fixtures.core.cdi.legacy

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier

@PipelineComponent(name = "ejemploA")
class ExampleA(
    private val exampleB: fixtures.core.cdi.legacy.interfaces.IExampleB,
    @Qualifier(value = "ejemploC") private val exampleC: fixtures.core.cdi.legacy.interfaces.IExampleC,
    @Qualifier(value = "ejemploE") private val exampleE: fixtures.core.cdi.legacy.interfaces.IExampleE
) : fixtures.core.cdi.legacy.interfaces.IExampleZ, fixtures.core.cdi.legacy.interfaces.IExampleA