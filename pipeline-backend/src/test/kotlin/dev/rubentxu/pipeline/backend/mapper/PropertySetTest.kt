package dev.rubentxu.pipeline.backend.mapper


import dev.rubentxu.pipeline.model.PropertiesError
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe


class PropertySetTest : StringSpec({

    "required should return value if present" {

        val propertySet = propertiesOf("key" to "value")
        val pathSegment = "key".propertyPath()
        val result = propertySet.required<String>(pathSegment)

        result.getOrThrow() shouldBe "value"
    }

    "required should fail if key is not present" {
        val propertySet: PropertySet = propertiesOf("key" to "value")
        val pathSegment = "key2".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key2' not found in PropertySet"
    }

    "required should fail if value is null" {
        val propertySet: PropertySet = propertiesOf("key" to null)
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key' is null in PropertySet"
    }

    "required should fail if value is not of type T" {
        val propertySet: PropertySet = propertiesOf("key" to 123)
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "Value for PathSegment 'key' is not of type String but Int"
    }

    "required should return value if value is a list and index is present" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[0]".propertyPath()
        val result = propertySet.required<String>(pathSegment)

        result.getOrThrow() shouldBe "value1"
    }

    "required should fail if value is a list and index is not present" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "Value for PathSegment 'key' is not of type String but ArrayList"
    }

    "required should fail if value is a list and index is out of range" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[2]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[2]' index 2 is out of range"
    }

    "required should fail if value is a list and index is not a number" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[a]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[a]' does not contain a valid number index"
    }

    "required should fail if value is a list and index is not range" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[-1]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[-1]' does not contain a valid number index"
    }

    "required should fail if value is a list and index is not present and value is a list" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "Value for PathSegment 'key' is not of type String but ArrayList"
    }

    "required should fail if value is a list and index is out of range and value is a list" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[2]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[2]' index 2 is out of range"
    }

    "required should fail if value is a list and index is not a number and value is a list" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[a]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[a]' does not contain a valid number index"
    }

    "required should fail if value is a list and index is not range and value is a list" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[-1]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[-1]' does not contain a valid number index"
    }

    "required should return value if nested path is present" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[0]".propertyPath()
        val result = propertySet.required<String>(nestedPath)

        result.getOrThrow() shouldBe "value1"
    }

    "required should fail if nested path is not present" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to listOf("value1", "value2")
            )
        )
        val nestedPath = "key1.key3[0]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(nestedPath).getOrThrow()
        }.message shouldBe "PathSegment 'key3' not found in PropertySet"
    }

    "required should fail if nested path is invalid" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1..key2[0]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(nestedPath).getOrThrow()
        }.message shouldBe "NestedPath is not valid for path 'key1..key2[0]'"
    }

    "required should fail if nested path index is out of range" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[2]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(nestedPath).getOrThrow()
        }.message shouldBe "PathSegment 'key2[2]' index 2 is out of range"
    }

    "required should fail if nested path index is not a number" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[a]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(nestedPath).getOrThrow()
        }.message shouldBe "NestedPath is not valid for path 'key1.key2[a]'"
    }

    "optional should return value if present" {
        val propertySet: PropertySet = propertiesOf("key" to "value")
        val pathSegment = "key".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe "value"
    }

    "optional should return null if key is not present" {
        val propertySet: PropertySet = propertiesOf("key" to "value")
        val pathSegment = "key2".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe null
    }

    "optional should return null if value is null" {
        val propertySet: PropertySet = propertiesOf("key" to null)
        val pathSegment = "key".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe null
    }

    "optional should return null if value is not of type T" {
        val propertySet: PropertySet = propertiesOf("key" to 123)
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.optional<String>(pathSegment).getOrThrow()
        }.message shouldBe "Value for PathSegment 'key' is not of type class kotlin.String"
    }

    "optional should return value if value is a list and index is present" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[0]".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe "value1"
    }

    "optional should return a list if value is a list and index is not present" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key".propertyPath()
        val result = propertySet.optional<List<String>>(pathSegment)

        result.getOrThrow() shouldBe listOf("value1", "value2")
    }

    "optional should return null if value is a list and index is out of range" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[2]".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe null
    }

    "optional should return error if value is a list and index is not a number" {
        val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
        val pathSegment = "key[a]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.optional<List<String>>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key[a]' does not contain a valid number index"
    }

    "optional should return value if value is a list and index is not present and value is a list" {
        val propertySet: PropertySet = propertiesOf(
            "key" to propertiesOf(
                "key2" to listOf("value1", "value2")
            )
        )
        val pathSegment = "key.key2[*].key3".propertyPath()
        val result = propertySet.optional<List<String>>(pathSegment)

        result.getOrThrow() shouldBe emptyList<String>()
    }

    "Optional should return a list of values when the path with an asterisk index points to a list of key-value " +
            "pairs where the key matches the filter of the asterisk index" {
                val propertySet: PropertySet = propertiesOf(
                    "key" to propertiesOf(
                        "key2" to listOf("key3" to "value1", "key3" to "value2")
                    )
                )
                val pathSegment = "key.key2[*].key3".propertyPath()
                val result = propertySet.optional<List<String>>(pathSegment)

                result.getOrThrow() shouldBe listOf("value1", "value2")
            }

    "optional should return list of PropertySet if path with asterisk index points to a list of PropertySet" {
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
        val result = propertySet.optional<List<PropertySet>>(pathSegment)

        result.getOrThrow() shouldBe listOf(
            PropertySet(
                data = mapOf("key4" to "valueKey4", "key8" to "valueKey8"),
                absolutePath = "key.key2[0].key3".propertyPath()
            ),
            PropertySet(
                data = mapOf("key5" to "valueKey5"),
                absolutePath = "key.key2[1].key3".propertyPath()
            )
        )
    }

    "optional should return value if nested path is present" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[0]".propertyPath()
        val result = propertySet.optional<String>(nestedPath)

        result.getOrThrow() shouldBe "value1"
    }

    "optional should return null if nested path is not present" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key3[0]".propertyPath()
        val result = propertySet.optional<String>(nestedPath)

        result.getOrThrow() shouldBe null
    }

    "optional should return null if nested path index is out of range" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[2]".propertyPath()
        val result = propertySet.optional<String>(nestedPath)

        result.getOrThrow() shouldBe null
    }

    "optional should return null if nested path index is not a number" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to listOf("value1", "value2")))
        val nestedPath = "key1.key2[a]".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.optional<String>(nestedPath).getOrThrow()
        }.message shouldBe "NestedPath is not valid for path 'key1.key2[a]'"
    }

    "required should fail if value is null" {
        val propertySet: PropertySet = propertiesOf("key" to null)
        val pathSegment = "key".propertyPath()

        shouldThrow<PropertiesError> {
            propertySet.required<String>(pathSegment).getOrThrow()
        }.message shouldBe "PathSegment 'key' is null in PropertySet"
    }

    "optional should return null if value is null" {
        val propertySet: PropertySet = propertiesOf("key" to null)
        val pathSegment = "key".propertyPath()
        val result = propertySet.optional<String>(pathSegment)

        result.getOrThrow() shouldBe null
    }

    "required should fail if path is invalid" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key..value".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("NestedPath is not valid for path 'key..value'")
    }

    "required should fail if path points to a non-list value but a list is expected" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to "value")
            val pathSegment = "key".propertyPath()
            propertySet.required<List<String>>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("Value for PathSegment 'key' is not of type List but String")
    }

    "required should fail if path points to a list but a non-list value is expected" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("Value for PathSegment 'key' is not of type String but ArrayList")
    }

    "required should fail if path points to a list and index is out of range" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[3]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("PathSegment 'key[3]' index 3 is out of range")
    }

    "required should fail if path points to a list and index is not a number" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("PathSegment 'key[a]' does not contain a valid number index")
    }

    "required should fail if path points to a map and key is not present in the map" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("Error in path 'key.key3' | PathSegment 'key3' not found in PropertySet")
    }

    "required should return value if path points to a map and key is present" {
        val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
        val pathSegment = "key.key2".propertyPath()
        val result = propertySet.required<String>(pathSegment)

        result.getOrThrow() shouldBe "value"
    }

    "required should fail if path points to a map and key is not present" {
        val result = runCatching {
            val propertySet: PropertySet = propertiesOf("key" to propertiesOf("key2" to "value"))
            val pathSegment = "key.key3".propertyPath()
            propertySet.required<String>(pathSegment)
        }
        result.exceptionOrNull() shouldBe PropertiesError("Error in path 'key.key3' | PathSegment 'key3' not found in PropertySet")
    }

    "contains returns true when path segment is present" {
        val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))
        val segment = "key".pathSegment()
        val result = propertySetList.contains<String>(segment)

        result shouldBe true
    }

    "contains returns false when path segment is not present" {
        val propertySetList = listPropertiesOf(propertiesOf("key" to "value"))
        val segment = "nonexistent".pathSegment()
        val result = propertySetList.contains<String>(segment)

        result shouldBe false
    }


    "required should return cached value if nested path is called more than once" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to
                        propertiesOf("key3" to "valueKey3", "key4" to "valueKey4")
            )
        )

        val result1 = runCatching {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result1.getOrThrow() shouldBe "valueKey3"


        // Segunda llamada para obtener el resultado de la caché
        val result2 = runCatching {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result2.getOrThrow() shouldBe "valueKey3"


        val result3 = runCatching {
            val nestedPath = "key1.key2.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result3.getOrThrow() shouldBe "valueKey4"

        val result4 = runCatching {
            val nestedPath = "key1.key2.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
        result4.getOrThrow() shouldBe "valueKey4"


        val result5 = runCatching {
            val nestedPath = "key1.key2.key3".propertyPath()
            propertySet.required<String>(nestedPath)
        }

        result5.getOrThrow() shouldBe "valueKey3"

    }

    "required should return different values if nested path is different" {
        val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to "value", "key3" to "value2"))

        val nestedPath1 = "key1.key2".propertyPath()

        val result1 = runCatching {
            propertySet.required<String>(nestedPath1)
        }
        result1.getOrThrow() shouldBe "value"

        // Segunda llamada con una ruta diferente, por lo que no debería obtenerse de la caché
        val result2 = runCatching {
            val nestedPath2 = "key1.key3".propertyPath()
            propertySet.required<String>(nestedPath2)
        }
        result2.getOrThrow() shouldBe "value2"

        // Verificar que los resultados son diferentes
        result1.getOrThrow() shouldNotBe result2.getOrThrow()
    }

    "optional should return empty list if path with asterisk index points to an empty list" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf("key2" to emptyList<Any>())
        )
        val nestedPath = "key1.key2[*].key3".propertyPath()
        val result = propertySet.optional<List<String>>(nestedPath)

        result.getOrThrow() shouldBe emptyList()
    }

    "optional should return list of values if path with asterisk index points to a list" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to listOf(
                    propertiesOf("key3" to "value1"),
                    propertiesOf("key3" to "value2")
                )
            )
        )
        val nestedPath = "key1.key2[*].key3".propertyPath()
        val result = propertySet.optional<List<String>>(nestedPath)

        result.getOrThrow() shouldBe listOf("value1", "value2")
    }

    "optional should return null if path with asterisk index points to a non-existing key" {
        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to listOf(
                    propertiesOf("key3" to "value1"),
                    propertiesOf("key3" to "value2")
                )
            )
        )
        val nestedPath = "key1.key2[*].nonExistingKey".propertyPath()
        val result = propertySet.optional<List<String>>(nestedPath)

        result.getOrThrow() shouldBe emptyList()
    }


    "required should throw error if path with asterisk index points to an empty list" {
        shouldThrow<PropertiesError> {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to emptyList<Any>()))
            val nestedPath = "key1.key2[*].key3".propertyPath()
            propertySet.required<List<String>>(nestedPath)
        }
    }

    "required should return list of values if path with asterisk index points to a list" {
        val result = runCatching {
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
        result.getOrThrow() shouldBe listOf("value1", "value2")
    }

    "required should throw error if path with asterisk index points to a non-existing key" {
        shouldThrow<PropertiesError> {
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
    }

    "required should throw error if path with asterisk index points to an empty list" {
        shouldThrow<PropertiesError> {
            val propertySet: PropertySet = propertiesOf("key1" to propertiesOf("key2" to emptyList<Any>()))
            val nestedPath = "key1.key2[*].key3.key4".propertyPath()
            propertySet.required<String>(nestedPath)
        }
    }

    "required should return a list of PropertySets when the path with an asterisk index points to a list of PropertySets" {

        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to listOf(
                    propertiesOf("key3" to "value1"),
                    propertiesOf("key3" to "value2")
                )
            )
        )
        val nestedPath = "key1.key2[*].key3".propertyPath()
        val result = propertySet.required<List<PropertySet>>(nestedPath)

        val resultPropertySet = result.getOrThrow()
        resultPropertySet.getOrNull(0)?.absolutePath.toString() shouldBe "key1.key2[0].key3"
        resultPropertySet[1].absolutePath.toString() shouldBe "key1.key2[1].key3"
    }

    "required should return a PropertySet when the path with an asterisk index points to a list of PropertySets" {

        val propertySet: PropertySet = propertiesOf(
            "key1" to propertiesOf(
                "key2" to propertiesOf("key3" to "value1")
            )
        )

        val nestedPath = "key1.key2".propertyPath()
        val result = propertySet.required<PropertySet>(nestedPath)

        val resultPropertySet = result.getOrThrow()
        resultPropertySet.absolutePath.toString() shouldBe "key1.key2"
        resultPropertySet.data["key3"] shouldBe "value1"
    }

    "required should return value if present with errorPathContext" {
        val result = runCatching {
            val subPropertySet = propertiesOf("key" to "value")
            subPropertySet.required<String>("key")
        }
        result.getOrThrow() shouldBe "value"
    }

    "required should fail if key is not present with errorPathContext" {
        shouldThrow<PropertiesError> {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to "value4"))
            propertySet.required<String>("key2.key3")
        }
    }

    "required should fail if value is null with errorPathContext" {
        shouldThrow<PropertiesError> {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to null))
            propertySet.required<String>("key2.key4")
        }
    }

    "required should fail if value is not of type T with errorPathContext" {
        shouldThrow<PropertiesError> {
            val propertySet: PropertySet = propertiesOf("key2" to propertiesOf("key4" to 123))
            propertySet.required<String>("key2.key4")
        }
    }

    "empty map should return empty PropertySet" {
        val map = emptyMap<String, Any?>()
        val propertySet = map.toPropertySet()
        propertySet.data shouldBe emptyMap()
    }

    "map with single non-map non-list value should return equivalent PropertySet" {
        val map = mapOf("key" to "value")
        val propertySet = map.toPropertySet()
        propertySet.data shouldBe map
    }

    "map with single map value should return nested PropertySet" {
        val map = mapOf("key" to mapOf("nestedKey" to "nestedValue"))
        val propertySet = map.toPropertySet()
        propertySet.data["key"] shouldBe mapOf("nestedKey" to "nestedValue").toPropertySet()
    }

    "map with single list of maps value should return PropertySet with list of PropertySets" {
        val map = mapOf("key" to listOf(mapOf("nestedKey" to "nestedValue")))
        val propertySet = map.toPropertySet()
        propertySet.data["key"] shouldBe listOf(mapOf("nestedKey" to "nestedValue").toPropertySet())
    }

    "map with single list of non-maps value should return equivalent PropertySet" {
        val map = mapOf("key" to listOf("value1", "value2"))
        val propertySet = map.toPropertySet()
        propertySet.data shouldBe map
    }

    "map with multiple values should return equivalent PropertySet" {
        val map = mapOf(
            "key1" to "value1",
            "key2" to mapOf("nestedKey" to "nestedValue"),
            "key3" to listOf(mapOf("nestedKey" to "nestedValue")),
            "key4" to listOf("value1", "value2")
        )
        val propertySet = map.toPropertySet()
        propertySet.data["key1"] shouldBe "value1"
        propertySet.data["key2"] shouldBe mapOf("nestedKey" to "nestedValue").toPropertySet()
        propertySet.data["key3"] shouldBe listOf(mapOf("nestedKey" to "nestedValue").toPropertySet())
        propertySet.data["key4"] shouldBe listOf("value1", "value2")
    }

    "deeply nested map should return deeply nested PropertySet" {
        val map = mapOf("key1" to mapOf("key2" to mapOf("key3" to "value")))
        val propertySet = map.toPropertySet()
        val nestedPropertySet1 = propertySet.data["key1"] as PropertySet
        val nestedPropertySet2 = nestedPropertySet1.data["key2"] as PropertySet
        nestedPropertySet2.data["key3"] shouldBe "value"
    }
})
