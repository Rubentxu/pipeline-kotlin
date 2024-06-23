package dev.rubentxu.pipeline.core.models.project.strategies

import dev.rubentxu.pipeline.core.models.project.Metadata

class BuildStrategy(
    id: String,
    name: String,
    description: String,
    command: String,
    metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)

class TestStrategy(
    id: String,
    name: String,
    description: String,
    command: String, metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)

class PublishStrategy(
    id: String,
    name: String,
    description: String,
    command: String,
    metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)

class DeployStrategy(
    id: String,
    name: String,
    description: String, command: String, metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)

class UndeployStrategy(
    id: String,
    name: String,
    description: String,
    command: String,
    metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)

class RollbackStrategy(
    id: String,
    name: String,
    description: String,
    command: String,
    metadata: Metadata,
) :
    ProjectActionStrategy(id, name, description, command, metadata)