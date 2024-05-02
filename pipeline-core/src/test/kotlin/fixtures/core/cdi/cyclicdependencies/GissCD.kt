package fixtures.core.cdi.cyclicdependencies

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier
import fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD
import fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD

@PipelineComponent(name = "gissCompany")
class GissCD(@Qualifier("trucker") private val driver: fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD) :
    fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD {

    override fun startWork(human: fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD) {
        println("Hiring a new employee for GissCD...")
        human.work()
    }

}