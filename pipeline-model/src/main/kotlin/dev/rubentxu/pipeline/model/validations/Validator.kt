package dev.rubentxu.pipeline.model.validations

import java.net.URL
import kotlin.reflect.KClass


open class Validator<K, T>(
    val sut: T?,
    val tag: String = "",
    val validationResults: MutableList<ValidationResult> = mutableListOf()) {


    private val tagMsg: String
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


class StringValidator private constructor(sut: String?, tag: String = "") :
    Validator<StringValidator, String>(sut, tag) {
    companion object {
        private val EMAIL_REGEX = "^[A-Za-z](.*)([@]{1})(.{1,})(\\.)(.{1,})".toRegex()
        private val HTTP_PROTOCOL_REGEX =
            "^(?:http[s]?:\\/\\/.)?(?:www\\.)?[-a-zA-Z0-9@%._\\+~#=]{2,256}\\.[a-z]{2,6}\\b(?:[-a-zA-Z0-9@:%_\\+.~#?&\\/\\/=]*)".toRegex()

        fun from(text: String?, tag: String = ""): StringValidator = StringValidator(text, tag)
    }

    fun moreThan(size: Int): StringValidator =
        test("Length of '$sut' must be more than $size") { it.length > size } as StringValidator

    fun lessThan(size: Int): StringValidator =
        test("Length of '$sut' must be less than $size") { it.length < size } as StringValidator

    fun between(minSize: Int, maxSize: Int): StringValidator {
        moreThan(minSize)
        return lessThan(maxSize)
    }

    fun contains(subString: String): StringValidator =
        test("'$sut' must contain '$subString'") { it.contains(subString) } as StringValidator

    fun isEmail(): StringValidator = test("'$sut' must be a valid email") { it.matches(EMAIL_REGEX) } as StringValidator

    fun matchRegex(regex: Regex): StringValidator =
        test("'$sut' must match the regex") { it.matches(regex) } as StringValidator

    fun isHttpProtocol(): StringValidator =
        test("'$sut' must be a valid HTTP protocol URL") { it.matches(HTTP_PROTOCOL_REGEX) } as StringValidator

    fun isURL(): StringValidator = test("'$sut' must be a valid URL") {
        try {
            URL(it)
            true
        } catch (ex: Exception) {
            false
        }
    } as StringValidator

    fun containsIn(array: List<String>): StringValidator =
        test("'$sut' must be one of ${array.joinToString()}") { array.contains(it) } as StringValidator

    fun notEmpty(): StringValidator = test("'$sut' must not be empty") { !it.isNullOrEmpty() } as StringValidator
}


class MapValidator private constructor(sut: Map<*, *>?, tag: String) : Validator<MapValidator, Map<*, *>>(sut, tag) {
    companion object {
        fun from(map: Map<*, *>?, tag: String = ""): MapValidator = MapValidator(map, tag)
    }

    override fun notNull(): MapValidator = super.notNull() as MapValidator


    fun getValueByPath(key: String?): Any? {
        if (key == null) return null
        val result = sut?.findDeep(key)
        return if (result is String) result.trim() else result
    }

    fun containsKeyByPath(key: String?): Boolean {
        if (key == null) return false
        return sut?.findDeep(key) != null
    }

    fun notEmpty(): MapValidator =
        test("'$sut' must not be empty") { !it.isNullOrEmpty() } as MapValidator

}

class NumberValidator private constructor(sut: Number?=null, tag: String = "", validationResults: MutableList<ValidationResult> = mutableListOf()) :
    Validator<NumberValidator, Number>(sut, tag, validationResults) {

        constructor(validator: Validator<*, Number>) : this(validator.sut, validator.tag, validator.validationResults)


    companion object {
        fun from(number: Number?, tag: String = ""): NumberValidator =  NumberValidator(number, tag)

        fun from(validator: Validator<*, *>): NumberValidator {
            val number: Number? = if (validator.sut != null)  parseAnyToNumber(validator.sut) else null
            return NumberValidator(number, validator.tag, validator.validationResults)
        }
    }

    fun moreThan(n: Number): NumberValidator =
        test("$sut must be more than $n") { it.toDouble() > n.toDouble() } as NumberValidator

    fun lessThan(n: Number): NumberValidator =
        test("$sut must be less than $n") { it.toDouble() < n.toDouble() } as NumberValidator

    fun between(min: Number, max: Number): NumberValidator {
        moreThan(min)
        return lessThan(max)
    }

    fun isInteger(): NumberValidator = test("$sut must be an integer") { it.toDouble() % 1 == 0.0 } as NumberValidator

    fun isDouble(): NumberValidator = test("$sut must be a double") { it.toDouble() % 1 != 0.0 } as NumberValidator

    fun isPositive(): NumberValidator = test("$sut must be positive") { it.toDouble() > 0 } as NumberValidator

    fun isNegative(): NumberValidator = test("$sut must be negative") { it.toDouble() < 0 } as NumberValidator

    fun isZero(): NumberValidator = test("$sut must be zero") { it.toDouble() == 0.0 } as NumberValidator
}

class CollectionValidator private constructor(sut: List<*>?, tag: String = "") :
    Validator<CollectionValidator, List<*>>(sut, tag) {
    companion object {
        fun from(list: List<*>?, tag: String = ""): CollectionValidator = CollectionValidator(list, tag)
    }

    fun notEmpty(): CollectionValidator =
        test("'$sut' must not be empty") { !it.isNullOrEmpty() } as CollectionValidator

    fun size(size: Int): CollectionValidator =
        test("'$sut' must have size $size") { it.size == size } as CollectionValidator

    fun sizeMoreThan(size: Int): CollectionValidator =
        test("'$sut' must have size more than $size") { it.size > size } as CollectionValidator

    fun sizeLessThan(size: Int): CollectionValidator =
        test("'$sut' must have size less than $size") { it.size < size } as CollectionValidator

    fun sizeBetween(minSize: Int, maxSize: Int): CollectionValidator {
        sizeMoreThan(minSize)
        return sizeLessThan(maxSize)
    }

    fun contains(element: Any): CollectionValidator =
        test("'$sut' must contain '$element'") { it.contains(element) } as CollectionValidator

    fun containsAll(elements: List<Any>): CollectionValidator =
        test("'$sut' must contain all of ${elements.joinToString()}") { it.containsAll(elements) } as CollectionValidator

    fun containsAny(elements: List<Any>): CollectionValidator =
        test("'$sut' must contain any of ${elements.joinToString()}") { it.any { element -> elements.contains(element) } } as CollectionValidator
}


data class ValidationResult(val isValid: Boolean, val errorMessage: String = "")

fun String.validate(tag: String = ""): StringValidator = StringValidator.from(this, tag)

fun Number.validate(tag: String = ""): NumberValidator = NumberValidator.from(this, tag)

fun List<*>.validate(tag: String = ""): CollectionValidator = CollectionValidator.from(this, tag)

fun Map<*, *>.validate(tag: String = ""): MapValidator = MapValidator.from(this, tag)

fun Nothing?.validate(tag: String = ""): Validator<Validator<*, Nothing?>, Nothing?> = Validator.from(this, tag)

fun Map<*, *>.validateAndGet(path: String, tag: String = ""): Validator<*, *> {
    val value = this.validate(tag).getValueByPath(path)
    return value.validate(tag)
}


fun Map<*, *>.findDeep(path: String): Any? {
    if (path.isEmpty()) return null

    return path.split(".").fold(this as Any?) { current, key ->
        if (current is Map<*, *>) current[key] else null
    }
}


fun <T> T.validate(): Validator<Validator<*, T>, T> = Validator.from(this)

fun <T> T.validate(tag: String): Validator<Validator<*, T>, T> = Validator.from(this, tag)


fun parseAnyToNumber(value: Any): Number? {
    return when (value) {
        is Number -> value
        is String -> when {
            value.contains(".") -> value.toDoubleOrNull()
            value.toLongOrNull() != null && value.toLongOrNull()!! < Int.MAX_VALUE -> value.toIntOrNull()
            else -> value.toLongOrNull()
        }
        else -> null
    }
}