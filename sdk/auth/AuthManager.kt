package sdk.auth

class AuthManager(
    private var apiKey: String? = null,
    private var accessToken: String? = null
) {
    fun setApiKey(key: String) {
        apiKey = key
    }

    fun setAccessToken(token: String) {
        accessToken = token
    }

    fun clearSession() {
        accessToken = null
    }

    fun headers(): Map<String, String> = buildMap {
        apiKey?.let { put("apikey", it) }
        accessToken?.let { put("Authorization", "Bearer $it") }
    }

    val isAuthenticated: Boolean
        get() = apiKey != null || accessToken != null
}
