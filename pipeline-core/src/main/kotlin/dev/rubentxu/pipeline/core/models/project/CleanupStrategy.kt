package dev.rubentxu.pipeline.core.models.project

import dev.rubentxu.pipeline.core.models.project.strategies.ProjectActionStrategy

class CleanupStrategy(
    id: String,
    name: String,
    description: String,
    command: String,
    metadata: Metadata
) : ProjectActionStrategy(id, name, description, command, metadata)