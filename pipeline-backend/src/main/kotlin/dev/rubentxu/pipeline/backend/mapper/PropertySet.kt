package dev.rubentxu.pipeline.backend.mapper

import arrow.core.*
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.result
import dev.rubentxu.pipeline.model.PropertiesError
import kotlinx.serialization.*
import java.util.concurrent.ConcurrentHashMap


typealias ListProperties = List<PropertySet>


typealias PropertiesCache = ConcurrentHashMap<PropertyPath, PropertySet>

/**
 * Type alias for a map where the key is a string and the value can be any
 * type. This is used to represent a set of properties, where each property
 * is a key-value pair.
 */
class PropertySet(
    val data: Map<String, Any?>,
    val absolutePath: PropertyPath,
    private val cache: PropertiesCache = ConcurrentHashMap(),
) : Map<String, Any?> by data {


    fun getParentPropertySet(nestedPath: NestedPath): Result<PropertySet> = result {
        val keys = nestedPath.getPathSegments().bind()

        val parentPath = getFullParentPath(nestedPath)
        val result = cache.getOrPut(parentPath.bind(), {
            keys.dropLast(1).fold(this@PropertySet) { acc: PropertySet, key: PropertyPath ->
                acc.required<PropertySet>(key as PathSegment) as PropertySet
            }
        })
        result
    }

    private fun getFullParentPath(nestedPath: NestedPath): Result<PropertyPath> = result {
        val segments = nestedPath.getPathSegments().bind()
        val path = segments.dropLast(1).joinToString(".")
        path.propertyPath()
    }

    override fun equals(other: Any?): Boolean {
        if (other is PropertySet) {
            return data == other.data && absolutePath == other.absolutePath
        }
        return false
    }

    override fun hashCode(): Int {
        return data.hashCode() + absolutePath.hashCode()
    }

    override fun toString(): String {
        return "Path: ${absolutePath}, Data: ${data}"
    }
}


/**
 * Creates a PropertySet from a variable number of pairs of String and
 * Any?.
 *
 * This function takes a variable number of pairs as input, where each
 * pair's first element is the property path and the second element is the
 * value. It then converts each pair to a map and transforms the map into a
 * PropertySet.
 *
 * @param pairs The pairs to be converted into a PropertySet. Each pair's
 *     first element is the property path and the second element is the
 *     value.
 * @return A PropertySet that contains the given pairs.
 */
fun propertiesOf(vararg pairs: Pair<String, Any?>): PropertySet {
    return pairs.toMap().toPropertySet()
}

/**
 * Creates a list of PropertySets from a variable number of PropertySets.
 *
 * This function takes a variable number of PropertySets as input and
 * transforms each PropertySet in the list using the toPropertySet()
 * function.
 *
 * @param pairs The PropertySets to be transformed.
 * @return A list of PropertySets that have been transformed.
 */
fun listPropertiesOf(vararg pairs: PropertySet): List<PropertySet> {
    return pairs.map { it.toPropertySet() }
}


/**
 * Extension function for Map class to convert a map to a PropertySet.
 *
 * @return PropertySet instance.
 */
fun Map<String, Any?>.toPropertySet(parentPath: PropertyPath = EmptyPropertyPath): PropertySet {
    val updatedMap = this.mapValues { (key, value) ->
        when (value) {
            is Map<*, *> -> (value as Map<String, Any?>).toPropertySet(parentPath.combine(key.propertyPath()))
            is List<*> -> value.map {
                if (it is Map<*, *>) {
                    (it as Map<String, Any?>).toPropertySet(parentPath.combine(key.propertyPath()))
                } else {
                    it
                }
            }

            else -> value
        }
    }
    return PropertySet(updatedMap, parentPath)
}


/**
 * Exception class for validation errors.
 *
 * @property message The error message. This is used to throw an exception
 *     when a validation error occurs.
 */
class PropertiesException(message: String) : Exception(message)

/**
 * Extension function for String class to convert a string to a
 * PropertyPath. This function checks if the string contains a ".", if
 * so, it converts the string to a NestedPath, otherwise, it converts the
 * string to a PathSegment.
 *
 * @return PropertyPath instance which could be either a NestedPath or a
 *     PathSegment.
 */
fun String.propertyPath(): PropertyPath {
    return either {
        toPropertyPath(this@propertyPath)
    }.getOrElse { InvalidPropertyPath(this, it.message) }
}

fun Raise<PropertiesError>.toPropertyPath(path: String): PropertyPath {
    return when {
        path.contains(".") -> path.nestedPath()
        path.isNotEmpty() -> path.pathSegment()
        else -> EmptyPropertyPath
    }
}

