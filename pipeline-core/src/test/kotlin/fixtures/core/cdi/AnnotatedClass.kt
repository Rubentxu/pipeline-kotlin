package fixtures.core.cdi

import dev.rubentxu.pipeline.core.cdi.annotations.Inject
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.interfaces.ILogger
import fixtures.core.cdi.interfaces.IGreeting


@PipelineComponent
class AnnotatedClass : fixtures.core.cdi.interfaces.IGreeting {
    @Inject
    lateinit var logger: ILogger


    override fun sayHello(name: String) {
        println("Hello, $name!")
    }
}