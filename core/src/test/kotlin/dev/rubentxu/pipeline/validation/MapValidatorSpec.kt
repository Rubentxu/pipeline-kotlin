package dev.rubentxu.pipeline.validation

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe

class MapValidatorSpec : FunSpec({
    context("MapValidator tests") {
        val sampleMap = mapOf("key1" to "value1", "key2" to mapOf("nestedKey" to "nestedValue"))

        test("notNull should validate if the map is not null") {
            val validator = sampleMap.validate()
            validator.notNull().isValid shouldBe true
            val sut = null
            sut.validate().notNull().isValid shouldBe false
        }

        test("getKey should validate if the key exists") {
            val validator = sampleMap.validateAndGet("key1")
            validator.isString().isValid shouldBe true
            sampleMap.validateAndGet("nonexistentKey").isValid shouldBe false
        }

        test("getKey with key parameter should validate if the specific key exists") {
            sampleMap.validateAndGet("key1").isString().isValid shouldBe true
            sampleMap.validateAndGet("nonexistentKey").isString().isValid shouldBe false
        }

        test("getValue should return the value of the key") {
            val validator = sampleMap.validate("nameVar1")
            validator.getValueByPath("key1") shouldBe "value1"
        }

        test("getValue should return the value of the key") {
            val validator = sampleMap.validateAndGet("key1")
            validator.getValue() shouldBe "value1"
        }

        test("findDeep should find nested values") {
            val validator = sampleMap.validateAndGet("key2.nestedKey").isString().isValid shouldBe true
            val validator2 = sampleMap.validateAndGet("key2.nonexistentKey").isString().isValid shouldBe false
        }

        test("notNull should throw an exception when the map is null") {
            val sut: Map<String, String>? = null
            val validation = sut.validate()

            validation.notNull().isValid shouldBe false
        }

        test("notNull should return the same instance of MapValidation when the map is not null") {
            val sut = emptyMap<String, String>()
            val validation = MapValidator.from(sut)

            validation.notNull() shouldBe validation
        }

        // ... Resto de las pruebas

        test("getValueByPath should return the resolved value of the map") {
            val sut = mapOf("key" to "value")
            val validation = sut.validate()

            validation.getValueByPath("key") shouldBe "value"
        }

        test("getValue should return null when the map is null") {
            val sut: Map<String, String>? = null
            val validation = sut.validate()

            validation.getValue() shouldBe null
        }

        // ... Resto de las pruebas

        test("getKey should set the resolvedValue property to the value of the key in the map") {
            val sut = mapOf("key" to "value")
            val validation = sut.validateAndGet("key")

            validation.getValue() shouldBe "value"
        }

        // ... Resto de las pruebas

        test("getKey should return a new instance of MapValidation when the key is present in the map but the resolved value is a false boolean") {
            val sut = mapOf("key" to false)
            val validation = sut.validateAndGet("key")

            validation.getValue() shouldBe false
        }
    }
})
