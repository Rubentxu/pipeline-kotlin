package dev.rubentxu.pipeline.model.scm

interface ScmExtensions {
    fun toMap(): Map<String, Any>
    companion object fun fromMap(map: Map<String, Any>): ScmExtensions
}

class SparseCheckoutPath(private var sparseCheckoutPath: String) : ScmExtensions {
    override fun toMap(): Map<String, Any> = mapOf(
        "\$class" to "SparseCheckoutPaths",
        "sparseCheckoutPaths" to listOf(mapOf("path" to sparseCheckoutPath))
    )

    override fun fromMap(map: Map<String, Any>): ScmExtensions {
        val path = map["sparseCheckoutPath"] as? String ?: throw IllegalArgumentException("Path missing")
        return SparseCheckoutPath(path)
    }

}


class CloneOption(
    private val depth: Int,
    private val timeout: Int,
    private val noTags: Boolean,
    private val shallow: Boolean
) : ScmExtensions {
    override fun toMap(): Map<String, Any> = mapOf(
        "\$class" to "CloneOption",
        "depth" to depth,
        "noTags" to noTags,
        "shallow" to shallow,
        "timeout" to timeout
    )

   override fun fromMap(map: Map<String, Any>): ScmExtensions {
        if(map.containsValue("\$class") && map["\$class"] != "CloneOption") {
            throw IllegalArgumentException("Class missing from scm extensions")
        }
        val depth = map.getOrDefault("depth", 0) as Int
        val timeout = map.getOrDefault("timeout", 60) as Int
        val noTags = map.getOrDefault("noTags", false) as Boolean
        val shallow = map.getOrDefault("shallow", false) as Boolean
        return CloneOption(depth, timeout, noTags, shallow)
    }

}

class RelativeTargetDirectory(private var relativeTargetDirectory: String) : ScmExtensions {
    override fun toMap(): Map<String, Any> = mapOf(
        "\$class" to "RelativeTargetDirectory",
        "relativeTargetDir" to relativeTargetDirectory
    )

    override fun fromMap(map: Map<String, Any>): ScmExtensions {
        val dir = map.getOrDefault("relativeTargetDirectory","") as String
        return RelativeTargetDirectory(dir)
    }

}

