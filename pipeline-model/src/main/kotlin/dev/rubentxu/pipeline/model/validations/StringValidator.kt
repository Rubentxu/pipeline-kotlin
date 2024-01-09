package dev.rubentxu.pipeline.model.validations

import java.net.URL

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

fun String.evaluate(tag: String = ""): StringValidator = StringValidator.from(this, tag)


fun <K,T> Validator<K, T>.isString(): StringValidator =
    StringValidator.from(sut as String?, tag).test("${tagMsg}Must be a string") { it is String } as StringValidator
