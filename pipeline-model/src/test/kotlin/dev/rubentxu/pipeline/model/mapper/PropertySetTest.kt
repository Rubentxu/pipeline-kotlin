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
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key2' not found in PropertySet"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key' is null in PropertySet"))
    }

    "required should fail if value is not of type T" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to 123)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.String"))
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
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[a]' does not contain a valid number index"))
    }

    "required should fail if value is a list and index is not range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[-1]' does not contain a valid number index"))
    }

    "required should fail if value is a list and index is not present and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if value is a list and index is out of range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[2]' index 2 is out of range"))
    }

    "required should fail if value is a list and index is not a number and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[a]' does not contain a valid number index"))
    }


    "required should fail if value is a list and index is not range and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[-1]' does not contain a valid number index"))
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
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf("value1", "value2")
                )
            )
            val nestedPath = "key1.key3[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key1.key3[0]' | PathSegment 'key3' not found in PropertySet"))
    }

    "required should fail if nested path is invalid" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1..key2[0]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("NestedPath is not valid for path 'key1..key2[0]'"))
    }

    "required should fail if nested path index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key1.key2[2]' | PathSegment 'key2[2]' index 2 is out of range"))
    }

    "required should fail if nested path index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("NestedPath is not valid for path 'key1.key2[a]'"))
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
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.String"))
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
        result.toEither() shouldBe Either.Left(PropertiesError(message = "PathSegment 'key[a]' does not contain a valid number index"))
    }

    "optional should return value if value is a list and index is not present and value is a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key" to propertiesOf(
                    "key2" to listOf("value1", "value2")
                )
            )
            val pathSegment = "key.key2[*].key3".propertyPath()
            propertySet.optional<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "Optional should return a list of values when the path with an asterisk index points to a list of key-value " +
            "pairs where the key matches the filter of the asterisk index" {
                val result = effect {
                    val propertySet: PropertySet = propertiesOf(
                        "key" to propertiesOf(
                            "key2" to listOf("key3" to "value1", "key3" to "value2")
                        )
                    )
                    val pathSegment = "key.key2[*].key3".propertyPath()
                    propertySet.optional<List<String>>(pathSegment)
                }
                result.toEither() shouldBe Either.Right(listOf("value1", "value2"))
            }

    "optional should return list of PropertySet if path with asterisk index points to a list of PropertySet" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key" to propertiesOf(
                    "key2" to listOf(
                        "key3" to propertiesOf("key4" to "valueKey4", "key8" to "valueKey8"),
                        "key3" to propertiesOf("key5" to "valueKey5"),
                        "key7" to propertiesOf("key6" to "valueKey6")
                    )
                )
            )
            val pathSegment = "key.key2[*].key3".propertyPath()
            propertySet.optional<List<PropertySet>>(pathSegment)
        }
        result.toEither() shouldBe Either.Right(
            listOf(
                PropertySet(
                    data = mapOf("key4" to "valueKey4", "key8" to "valueKey8"),
                    absolutePath = "key.key2[0].key3".propertyPath()
                ),
                PropertySet(
                    data = mapOf("key5" to "valueKey5"),
                    absolutePath = "key.key2[1].key3".propertyPath()
                )
            )
        )
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
        result.toEither() shouldBe Either.Left(PropertiesError(message = "NestedPath is not valid for path 'key1.key2[a]'"))
    }

    "required should fail if value is null" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to null)
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key' is null in PropertySet"))
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
        result.toEither() shouldBe Either.Left(PropertiesError("NestedPath is not valid for path 'key..value'"))
    }

    "required should fail if path points to a non-list value but a list is expected" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.collections.List"))
    }

    "required should fail if path points to a list but a non-list value is expected" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Value for PathSegment 'key' is not of type class kotlin.String"))
    }

    "required should fail if path points to a list and index is out of range" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[3]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[3]' index 3 is out of range"))
    }

    "required should fail if path points to a list and index is not a number" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("PathSegment 'key[a]' does not contain a valid number index"))
    }

    "required should fail if path points to a map and key is not present in the map" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key.key3' | PathSegment 'key3' not found in PropertySet"))
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
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key.key3' | PathSegment 'key3' not found in PropertySet"))
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


    "required should return cached value if nested path is called more than once" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to
                        propertiesOf("key3" to "valueKey3", "key4" to "valueKey4")
            )
        )

        val result1 = effect {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result1.toEither() shouldBe Either.Right("valueKey3")


        // Segunda llamada para obtener el resultado de la caché
        val result2 = effect {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result2.getOrNull() shouldBe "valueKey3"


        val result3 = effect {
            val nestedPath = "key1.key2.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result3.getOrNull() shouldBe "valueKey4"

        val result4 = effect {
            val nestedPath = "key1.key2.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result4.getOrNull() shouldBe "valueKey4"


        val result5 = effect {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }

        result5.toEither() shouldBe Either.Right("valueKey3")

    }

    "required should return different values if nested path is different" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to "value", "key3" to "value2"))

        val nestedPath1 = "key1.key2".propertyPath()

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

    "optional should return empty list if path with asterisk index points to an empty list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to emptyList<Any>()
                )
            )
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.optional<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }

    "optional should return list of values if path with asterisk index points to a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf(
                        propertiesOf("key3" to "value1"),
                        propertiesOf("key3" to "value2"),
                        propertiesOf("key4" to "value3")
                    )
                )
            )
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.optional<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(listOf("value1", "value2"))
    }

    "optional should return null if path with asterisk index points to a non-existing key" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf(
                        propertiesOf("key3" to "value1"),
                        propertiesOf("key3" to "value2")
                    )
                )
            )
            val nestedPath = "key1.key2[*].nonExistingKey".propertyPath()
            propertySet.optional<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(null)
    }


    "required should throw error if path with asterisk index points to an empty list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to emptyList<Any>()))
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.required<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key1.key2[*].key3' | PathSegment 'key2[*].key3' does not contain an index key3"))
    }

    "required should return list of values if path with asterisk index points to a list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf(
                        propertiesOf("key3" to "value1"),
                        propertiesOf("key3" to "value2")
                    )
                )
            )
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.required<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Right(listOf("value1", "value2"))
    }

    "required should throw error if path with asterisk index points to a non-existing key" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf(
                        propertiesOf("key3" to "value1"),
                        propertiesOf("key3" to "value2")
                    )
                )
            )
            val nestedPath = "key1.key2[*].nonExistingKey".propertyPath()
            propertySet.required<List<String>>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key1.key2[*].nonExistingKey' | PathSegment 'key2[*].nonExistingKey' does not contain an index nonExistingKey"))
    }

    "required should throw error if path with asterisk index points to an empty list" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to emptyList<Any>()))
            val nestedPath = "key1.key2[*].key3.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key1.key2[*].key3.key4' | PathSegment 'key2[*].key3' does not contain an index key3"))
    }

    "required should return a list of PropertySets when the path with an asterisk index points to a list of PropertySets" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to listOf(
                        propertiesOf("key3" to propertiesOf("key4" to "value1")),
                        propertiesOf("key3" to propertiesOf("key4" to "value2"))
                    )
                )
            )
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.required<List<PropertySet>>(nestedPath)
        }
        val resultPropertySet = result.toEither().getOrNull()
        resultPropertySet?.get(0)?.absolutePath.toString() shouldBe "key1.key2[0].key3"
        resultPropertySet?.get(1)?.absolutePath.toString() shouldBe "key1.key2[1].key3"
        result.toEither() shouldBe Either.Right(
            listOf(
                PropertySet(
                    data = mapOf("key4" to "value1"),
                    absolutePath = "key1.key2[0].key3".propertyPath()
                ),
                PropertySet(
                    data = mapOf("key4" to "value2"),
                    absolutePath = "key1.key2[1].key3".propertyPath()
                )
            )
        )
    }

    "required should return a PropertySet when the path with an asterisk index points to a list of PropertySets" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf(
                "key1" to propertiesOf(
                    "key2" to propertiesOf("key3" to propertiesOf("key4" to "value1"))
                )
            )

            val nestedPath = "key1.key2".propertyPath()
            propertySet.required<PropertySet>(nestedPath)
        }
        val resultEither = result.toEither()

        resultEither shouldBe Either.Right(
            PropertySet(
                data = mapOf("key3" to propertiesOf("key4" to "value1")),
                absolutePath = "key1.key2".propertyPath()
            )
        )
        resultEither.getOrNull()?.absolutePath.toString() shouldBe "key1.key2"
    }

    "required should return value if present with errorPathContext" {
        val result = effect {
            val errorPathContext = "root".propertyPath()
            val subPropertySet = propertiesOf("key" to "value")
            subPropertySet.required<String>("key")
        }
        result.toEither() shouldBe Either.Right("value")
    }

    "required should fail if key is not present with errorPathContext" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to "value4"))
            propertySet.required<String>("key2.key3")
        }
        result.toEither() shouldBe
                Either.Left(PropertiesError("Error in path 'key2.key3' | PathSegment 'key3' not found in PropertySet"))
    }

    "required should fail if value is null with errorPathContext" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to null))
            propertySet.required<String>("key2.key4")
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key2.key4' | PathSegment 'key4' is null in PropertySet"))
    }

    "required should fail if value is not of type T with errorPathContext" {
        val result = effect {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to 123))
            propertySet.required<String>("key2.key4")
        }
        result.toEither() shouldBe Either.Left(PropertiesError("Error in path 'key2.key4' | Value for PathSegment 'key4' is not of type class kotlin.String"))
    }
})



