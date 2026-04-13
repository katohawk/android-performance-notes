package sdk.client

data class Response(
    val status: Int,
    val body: String,
    val headers: Map<String, String> = emptyMap()
) {
    val isSuccessful: Boolean get() = status in 200..299
}
