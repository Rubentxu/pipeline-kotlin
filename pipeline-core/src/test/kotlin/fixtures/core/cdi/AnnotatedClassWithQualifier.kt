package fixtures.core.cdi

import fixtures.core.cdi.interfaces.IGreeting
import dev.rubentxu.pipeline.core.cdi.annotations.Inject
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier
import dev.rubentxu.pipeline.core.interfaces.ILogger



class AnnotatedClassWithQualifier {
    @Inject
    @Qualifier("special")
    lateinit var logger: ILogger

}