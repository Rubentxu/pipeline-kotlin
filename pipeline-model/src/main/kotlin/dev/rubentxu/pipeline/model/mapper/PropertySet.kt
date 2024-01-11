package dev.rubentxu.pipeline.model.mapper

import arrow.core.*
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure

/**
 * Type alias for a map where the key is a string and the value can be any
 * type. This is used to represent a set of properties, where each property
 * is a key-value pair.
 */
typealias PropertySet = Map<String, Any?>

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
 * PropertyPath.
 *
 * @return Either a ValidationError or a PropertyPath. This is used to
 *     convert a string to a PropertyPath. If the string contains a ".", it
 *     is converted to a NestedPath. Otherwise, it is converted to a
 *     PathSegment.
 */
context(Raise<ValidationError>)
inline fun String.propertyPath(): PropertyPath =
    if (this.contains(".")) NestedPath(this) else PathSegment(this)

/**
 * Sealed interface for PropertyPath. This is used to represent a path to a
 * property. It can be a PathSegment or a NestedPath.
 */
sealed interface PropertyPath

/**
 * Data class for PathSegment.
 *
 * @property path The path segment. This is used to represent a segment of
 *     a path to a property. It contains methods to check if the segment
 *     contains an index, to get the key from the segment, to get the index
 *     from the segment, and to check if the index is a number.
 */
data class PathSegment private constructor(val path: String) : PropertyPath {
    private val indexRegex = """.*\[(.*)\]""".toRegex()

    companion object {

        context(Raise<ValidationError>)
        operator fun invoke(path: String): PropertyPath {
            ensure(path.isNotEmpty()) { ValidationError("PathSegment cannot be empty") }
            ensure(path.all { it.isDefined() }) { ValidationError("PathSegment can only contain alphanumeric characters : ${this}") }
            ensure(!path.contains(".")) { ValidationError("PathSegment cannot contain '.'") }
            return PathSegment(path)
        }
    }

    fun containsIndex(): Boolean = indexRegex.matches(path)
    fun getKey(): String = path.substringBefore("[")
    fun getIndex(): Int? {
        try {
            return indexRegex.find(path)?.groupValues?.get(1)?.toInt()
        } catch (e: NumberFormatException) {
            return null
        }
    }

    fun indexIsNumber(): Boolean = getIndex()?.let { it is Int } ?: false
}

/**
 * Data class for NestedPath.
 *
 * @property path The nested path. This is used to represent a nested path
 *     to a property. It contains methods to validate the path and to get
 *     the path segments from the nested path.
 */

data class NestedPath private constructor(val path: String) : PropertyPath {
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

        fun validatePath(path: String): Boolean = nestedPathRegex.matches(path)
    }

    fun getPathSegments(): Either<NonEmptyList<ValidationError>, List<PropertyPath>> = either {
        path.split(".").mapOrAccumulate {
            it.propertyPath()
        }.bind()
    }
}

/**
 * Gets the required value of type T from the PropertySet.
 *
 * @param path The PropertyPath.
 * @return Either a ValidationError or the required value of type T. This
 *     is used to get a required value from the PropertySet. If the path is
 *     a PathSegment, it calls the required function for PathSegment. If
 *     the path is a NestedPath, it calls the required function for
 *     NestedPath.
 */
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(path: PropertyPath): T {
    return when (path) {
        is PathSegment -> required<T>(path as PathSegment)
        is NestedPath -> required<T>(path as NestedPath)
    }
}

