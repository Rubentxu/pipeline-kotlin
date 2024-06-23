package dev.rubentxu.pipeline.core.stores.dispachers

import dev.rubentxu.pipeline.core.models.interfaces.DataModel
import dev.rubentxu.pipeline.core.models.pipeline.*
import dev.rubentxu.pipeline.core.stores.interfaces.CoreAction
import dev.rubentxu.pipeline.core.stores.interfaces.Dispacher

class PipelineDispacher : Dispacher<PipelineDefinition> {

    override fun apply(state: PipelineDefinition, action: CoreAction<out DataModel>): PipelineDefinition {
        val payload = action.payload
        when (payload) {
            is PipelineSettings -> {
                return PipelineDefinition(
                    settings = action.execute(state.settings) as PipelineSettings,
                    cache = state.cache,
                    toolsManager = state.toolsManager

                )
            }

            is CacheDescriptor -> {
                return PipelineDefinition(
                    settings = state.settings,
                    cache = action.execute(state.cache) as CacheDescriptor,
                    toolsManager = state.toolsManager

                )
            }

            is AlternativesToolsDescriptor -> {
                return PipelineDefinition(
                    settings = state.settings,
                    cache = state.cache,
                    toolsManager = action.execute(state.toolsManager) as ToolsManagerDescriptor

                )
            }

            is AsdfToolsManagerDescriptor -> {
                return PipelineDefinition(
                    settings = state.settings,
                    cache = state.cache,
                    toolsManager = ToolsManagerDescriptor(
                        asdf = action.execute(state.toolsManager.asdf) as AsdfToolsManagerDescriptor,
                        alternatives = state.toolsManager.alternatives
                    )

                )
            }

            else -> {
                return state
            }
        }

    }
}