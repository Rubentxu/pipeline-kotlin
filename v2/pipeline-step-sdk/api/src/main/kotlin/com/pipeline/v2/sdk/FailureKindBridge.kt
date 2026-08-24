package com.pipeline.v2.sdk

import com.pipeline.v2.domain.FailureKind as DomainFailureKind

/**
 * Typealias bridge for domain FailureKind.
 * The SDK references FailureKind via this bridge to avoid circular dependencies.
 */
typealias FailureKindBridge = DomainFailureKind