/**
 * Extension function for String class to convert a string to a
 * PathSegment.
 *
 * @return PropertyPath instance which is a PathSegment.
 */

inline fun String.pathSegment(): PathSegment = PathSegment(this) as PathSegment


/**
 * Extension function for String class to convert a string to a NestedPath.
 *
 * @return PropertyPath instance which is a NestedPath.
 */

inline fun String.nestedPath(): NestedPath = NestedPath(this) as NestedPath

sealed class IndexType
data class NumberIndex(val index: Int) : IndexType()
data class AsteriskIndex(val keyFilter: String) : IndexType()
object EmptyValidIndex : IndexType()


sealed interface PropertyPath {
    val path: String

    fun empty(): PropertyPath = EmptyPropertyPath
    fun combine(other: PropertyPath): PropertyPath
}


object EmptyPropertyPath : PropertyPath {
    override val path: String = ""

    override fun combine(other: PropertyPath): PropertyPath {
        return other
    }

    override fun toString(): String {
        return path
    }

}

data class InvalidPropertyPath(override val path: String, val errorMsg: String) : PropertyPath {
    override fun combine(other: PropertyPath): PropertyPath {
        return this
    }

    override fun toString(): String {
        return errorMsg
    }
}

/**
 * Data class for PathSegment. This class is used to represent a segment
 * of a path to a property. It contains methods to check if the segment
 * contains an index, to get the key from the segment, to get the index
 * from the segment, and to check if the index is a number.
 *
 * @property path The path segment.
 */
data class PathSegment private constructor(override val path: String) : PropertyPath {
    private val indexRegex = """.*\[(.*)\].*""".toRegex()
    private val numberRegex = """.*\[([\d]+)\]""".toRegex()
    private val asteriskRegex = """.*\[\*].*""".toRegex()

    companion object {

        /**
         * Factory method to create a PathSegment instance. It validates the path
         * string and ensures it doesn't contain any "." character.
         *
         * @param path The path string.
         * @return PropertyPath instance which is a PathSegment.
         */

        operator fun invoke(path: String): Result<PropertyPath> = result {
            ensure(path.isNotEmpty()) { PropertiesError("PathSegment cannot be empty") }
            ensure(path.all { it.isDefined() }) { PropertiesError("PathSegment can only contain alphanumeric characters : ${this}") }
//            ensure(!path.contains(".")) { ValidationError("PathSegment cannot contain '.'") }
            PathSegment(path)
        }
    }

    /**
     * Checks if the path contains an index.
     *
     * @return Boolean indicating if the path contains an index.
     */
    fun containsIndex(): Boolean = indexRegex.matches(path)

    fun containsNumberIndex(): Boolean = numberRegex.matches(path)

    fun containsAsteriskIndex(): Boolean = asteriskRegex.matches(path)

    /**
     * Retrieves the key from the path.
     *
     * @return The key as a string.
     */

    fun getKey(): String = path.substringBefore("[")

    /**
     * Retrieves the index from the path.
     *
     * @return The index as an integer or null if the index is not a number.
     */
    fun getIndexType(): IndexType {
        if (containsIndex()) {
            if (containsNumberIndex()) {
                val index = numberRegex.find(path)!!.groupValues.get(1).toInt()
                return NumberIndex(index)
            } else if (containsAsteriskIndex()) {
                val keyFilter = path.split(".").last()
                return AsteriskIndex(keyFilter)
            }
        }
        return EmptyValidIndex
    }

    override fun combine(other: PropertyPath): PropertyPath {
        return when (other) {
            is EmptyPropertyPath -> return this
            else -> "${this.path}.${other.path}".propertyPath()
        }
    }

    override fun toString(): String {
        return path
    }

}

/**
 * Data class for NestedPath. This class is used to represent a nested path
 * to a property. It contains methods to validate the path and to get the
 * path segments from the nested path.
 *
 * @property path The nested path.
 */
data class NestedPath private constructor(override val path: String) : PropertyPath {

