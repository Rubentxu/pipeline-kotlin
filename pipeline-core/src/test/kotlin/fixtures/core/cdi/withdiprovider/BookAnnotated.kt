package fixtures.core.cdi.withdiprovider


import fixtures.core.cdi.interfaces.IBook
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.Configurable
import dev.rubentxu.pipeline.core.interfaces.IConfigClient

@PipelineComponent
class BookAnnotated :  fixtures.core.cdi.interfaces.IBook, Configurable {

    override fun countLines(): Int {
        return 30
    }

    override fun configure(configClient: IConfigClient) {

    }
}