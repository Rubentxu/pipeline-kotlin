package dev.rubentxu.pipeline.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class NumberValidatorSpec : FunSpec({

    context("Number validation tests") {

        test("moreThan should validate if number is more than specified value") {
            val validator = 4.validate()
            validator.moreThan(3).isValid shouldBe true
            validator.moreThan(11).isValid shouldBe false
        }

        test("lessThan should validate if number is less than specified value") {
            val validator = 4.validate()
            validator.lessThan(15).isValid shouldBe true
            validator.lessThan(3).isValid shouldBe false
        }

        test("between should validate if number is between specified values") {
            val validator = 4.validate()
            validator.between(3, 15).isValid shouldBe true
            validator.between(15, 20).isValid shouldBe false
        }

        test("isPositive should validate if number is positive") {
            val validator = 4.validate()
            validator.isPositive().isValid shouldBe true

        }

        test("isNegative should validate if number is negative") {
            val validator = (-4).validate()
            validator.isNegative().isValid shouldBe true

        }

        test("isZero should validate if number is zero") {
            val validator = 0.validate()
            validator.isZero().isValid shouldBe true

        }

        test("isNumber should validate if string is a valid number") {
            val validator = "123".validate()
            validator.isNumber().isValid shouldBe true
            StringValidator.from("abc").isNumber().isValid shouldBe false
        }



    }
})