    /**
     * Factory method to create a NestedPath instance. It validates the path
     * string and ensures it contains either a "." or "[]" character.
     *
     * @param path The path string.
     * @return PropertyPath instance which is a NestedPath.
     */
    companion object {
        private val nestedPathRegex = """([a-zA-Z0-9]+(\[[\d\*]+\])?\.)*[a-zA-Z0-9]+(\[[\d\*]+\])?""".toRegex()

        operator fun invoke(path: String): Result<PropertyPath> = result {
            ensure(validatePath(path)) { PropertiesError("NestedPath is not valid for path '$path'") }
            ensure(path.isNotEmpty()) { PropertiesError("NestedPath cannot be empty") }
            ensure(path.contains(".") || path.contains("[")) { PropertiesError("NestedPath must contain '.' or '[]'") }
            ensure(path.all { it.isDefined() }) { PropertiesError("NestedPath can only contain alphanumeric characters : ${this}") }
            NestedPath(path)
        }


        /**
         * Validates the path string.
         *
         * @param path The path string.
         * @return Boolean indicating if the path string is valid.
         */
        fun validatePath(path: String): Boolean = nestedPathRegex.matches(path)

    }


    fun getPathSegments(): Result<List<PathSegment>> = result {
        val segments = path.split(".")
        val pathSegments = mutableListOf<PathSegment>()

        var i = 0
        while (i < segments.size) {
            var segment = segments[i]

            // If the segment ends with "[*]", concatenate it with the next segment
            if (segment.endsWith("[*]")) {
                if (i + 1 < segments.size) {
                    segment += "." + segments[i + 1]
                    i++ // Skip the next segment as it has been concatenated with the current one
                }
            }
            // Convert the segment to a PathSegment and add it to the list
            pathSegments.add(PathSegment(segment) as PathSegment)
            i++
        }
        pathSegments
    }

    override fun combine(other: PropertyPath): PropertyPath {
        return when (other) {
            is EmptyPropertyPath -> return this
            else -> NestedPath("${this.path}.${other.path}")
        }
    }

    override fun toString(): String {
        return path
    }
}

/**
 * Retrieves the required value of type T from the PropertySet. This
 * function checks if the path is a PathSegment or a NestedPath and calls
 * the corresponding required function.
 *
 * @param path The PropertyPath.
 * @return The required value of type T.
 */

