package dev.rubentxu.pipeline.backend.mapper

import dev.rubentxu.pipeline.model.PropertiesError
import java.util.concurrent.ConcurrentHashMap


/**
 * Type alias for a map where the key is a string and the value can be any
 * type. This is used to represent a set of properties, where each property
 * is a key-value pair.
 */
class PropertySet(
    val data: Map<String, Any?>,
    val absolutePath: PropertyPath,
    private val cache: ConcurrentHashMap<String, PropertySet> = ConcurrentHashMap(),
) : Map<String, Any?> by data {

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
        return "Path: $absolutePath, Data: $data"
    }
}


fun Any?.toPropertySet(parentPath: PropertyPath = "".propertyPath()): Any? {
    return when (this) {
        is Map<*, *> -> {
            if (this.all { it.key is String }) {
                (this as Map<String, Any?>).toPropertySet(parentPath)
            } else {
                throw PropertiesError("All keys in the map must be of type String")
            }
        }

        is List<*> -> {
            if (this.all { it is Map<*, *> && it.all { entry -> entry.key is String } }) {
                (this as List<Map<String, Any?>>).toPropertySet(parentPath)
            } else {
                throw PropertiesError("All elements in the list must be of type Map<String, Any?>")
            }
        }

        else -> this
    }
}

fun Map<String, Any?>.toPropertySet(parentPath: PropertyPath = "".propertyPath()): PropertySet {
    val updatedMap = this.mapValues { (key, value) ->
        value.toPropertySet(parentPath.resolve(key.propertyPath()))
    }
    return PropertySet(updatedMap, parentPath)
}

fun List<Map<String, Any?>>.toPropertySet(parentPath: PropertyPath = "".propertyPath()): List<PropertySet> {
    return this.map { it.toPropertySet(parentPath) }
}


/**
 * Exception class for validation errors.
 *
 * @property message The error message. This is used to throw an exception
 *     when a validation error occurs.
 */
class PropertiesException(message: String) : Exception(message)

fun String.propertyPath(): PropertyPath {
    return try {
        toPropertyPath(this)
    } catch (e: Exception) {
        InvalidPropertyPath(this, e.message ?: "")
    }
}

