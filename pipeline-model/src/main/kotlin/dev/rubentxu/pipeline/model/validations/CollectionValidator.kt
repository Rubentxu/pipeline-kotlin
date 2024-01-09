package dev.rubentxu.pipeline.model.validations

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


fun List<*>.validate(tag: String = ""): CollectionValidator = CollectionValidator.from(this, tag)

fun <K,T> Validator<K, T>.isList(): CollectionValidator = CollectionValidator.from(sut as List<*>?, tag)
    .test("${tagMsg}Must be a list") { it is List<*> } as CollectionValidator