inline fun <reified T> PropertySet.required(path: PropertyPath): Result<T> = result {
    return when (path) {
        is PathSegment -> required<T>(path)
        is NestedPath -> required<T>(path)
        is EmptyPropertyPath -> raise(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> raise(PropertiesError(path.errorMsg))
    }
}

/**
 * Retrieves a required value of type T from the PropertySet.
 *
 * @param path The path segment to check.
 * @return The required value of type T. This function is used to get a
 *     required value from the PropertySet using a PathSegment. It checks
 *     if the key is present in the PropertySet, if the value is not null,
 *     and if the value is of type T. If all checks pass, it returns the
 *     value. Otherwise, it throws a ValidationErrorException.
 */

inline fun <reified T> PropertySet.required(path: String): Result<T> = result {
    val propertyPath: PropertyPath = path.propertyPath()
    return required<T>(propertyPath)
}


/**
 * Retrieves a required value of type T from the PropertySet.
 *
 * @param segment The PathSegment.
 * @return The required value of type T. This function is used to get a
 *     required value from the PropertySet using a PathSegment. It checks
 *     if the key is present in the PropertySet, if the value is not null,
 *     and if the value is of type T. If all checks pass, it returns the
 *     value. Otherwise, it throws a ValidationErrorException.
 */

inline fun <reified T> PropertySet.required(segment: PathSegment): Result<T> = result {
    getValue<T>(segment).bind()
}


/**
 * Retrieves a value of type T from the PropertySet.
 *
 * @param segment The PathSegment.
 * @return The value of type T. This function is used to get a value from
 *     the PropertySet using a PathSegment. If the segment contains an
 *     index, it checks if the index is a number, if the value is a list,
 *     and if the index is in range of the list. If all checks pass, it
 *     returns the value at the index. Otherwise, it throws a
 *     ValidationErrorException. If the segment does not contain an index,
 *     it returns the value.
 */

inline fun <reified T> PropertySet.getValue(segment: PathSegment): Result<T> = result {
    ensure(containsKey(segment.getKey())) { PropertiesError("PathSegment '${segment.getKey()}' not found in PropertySet") }
    val value: Any? = updateAbsolutePathIfPropertySet<T>(get(segment.getKey()), segment).bind()

    if (segment.containsIndex()) {
        getValueFromCollection<T>(segment, value, false)
    }
    ensure(value != null) { PropertiesError("PathSegment '${segment.getKey()}' is null in PropertySet") }
    ensure(value is T) { PropertiesError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class.simpleName} but ${value::class.simpleName}") }

    value
}


inline fun <reified T> PropertySet.updateAbsolutePathIfPropertySet(value: Any?, segment: PathSegment): Result<T> = result {
    if (value is PropertySet) {
        val newPath = absolutePath.combine(segment)
        PropertySet(value.data, newPath) as T
    }
    if (value is List<*> && value.isNotEmpty() && value.first() is PropertySet) {
        value.mapIndexed { index, it ->
            val newPath = absolutePath.combine(segment).path.replace("[*]", "[$index]").propertyPath()
            it as PropertySet
            PropertySet(it.data, newPath)
        } as T
    }
    value as T
}


/**
 * This function is used to get a value from a collection based on the
 * provided path segment. It supports three types of indices: NumberIndex,
 * AsteriskIndex, and EmptyValidIndex.
 *
 * @param segment The path segment that contains the key and index
 *     information.
 * @param value The collection from which the value is to be retrieved.
 * @param isOptional A boolean flag indicating whether the value is
 *     optional or not.
 * @return The value retrieved from the collection. If the value is not
 *     found and isOptional is true, it returns null. If the value is not
 *     found and isOptional is false, it raises a ValidationError.
 */

inline fun <reified T> PropertySet.getValueFromCollection(
    segment: PathSegment,
    value: Any?,
    isOptional: Boolean,
): Result<Any?> = result {
    val indexType = segment.getIndexType()
    when (indexType) {
        is NumberIndex -> {
            ensure(value is List<*>) { PropertiesError("Value for PathSegment '${segment.path}' is not a list") }
            val index = indexType.index
            ensure(index != null) { PropertiesError("PathSegment '${segment.path}' does not contain an index ${index}") }
            if (isOptional) {
                if (!value.isInRangeCollection(index)) {
                    null
                }
            } else {
                ensure(value.isInRangeCollection(index)) { PropertiesError("PathSegment '${segment.path}' index ${index} is out of range") }
            }

            return updateAbsolutePathIfPropertySet<T>(value[index], segment)
        }

        is AsteriskIndex -> {
            ensure(value is List<*>) { PropertiesError("Value for PathSegment '${segment.path}' is not a list") }
            val filterKey = indexType.keyFilter
            val result = value.mapNotNull { item ->
                when (item) {
                    is Pair<*, *> -> if (item.first == filterKey) item.second else null
                    is String -> if (item == filterKey) item else null
                    is PropertySet -> if (item.containsKey(filterKey)) item[filterKey] else null
                    else -> null
                }
            }
            if (result.isEmpty()) {
                if (!isOptional) {
                    return raise(PropertiesError("PathSegment '${segment.path}' does not contain an index ${filterKey}"))
                }
            }
            return updateAbsolutePathIfPropertySet<T>(result, segment)
        }

        is EmptyValidIndex -> {
            return raise(PropertiesError("PathSegment '${segment.path}' does not contain a valid number index"))
        }
    }
}


/**
 * Retrieves an optional value of type T from the PropertySet.
 *
 * This function takes a PathSegment as input and retrieves the value
 * associated with the key in the segment. If the value is null, it returns
 * null. If the segment contains an index, it checks if the index is a
 * number, if the value is a list, and if the index is in range of the
 * list. If all checks pass, it returns the value at the index. If the
 * segment does not contain an index, it checks if the value is of type T
 * and returns the value.
 *
 * @param segment The PathSegment to retrieve the value from.
 * @return The value of type T or null if the value is not present or does
 *     not pass the checks.
 */

inline fun <reified T> PropertySet.getOptionalValue(
    segment: PathSegment,
): Result<T?> = result {
    val value: Any? = updateAbsolutePathIfPropertySet<T>(get(segment.getKey()), segment).bind()

    if (segment.containsIndex() && value != null) {
        getValueFromCollection<T>(segment, value, true) as T?
    }
    ensure(value is T?) { PropertiesError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    if (value is List<*> && value.isEmpty()) {
        emptyList<T>() as T?
    }
    value
}

/**
 * Checks if the index is in range of the collection.
 *
 * @param index The index to check.
 * @return Boolean indicating if the index is in range of the collection.
 *     This function is used to check if an index is in range of a
 *     collection. It returns true if the index is greater than or equal to
 *     0 and less than the size of the collection. Otherwise, it returns
 *     false.
 */
fun List<*>.isInRangeCollection(index: Int): Boolean {
    return index >= 0 && index < size
}

/**
 * Retrieves a required value of type T from the PropertySet.
 *
 * This function is used to get a required value from the PropertySet using
 * a NestedPath. It gets the path segments from the nested path, then it
 * folds the segments (except the last one) to get a PropertySet for each
 * segment. Finally, it gets the required value from the last segment.
 *
 * @param nestedPath The NestedPath instance representing the path to the
 *     required value in the PropertySet.
 * @return The required value of type T.
 */

inline fun <reified T> PropertySet.required(nestedPath: NestedPath): Result<T> = result {
    val keys = nestedPath.getPathSegments().bind()
    val parentProperties = this@required.getParentPropertySet(nestedPath).bind()

    parentProperties.required<T>(keys.last() as PathSegment)
        .recover { error ->
            Result.failure<T>(exception = PropertiesError("Error in nested path '${nestedPath.path}': ${error.message}")) as T
        }.bind()

}


/**
 * Unwraps the Either instance of ValidationError or T.
 *
 * @return The value of type T. This function is used to unwrap an Either
 *     instance of ValidationError or T. If the Either instance is a
 *     ValidationError, it throws a ValidationErrorException. If the Either
 *     instance is a T, it returns the T.
 */
fun <T> Either<PropertiesError, T>.unwrap(): T {
    return this.fold(
        { throw PropertiesException(it.message) },
        { it }
    )
}

/**
 * Retrieves an optional value of type T from the PropertySet.
 *
 * @param path The PropertyPath.
 * @return The value of type T or null if the value is not present. This
 *     function is used to get an optional value from the PropertySet. If
 *     the path is a PathSegment, it calls the optional function for
 *     PathSegment. If the path is a NestedPath, it calls the optional
 *     function for NestedPath.
 */

inline fun <reified T> PropertySet.optional(path: PropertyPath): Result<T?> = result {
    return when (path) {
        is PathSegment -> optional<T>(path)
        is NestedPath -> optional<T>(path)
        is EmptyPropertyPath -> raise(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> raise(PropertiesError(path.errorMsg))
    }
}


/**
 * Retrieves an optional value of type T from the PropertySet.
 *
 * @param segment The PathSegment.
 * @return The value of type T or null if the value is not present. This
 *     function is used to get an optional value from the PropertySet using
 *     a PathSegment. It checks if the key is present in the PropertySet.
 *     If the key is not present, it returns null. If the key is present,
 *     it gets the value and checks if the value is of type T. If the value
 *     is not of type T, it returns a ValidationError. If the value is of
 *     type T, it returns the value.
 */

inline fun <reified T> PropertySet.optional(segment: PathSegment): Result<T?> = getOptionalValue<T?>(segment)


/**
 * Retrieves an optional value of type T from the PropertySet.
 *
 * @param path The path segment to check.
 * @return The value of type T or null if the value is not present. This
 *     function is used to get an optional value from the PropertySet using
 *     a PathSegment. It checks if the key is present in the PropertySet.
 *     If the key is not present, it returns null. If the key is present,
 *     it gets the value and checks if the value is of type T. If the value
 *     is not of type T, it returns a ValidationError. If the value is of
 *     type T, it returns the value.
 */

inline fun <reified T> PropertySet.optional(path: String): Result<T?> = result {
    val pathSegment: PropertyPath = path.propertyPath()
    return when (pathSegment) {
        is PathSegment -> optional<T>(pathSegment)
        is NestedPath -> optional<T>(pathSegment)
        is EmptyPropertyPath -> raise(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> raise(PropertiesError(pathSegment.errorMsg))
    }
}


/**
 * Retrieves an optional value of type T from the PropertySet.
 *
 * @param path The NestedPath.
 * @return The value of type T or null if the value is not present. This
 *     function is used to get an optional value from the PropertySet using
 *     a NestedPath. It gets the path segments from the nested path, then
 *     it folds the segments (except the last one) to get a PropertySet for
 *     each segment. Finally, it gets the optional value from the last
 *     segment.
 */

inline fun <reified T> PropertySet.optional(path: NestedPath): Result<T?> = result {
    val keys = path.getPathSegments().bind()
    val partialPath = keys.dropLast(1)

    val finalPath: PropertySet = partialPath.fold(this@optional) { acc: PropertySet, key: PropertyPath ->
        acc.optional<PropertySet>(key as PathSegment).bind() ?: PropertySet(emptyMap(), path)
    }

    finalPath.optional<T>(keys.last()).bind()
}


/**
 * Checks if a specific path segment is present in any of the PropertySet
 * instances in the list.
 *
 * @param segment The path segment to check.
 * @return Boolean indicating whether the path segment is present in any of
 *     the PropertySet instances in the list. Returns true if the path
 *     segment is present, otherwise, returns false.
 */

inline fun <reified T> List<PropertySet>.contains(segment: PathSegment): Boolean {
    return this.any { property: PropertySet -> property.optional<T>(segment) != null }
}