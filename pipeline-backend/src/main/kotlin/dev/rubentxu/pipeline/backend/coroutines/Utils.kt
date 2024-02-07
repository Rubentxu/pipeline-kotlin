package dev.rubentxu.pipeline.backend.coroutines

import arrow.core.raise.result
import arrow.fx.coroutines.parZip


// Version with 2 parameters
suspend inline fun <A, B, C> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline f: suspend (A, B) -> C,
): Result<C> = result {
    parZip(
        { fa().bind() },
        { fb().bind() }
    ) { a: A, b: B -> f(a, b) }
}

// Version with 3 parameters
suspend inline fun <A, B, C, D> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline f: suspend (A, B, C) -> D,
): Result<D> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() }
    ) { a: A, b: B, c: C -> f(a, b, c) }
}

// Version with 4 parameters
suspend inline fun <A, B, C, D, E> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline f: suspend (A, B, C, D) -> E,
): Result<E> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() }
    ) { a: A, b: B, c: C, d: D -> f(a, b, c, d) }
}

// Version with 5 parameters
suspend inline fun <A, B, C, D, E, F> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline fe: suspend () -> Result<E>,
    crossinline f: suspend (A, B, C, D, E) -> F,
): Result<F> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() },
        { fe().bind() }
    ) { a: A, b: B, c: C, d: D, e: E -> f(a, b, c, d, e) }
}

// Version with 6 parameters
suspend inline fun <A, B, C, D, E, F, G> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline fe: suspend () -> Result<E>,
    crossinline ff: suspend () -> Result<F>,
    crossinline f: suspend (A, B, C, D, E, F) -> G,
): Result<G> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() },
        { fe().bind() },
        { ff().bind() }
    ) { a: A, b: B, c: C, d: D, e: E, f: F -> f(a, b, c, d, e, f) }
}

// Version with 7 parameters
suspend inline fun <A, B, C, D, E, F, G, H> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline fe: suspend () -> Result<E>,
    crossinline ff: suspend () -> Result<F>,
    crossinline fg: suspend () -> Result<G>,
    crossinline f: suspend (A, B, C, D, E, F, G) -> H,
): Result<H> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() },
        { fe().bind() },
        { ff().bind() },
        { fg().bind() }
    ) { a: A, b: B, c: C, d: D, e: E, f: F, g: G -> f(a, b, c, d, e, f, g) }
}

// Version with 8 parameters
suspend inline fun <A, B, C, D, E, F, G, H, I> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline fe: suspend () -> Result<E>,
    crossinline ff: suspend () -> Result<F>,
    crossinline fg: suspend () -> Result<G>,
    crossinline fh: suspend () -> Result<H>,
    crossinline f: suspend (A, B, C, D, E, F, G, H) -> I,
): Result<I> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() },
        { fe().bind() },
        { ff().bind() },
        { fg().bind() },
        { fh().bind() }
    ) { a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H -> f(a, b, c, d, e, f, g, h) }
}

suspend inline fun <A, B, C, D, E, F, G, H, I, J> parZipResult(
    crossinline fa: suspend () -> Result<A>,
    crossinline fb: suspend () -> Result<B>,
    crossinline fc: suspend () -> Result<C>,
    crossinline fd: suspend () -> Result<D>,
    crossinline fe: suspend () -> Result<E>,
    crossinline ff: suspend () -> Result<F>,
    crossinline fg: suspend () -> Result<G>,
    crossinline fh: suspend () -> Result<H>,
    crossinline fi: suspend () -> Result<I>,
    crossinline f: suspend (A, B, C, D, E, F, G, H, I) -> J,
): Result<J> = result {
    parZip(
        { fa().bind() },
        { fb().bind() },
        { fc().bind() },
        { fd().bind() },
        { fe().bind() },
        { ff().bind() },
        { fg().bind() },
        { fh().bind() },
        { fi().bind() }
    ) { a: A, b: B, c: C, d: D, e: E, f: F, g: G, h: H, i: I -> f(a, b, c, d, e, f, g, h, i) }
}
