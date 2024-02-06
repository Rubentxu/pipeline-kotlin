package dev.rubentxu.pipeline.backend.mapper

import arrow.core.raise.result


fun <T> T.toResult(): Result<T> =
    if (this is Throwable) Result.failure(this) else Result.success(this)

