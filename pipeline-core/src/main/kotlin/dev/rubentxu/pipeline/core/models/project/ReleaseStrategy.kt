package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.ProjectDescriptorModel
import kotlin.Metadata

class ReleaseStrategy(
    var id: String,
    var name: String,
    var description: String,
    var metadata: Metadata
) : ProjectDescriptorModel  {


    override fun toMap(): Map<String, Any> {
        return mapOf(
            "id" to id,
            "name" to name,
            "description" to description,
            "metadata" to metadata
        )
    }

}
