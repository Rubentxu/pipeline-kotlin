package dev.rubentxu.pipeline.core.cdi.dependencies


import core.cdi.cyclicdependencies.*
import core.cdi.cyclicdependencies.interfaces.*
import core.cdi.nocyclicdependencies.*
import core.cdi.nocyclicdependencies.interfaces.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.matchers.nulls.shouldNotBeNull

class DependencyGraphSpec : StringSpec({


    "Given a DependencyGraph, when a node is added with a key and a class, then the node should be available in the graph" {
        val dependencyGraph = DependencyGraph()
        val key = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal::class, "animal")
        val node = dependencyGraph.addNode(key, fixtures.core.cdi.nocyclicdependencies.Duck::class)

        node.shouldNotBeNull()
        node.clazz shouldBe fixtures.core.cdi.nocyclicdependencies.Duck::class
        node.key shouldBe key
    }

    "Given a DependencyGraph, when dependencies are resolved, then an exception should be thrown if a circular dependency is detected" {
        val dependencyGraph = DependencyGraph()

        val keyCar = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.ICarCD::class)
        val keyCarCompany = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD::class, "carCompany")
        val keyVehicle = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.IVehicleCD::class, "carCompany")
        val keyAnimal = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.IAnimalCD::class)
        val keyMercaComp = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.ICompanyCD::class, "gissCompany")
        val keyCabby = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD::class, "cabby")
        val keyTrucker = DependencyKey(fixtures.core.cdi.cyclicdependencies.interfaces.IHumanCD::class, "trucker")
        dependencyGraph.addNode(keyCar, fixtures.core.cdi.cyclicdependencies.AnimalCarCD::class)
        dependencyGraph.addNode(keyCarCompany, fixtures.core.cdi.cyclicdependencies.CarCompanyCD::class)
        dependencyGraph.addNode(keyVehicle, fixtures.core.cdi.cyclicdependencies.CarCompanyCD::class)
        dependencyGraph.addNode(keyAnimal, fixtures.core.cdi.cyclicdependencies.DuckCD::class)
        dependencyGraph.addNode(keyMercaComp, fixtures.core.cdi.cyclicdependencies.GissCD::class)
        dependencyGraph.addNode(keyCabby, fixtures.core.cdi.cyclicdependencies.TaxiDriverCD::class)
        dependencyGraph.addNode(keyTrucker, fixtures.core.cdi.cyclicdependencies.TruckDriverCD::class)

        val exception = shouldThrow<Exception> {
            dependencyGraph.resolveDependencies()
        }

        exception.message.shouldNotBeNull()
        exception.message!!.startsWith("Circular dependency detected: core.cdi.cyclicdependencies.GissCD")
        exception.message!!.contains("depends on")
        exception.message!!.contains("cdi.cyclicdependencies.TruckDriverCD")
    }

    "Given a DependencyGraph, when dependencies are resolved, then dependencies should be iterated and sorted based on the number of dependencies defined in the constructor" {
        val dependencyGraph = DependencyGraph()

        val keyAnimalCar = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.ICar::class)
        val keyCarCompany = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany::class, "carCompany")
        val keyCarCompanyVehicle = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.IVehicle::class, "carCompany")
        val keyDuck = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.IAnimal::class)
        val keyGiss = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.ICompany::class, "gissCompany")
        val keyTaxiDriver = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman::class, "cabby")
        val keyTruckDriver = DependencyKey(fixtures.core.cdi.nocyclicdependencies.interfaces.IHuman::class, "trucker")


        dependencyGraph.addNode(keyAnimalCar, fixtures.core.cdi.nocyclicdependencies.AnimalCar::class)
        dependencyGraph.addNode(keyCarCompany, fixtures.core.cdi.nocyclicdependencies.CarCompany::class)
        dependencyGraph.addNode(keyCarCompanyVehicle, fixtures.core.cdi.nocyclicdependencies.CarCompany::class)
        dependencyGraph.addNode(keyDuck, fixtures.core.cdi.nocyclicdependencies.Duck::class)
        dependencyGraph.addNode(keyGiss, fixtures.core.cdi.nocyclicdependencies.Giss::class)
        dependencyGraph.addNode(keyTaxiDriver, fixtures.core.cdi.nocyclicdependencies.TaxiDriver::class)
        dependencyGraph.addNode(keyTruckDriver, fixtures.core.cdi.nocyclicdependencies.TruckDriver::class)

        val sortedNodes = dependencyGraph.resolveDependencies()

        sortedNodes.size shouldBe 7
        checkAnyInstance(sortedNodes, keyDuck, emptyList())
        checkAnyInstance(sortedNodes, keyAnimalCar, listOf(keyDuck))
        checkAnyInstance(sortedNodes, keyCarCompany, listOf(keyAnimalCar))
        checkAnyInstance(sortedNodes, keyGiss, listOf(keyTruckDriver))
        checkAnyInstance(sortedNodes, keyTaxiDriver, listOf(keyCarCompanyVehicle))
        checkAnyInstance(sortedNodes, keyCarCompanyVehicle, listOf(keyAnimalCar))
        checkAnyInstance(sortedNodes, keyTruckDriver, listOf(keyCarCompanyVehicle, keyCarCompany))
    }


})

fun checkAnyInstance(nodes: List<DependencyNode>, expectedKey: DependencyKey, expectedDependencies: List<DependencyKey>) {
    nodes.any { node ->
        if (node.key == expectedKey && node.dependencies.size == expectedDependencies.size) {
            node.dependencies.map { it.key }.shouldContainExactlyInAnyOrder(expectedDependencies)
            true
        } else {
            false
        }
    }
}