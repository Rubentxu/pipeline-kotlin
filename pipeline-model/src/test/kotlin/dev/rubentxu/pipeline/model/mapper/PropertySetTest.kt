package dev.rubentxu.pipeline.model.mapper

import arrow.core.Either
import arrow.core.raise.effect
import arrow.core.raise.toEither
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe


class PropertySetTest : StringSpec({

    "required should return value if present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "required should fail if key is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key2' not found in PropertySet"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key' is null in PropertySet"))
    }

    "required should fail if value is not of type T" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to 123)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should return value if value is a list and index is present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "required should fail if value is a list and index is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }

    "required should fail if value is a list and index is not range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[-1]' index -1 is out of range"))
    }

    "required should fail if value is a list and index is not present and value is a list" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number and value is a list" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }


    "required should fail if value is a list and index is not range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[-1]' index -1 is out of range"))
    }

    "required should return value if nested path is present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "required should fail if nested path is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "required should fail if nested path is invalid" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1..key2[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key1..key2[0]'"))
    }

    "required should fail if nested path index is out of range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key2[2]' index 2 is out of range"))
    }

    "required should fail if nested path index is not a number" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key1.key2[a]'"))
    }

    "optional should return value if present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "optional should return null if key is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if value is null" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if value is not of type T" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to 123)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "optional should return value if value is a list and index is present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "optional should return a list if value is a list and index is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.optional<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(listOf("value1", "value2"))
    }

    "optional should return null if value is a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return error if value is a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.optional<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError(message = "PathSegment 'key[a]' does not contain a number index"))
    }

    "optional should return value if nested path is present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "optional should return null if nested path is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if nested path index is out of range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if nested path index is not a number" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            propertySet.optional<String>(nestedPath)


        }
        result.toEither() shouldBe Either.Left(ValidationError(message = "NestedPath is not valid for path 'key1.key2[a]'"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key' is null in PropertySet"))
    }

    "optional should return null if value is null" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "required should fail if path is invalid" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key..value".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key..value'"))
    }

    "required should fail if path points to a non-list value but a list is expected" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.collections.List"))
    }

    "required should fail if path points to a list but a non-list value is expected" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if path points to a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[3]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[3]' index 3 is out of range"))
    }

    "required should fail if path points to a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }

    "required should fail if path points to a map and key is not present in the map" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to mutableMapOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "required should return value if path points to a map and key is present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to mutableMapOf("key2" to "value"))
            val pathSegment = "key.key2".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "required should fail if path points to a map and key is not present" {
        val result = effect {
            val propertySet: PropertySet = mutableMapOf("key" to mutableMapOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "contains returns true when path segment is present" {
        val propertySetList = listOf(mapOf("key" to "value").toPropertySet())

        val result = effect {
            val segment = "key".pathSegment()
            propertySetList.contains<String>(segment)
        }
        result.toEither() shouldBe Either.Right(true)
    }

    "contains returns false when path segment is not present" {
        val propertySetList = listOf(mapOf("key" to "value").toPropertySet())

        val result = effect {
            val segment = "nonexistent".pathSegment()
            propertySetList.contains<String>(segment)
        }
        result.toEither() shouldBe Either.Right(false)
    }

    "firstOrNull returns first PropertySet when path segment is present" {
        val propertySetList = listOf(mapOf("key" to "value").toPropertySet(), mapOf("key" to "value2").toPropertySet())

        val result = effect {
            val segment = "key".pathSegment()
            propertySetList.firstOrNull<String>(segment)
        }
        result.toEither() shouldBe Either.Right(mapOf("key" to "value").toPropertySet())
    }

    "firstOrNull returns null when path segment is not present" {
        val result = effect {
            val propertySetList = listOf(mapOf("key" to "value").toPropertySet())
            val segment = "nonexistent".pathSegment()
            propertySetList.firstOrNull<String>(segment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "allOrNull returns all PropertySets when path segment is present" {
        val propertySetList = listOf(mapOf("key" to "value").toPropertySet(), mapOf("key" to "value2").toPropertySet())

        val result = effect {
            val segment = "key".pathSegment()
            propertySetList.allOrEmpty<String>(segment)
        }
        result.toEither() shouldBe Either.Right(propertySetList)
    }

    "allOrNull returns empty list when path segment is not present" {
        val propertySetList = listOf(mapOf("key" to "value").toPropertySet())

        val result = effect {
            val segment = "nonexistent".pathSegment()
            propertySetList.allOrEmpty<String>(segment)
        }
        result.toEither() shouldBe Either.Right(emptyList<PropertySet>())
    }

    "merge should return empty PropertySet when both PropertySets are empty" {
        val first: PropertySet = emptyMap()
        val second: PropertySet = emptyMap()
        val result = mergePropertySets(first, second, false)
        result shouldBe emptyMap()
    }

    "merge should return second PropertySet when first PropertySet is empty" {
        val first: PropertySet = emptyMap()
        val second: PropertySet = mapOf("key" to "value")
        val result = mergePropertySets(first, second, false)
        result shouldBe second
    }

    "merge should return first PropertySet when second PropertySet is empty" {
        val first: PropertySet = mapOf("key" to "value")
        val second: PropertySet = emptyMap()
        val result = mergePropertySets(first, second, false)
        result shouldBe first
    }

    "merge should return combined PropertySet when no duplicate keys" {
        val first: PropertySet = mapOf("key1" to "value1").toPropertySet()
        val second: PropertySet = mapOf("key2" to "value2").toPropertySet()
        val result = mergePropertySets(first, second, false)
        result shouldBe mapOf("key1" to "value1", "key2" to "value2")
    }

    "merge should return combined PropertySet with second value when duplicate keys" {
        val first = mapOf("key" to "value1").toPropertySet()
        val second: PropertySet = mapOf("key" to "value2")
        val result = mergePropertySets(first, second, false)
        result shouldBe mapOf("key" to "value2")
    }

    "merge should return combined PropertySet with merged list when duplicate keys with list values and mergeLists is true" {
        val first: PropertySet = mapOf("key" to listOf("value1"))
        val second: PropertySet = mapOf("key" to listOf("value2"))
        val result = mergePropertySets(first, second, true)
        result shouldBe mapOf("key" to listOf("value1", "value2"))
    }

    "merge should return combined PropertySet with second list when duplicate keys with list values and mergeLists is false" {
        val first: PropertySet = mapOf("key" to listOf("value1"))
        val second: PropertySet = mapOf("key" to listOf("value2"))
        val result = mergePropertySets(first, second, false)
        result shouldBe mapOf("key" to listOf("value2"))
    }



})

