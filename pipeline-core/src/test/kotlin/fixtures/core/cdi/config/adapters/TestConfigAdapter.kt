package fixtures.core.cdi.config.adapters

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.IConfigAdapter

@PipelineComponent
class TestConfigAdapter : IConfigAdapter {
    override fun loadData(): Map<String, Any> {
        return emptyMap()
    }
}