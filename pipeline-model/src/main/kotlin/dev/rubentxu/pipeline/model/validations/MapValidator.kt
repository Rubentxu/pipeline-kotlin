package dev.rubentxu.pipeline.model.validations


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

fun Map<*, *>.validateAndGet(path: String, tag: String = ""): Validator<*, *> {
    val value = this.evaluate(tag).getValueByPath(path)
    return value.evaluate(tag)
}

fun Map<*, *>.evaluate(tag: String = ""): MapValidator = MapValidator.from(this, tag)

fun <K,T> Validator<K, T>.isMap(): MapValidator =
    MapValidator.from(sut as Map<*, *>?, tag).test("${tagMsg}Must be a map") { it is Map<*, *> } as MapValidator


fun Map<*, *>.findDeep(path: String): Any? {
    if (path.isEmpty()) return null

    var current: Any? = this
    val keys = path.split(".")
    for (key in keys) {
        current = when {
            key.contains("[") && key.contains("]") -> {
                val keyName = key.substringBefore("[")
                val index = key.substringAfter("[").substringBefore("]").toInt()
                val list = (current as? Map<*, *>)?.get(keyName) as? List<*>
                list?.getOrNull(index)
            }
            current is Map<*, *> -> {
                current.getOrDefault(key, null)
            }
            else -> {
                return null
            }
        }
    }
    return current
}

fun Map<*, *>.findDeep(path: String, cache: MutableMap<String, Any?>): Any? {
    if (path.isEmpty()) return null

    var current: Any? = this
    var currentPath = ""
    val keys = path.split(".")
    for (key in keys) {
        currentPath = if (currentPath.isEmpty()) key else "$currentPath.$key"

        // Comprueba si el resultado ya está en la caché
        cache[currentPath]?.let { current = it } ?: run {
            current = when {
                key.contains("[") && key.contains("]") -> {
                    val keyName = key.substringBefore("[")
                    val index = key.substringAfter("[").substringBefore("]").toInt()
                    val list = (current as? Map<*, *>)?.get(keyName) as? List<*>
                    list?.getOrNull(index)
                }
                current is Map<*, *> -> {
                    (current as Map<*, *>).getOrDefault(key, null)
                }
                else -> {
                    return null
                }
            }

        }
    }
    cache[currentPath] = current
    return current
}
