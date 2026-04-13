package sdk.core

data class ApiError(
    val code: Int,
    val message: String,
    val details: String? = null
) {
    fun asException(): ApiException = ApiException(this)

    companion object {
        fun unauthorized(message: String = "Unauthorized") =
            ApiError(code = 401, message = message)

        fun notFound(message: String = "Not found") =
            ApiError(code = 404, message = message)

        fun timeout(message: String = "Request timed out") =
            ApiError(code = 408, message = message)

        fun internal(message: String = "Internal server error") =
            ApiError(code = 500, message = message)

        fun network(cause: Throwable) =
            ApiError(code = -1, message = "Network error: ${cause.message}")
    }
}

class ApiException(val error: ApiError) : RuntimeException(error.message)
