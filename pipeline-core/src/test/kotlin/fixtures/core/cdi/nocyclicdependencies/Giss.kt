package fixtures.core.cdi.nocyclicdependencies

import fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany
import fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman
import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.annotations.Qualifier

@PipelineComponent(name = "gissCompany")
class Giss(@Qualifier("trucker") private val driver: fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman) :
    fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany {

    override fun startWork(human: fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman) {
        println("Hiring a new employee for Giss...")
        human.work()
    }

}