fun toPropertyPath(path: String): PropertyPath {
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

fun String.pathSegment(): PathSegment =
    PathSegment(this).getOrElse { InvalidPropertyPath(this, it.message ?: "") } as PathSegment


/**
 * Extension function for String class to convert a string to a NestedPath.
 *
 * @return PropertyPath instance which is a NestedPath.
 */

fun String.nestedPath(): PropertyPath = NestedPath(this).getOrElse { InvalidPropertyPath(this, it.message ?: "") }

sealed class IndexType
data class NumberIndex(val index: Int) : IndexType()
data class AsteriskIndex(val keyFilter: String) : IndexType()
object EmptyValidIndex : IndexType()


sealed interface PropertyPath {
    val path: String

    fun empty(): PropertyPath = EmptyPropertyPath
    fun resolve(other: PropertyPath): PropertyPath
}


object EmptyPropertyPath : PropertyPath {
    override val path: String = ""

    override fun resolve(other: PropertyPath): PropertyPath {
        return other
    }

    override fun toString(): String {
        return path.toString()
    }

}

data class InvalidPropertyPath(override val path: String, val errorMsg: String) : PropertyPath {
    override fun resolve(other: PropertyPath): PropertyPath {
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
        operator fun invoke(path: String): Result<PathSegment> = runCatching {
            require(path.isNotEmpty()) { "PathSegment cannot be empty" }
            require(path.all { it.isDefined() }) { "PathSegment can only contain alphanumeric characters : ${this}" }
            PathSegment(path)
        }
    }

    fun containsIndex(): Boolean = indexRegex.matches(path.toString())

    fun containsNumberIndex(): Boolean = numberRegex.matches(path.toString())

    fun containsAsteriskIndex(): Boolean = asteriskRegex.matches(path.toString())

    fun getKey(): String = path.toString().substringBefore("[")

    fun getIndexType(): IndexType {
        return when {
            containsNumberIndex() -> {
                val index = numberRegex.find(path.toString())!!.groupValues[1].toInt()
                NumberIndex(index)
            }

            containsAsteriskIndex() -> {
                val keyFilter = path.toString().split(".").last()
                AsteriskIndex(keyFilter)
            }

            else -> EmptyValidIndex
        }
    }

    override fun resolve(other: PropertyPath): PropertyPath {
        return when (other) {
            is EmptyPropertyPath -> this
            else -> PathSegment("$path.${other.path}")
        }
    }

    override fun toString(): String {
        return path.toString()
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

    companion object {
        private val nestedPathRegex = """([a-zA-Z0-9]+(\[[\d\*]+\])?\.)*[a-zA-Z0-9]+(\[[\d\*]+\])?""".toRegex()

        operator fun invoke(path: String): Result<NestedPath> = runCatching {
            require(path.isNotEmpty()) { "NestedPath cannot be empty" }
            require(path.contains(".") || path.contains("[")) { "NestedPath must contain '.' or '[]'" }
            require(path.all { it.isDefined() }) { "NestedPath can only contain alphanumeric characters : ${this}" }
            require(validatePath(path)) { "NestedPath is not valid for path '$path'" }
            NestedPath(path)
        }

        fun validatePath(path: String): Boolean = nestedPathRegex.matches(path)
    }

    fun getPathSegments(): Result<List<PathSegment>> = runCatching {
        val segments = path.toString().split(".")
        val pathSegments = mutableListOf<PathSegment>()

        var i = 0
        while (i < segments.size) {
            var segment = segments[i]

            if (segment.endsWith("[*]")) {
                if (i + 1 < segments.size) {
                    segment += "." + segments[i + 1]
                    i++
                }
            }
            pathSegments.add(PathSegment(segment).getOrThrow())
            i++
        }
        pathSegments
    }

    override fun resolve(other: PropertyPath): PropertyPath {
        return when (other) {
            is EmptyPropertyPath -> this
            else -> NestedPath("$path.${other.path}")
        }
    }

    override fun toString(): String {
        return path.toString()
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

inline fun <reified T> PropertySet.required(path: PropertyPath): Result<T> = runCatching {
    when (path) {
        is PathSegment -> required<T>(path)
        is NestedPath -> required<T>(path)
        is EmptyPropertyPath -> Result.failure(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> Result.failure(PropertiesError(path.errorMsg))
    }
}.getOrElse {
    Result.failure(PropertiesError(it.message ?: "Error in required"))
}

inline fun <reified T> PropertySet.required(path: String): Result<T> {
    val propertyPath: PropertyPath = path.propertyPath()
    return required<T>(propertyPath)
}

inline fun <reified T> PropertySet.required(segment: PathSegment): Result<T> = runCatching {
    return Result.success(getValue<T>(segment))
}.getOrElse {
    Result.failure(PropertiesError(it.message ?: "Error in required"))
}

inline fun <reified T> PropertySet.getValue(segment: PathSegment): T {
    ensure(containsKey(segment.getKey())) { PropertiesError("PathSegment '${segment.getKey()}' not found in PropertySet") }
    var value: Any? = updateAbsolutePathIfPropertySet<T>(get(segment.getKey()), segment).getOrThrow()

    if (segment.containsIndex()) {
        value = getValueFromCollection<T>(segment, value, false).getOrThrow()
    }
    ensure(value != null) { PropertiesError("PathSegment '${segment.getKey()}' is null in PropertySet") }
    ensure(value is T) { PropertiesError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class.simpleName} but ${value!!::class.simpleName}") }

    return value as T
}

inline fun <reified T> PropertySet.updateAbsolutePathIfPropertySet(value: Any?, segment: PathSegment): Result<T> {
    return runCatching {
        when (value) {
            is PropertySet -> {
                val newPath = absolutePath.resolve(segment)
                PropertySet(value.data, newPath) as T
            }

            is List<*> -> {
                if (value.isNotEmpty() && value.first() is PropertySet) {
                    value.mapIndexed { index, it ->
                        val tempPath = segment.path.replace("[*]", "[$index]").propertyPath()
                        val newPath = absolutePath.resolve(tempPath)
                        it as PropertySet
                        PropertySet(it.data, newPath)
                    } as T
                } else {
                    value as T
                }
            }

            else -> value as T
        }
    }
}


inline fun <reified T> PropertySet.getValueFromCollection(
    segment: PathSegment,
    value: Any?,
    isOptional: Boolean,
): Result<T?> {
    val indexType = segment.getIndexType()
    return when (indexType) {
        is NumberIndex -> {
            if (value !is List<*>) {
                return Result.failure(PropertiesError("Value for PathSegment '${segment.path}' is not a list"))
            }
            val index = indexType.index
            if (isOptional && !value.isInRangeCollection(index)) {
                return Result.success(null)
            } else if (!value.isInRangeCollection(index)) {
                return Result.failure(PropertiesError("PathSegment '${segment.path}' index ${index} is out of range"))
            }
            updateAbsolutePathIfPropertySet<T>(value[index], segment)
        }

        is AsteriskIndex -> {
            if (value !is List<*>) {
                return Result.failure(PropertiesError("Value for PathSegment '${segment.path}' is not a list"))
            }
            val filterKey = indexType.keyFilter
            val result = value.mapNotNull { item ->
                when (item) {
                    is Pair<*, *> -> if (item.first == filterKey) item.second else null
                    is String -> if (item == filterKey) item else null
                    is PropertySet -> if (item.containsKey(filterKey)) item[filterKey] else null
                    else -> null
                }
            }
            if (result.isEmpty() && !isOptional) {
                return Result.failure(PropertiesError("PathSegment '${segment.path}' does not contain an index ${filterKey}"))
            }
            updateAbsolutePathIfPropertySet<T>(result, segment)
        }

        is EmptyValidIndex -> {
            Result.failure(PropertiesError("PathSegment '${segment.path}' does not contain a valid number index"))
        }
    }
}

fun ensure(b: Boolean, function: () -> PropertiesError) {
    if (!b) {
        function()
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
): Result<T?> {
    val value: Any? = updateAbsolutePathIfPropertySet<T>(get(segment.getKey()), segment).getOrNull()

    if (segment.containsIndex() && value != null) {
        return getValueFromCollection<T>(segment, value, true)
    }
    if (value !is T?) {
        return Result.failure(PropertiesError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}"))
    }
    if (value is List<*> && value.isEmpty()) {
        return Result.success(emptyList<T>() as T?)
    }
    return Result.success(value)
}

fun List<*>.isInRangeCollection(index: Int): Boolean {
    return index >= 0 && index < size
}

inline fun <reified T> PropertySet.required(nestedPath: NestedPath): Result<T> {
    val keys =
        nestedPath.getPathSegments().getOrNull() ?: return Result.failure(PropertiesError("Invalid path segments"))

    var currentPropertySet: PropertySet = this
    for (i in 0 until keys.size - 1) {
        val key = keys[i]
        currentPropertySet = currentPropertySet.get(key.getKey()) as? PropertySet ?: return Result.failure(
            PropertiesError("Invalid path segment")
        )
    }

    val lastKey = keys.last()
    return currentPropertySet.required<T>(lastKey)
}

inline fun <reified T> PropertySet.optional(path: PropertyPath): Result<T?> {
    return when (path) {
        is PathSegment -> optional<T>(path)
        is NestedPath -> optional<T>(path)
        is EmptyPropertyPath -> Result.failure(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> Result.failure(PropertiesError(path.errorMsg))
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

inline fun <reified T> PropertySet.optional(path: String): Result<T?> {
    val pathSegment: PropertyPath = path.propertyPath()
    return when (pathSegment) {
        is PathSegment -> optional<T>(pathSegment)
        is NestedPath -> optional<T>(pathSegment)
        is EmptyPropertyPath -> Result.failure(PropertiesError("PathSegment cannot be empty"))
        is InvalidPropertyPath -> Result.failure(PropertiesError(pathSegment.errorMsg))
    }
}

inline fun <reified T> PropertySet.optional(path: NestedPath): Result<T?> {
    val keys = path.getPathSegments().getOrNull() ?: return Result.failure(PropertiesError("Invalid path segments"))
    val partialPath = keys.dropLast(1)

    val finalPath: PropertySet = partialPath.fold(this) { acc: PropertySet, key: PropertyPath ->
        acc.optional<PropertySet>(key as PathSegment).getOrNull() ?: PropertySet(emptyMap(), path)
    }

    return finalPath.optional<T>(keys.last())
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
    return this.any { property: PropertySet -> property.optional<T?>(segment) != null }
}