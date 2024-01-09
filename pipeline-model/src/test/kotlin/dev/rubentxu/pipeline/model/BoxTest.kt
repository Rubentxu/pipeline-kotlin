package dev.rubentxu.pipeline.model

import arrow.core.Either
import arrow.core.Either.Companion.zipOrAccumulate
import arrow.core.NonEmptyList
import arrow.core.raise.either
import arrow.core.raise.ensure
import arrow.core.raise.ensureNotNull
import arrow.core.raise.mapOrAccumulate
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.async
import kotlinx.coroutines.delay


// Implementar el Functor para Box

class BoxTest : StringSpec({
    "debe multiplicar el contenido de Box por 2" {
        val box = Box(123)
        val mappedBox = box.map { it + 1 }
        println(mappedBox) // Output: Box(value=124)

        mappedBox shouldBe Box(124)
    }

    "pruebas con corutinas" {

        val tarea1 = async { tareaConTiempo(1, 4000) }
        val tarea2 = async { tareaConTiempo(2, 2000) }
        val tarea3 = async { tareaConTiempo(3, 3000) }

        // Esperamos los resultados de las tareas
        tarea1.await()
        println("Tarea 1 completada")
        tarea2.await()
        println("Tarea 2 completada")
        tarea3.await()
        println("Tarea 3 completada")

        println("Todas las tareas completadas")
    }
})

suspend fun tareaConTiempo(numero: Int, duracion: Long): String {
    val tiempoInicio = System.currentTimeMillis()

    delay(duracion) // Simula una tarea que tarda un tiempo determinado

    val tiempoFin = System.currentTimeMillis()
    val tiempoTotal = tiempoFin - tiempoInicio
    println("Tarea $numero completada en $tiempoTotal ms")

    return "Resultado $numero"
}
