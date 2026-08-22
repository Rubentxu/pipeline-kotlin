package com.pipeline.v2.domain

data class StepDescriptor(
    val id: String,
    val type: String,
    val configRef: String,
)
