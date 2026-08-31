package dev.rubentxu.pipeline.v2.credentials.multipart

import java.nio.file.Files
import java.nio.file.Path

/**
 * Kind of materialization for file-based credential types.
 *
 * Re-exported from dev.rubentxu.pipeline.v2.credentials.spi for backward compatibility.
 */
typealias MaterializationKind = dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKind

/**
 * Result of credential materialization.
 *
 * Re-exported from dev.rubentxu.pipeline.v2.credentials.spi for backward compatibility.
 */
typealias MaterializedCredential = dev.rubentxu.pipeline.v2.credentials.spi.MaterializedCredential

/**
 * Thrown when a credential kind cannot be materialized to a file.
 *
 * Re-exported from dev.rubentxu.pipeline.v2.credentials.spi for backward compatibility.
 */
typealias MaterializationKindUnsupportedException = dev.rubentxu.pipeline.v2.credentials.spi.MaterializationKindUnsupportedException
