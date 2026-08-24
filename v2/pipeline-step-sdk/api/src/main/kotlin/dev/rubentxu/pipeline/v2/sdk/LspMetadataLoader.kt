package dev.rubentxu.pipeline.v2.sdk

object LspMetadataLoader {
    private const val RESOURCE_PATH_PREFIX = "META-INF/pipeline/step-metadata/"

    fun loadAll(
        classLoader: ClassLoader = Thread.currentThread().contextClassLoader,
    ): Result<List<LspMetadata>> {
        val successes = mutableListOf<LspMetadata>()
        val errors = mutableListOf<String>()

        try {
            val resources = classLoader.getResources(RESOURCE_PATH_PREFIX)
            while (resources.hasMoreElements()) {
                val url = resources.nextElement()
                val path = url.path ?: ""
                val fileName = if (path.contains("/")) {
                    path.substring(path.lastIndexOf('/') + 1)
                } else {
                    path
                }

                try {
                    val connection = url.openConnection()
                    connection.inputStream.use { input ->
                        val content = input.bufferedReader().readText()
                        val metadata = LspMetadata.fromJson(content)
                        if (metadata != null) {
                            successes.add(metadata)
                        } else {
                            errors.add("Failed to parse: $fileName")
                        }
                    }
                } catch (e: Exception) {
                    errors.add("${url}: ${e.message}")
                }
            }
        } catch (e: Exception) {
            return Result.failure(e)
        }

        return Result.success(successes)
    }
}

data class LspLoadDiagnostics(
    val partialSuccess: List<LspMetadata>,
    val diagnostics: List<String>,
)
