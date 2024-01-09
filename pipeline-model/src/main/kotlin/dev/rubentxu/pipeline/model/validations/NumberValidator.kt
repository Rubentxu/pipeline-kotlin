package dev.rubentxu.pipeline.model.validations

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

fun Number.evaluate(tag: String = ""): NumberValidator = NumberValidator.from(this, tag)

fun <K,T> Validator<K, T>.isNumber(): NumberValidator {
    val value: Number? = if (this.sut != null)  parseAnyToNumber(sut) else null
    return test(errorMessage = "${tagMsg}Must be a number", instance = NumberValidator.from(validator = this) as Validator<*, T>) { value is Number } as NumberValidator
}

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