/**
 * Gets the required value of type T from the PropertySet.
 *
 * @param segment The PathSegment.
 * @return Either a ValidationError or the required value of type T. This
 *     is used to get a required value from the PropertySet using a
 *     PathSegment. It checks if the key is present in the PropertySet, if
 *     the value is not null, and if the value is of type T. If all checks
 *     pass, it returns the value. Otherwise, it returns a ValidationError.
 */
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(segment: PathSegment): T {
    ensure(containsKey(segment.getKey())) { ValidationError("PathSegment '${segment.getKey()}' not found in PropertySet") }
    val value: Any? = getValue<T>(segment)
    ensure(value != null) { ValidationError("PathSegment '${segment.getKey()}' is null in PropertySet") }
    ensure(value is T) { ValidationError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    return value as T
}

/**
 * Gets the value of type T from the PropertySet.
 *
 * @param segment The PathSegment.
 * @param raise The Raise instance for ValidationError.
 * @return The value of type T. This is used to get a value from the
 *     PropertySet using a PathSegment. If the segment contains an index,
 *     it checks if the index is a number, if the value is a list, and if
 *     the index is in range of the list. If all checks pass, it returns
 *     the value at the index. Otherwise, it returns a ValidationError. If
 *     the segment does not contain an index, it returns the value.
 */
context(Raise<ValidationError>)
inline fun <reified T> PropertySet.getValue(
    segment: PathSegment,
): Any? {
    val value: Any? = get(segment.getKey())

    if (segment.containsIndex()) {
        ensure(segment.indexIsNumber()) { ValidationError("PathSegment '${segment.path}' does not contain a number index") }
        val index: Int? = segment.getIndex()
        ensure(index != null) { ValidationError("PathSegment '${segment.path}' does not contain an index ${index}") }
        ensure(value is List<*>) { ValidationError("Value for PathSegment '${segment.path}' is not a list") }
        ensure(value.isInRangeCollection(index)) { ValidationError("PathSegment '${segment.path}' index ${index} is out of range") }
        return value[index] as T?
    }
    return value as T?
}

/**
 * Checks if the index is in range of the collection.
 *
 * @param index The index to check.
 * @return Boolean indicating if the index is in range of the collection.
 *     This is used to check if an index is in range of a collection. It
 *     returns true if the index is greater than or equal to 0 and less
 *     than the size of the collection. Otherwise, it returns false.
 */
fun List<*>.isInRangeCollection(index: Int): Boolean {
    return index >= 0 && index < size
}

/**
 * Gets the required value of type T from the PropertySet.
 *
 * @param path The NestedPath.
 * @return Either a ValidationError or the required value of type T. This
 *     is used to get a required value from the PropertySet using a
 *     NestedPath. It gets the path segments from the nested path, then it
 *     folds the segments (except the last one) to get a PropertySet for
 *     each segment. Finally, it gets the required value from the last
 *     segment.
 */

context(Raise<ValidationError>)
inline fun <reified T> PropertySet.required(path: NestedPath): T {
    val keys = path.getPathSegments().getOrElse { raise(it.first()) }
    val result = keys.dropLast(1).fold(this@required) { acc: PropertySet, key: PropertyPath ->
        acc.required<PropertySet>(key as PathSegment)
    }
    return result.required<T>(keys.last() as PathSegment)
}

/**
 * Gets the required value of type T from the PropertySet.
 *
 * @param path The Either instance of ValidationError or PropertyPath.
 * @return Either a ValidationError or the required value of type T. This
 *     is used to get a required value from the PropertySet using an Either
 *     instance of ValidationError or PropertyPath. If the path is a
 *     ValidationError, it returns the ValidationError. If the path is a
 *     PropertyPath, it gets the required value from the PropertyPath.
 */
//inline fun <reified T> PropertySet.required(path: Either<ValidationError, PropertyPath>): Either<ValidationError, T> {
//    return path.flatMap { validPath ->
//        this.required<T>(validPath)
//    }
//}

/**
 * Unwraps the Either instance of ValidationError or T.
 *
 * @return The value of type T.
 * @throws ValidationErrorException if the Either instance is Left. This is
 *     used to unwrap an Either instance of ValidationError or T. If the
 *     Either instance is a ValidationError, it throws a
 *     ValidationErrorException. If the Either instance is a T, it returns
 *     the T.
 */
fun <T> Either<ValidationError, T>.unwrap(): T {
    return this.fold(
        { throw ValidationErrorException(it.message) },
        { it }
    )
}

context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(path: PropertyPath):  T? {
    return when (path) {
        is PathSegment -> optional<T>(path)
        is NestedPath -> optional<T>(path)
    }
}

context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(segment: PathSegment): T? {
    if (!containsKey(segment.getKey())) return null
    val value: Any? = getValue<T>(segment)
    ensure(value is T?) { ValidationError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    return value
}

context(Raise<ValidationError>)
inline fun <reified T> PropertySet.optional(path: NestedPath): T? {
    // Obtiene los segmentos de la ruta. Si falla, se lanza una excepción.
    val keys = path.getPathSegments().getOrElse { raise(it.first()) }

    // Se acumula un PropertySet navegando a través de las claves,
    val partialPath = keys.dropLast(1)

    // pero el uso de 'required' y 'optional' parece sospechoso.
    val finalPath: PropertySet = partialPath.fold(this@optional) { acc: PropertySet, key: PropertyPath ->
        acc.optional<PropertySet>(key as PathSegment) ?: emptyMap()
    }

    // Aquí parece ser donde ocurre la recursión.
    // El método 'optional' se llama a sí mismo.
    return finalPath.optional<T>(keys.last() as PathSegment)
}


