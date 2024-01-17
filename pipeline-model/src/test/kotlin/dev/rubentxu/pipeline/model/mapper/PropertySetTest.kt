package dev.rubentxu.pipeline.model.mapper

import arrow.core.Either
import arrow.core.raise.effect
import arrow.core.raise.getOrNull
import arrow.core.raise.toEither
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe


class PropertySetTest : StringSpec({

    "required should return value if present" {
        val result = effect {
            val propertySet = propertiesOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "required should fail if key is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key2' not found in PropertySet"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key' is null in PropertySet"))
    }

    "required should fail if value is not of type T" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to 123)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should return value if value is a list and index is present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "required should fail if value is a list and index is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }

    "required should fail if value is a list and index is not range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[-1]' index -1 is out of range"))
    }

    "required should fail if value is a list and index is not present and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }


    "required should fail if value is a list and index is not range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[-1]' index -1 is out of range"))
    }

    "required should return value if nested path is present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "required should fail if nested path is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "required should fail if nested path is invalid" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1..key2[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key1..key2[0]'"))
    }

    "required should fail if nested path index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key2[2]' index 2 is out of range"))
    }

    "required should fail if nested path index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key1.key2[a]'"))
    }

    "optional should return value if present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "optional should return null if key is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if value is not of type T" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to 123)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "optional should return value if value is a list and index is present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "optional should return a list if value is a list and index is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.optional<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(listOf("value1", "value2"))
    }

    "optional should return null if value is a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return error if value is a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.optional<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError(message = "PathSegment 'key[a]' does not contain a number index"))
    }

    "optional should return value if nested path is present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right("value1")
    }

    "optional should return null if nested path is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if nested path index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            propertySet.optional<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return null if nested path index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            propertySet.optional<String>(nestedPath)


        }
        result.toEither() shouldBe Either.Left(ValidationError(message = "NestedPath is not valid for path 'key1.key2[a]'"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key' is null in PropertySet"))
    }

    "optional should return null if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.optional<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "required should fail if path is invalid" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key..value".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("NestedPath is not valid for path 'key..value'"))
    }

    "required should fail if path points to a non-list value but a list is expected" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.collections.List"))
    }

    "required should fail if path points to a list but a non-list value is expected" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if path points to a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[3]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[3]' index 3 is out of range"))
    }

    "required should fail if path points to a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key[a]' does not contain a number index"))
    }

    "required should fail if path points to a map and key is not present in the map" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "required should return value if path points to a map and key is present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key2".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "required should fail if path points to a map and key is not present" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(ValidationError("PathSegment 'key3' not found in PropertySet"))
    }

    "contains returns true when path segment is present" {
        val result = effect {
            val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))
            val segment = "key".pathSegment()
            propertySetList.contains<String>(segment)
        }
        result.toEither() shouldBe Either.Right(true)
    }

    "contains returns false when path segment is not present" {
        val result = effect {
            val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))
            val segment = "nonexistent".pathSegment()
            propertySetList.contains<String>(segment)
        }
        result.toEither() shouldBe Either.Right(false)
    }

    "firstOrNull returns first PropertySet when path segment is present" {
        val result = effect {
            val propertySetList = listPropertiesOf(propertiesOf("key" to "value"), propertiesOf("key2" to "value2"))
            val segment = "key".pathSegment()
            propertySetList.firstOrNull<String>(segment)
        }
        result.toEither() shouldBe Either.Right(mapOf("key" to "value").toPropertySet())
    }

    "firstOrNull returns null when path segment is not present" {
        val result = effect {
            val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))
            val segment = "nonexistent".pathSegment()
            propertySetList.firstOrNull<String>(segment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "allOrNull returns all PropertySets when path segment is present" {
        val propertySetList = listPropertiesOf(propertiesOf("key" to "value", "key" to "value2"))
        val result = effect {
            val segment = "key".pathSegment()
            propertySetList.allOrEmpty<String>(segment)
        }
        result.toEither() shouldBe Either.Right(propertySetList)
    }

    "allOrNull returns empty list when path segment is not present" {
        val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))

        val result = effect {
            val segment = "nonexistent".pathSegment()
            propertySetList.allOrEmpty<String>(segment)
        }
        result.toEither() shouldBe Either.Right(emptyList<PropertySet>())
    }

    "required should return cached value if nested path is called more than once" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to
                propertiesOf("key3" to "valueKey3", "key4" to "valueKey4")))


        val result1 = effect {
            val nestedPath = effect { "key1.key2.key3".propertyPath() }.getOrNull()!!
            propertySet.required<String>(nestedPath)
        }
        result1.toEither() shouldBe Either.Right("valueKey3")


        // Segunda llamada para obtener el resultado de la caché
        val result2 = effect {
            val nestedPath = effect { "key1.key2.key3".propertyPath() }.getOrNull()!!
            propertySet.required<String>(nestedPath)
        }
        result2.getOrNull() shouldBe "valueKey3"


        val result3 = effect {
            val nestedPath = effect { "key1.key2.key4".propertyPath() }.getOrNull()!!
            propertySet.required<String>(nestedPath)
        }
        result3.getOrNull() shouldBe "valueKey4"

        val result4 = effect {
            val nestedPath = effect { "key1.key2.key4".propertyPath() }.getOrNull()!!
            propertySet.required<String>(nestedPath)
        }
        result4.getOrNull() shouldBe "valueKey4"


        val result5 = effect {
            val nestedPath = effect { "key1.key2.key3".propertyPath() }.getOrNull()!!
            propertySet.required<String>(nestedPath)
        }

        result5.toEither() shouldBe Either.Right("valueKey3")

    }

    "required should return different values if nested path is different" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to "value", "key3" to "value2"))

        val nestedPath1 =  effect { "key1.key2".propertyPath() }.getOrNull()!!

        val result1 = effect {
            propertySet.required<String>(nestedPath1)
        }
        result1.getOrNull() shouldBe "value"

        // Segunda llamada con una ruta diferente, por lo que no debería obtenerse de la caché
        val result2 = effect {
            val nestedPath2 = "key1.key3".propertyPath()
            propertySet.required<String>(nestedPath2)
        }
        result2.getOrNull() shouldBe "value2"

        // Verificar que los resultados son diferentes
        result1 shouldNotBe result2
    }

})



