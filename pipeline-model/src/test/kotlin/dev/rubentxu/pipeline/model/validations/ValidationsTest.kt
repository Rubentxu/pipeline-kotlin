package dev.rubentxu.pipeline.model.validations

import arrow.core.left
import arrow.core.right
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.mapper.PropertiesError
import dev.rubentxu.pipeline.model.mapper.toPropertySet
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class ValidationsTest : StringSpec({
//
//    "evaluate should return value if predicate is true" {
//        val validations: Validations<String> = "value".right()
//        val result = validations.evaluate({ it == "value" }, "Value is not 'value'")
//        result shouldBe "value".right()
//    }
//
//    "evaluate should return ValidationError if predicate is false" {
//        val validations: Validations<String> = "value".right()
//        val result = validations.evaluate({ it == "other" }, "Value is not 'other'")
//        result shouldBe mutableListOf(PropertiesError("Value is not 'other'")).left()
//    }
//
//    "notNull should return value if value is not null" {
//        val validations: Validations<String?> = "value".right()
//        val result = validations.notNull()
//        result shouldBe "value".right()
//    }
//
//    "notNull should return ValidationError if value is null" {
//        val validations: Validations<String?> = null.right()
//        val result = validations.notNull()
//        result shouldBe mutableListOf(PropertiesError("Value is null")).left()
//    }
//
//    "isNumber should return value if value is a number" {
//        val validations: Validations<Any> = 123.right()
//        val result = validations.isNumber()
//        result shouldBe 123.right()
//    }
//
//    "isNumber should return ValidationError if value is not a number" {
//        val validations: Validations<Any> = "value".right()
//        val result = validations.isNumber()
//        result shouldBe mutableListOf(PropertiesError("Value is not a number")).left()
//    }
//
//    "isPositive should return value if value is positive" {
//        val validations: NumberValidations = 123.right()
//        val result = validations.isPositive()
//        result shouldBe 123.right()
//    }
//
//    "isPositive should return ValidationError if value is not positive" {
//        val validations: NumberValidations = (-123).right()
//        val result = validations.isPositive()
//        result shouldBe mutableListOf(PropertiesError("Value is not positive")).left()
//    }
//
//    "containsKey should return value if key is present" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.containsKey("key")
//        result shouldBe propertySet.right()
//    }
//
//    "containsKey should return ValidationError if key is not present" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.containsKey("other")
//        result shouldBe mutableListOf(PropertiesError("Key 'other' is not present")).left()
//    }
//
//    "containsValue should return value if value is present" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.containsValue("value")
//        result shouldBe propertySet.right()
//    }
//
//    "containsValue should return ValidationError if value is not present" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.containsValue("other")
//        result shouldBe mutableListOf(PropertiesError("Value 'other' is not present")).left()
//    }
//
//    "isEmpty should return value if PropertySet is empty" {
//        val propertySet: PropertySet = emptyMap<String, Any?>().toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.verifyEmpty()
//        result shouldBe propertySet.right()
//    }
//
//    "isEmpty should return ValidationError if PropertySet is not empty" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.notEmpty()
//        result shouldBe mutableListOf(PropertiesError("PropertySet is not empty")).left()
//    }
//
//    "isNotEmpty should return value if PropertySet is not empty" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.notEmpty()
//        result shouldBe propertySet.right()
//    }
//
//    "isNotEmpty should return ValidationError if PropertySet is empty" {
//        val propertySet: PropertySet = emptyMap<String, Any?>().toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations.notEmpty()
//        result shouldBe mutableListOf(PropertiesError("PropertySet is not empty")).left()
//    }
//
//    "chain of validations should return value if all predicates are true" {
//        val validations: Validations<String> = "value".right()
//        val result = validations
//            .evaluate({ it == "value" }, "Value is not 'value'")
//            .evaluate({ it.length == 5 }, "Length is not 5")
//        result shouldBe "value".right()
//    }
//
//    "chain of validations should return ValidationError if any predicate is false" {
//        val validations: Validations<String> = "value".right()
//        val result = validations
//            .evaluate({ it == "value" }, "Value is not 'value'")
//            .evaluate({ it.length == 6 }, "Length is not 6")
//        result shouldBe mutableListOf(PropertiesError("Length is not 6")).left()
//    }
//
//    "chain of number validations should return value if all predicates are true" {
//        val validations: NumberValidations = 123.right()
//        val result = validations
//            .isPositive()
//            .evaluate({ it.toInt() < 200 }, "Value is not less than 200")
//        result shouldBe 123.right()
//    }
//
//    "chain of number validations should return ValidationError if any predicate is false" {
//        val validations: NumberValidations = 123.right()
//        val result = validations
//            .isPositive()
//            .evaluate({ it.toInt() > 200 }, "Value is not greater than 200")
//        result shouldBe mutableListOf(PropertiesError("Value is not greater than 200")).left()
//    }
//
//    "chain of property validations should return value if all predicates are true" {
//        val propertySet: PropertySet = mapOf("key" to "value").toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations
//            .containsKey("key")
//            .containsValue("value")
//            .notEmpty()
//        result shouldBe propertySet.right()
//    }
//
//    "chain of property validations should return ValidationError if any predicate is false" {
//        val propertySet: PropertySet = emptyMap<String, Any?>().toPropertySet()
//        val validations: PropertyValidations = propertySet.right()
//        val result = validations
//            .containsKey("key")
//            .containsValue("value")
//            .notEmpty()
//        result shouldBe mutableListOf(
//            PropertiesError("Key 'key' is not present"),
//            PropertiesError("Value 'value' is not present"),
//            PropertiesError("PropertySet is empty")
//        ).left()
//    }
})