package dev.rubentxu.pipeline.v2.credentials.multipart

import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind as SpiMaterializationKind
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential as SpiMaterializedCredential
import dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKindUnsupportedException as SpiMaterializationKindUnsupportedException
import java.nio.file.Files
import java.nio.file.Path

/**
 * Materialization types re-exported from credentials SPI package.
 *
 * These types are now canonical in dev.rubentxu.pipeline.v2.credentials.spi.
 * This file re-exports them for backward compatibility with code that imports from multipart.
 */
typealias MaterializationKind = SpiMaterializationKind
typealias MaterializedCredential = SpiMaterializedCredential
typealias MaterializationKindUnsupportedException = SpiMaterializationKindUnsupportedException
