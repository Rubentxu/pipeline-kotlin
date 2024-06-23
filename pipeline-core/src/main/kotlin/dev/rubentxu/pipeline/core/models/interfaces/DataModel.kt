package dev.rubentxu.pipeline.core.models.interfaces


import java.io.Serializable

interface DataModel : Serializable {
    fun toMap(): Map<String, Any>

}