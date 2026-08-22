package com.pipeline.v2.testkit

import com.pipeline.v2.domain.StepDescriptor

object StepDescriptorAssertions {
    /** True iff at least one step matches both [id] AND [type] exactly. */
    fun hasStep(steps: List<StepDescriptor>, id: String, type: String): Boolean =
        steps.any { it.id == id && it.type == type }
}
