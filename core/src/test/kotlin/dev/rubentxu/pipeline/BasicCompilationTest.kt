package dev.rubentxu.pipeline

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

/**
 * Basic compilation test to ensure the project builds correctly.
 */
class BasicCompilationTest : FunSpec({
    
    test("basic test should pass") {
        val result = 2 + 2
        result shouldBe 4
    }
    
    test("DSL v2 testing framework should be available") {
        // This just tests that our new testing framework classes can be imported
        val registry = dev.rubentxu.pipeline.steps.testing.StepMockRegistry()
        registry shouldBe registry
    }
})