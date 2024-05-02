package fixtures.core.cdi.cyclicdependencies.interfaces

interface ICompanyCD {

    fun startWork(human: fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD)

}
