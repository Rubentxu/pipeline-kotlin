package fixtures.core.cdi.config.notAnnotated

import dev.rubentxu.pipeline.core.interfaces.IConfigAdapter

class NotAnnotatedConfigAdapter : IConfigAdapter {
    override fun loadData(): Map<String, Any> {
        return emptyMap()
    }
}