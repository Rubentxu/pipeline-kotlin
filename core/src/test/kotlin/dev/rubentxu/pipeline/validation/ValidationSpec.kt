package dev.rubentxu.pipeline.validation

import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.util.Locale

class ValidationSpec : FunSpec({
    beforeTest {
        Locale.setDefault(Locale("en", "US"))
    }

    test("test notNull() with non-null value") {
        val sut = "hello"
        val validator = sut.validate()

        validator.notNull()

        validator.isValid shouldBe true
    }

    test("test notNull() with null value") {
        val sut = null
        val validator = sut.validate()

        validator.notNull()

        validator.isValid shouldBe false
        validator.validationResults.size shouldBe 1
    }

    test("test test() with valid predicate") {
        val sut = 5
        val validator = sut.validate()

        validator.notNull().moreThan(3)
        validator.isValid shouldBe true
    }

    test("test test() with invalid predicate") {
        val sut = 2
        val validator = sut.validate()

        validator.notNull().moreThan(3)

        validator.isValid shouldBe false
        validator.validationResults.size shouldBe 2
        validator.validationResults[1].errorMessage shouldBe "2 must be more than 3"
    }

    test("test throwIfInvalid() with valid validation") {
        val sut = "hello"

        shouldThrow<IllegalArgumentException>{
            sut.validate()
                .notNull()
                .contains("lolailo")
                .throwIfInvalid("Custom error message")
        }.message shouldBe "Custom error message, 'hello' must contain 'lolailo'"
    }

    test("test with invalid validation") {
        val sut = null
        val validator = sut.validate()

        validator.notNull()

        validator.isValid shouldBe false
    }

    test("test defaultValueIfInvalid() with valid validation") {
        val sut = "hello"
        val result = sut.validate()
            .notNull()
            .contains("lolailo")
            .defaultValueIfInvalid("default")



        result shouldBe "default"
    }

    test("test defaultValueIfInvalid() with invalid validation") {
        val sut = null
        val validator = sut.validate()

        val result = validator.defaultValueIfInvalid("default")

        result shouldBe "default"
    }
})
