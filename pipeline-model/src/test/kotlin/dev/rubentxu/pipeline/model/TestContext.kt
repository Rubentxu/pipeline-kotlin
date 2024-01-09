package dev.rubentxu.pipeline.model

import arrow.core.*
import arrow.core.raise.*
import arrow.core.raise.mapOrAccumulate


data class Box<A>(val value: A)

// Crear una extensión para Functor
fun <A, B> Box<A>.map(f: (A) -> B): Box<B> = Box(f(this.value))

fun monadExample() {
    val result = Option(3).flatMap { a -> Option(4).map { b -> a * b } }
    println(result) // Output: Some(12)
}


fun functorExample() {
    val option = 1.some()
    val incremented = option.map { it + 1 }
    println(incremented) // Output: Some(2)
}



fun foldableExample() {
    val sum = listOf(1, 2, 3, 4).fold(0) { acc, n -> acc + n }
    println(sum) // Output: 10
}



sealed interface BookValidationError
object EmptyTitle: BookValidationError
object NoAuthors: BookValidationError
data class EmptyAuthor(val index: Int): BookValidationError

object EmptyAuthorName

data class Author private constructor(val name: String) {
    companion object {
        operator fun invoke(name: String): Either<EmptyAuthorName, Author> = either {
            ensure(name.isNotEmpty()) { EmptyAuthorName }
            Author(name)
        }
    }
}

data class Book private constructor(
    val title: String, val authors: NonEmptyList<Author>
) {
    companion object {
        operator fun invoke(
            title: String, authors: Iterable<String>
        ): Either<NonEmptyList<BookValidationError>, Book> = either {
            zipOrAccumulate(
                { ensure(title.isNotEmpty()) { EmptyTitle } },
                {
                    val validatedAuthors = mapOrAccumulate(authors.withIndex()) { nameAndIx ->
                        Author(nameAndIx.value)
                            .mapLeft { EmptyAuthor(nameAndIx.index) }
                            .bind()
                    }
                    ensureNotNull(validatedAuthors.toNonEmptyListOrNull()) { NoAuthors }
                }
            ) { _, authorsNel ->
                Book(title, authorsNel)
            }
        }
    }
}
