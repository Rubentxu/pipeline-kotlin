package dev.rubentxu.pipeline.core.cdi

import dev.rubentxu.pipeline.core.cdi.annotations.PipelineComponent
import dev.rubentxu.pipeline.core.cdi.fixtures.MyAnnotatedClass
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.ints.shouldBeExactly
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.reflect.KClass

class ClassesInPackageScannerTest : StringSpec({
    val scanner = ClassesInPackageScanner()

    "findAnnotatedClasses should return classes with the specified annotation" {
        val result = scanner.findAnnotatedClasses("dev.rubentxu.pipeline.core.cdi", PipelineComponent::class)
        result.isSuccess shouldBe true

        val classes: Set<KClass<*>>? = result.getOrNull()
        classes.shouldNotBeNull()
        classes.shouldNotBeEmpty()
        classes.size shouldBeExactly 1
        classes.any { it.simpleName == "MyPipelineComponentClass" } shouldBe true
    }

    "findImplementers should return classes that implement the specified interface" {
        val result = scanner.findImplementers(
            "dev.rubentxu.pipeline.core.cdi",
            MyAnnotatedClass::class
        )
        result.isSuccess shouldBe true
        val classes: Set<KClass<*>>? = result.getOrNull()
        classes.shouldNotBeNull()
        classes.shouldNotBeEmpty()
        classes.size shouldBeExactly 1
        classes.any { it.simpleName == "MyAnnotatedClass" } shouldBe true
    }

    "findDefaultImplementers should return classes that implement the specified interface" {
        val result =
            scanner.findDefaultImplementers("dev.rubentxu.pipeline.core.cdi.fixtures")
        result.isSuccess shouldBe true
        val classes: Set<KClass<*>>? = result.getOrNull()
        classes.shouldNotBeNull()
        classes.shouldNotBeEmpty()
        classes.size shouldBeExactly 4
    }
})