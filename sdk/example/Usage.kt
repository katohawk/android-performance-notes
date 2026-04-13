package sdk.example

import sdk.client.ApiClient
import sdk.core.Result
import kotlinx.coroutines.runBlocking
import kotlin.time.Duration.Companion.seconds

fun main() = runBlocking {

    val client = ApiClient("https://api.example.com") {
        apiKey = "sk_live_abc123"
        timeout = 10.seconds
        retry {
            maxAttempts = 3
            initialDelay = 500.milliseconds
        }
    }

    // Query with filters
    val result = client.from("users")
        .select("id", "name", "email")
        .eq("status", "active")
        .limit(20)
        .execute()

    when (result) {
        is Result.Success -> println("Users: ${result.data.body}")
        is Result.Failure -> println("Error ${result.error.code}: ${result.error.message}")
    }

    // Insert
    val insertResult = client.from("users")
        .insert("""{"name": "Alice", "email": "alice@example.com"}""")
        .execute()

    insertResult
        .onSuccess { println("Created: ${it.body}") }
        .onFailure { println("Failed: ${it.message}") }

    // With auth token
    client.setAccessToken("eyJhbGciOiJIUzI1NiIs...")

    val protectedResult = client.from("profiles")
        .select("id", "role")
        .eq("role", "admin")
        .execute()

    protectedResult.getOrNull()?.let {
        println("Admins: ${it.body}")
    }
}
