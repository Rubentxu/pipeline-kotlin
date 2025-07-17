// WITH_STDLIB

fun box(): String {
    val result = "Hello World"
    return if (result == "Hello World") "OK" else "Fail: $result"
}