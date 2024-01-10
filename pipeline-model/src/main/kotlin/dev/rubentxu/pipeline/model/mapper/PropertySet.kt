package dev.rubentxu.pipeline.model.mapper

import arrow.core.*
import arrow.core.raise.Raise
import arrow.core.raise.either
import arrow.core.raise.ensure


typealias PropertySet = Map<String, Any?>

data class ValidationError(val message: String)

inline fun String.propertyPath(): Either<ValidationError, PropertyPath> =
    if (this.contains(".")) NestedPath(this) else PathSegment(this)


sealed interface PropertyPath

data class PathSegment private constructor(val path: String) : PropertyPath {
    private val indexRegex = """.*\[(.*)\]""".toRegex()

    companion object {
        operator fun invoke(path: String): Either<ValidationError, PropertyPath> = either {
            ensure(path.isNotEmpty()) { ValidationError("PathSegment cannot be empty") }
            ensure(path.all { it.isDefined() }) { ValidationError("PathSegment can only contain alphanumeric characters : ${this}") }
            ensure(!path.contains(".")) { ValidationError("PathSegment cannot contain '.'") }
            PathSegment(path)
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

data class NestedPath private constructor(val path: String) : PropertyPath {

    companion object {
        operator fun invoke(path: String): Either<ValidationError, PropertyPath> = either {
            ensure(path.isNotEmpty()) { ValidationError("NestedPath cannot be empty") }
            ensure(path.contains(".") || path.contains("[")) { ValidationError("NestedPath must contain '.' or '[]'") }
            ensure(path.all { it.isDefined() }) { ValidationError("NestedPath can only contain alphanumeric characters : ${this}") }
            NestedPath(path)
        }
    }

    fun getPathSegments(): Either<NonEmptyList<ValidationError>, List<PathSegment>> = either {
        path.split(".").mapOrAccumulate {
            it.propertyPath().fold({ raise(it) }, { it as PathSegment })
        }.bind()
    }
}

inline fun <reified T> PropertySet.required(path: PropertyPath): Either<ValidationError, T> = either {
    return when (path) {
        is PathSegment -> required<T>(path)
        is NestedPath -> required<T>(path)
    }
}

inline fun <reified T> PropertySet.required(segment: PathSegment): Either<ValidationError, T> = either {
    ensure(containsKey(segment.getKey())) { ValidationError("PathSegment '${segment.getKey()}' not found in PropertySet") }
    val value: Any? = getValue<T>(segment, this)
    ensure(value != null) { ValidationError("PathSegment '${segment.getKey()}' is null in PropertySet") }
    ensure(value is T) { ValidationError("Value for PathSegment '${segment.getKey()}' is not of type ${T::class}") }
    value
}

inline fun <reified T>  PropertySet.getValue(
    segment: PathSegment,
    raise: Raise<ValidationError>,
): Any? {
    val value: Any? = get(segment.getKey())

    if (segment.containsIndex()) {
        raise.ensure(segment.indexIsNumber()) { ValidationError("PathSegment '${segment.path}' does not contain a number index") }
        val index: Int? = segment.getIndex()
        raise.ensure(index != null) { ValidationError("PathSegment '${segment.path}' does not contain an index ${index}") }
        raise.ensure(value is List<*>) { ValidationError("Value for PathSegment '${segment.path}' is not a list") }
        raise.ensure(value.isInRangeCollection(index)) { ValidationError("PathSegment '${segment.path}' index ${index} is out of range") }
        return value[index] as T
    }
    return value
}

fun List<*>.isInRangeCollection(index: Int): Boolean {
    return index >= 0 && index < size
}


inline fun <reified T > PropertySet.required(path: NestedPath): Either<ValidationError, T> = either {
    val keys = path.getPathSegments().getOrElse { raise(it.first()) }
    val result = keys.dropLast(1).fold(this@required) { acc: PropertySet, key: PathSegment ->
        acc.required<PropertySet>(key).bind()
    }.required<T>(keys.last()).bind()
    result
}
