package dev.rubentxu.pipeline.steps.plugin

import org.jetbrains.kotlin.fir.FirSession
import org.jetbrains.kotlin.fir.FirSessionComponent

/**
 * A FIR session component that stores metadata about discovered @Step functions.
 * 
 * This service acts as a central repository for metadata collected by the
 * StepDiscoveryChecker. Other compiler phases, like the IR backend,
 * can then access this service to get the information they need.
 */
class StepMetadataService(session: FirSession) : FirSessionComponent {
    private val _discoveredFunctions = mutableListOf<StepFunctionMetadata>()
    val discoveredFunctions: List<StepFunctionMetadata> = _discoveredFunctions

    fun addDiscoveredFunction(metadata: StepFunctionMetadata) {
        _discoveredFunctions.add(metadata)
    }
}

val FirSession.stepMetadataService: StepMetadataService by FirSession.sessionComponentAccessor()

/**
 * Metadata about a discovered @Step function for use across compilation phases.
 */
data class StepFunctionMetadata(
    val name: String,
    val description: String,
    val category: String,
    val securityLevel: String,
    val parameters: List<StepParameterMetadata>,
    val returnType: String,
    val packageName: String,
    val isTopLevel: Boolean
)

/**
 * Metadata about a @Step function parameter.
 */
data class StepParameterMetadata(
    val name: String,
    val type: String,
    val hasDefault: Boolean,
    val isNullable: Boolean
)
