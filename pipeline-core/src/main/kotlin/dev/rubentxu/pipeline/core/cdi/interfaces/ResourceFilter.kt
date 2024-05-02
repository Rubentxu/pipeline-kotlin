package dev.rubentxu.pipeline.core.cdi.interfaces

interface ResourceFilter<T> {
    fun acceptScannedResource(item: T): Boolean
}