package fixtures.core.cdi.withdiprovider


import fixtures.core.cdi.interfaces.IDummy

class DummyNotAnnotated: fixtures.core.cdi.interfaces.IDummy {

    override fun doSomething() {
        println("Doing something")
    }

}
