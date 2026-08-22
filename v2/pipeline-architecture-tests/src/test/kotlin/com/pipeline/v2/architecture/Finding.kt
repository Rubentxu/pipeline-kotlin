package com.pipeline.v2.architecture

import java.nio.file.Path

data class Finding(
    val file: Path,
    val line: Int,
    val token: String,
    val excerpt: String,
)
