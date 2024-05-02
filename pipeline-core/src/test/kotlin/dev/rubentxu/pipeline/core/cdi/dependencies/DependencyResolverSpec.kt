package dev.rubentxu.pipeline.core.cdi.dependencies


import fixtures.core.cdi.legacy.interfaces.IExampleH
import dev.rubentxu.pipeline.core.interfaces.ILogger
import dev.rubentxu.pipeline.core.logger.Logger
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.maps.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf


class DependencyResolverSpec : FunSpec({

    test("Dado un DependencyResolver, cuando se obtienen las instancias, entonces se debe devolver un Map") {
        val resolver = DependencyResolver()
        val instances = resolver.getInstances()

        instances.shouldBeInstanceOf<Map<*, *>>()
        instances.shouldHaveSize(0)
    }

    test("Dado un DependencyResolver, cuando se registran componentes y se inicializa, entonces se deben obtener las instancias correctamente") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()

        val instances = resolver.getInstances()

        instances.shouldBeInstanceOf<Map<*, *>>()
        instances.shouldHaveSize(1)
    }

    test("Given a DependencyResolver and ILogger are registered, when the instances are obtained with getInstance(), then the correct instances should be returned") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()

        val loggerInstance = resolver.getInstance(ILogger::class)
        loggerInstance.shouldBeInstanceOf<Logger>()
    }

    test("Given a DependencyResolver and an instance with annotated fields, when the function injectDependenciesByFields() is called with the instance, then the dependencies should be correctly injected into the annotated fields") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()
        val instance = fixtures.core.cdi.AnnotatedClass()

        resolver.injectDependenciesByFields(instance)
        instance.logger.shouldBeInstanceOf<Logger>()
    }

    test("Given a DependencyResolver and an instance with annotated fields, when the function injectDependenciesByFields() is called with the instance and a dependency is not found, then an exception should be thrown") {
        val resolver = DependencyResolver()
        resolver.initialize()
        val instance = fixtures.core.cdi.AnnotatedClass()

        val exception = shouldThrow<Exception> {
            resolver.injectDependenciesByFields(instance)
        }

        exception.message shouldBe "Dependency not found for type: 'dev.rubentxu.pipeline.core.interfaces.ILogger' and Qualifier name: ''. Check if the class exists or the qualifier name is correct"
    }

    test("Given a DependencyResolver and an instance with annotated fields, when dependencies are injected and the type of the dependency does not match the type of the field, then an exception should be thrown") {
        val resolver = DependencyResolver()

        val exception = shouldThrow<IllegalArgumentException> {
            resolver.register(ILogger::class, fixtures.core.cdi.AnnotatedClass::class)
        }

        exception.message?.contains("The registered class fixtures.core.cdi.AnnotatedClass does not implement the dependency interface dev.rubentxu.pipeline.core.interfaces.ILogger") shouldBe true
    }

    test("Given a DependencyResolver, when a class without the @PipelineComponent annotation is registered, then an exception should be thrown") {
        val resolver = DependencyResolver()

        val exception = shouldThrow<IllegalArgumentException> {
            resolver.register(ILogger::class, fixtures.core.cdi.AnnotatedClassWithoutPipelineComponent::class)
        }

        exception.message?.startsWith("The class fixtures.core.cdi.AnnotatedClassWithoutPipelineComponent is not annotated with @PipelineComponent. Please annotate the class with @PipelineComponent.") shouldBe true
    }

    test("Given a DependencyResolver, when trying to get an instance of a class that has not been registered, then an exception should be thrown") {
        val resolver = DependencyResolver()

        val exception = shouldThrow<Exception> {
            resolver.getInstance(IExampleH::class)
        }

        exception.message shouldBe "Dependency not found for type: 'fixtures.core.cdi.legacy.interfaces.IExampleH' and Qualifier name: ''. Check if the class exists or the qualifier name is correct"
    }

    test("Given a DependencyResolver and an instance without fields annotated with @Inject, when trying to inject dependencies, then it should not inject dependencies") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()
        val instance = fixtures.core.cdi.AnnotatedClassWithoutPipelineComponent()

        resolver.injectDependenciesByFields(instance)

        val exception = shouldThrow<UninitializedPropertyAccessException> {
            instance.logger shouldBe null
        }
        exception.message shouldBe "lateinit property logger has not been initialized"
    }

    test("Given a DependencyResolver and an instance with fields annotated with @Inject, when trying to inject dependencies and the type of the dependency does not match the type of the field, then an exception should be thrown") {
        val resolver = DependencyResolver()

        val exception = shouldThrow<IllegalArgumentException> {
            resolver.register(ILogger::class, fixtures.core.cdi.AnnotatedClass::class)
        }

        exception.message?.startsWith("The registered class fixtures.core.cdi.AnnotatedClass does not implement the dependency interface dev.rubentxu.pipeline.core.interfaces.ILogger") shouldBe true
    }

    test("Given a DependencyResolver and an instance with fields annotated with @Inject and @Qualifier, when trying to inject dependencies and a dependency with a specific qualifier name is not found, then an exception should be thrown") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()
        val instance = fixtures.core.cdi.AnnotatedClassWithQualifier()

        val exception = shouldThrow<Exception> {
            resolver.injectDependenciesByFields(instance)
        }

        exception.message shouldBe "Dependency not found for type: 'dev.rubentxu.pipeline.core.interfaces.ILogger' and Qualifier name: 'special'. Check if the class exists or the qualifier name is correct"
    }

    test("Given a DependencyResolver, when the function getSizedInstances() is called, then the correct number of registered instances should be returned") {
        val resolver = DependencyResolver()
        resolver.register(ILogger::class, Logger::class)
        resolver.initialize()

        val size = resolver.getSizedInstances()

        size shouldBe 1
    }

    test("Given a DependencyResolver, when the function registerCoreComponent() is called with an instance and its type, then the instance should be correctly registered") {
        val resolver = DependencyResolver()

        resolver.registerCoreComponent(
            fixtures.core.cdi.interfaces.IGreeting::class,
            fixtures.core.cdi.AnnotatedClassWithoutPipelineComponent()
        )
        resolver.initialize()

        val instance = resolver.getInstance(fixtures.core.cdi.interfaces.IGreeting::class)

        instance.shouldBeInstanceOf<fixtures.core.cdi.AnnotatedClassWithoutPipelineComponent>()
    }

})
