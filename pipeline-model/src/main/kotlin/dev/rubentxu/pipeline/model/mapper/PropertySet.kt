package dev.rubentxu.pipeline.model.mapper

import arrow.core.*
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure
import java.util.concurrent.ConcurrentHashMap

/**
 * Type alias for a map where the key is a string and the value can be any
 * type. This is used to represent a set of properties, where each property
 * is a key-value pair.
 */

class PropertySet(
    map: Map<String, Any?>,
    private val cache: ConcurrentHashMap<PropertyPath, PropertySet> = ConcurrentHashMap(),
) : Map<String, Any?> by map {

    context(Raise<ValidationError>)
    fun getParentPropertySet(nestedPath: NestedPath): PropertySet {
        val keys = nestedPath.getPathSegments().getOrElse { raise(it.first()) }

        val parentPath = getFullParentPath(nestedPath)
        val result = cache.getOrPut(parentPath, {
            keys.dropLast(1).fold(this) { acc: PropertySet, key: PropertyPath ->
                acc.required<PropertySet>(key as PathSegment)
            }
        })
        return result
    }

    context(Raise<ValidationError>)
    private fun getFullParentPath(nestedPath: NestedPath): PropertyPath {
        val segments = nestedPath.getPathSegments().getOrElse { raise(it.first()) }
        val path = segments.dropLast(1).joinToString(".")
        return path.propertyPath()
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        return true
    }

    override fun hashCode(): Int {
        return javaClass.hashCode()
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
fun Map<String, Any?>.toPropertySet(): PropertySet {
    return PropertySet(this)

}

/**
 * Data class for validation errors.
 *
 * @property message The error message. This is used to represent an error
 *     that occurs during validation.
 */
data class ValidationError(val message: String)

/**
 * Exception class for validation errors.
 *
 * @property message The error message. This is used to throw an exception
 *     when a validation error occurs.
 */
class ValidationErrorException(message: String) : Exception(message)

/**
 * Extension function for String class to convert a string to a
 * PropertyPath. This function checks if the string contains a ".", if
 * so, it converts the string to a NestedPath, otherwise, it converts the
 * string to a PathSegment.
 *
 * @return PropertyPath instance which could be either a NestedPath or a
 *     PathSegment.
 */
context(Raise<ValidationError>)
inline fun String.propertyPath(): PropertyPath =
    if (this.contains(".")) NestedPath(this) else PathSegment(this)


/**
 * Extension function for String class to convert a string to a
 * PathSegment.
 *
 * @return PropertyPath instance which is a PathSegment.
 */
context(Raise<ValidationError>)
inline fun String.pathSegment(): PathSegment = PathSegment(this) as PathSegment


/**
 * Extension function for String class to convert a string to a NestedPath.
 *
 * @return PropertyPath instance which is a NestedPath.
 */
context(Raise<ValidationError>)
inline fun String.nestedPath(): NestedPath = NestedPath(this) as NestedPath


/**
 * Sealed interface for PropertyPath. This is used to represent a path to a
 * property. It can be a PathSegment or a NestedPath.
 */
sealed interface PropertyPath

/**
 * Data class for PathSegment. This class is used to represent a segment
 * of a path to a property. It contains methods to check if the segment
 * contains an index, to get the key from the segment, to get the index
 * from the segment, and to check if the index is a number.
 *
 * @property path The path segment.
 */
data class PathSegment private constructor(val path: String) : PropertyPath {
    private val indexRegex = """.*\[(.*)\]""".toRegex()

    companion object {

        /**
         * Factory method to create a PathSegment instance. It validates the path
         * string and ensures it doesn't contain any "." character.
         *
         * @param path The path string.
         * @return PropertyPath instance which is a PathSegment.
         */
        context(Raise<ValidationError>)
        operator fun invoke(path: String): PropertyPath {
            ensure(path.isNotEmpty()) { ValidationError("PathSegment cannot be empty") }
            ensure(path.all { it.isDefined() }) { ValidationError("PathSegment can only contain alphanumeric characters : ${this}") }
            ensure(!path.contains(".")) { ValidationError("PathSegment cannot contain '.'") }
            return PathSegment(path)
        }
    }

    /**
     * Checks if the path contains an index.
     *
     * @return Boolean indicating if the path contains an index.
     */
    fun containsIndex(): Boolean = indexRegex.matches(path)

    /**
     * Retrieves the key from the path.
     *
     * @return The key as a string.
     */
    context(Raise<ValidationError>)
    fun getKey(): String = path.substringBefore("[")

    /**
     * Retrieves the index from the path.
     *
     * @return The index as an integer or null if the index is not a number.
     */
    fun getIndex(): Int? {
        try {
            return indexRegex.find(path)?.groupValues?.get(1)?.toInt()
        } catch (e: NumberFormatException) {
            return null
        }
    }

    /**
     * Checks if the index is a number.
     *
     * @return Boolean indicating if the index is a number.
     */
    fun indexIsNumber(): Boolean = getIndex()?.let { it is Int } ?: false

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
data class NestedPath private constructor(val path: String) : PropertyPath {

    /**
     * Factory method to create a NestedPath instance. It validates the path
     * string and ensures it contains either a "." or "[]" character.
     *
     * @param path The path string.
     * @return PropertyPath instance which is a NestedPath.
     */
    companion object {
        private val nestedPathRegex = """([a-zA-Z0-9]+(\[[0-9]+\])?\.)*[a-zA-Z0-9]+(\[[0-9]+\])?""".toRegex()

        context(Raise<ValidationError>)
        operator fun invoke(path: String): PropertyPath {
            ensure(validatePath(path)) { ValidationError("NestedPath is not valid for path '$path'") }
            ensure(path.isNotEmpty()) { ValidationError("NestedPath cannot be empty") }
            ensure(path.contains(".") || path.contains("[")) { ValidationError("NestedPath must contain '.' or '[]'") }
            ensure(path.all { it.isDefined() }) { ValidationError("NestedPath can only contain alphanumeric characters : ${this}") }
            return NestedPath(path)
        }


        /**
         * Validates the path string.
         *
         * @param path The path string.
         * @return Boolean indicating if the path string is valid.
         */
        fun validatePath(path: String): Boolean = nestedPathRegex.matches(path)

    }

    /**
     * Retrieves the path segments from the nested path.
     *
     * @return Either a ValidationError or a list of PropertyPath instances.
     */
    fun getPathSegments(): Either<NonEmptyList<ValidationError>, List<PropertyPath>> = either {
        path.split(".").mapOrAccumulate {
            it.propertyPath()
        }.bind()
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(path: PropertyPath): T {
    return when (path) {
        is PathSegment -> required<T>(path)
        is NestedPath -> required<T>(path)
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(path: String): T {
    val pathSegment: PropertyPath = path.propertyPath()
    return when (pathSegment) {
        is PathSegment -> required<T>(pathSegment)
        is NestedPath -> required<T>(pathSegment)
    }
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(segment: PathSegment): T = getValue<T>(segment)


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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.getValue(
    segment: PathSegment,
): T {
    ensure(containsKey(segment.getKey()))
    { ValidationError("PathSegment '${segment.getKey()}' not found in PropertySet") }
    val value: Any? = get(segment.getKey())

    if (segment.containsIndex()) {
        ensure(segment.indexIsNumber()) { ValidationError("PathSegment '${segment.path}' does not contain a number index") }
        val index: Int? = segment.getIndex()
        ensure(index != null) { ValidationError("PathSegment '${segment.path}' does not contain an index ${index}") }
        ensure(value is List<*>) { ValidationError("Value for PathSegment '${segment.path}' is not a list : ${value}") }
        ensure(value.isInRangeCollection(index)) { ValidationError("PathSegment '${segment.path}' index ${index} is out of range") }
        return value[index] as T
    }
    ensure(value != null) { ValidationError("PathSegment '${segment.getKey()}' is null in PropertySet") }
    ensure(value is T) { ValidationError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    return value
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.getOptionalValue(
    segment: PathSegment,
): T? {
    val value: Any? = get(segment.getKey())
    if (value == null) {
        return null
    }

    if (segment.containsIndex()) {
        ensure(segment.indexIsNumber()) { ValidationError("PathSegment '${segment.path}' does not contain a number index") }
        val index: Int? = segment.getIndex()
        ensure(index != null) { ValidationError("PathSegment '${segment.path}' does not contain an index ${index}") }
        ensure(value is List<*>) { ValidationError("Value for PathSegment '${segment.path}' is not a list") }
        if (!value.isInRangeCollection(index)) {
            return null
        }
        return value[index] as T?
    }
    ensure(value is T?) { ValidationError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    return value as T?
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(nestedPath: NestedPath): T {
    val keys = nestedPath.getPathSegments().getOrElse { raise(it.first()) }
    val parentProperties = this.getParentPropertySet(nestedPath)
    return parentProperties.required<T>(keys.last() as PathSegment)
}

/**
 * Unwraps the Either instance of ValidationError or T.
 *
 * @return The value of type T. This function is used to unwrap an Either
 *     instance of ValidationError or T. If the Either instance is a
 *     ValidationError, it throws a ValidationErrorException. If the Either
 *     instance is a T, it returns the T.
 */
fun <T> Either<ValidationError, T>.unwrap(): T {
    return this.fold(
        { throw ValidationErrorException(it.message) },
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(path: PropertyPath): T? {
    return when (path) {
        is PathSegment -> optional<T>(path)
        is NestedPath -> optional<T>(path)
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(segment: PathSegment): T? = getOptionalValue<T?>(segment)


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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(path: String): T? {
    val pathSegment: PropertyPath = path.propertyPath()
    return when (pathSegment) {
        is PathSegment -> optional<T>(pathSegment) as T?
        is NestedPath -> optional<T>(pathSegment)
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
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(path: NestedPath): T? {
    val keys = path.getPathSegments().getOrElse { raise(it.first()) }
    val partialPath = keys.dropLast(1)

    val finalPath: PropertySet = partialPath.fold(this) { acc: PropertySet, key: PropertyPath ->
        acc.optional<PropertySet>(key as PathSegment) ?: emptyMap<String, Any?>().toPropertySet()
    }

    return finalPath.optional<T>(keys.last() as PathSegment)
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
context(Raise<ValidationError>)
inline fun <reified T> List<PropertySet>.contains(segment: PathSegment): Boolean {
    return this.any { property: PropertySet -> property.optional<T>(segment) != null }
}


/**
 * Searches for the first PropertySet instance in the list that contains
 * the specified path segment and returns it. If no such PropertySet is
 * found, it returns null.
 *
 * @param segment The path segment to check.
 * @return The first PropertySet instance that contains the specified path
 *     segment, or null if no such PropertySet is found.
 */
context(Raise<ValidationError>)
inline fun <reified T> List<PropertySet>.firstOrNull(segment: PathSegment): PropertySet? {
    return this.firstOrNull { property: PropertySet -> property.optional<T>(segment) != null }
}

/**
 * Searches for the first PropertySet instance in the list that contains
 * the specified path segment and returns it. If no such PropertySet is
 * found, it returns null.
 *
 * @param segment The path segment to check.
 * @return The first PropertySet instance that contains the specified path
 *     segment, or null if no such PropertySet is found.
 */
context(Raise<ValidationError>)
inline fun <reified T> List<PropertySet>.firstOrNull(segment: String): PropertySet? {
    val pathSegment = segment.pathSegment()
    return this.firstOrNull { property: PropertySet -> property.optional<T>(pathSegment) != null }
}

/**
 * Filters all PropertySet instances in the list that contain the specified
 * path segment and returns a list of them. If no such PropertySet is
 * found, it returns an empty list.
 *
 * @param segment The path segment to check.
 * @return A list of PropertySet instances that contain the specified path
 *     segment. If no such PropertySet is found, it returns an empty list.
 */
context(Raise<ValidationError>)
inline fun <reified T> List<PropertySet>.allOrEmpty(segment: PathSegment): List<PropertySet> {
    return this.filter { property: PropertySet -> property.optional<T>(segment) != null }
}

