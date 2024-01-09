package dev.rubentxu.pipeline.model.mapper

import arrow.core.*
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe

class PropertySetTest : StringSpec({

    "required should return value if present" {
        val propertySet: PropertySet = mutableMapOf("key" to "value")
        val pathSegment = "key".propertyPath().getOrElse { throw Exception(it.message) }

        val result = propertySet.required<String>(pathSegment)
        result shouldBe "value".right()
    }

    "required should fail if key is not present" {
        val propertySet: PropertySet = mutableMapOf("key" to "value")
        val pathSegment = "key2".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<String>(pathSegment)
        result shouldBe ValidationError("PathSegment 'key2' not found in PropertySet").left()
    }

    "required should fail if value is null" {
        val propertySet: PropertySet = mutableMapOf("key" to null)
        val pathSegment = "key".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<String>(pathSegment)
        result shouldBe ValidationError("PathSegment 'key' is null in PropertySet").left()
    }

    "required should fail if value is not of type T" {
        val propertySet: PropertySet = mutableMapOf("key" to 123)
        val pathSegment = "key".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<String>(pathSegment)
        result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
    }

    "required should return value if value is a list and index is present" {
        val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[1]".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<List<String>>(pathSegment)
        result shouldBe listOf("value1", "value2").right()
    }

    "required should fail if value is a list and index is not present" {
        val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
        val pathSegment = "key".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<String>(pathSegment)
        result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
    }

    "required should fail if value is a list and index is out of range" {
        val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[2]".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<String>(pathSegment)
        result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
    }

    "required should fail if value is a list and index is not a number" {
        val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[a]".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<List<String>>(pathSegment)
        result shouldBe ValidationError("PathSegment 'key[a]' does not contain a number index").left()
    }

    "required should fail if value is a list and index is not range" {
        val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[-1]".propertyPath().getOrElse { throw Exception(it.message) }
        val result = propertySet.required<List<String>>(pathSegment)
        result shouldBe ValidationError("PathSegment 'key[-1]' does not range index").left()
    }

})


