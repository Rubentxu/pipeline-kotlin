package dev.rubentxu.pipeline.model.mapper

import arrow.core.*
import arrow.core.raise.Raise
import arrow.core.raise.effect
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe


class PropertySetTest : StringSpec({

    "required should return value if present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key".propertyPath()
            val result = propertySet.required<String>(pathSegment)

            result shouldBe "value"
        }
    }

    "required should fail if key is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            val result = propertySet.required<String>(pathSegment)

            result shouldBe ValidationError("PathSegment 'key2' not found in PropertySet").left()
        }

    }

    "required should fail if value is null" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key' is null in PropertySet").left()
        }
    }

    "required should fail if value is not of type T" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to 123)
            val pathSegment = "key".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
        }
    }

    "required should return value if value is a list and index is present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe "value1".right()
        }
    }

    "required should fail if value is a list and index is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
        }
    }

    "required should fail if value is a list and index is out of range" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[2]' index 2 is out of range").left()
        }
    }

    "required should fail if value is a list and index is not a number" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            val result = propertySet.required<List<String>>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[a]' does not contain a number index").left()
        }
    }

    "required should fail if value is a list and index is not range" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            val result = propertySet.required<List<String>>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[-1]' index -1 is out of range").left()
        }
    }

    "required should fail if value is a list and index is not present and value is a list" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            val result = propertySet.required<String>(pathSegment)
            result shouldBe ValidationError("Value for PathSegment 'key' is not of type class kotlin.String").left()
        }
    }

    "required should fail if value is a list and index is out of range and value is a list" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            val result = propertySet.required<List<String>>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[2]' index 2 is out of range").left()
        }
    }

    "required should fail if value is a list and index is not a number and value is a list" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            val result = propertySet.required<List<String>>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[a]' does not contain a number index").left()
        }
    }

    "required should fail if value is a list and index is not range and value is a list" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[-1]".propertyPath()
            val result = propertySet.required<List<String>>(pathSegment)
            result shouldBe ValidationError("PathSegment 'key[-1]' index -1 is out of range").left()
        }
    }

    "required should return value if nested path is present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            val result = propertySet.required<String>(nestedPath)

            result shouldBe "value1"
        }
    }

    "required should fail if nested path is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            val result = propertySet.required<String>(nestedPath)

            result shouldBe ValidationError("PathSegment 'key3' not found in PropertySet").left()
        }
    }

    "required should fail if nested path is invalid" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1..key2[0]".propertyPath()
            val result = propertySet.required<String>(nestedPath)

            result shouldBe ValidationError("NestedPath is not valid for path 'key1..key2[0]'").left()
        }
    }

    "required should fail if nested path index is out of range" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            val result = propertySet.required<String>(nestedPath)

            result shouldBe ValidationError("PathSegment 'key2[2]' index 2 is out of range").left()
        }
    }

    "required should fail if nested path index is not a number" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            val result = propertySet.required<String>(nestedPath)

            result shouldBe ValidationError("NestedPath is not valid for path 'key1.key2[a]'").left()
        }
    }

    "optional should return value if present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key".propertyPath()
            val result = propertySet.optional<String>(pathSegment)

            result shouldBe "value"
        }
    }

    "optional should return null if key is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to "value")
            val pathSegment = "key2".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe null.right()
        }
    }

    "optional should return null if value is null" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to null)
            val pathSegment = "key".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe null.right()
        }
    }

    "optional should return null if value is not of type T" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to 123)
            val pathSegment = "key".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe ValidationError(message = "Value for PathSegment 'key' is not of type class kotlin.String")
        }
    }

    "optional should return value if value is a list and index is present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[0]".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe "value1".right()
        }
    }

    "optional should return null if value is a list and index is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe null.right()
        }
    }

    "optional should return null if value is a list and index is out of range" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[2]".propertyPath()
            val result = propertySet.optional<String>(pathSegment)
            result shouldBe null.right()
        }
    }

    "optional should return null if value is a list and index is not a number" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key" to listOf("value1", "value2"))
            val pathSegment = "key[a]".propertyPath()
            val result = propertySet.optional<List<String>>(pathSegment)
            result shouldBe null.right()
        }
    }

    "optional should return value if nested path is present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[0]".propertyPath()
            val result = propertySet.optional<String>(nestedPath)

            result shouldBe "value1"
        }
    }

    "optional should return null if nested path is not present" {
        effect {
            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key3[0]".propertyPath()
            val result = propertySet.optional<String>(nestedPath)

            result shouldBe null.right()
        }
    }

    "optional should return null if nested path index is out of range" {
        effect {

            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[2]".propertyPath()
            val result = propertySet.optional<String>(nestedPath)

            result shouldBe null.right()
        }
    }

    "optional should return null if nested path index is not a number" {
        effect {

            val propertySet: PropertySet = mutableMapOf("key1" to mutableMapOf("key2" to listOf("value1", "value2")))
            val nestedPath = "key1.key2[a]".propertyPath()
            val result = propertySet.optional<String>(nestedPath)

            result shouldBe null.right()
        }
    }

})