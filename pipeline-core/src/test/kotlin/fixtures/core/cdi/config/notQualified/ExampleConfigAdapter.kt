package fixtures.core.cdi.config.notQualified

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.IConfigAdapter


@PipelineComponent
class ExampleConfigAdapter : IConfigAdapter {
    override fun loadData(): Map<String, Any> {
        return emptyMap()
    }
}