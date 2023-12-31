package dev.rubentxu.pipeline.model

import arrow.core.Option
import arrow.core.some


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
