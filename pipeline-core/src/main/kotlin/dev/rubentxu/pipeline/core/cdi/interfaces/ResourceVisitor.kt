package dev.rubentxu.pipeline.core.cdi.interfaces

interface ResourceVisitor<T> {
    fun visit(resource: T)
}