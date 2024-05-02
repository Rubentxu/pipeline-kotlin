package fixtures.core.cdi.legacy

import fixtures.core.cdi.legacy.interfaces.IExampleB
import fixtures.core.cdi.legacy.interfaces.IExampleC
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent


@PipelineComponent
class ExampleB(private val exampleD: fixtures.core.cdi.legacy.interfaces.IExampleC) :
    fixtures.core.cdi.legacy.interfaces.IExampleB