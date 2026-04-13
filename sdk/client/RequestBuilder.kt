package sdk.client

import sdk.core.ApiError
import sdk.core.Result

class RequestBuilder(
    private val client: ApiClient,
    private val table: String
) {
    private var method: HttpMethod = HttpMethod.GET
    private var columns: List<String> = emptyList()
    private val filters: MutableMap<String, String> = mutableMapOf()
    private var limitCount: Int? = null
    private var body: String? = null

    fun select(vararg columns: String) = apply {
        this.method = HttpMethod.GET
        this.columns = columns.toList()
    }

    fun insert(json: String) = apply {
        this.method = HttpMethod.POST
        this.body = json
    }

    fun eq(column: String, value: String) = apply {
        filters[column] = "eq.$value"
    }

    fun neq(column: String, value: String) = apply {
        filters[column] = "neq.$value"
    }

    fun gt(column: String, value: String) = apply {
        filters[column] = "gt.$value"
    }

    fun lt(column: String, value: String) = apply {
        filters[column] = "lt.$value"
    }

    fun limit(count: Int) = apply {
        this.limitCount = count
    }

    // Terminal operation. Everything before this is just building up query state.
    // The suspend boundary is here, not on the builder methods — callers can
    // construct queries on any thread without a coroutine scope.
    suspend fun execute(): Result<Response> {
        val path = buildPath()
        val headers = client.buildHeaders()
        return client.executeRequest(method, path, headers, body)
    }

    private fun buildPath(): String = buildString {
        append("/rest/v1/$table")
        val params = mutableListOf<String>()
        if (columns.isNotEmpty()) {
            params += "select=${columns.joinToString(",")}"
        }
        filters.forEach { (col, filter) ->
            params += "$col=$filter"
        }
        limitCount?.let { params += "limit=$it" }
        if (params.isNotEmpty()) {
            append("?${params.joinToString("&")}")
        }
    }
}

enum class HttpMethod { GET, POST, PUT, DELETE }
