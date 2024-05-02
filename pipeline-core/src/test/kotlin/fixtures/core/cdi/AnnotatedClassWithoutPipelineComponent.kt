package fixtures.core.cdi


import fixtures.core.cdi.interfaces.IGreeting
import dev.rubentxu.pipeline.core.interfaces.ILogger

class AnnotatedClassWithoutPipelineComponent : fixtures.core.cdi.interfaces.IGreeting {

    lateinit var logger: ILogger

    override fun sayHello(name: String) {
        println("Hello, $name!")
    }
}