package dev.rubentxu.pipeline.model.validations

import arrow.core.*
import dev.rubentxu.pipeline.model.mapper.PropertySet
import dev.rubentxu.pipeline.model.mapper.PropertiesError

typealias Validations<T> = Either<NonEmptyList<PropertiesError>, T>
typealias NumberValidations = Validations<Number>
typealias PropertyValidations = Validations<PropertySet>

inline fun <reified T> Validations<T>.evaluate(predicate: (T) -> Boolean, errorMessage: String): Validations<T> {
    return this.flatMap { value ->
        if (predicate(value)) {
            value.right()
        } else {
            val list = this.leftOrNull()?.plus(PropertiesError(errorMessage))?: nonEmptyListOf(PropertiesError(errorMessage))
            Either.Left(list)
        }
    }
}

inline fun <reified T> Validations<T>.notNull(): Validations<T> =
    this.evaluate({ it != null }, "Value is null")

inline fun <reified T> Validations<T>.isNumber(): NumberValidations {
    return this.evaluate({ it is Number }, "Value is not a number") as NumberValidations
}

fun NumberValidations.isPositive(): NumberValidations =
    this.evaluate({ it.toDouble() > 0 }, "Value is not positive")

fun PropertyValidations.containsKey(key: String): PropertyValidations {
    return this.evaluate({ it.containsKey(key) }, "Key '$key' is not present")
}

fun PropertyValidations.containsValue(value: Any): PropertyValidations {
    return this.evaluate({ it.containsValue(value) }, "Value '$value' is not present")
}

fun PropertyValidations.verifyEmpty(): PropertyValidations {
    return this.evaluate({ it.isEmpty() }, "PropertySet is empty")
}

fun PropertyValidations.notEmpty(): PropertyValidations {
    return this.evaluate({ it.isNotEmpty() }, "PropertySet is not empty")
}


