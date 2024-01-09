package dev.rubentxu.pipeline.validation

import dev.rubentxu.pipeline.model.validations.StringValidator
import dev.rubentxu.pipeline.model.validations.evaluate
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class StringValidatorSpec : FunSpec({
    val sampleEmail = "example@email.com"
    val sampleUrl = "https://www.example.com"
    val sampleString = "hello world"

    context("StringValidator tests") {

        test("moreThan should validate if string length is more than specified value") {
            val validator = sampleString.evaluate()
            validator.moreThan(3).isValid shouldBe true
            validator.moreThan(11).isValid shouldBe false
        }

        test("lessThan should validate if string length is less than specified value") {
            val validator = sampleString.evaluate()
            validator.lessThan(15).isValid shouldBe true
            validator.lessThan(5).isValid shouldBe false
        }

        test("between should validate if string length is between specified values") {
            val validator = sampleString.evaluate()
            validator.between(3, 15).isValid shouldBe true
            validator.between(15, 20).isValid shouldBe false
        }

        test("contains should validate if string contains specified substring") {
            val validator = sampleString.evaluate()
            validator.contains("hello").isValid shouldBe true
            validator.contains("bye").isValid shouldBe false
        }

        test("isEmail should validate if string is a valid email format") {
            val validator = sampleEmail.evaluate()
            validator.isEmail().isValid shouldBe true
            StringValidator.from("invalid_email").isEmail().isValid shouldBe false
        }

        test("matchRegex should validate if string matches the specified regex") {
            val regex = "^[a-z]+.*".toRegex()
            val validator = "abc123".evaluate()
            validator.matchRegex(regex).isValid shouldBe true
            StringValidator.from("123abc").matchRegex(regex).isValid shouldBe false
        }

        test("isHttpProtocol should validate if string is a valid HTTP URL") {
            val validator = StringValidator.from(sampleUrl)
            validator.isHttpProtocol().isValid shouldBe true
            StringValidator.from("ftp://example.com").isHttpProtocol().isValid shouldBe false
        }

        test("containsIn should validate if string is one of the elements in the specified list") {
            val list = listOf("apple", "banana", "orange")
            val validator = StringValidator.from("banana")
            validator.containsIn(list).isValid shouldBe true
            StringValidator.from("grape").containsIn(list).isValid shouldBe false
        }

        test("notEmpty should validate if string is not empty") {
            val validator = StringValidator.from("not empty")
            validator.notEmpty().isValid shouldBe true
            StringValidator.from("").notEmpty().isValid shouldBe false
        }
    }
})

