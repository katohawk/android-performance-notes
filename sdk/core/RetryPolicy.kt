package sdk.core

import kotlinx.coroutines.delay
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class RetryPolicy(
    val maxAttempts: Int = 3,
    val initialDelay: Duration = 200.milliseconds,
    val maxDelay: Duration = 5.seconds,
    val multiplier: Double = 2.0,
    val retryOn: (ApiError) -> Boolean = { it.code >= 500 || it.code == -1 }
) {
    class Builder {
        var maxAttempts: Int = 3
        var initialDelay: Duration = 200.milliseconds
        var maxDelay: Duration = 5.seconds
        var multiplier: Double = 2.0
        var retryOn: (ApiError) -> Boolean = { it.code >= 500 || it.code == -1 }

        fun build() = RetryPolicy(
            maxAttempts = maxAttempts,
            initialDelay = initialDelay,
            maxDelay = maxDelay,
            multiplier = multiplier,
            retryOn = retryOn
        )
    }

    suspend fun <T> execute(block: suspend (attempt: Int) -> Result<T>): Result<T> {
        var currentDelay = initialDelay
        var lastResult: Result<T> = Result.Failure(ApiError.internal("No attempts made"))

        repeat(maxAttempts) { attempt ->
            lastResult = block(attempt)
            when (lastResult) {
                is Result.Success -> return lastResult
                is Result.Failure -> {
                    val error = (lastResult as Result.Failure).error
                    if (!retryOn(error) || attempt == maxAttempts - 1) return lastResult
                    delay(currentDelay)
                    currentDelay = (currentDelay * multiplier).coerceAtMost(maxDelay)
                }
            }
        }
        return lastResult
    }
}

private operator fun Duration.times(factor: Double): Duration =
    (this.inWholeMilliseconds * factor).toLong().milliseconds
