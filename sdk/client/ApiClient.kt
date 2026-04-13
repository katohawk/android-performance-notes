package sdk.client

import sdk.auth.AuthManager
import sdk.core.ApiError
import sdk.core.Result
import sdk.core.RetryPolicy
import kotlinx.coroutines.withTimeout
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class ApiClient private constructor(
    private val baseUrl: String,
    private val auth: AuthManager,
    private val retryPolicy: RetryPolicy,
    private val timeout: Duration,
    private val engine: HttpEngine
) {
    constructor(baseUrl: String, configure: Config.() -> Unit = {}) : this(
        Config().apply(configure), baseUrl
    )

    private constructor(config: Config, baseUrl: String) : this(
        baseUrl = baseUrl.trimEnd('/'),
        auth = AuthManager(apiKey = config.apiKey),
        retryPolicy = config.retryPolicyBuilder?.build() ?: RetryPolicy(),
        timeout = config.timeout,
        engine = config.engine ?: MockHttpEngine()
    )

    class Config {
        var apiKey: String? = null
        var timeout: Duration = 30.seconds
        var engine: HttpEngine? = null
        internal var retryPolicyBuilder: RetryPolicy.Builder? = null

        fun retry(configure: RetryPolicy.Builder.() -> Unit) {
            retryPolicyBuilder = RetryPolicy.Builder().apply(configure)
        }
    }

    fun from(table: String) = RequestBuilder(this, table)

    fun setAccessToken(token: String) {
        auth.setAccessToken(token)
    }

    fun clearSession() {
        auth.clearSession()
    }

    internal fun buildHeaders(): Map<String, String> = buildMap {
        putAll(auth.headers())
        put("Content-Type", "application/json")
    }

    internal suspend fun executeRequest(
        method: HttpMethod,
        path: String,
        headers: Map<String, String>,
        body: String?
    ): Result<Response> {
        return retryPolicy.execute { _ ->
            try {
                val response = withTimeout(timeout) {
                    engine.execute(
                        url = "$baseUrl$path",
                        method = method.name,
                        headers = headers,
                        body = body
                    )
                }
                if (response.isSuccessful) {
                    Result.Success(response)
                } else {
                    Result.Failure(
                        ApiError(code = response.status, message = response.body)
                    )
                }
            } catch (e: kotlinx.coroutines.TimeoutCancellationException) {
                Result.Failure(ApiError.timeout())
            } catch (e: Exception) {
                Result.Failure(ApiError.network(e))
            }
        }
    }
}

interface HttpEngine {
    suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): Response
}

class MockHttpEngine : HttpEngine {
    override suspend fun execute(
        url: String,
        method: String,
        headers: Map<String, String>,
        body: String?
    ): Response {
        return Response(
            status = 200,
            body = """[{"id": 1, "name": "mock"}]""",
            headers = mapOf("content-type" to "application/json")
        )
    }
}
