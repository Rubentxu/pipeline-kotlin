package dev.rubentxu.pipeline.model.validations

import arrow.core.Either
import arrow.core.left
import arrow.core.right
import dev.rubentxu.pipeline.model.mapper.ValidationError
import kotlin.reflect.KClass


open class Validator<K, T>(
    val sut: T?,
    val tag: String = "",
    val validationResults: MutableList<ValidationResult> = mutableListOf()) {


    val tagMsg: String
        get() = if (tag.isNotEmpty()) "$tag with value " else ""

    open fun test(errorMessage: String, instance: Validator<*, T> = this, predicate: (T) -> Boolean): Validator<*, T> {
        try {
            if (sut == null) {
                validationResults.add(ValidationResult(false, errorMessage))
            } else if (predicate(sut)) {
                validationResults.add(ValidationResult(true))
            } else {
                validationResults.add(ValidationResult(false, errorMessage))
            }
        } catch (ex: Exception) {
            validationResults.add(ValidationResult(false, "Syntax Error, invalid expression. $errorMessage"))

        }
        @Suppress("UNCHECKED_CAST")
        return instance as Validator<*, T>
    }

    open val isValid: Boolean
        get() {
            if (validationResults.isEmpty()) {
                validationResults.add(ValidationResult(false, "There are no validations for the object."))
            }
            return validationResults.all { it.isValid }
        }

    val errors: List<String>
        get() = validationResults.filter { !it.isValid }.map { it.errorMessage }

    fun throwIfInvalid(customErrorMessage: String = ""): T {
        if (!isValid) {
            val errorMessage = (listOf(customErrorMessage) + errors).distinct().joinToString()
            throw IllegalArgumentException(errorMessage)
        }
        return sut ?: throw IllegalStateException("Subject under test is null")
    }

    fun <D> defaultValueIfInvalid(defaultValue: D): D {
        if (!isValid) {
            return defaultValue
        }
        return sut as D
    }

    fun getValue(): T? {
        return sut
    }

    fun isString(): StringValidator =
        StringValidator.from(sut as String?, tag).test("${tagMsg}Must be a string") { it is String } as StringValidator

    fun isNumber(): NumberValidator {
        val value: Number? = if (sut != null)  parseAnyToNumber(sut) else null
        return test(errorMessage = "${tagMsg}Must be a number", instance = NumberValidator.from(validator = this) as Validator<*, T>) { value is Number } as NumberValidator
    }


    fun isList(): CollectionValidator = CollectionValidator.from(sut as List<*>?, tag)
        .test("${tagMsg}Must be a list") { it is List<*> } as CollectionValidator

    fun isMap(): MapValidator =
        MapValidator.from(sut as Map<*, *>?, tag).test("${tagMsg}Must be a map") { it is Map<*, *> } as MapValidator

    open fun notNull(): K = test("${tagMsg}Must not be null") { it != null } as K

    fun isNull(): Validator<K, T> = test("${tagMsg}Must be null") { it == null } as Validator<K, T>

    fun notEqual(n: T): Validator<K, T> = test("${tagMsg}Must not be equal to $n") { it != n } as Validator<K, T>

    fun equal(n: T): Validator<K, T> = test("${tagMsg}Must be equal to $n") { it == n } as Validator<K, T>

    fun <R : Any> isClass(clazz: KClass<R>): Validator<K, R> {
        notNull()
        test("${tagMsg}Must be type $clazz. Current is ${(sut as Any).javaClass.name}") { clazz.isInstance(it) }
        @Suppress("UNCHECKED_CAST")
        return this as Validator<K, R>
    }

    fun isBoolean(): Validator<K, Boolean> {
        notNull()
        test("${tagMsg}Must be a boolean") { it is Boolean }
        @Suppress("UNCHECKED_CAST")
        return this as Validator<K, Boolean>
    }

    fun dependsOn(context: Map<String, Any>, vararg dependsOnKeys: String): Validator<K, T> {
        test("${tagMsg}Must have all the dependencies: ${dependsOnKeys.joinToString()}") {
            dependsOnKeys.all { key -> context.containsKey(key) }
        }
        @Suppress("UNCHECKED_CAST")
        return this as Validator<K, T>
    }

    fun dependsAnyOn(context: Map<String, Any>, vararg dependsOnKeys: String): Validator<K, T> {
        test("${tagMsg}Must have any of the dependencies: ${dependsOnKeys.joinToString()}") {
            dependsOnKeys.any { key -> context.containsKey(key) }
        }
        @Suppress("UNCHECKED_CAST")
        return this
    }

    companion object {
        fun <T> from(sut: T): Validator<Validator<*, T>, T> = Validator(sut)

        fun <T> from(sut: T, tag: String): Validator<Validator<*, T>, T> = Validator(sut, tag)
    }

}

data class ValidationResult(val isValid: Boolean, val errorMessage: String = "")

fun Nothing?.evaluate(tag: String = ""): Validator<Validator<*, Nothing?>, Nothing?> = Validator.from(this, tag)


fun <T> T.evaluate(): Validator<Validator<*, T>, T> = Validator.from(this)

fun <T> T.evaluate(tag: String): Validator<Validator<*, T>, T> = Validator.from(this, tag)


