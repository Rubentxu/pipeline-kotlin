package fixtures.core.cdi.withdiprovider

import fixtures.core.cdi.interfaces.IDummy
import fixtures.core.cdi.interfaces.IGreeting
import dev.rubentxu.pipeline.core.interfaces.Configurable
import dev.rubentxu.pipeline.core.interfaces.IConfigClient

class GreetingNotAnnotated(val dummy: fixtures.core.cdi.interfaces.IDummy): fixtures.core.cdi.interfaces.IGreeting, Configurable {


    override fun sayHello(name: String) {
        print("Hello, ${name}!")
        dummy.doSomething()
    }

    override fun configure(configClient: IConfigClient) {

    }
}
