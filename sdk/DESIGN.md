# SDK Design Decisions

Notes on why this SDK is shaped the way it is. These choices come from building and maintaining developer-facing libraries — things that seem like taste but are actually load-bearing.

## Why Coroutine-First

Every I/O method is a `suspend` function. No callback variants, no `LiveData` wrappers, no `Single<T>`.

Rationale:
- Callers control concurrency at the call site (`viewModelScope`, `lifecycleScope`, `withContext`). The SDK doesn't need to know or care about the caller's threading model.
- Cancellation propagates automatically — if the caller's scope is cancelled (e.g., user navigates away), in-flight requests are cancelled without the SDK needing an explicit `cancel()` API.
- Structured concurrency means no leaked requests. A `CoroutineScope` guarantees all child coroutines complete or cancel when the scope ends.

Trade-off: callers who aren't using coroutines have to bridge with `runBlocking` or wrap in a callback adapter. For a Kotlin-first SDK targeting modern Android apps, this is an acceptable trade-off. Every major Android library (Room, Retrofit, Ktor) has made the same choice.

## Why Builder Pattern (DSL-Style)

Client initialization uses a Kotlin DSL builder:

```kotlin
val client = ApiClient("https://api.example.com") {
    apiKey = "sk_live_..."
    timeout = 10.seconds
    retry { maxAttempts = 3 }
}
```

Rationale:
- Named parameters with defaults — callers only specify what they want to change
- Nested configuration (`retry { ... }`) scopes related settings together without a separate `RetryConfig` class in the public API
- The `Config` class is the only mutable state. Once the client is built, it's effectively immutable. This makes it safe to share across threads without synchronization.

Alternative considered: a classic builder with `.setApiKey()` / `.setTimeout()` / `.build()`. This works but is verbose in Kotlin, and the DSL approach is idiomatic for Kotlin libraries (see Ktor, Exposed, kotlinx.serialization).

## Why `Result<T>` Instead of Exceptions

`execute()` returns `Result<T>` (sealed class with `Success` and `Failure`), not a bare value that throws on error.

Rationale:
- HTTP errors are expected, not exceptional. A 404 is a valid API response, not a bug. Forcing callers to try-catch every call is noisy and easy to forget.
- Structured errors: `Failure` carries an `ApiError` with `code`, `message`, and optional `details`. Callers can pattern-match or inspect without parsing exception messages.
- Composable: `map`, `onSuccess`, `onFailure` allow chaining without nesting try-catch blocks.
- Explicit: looking at a function signature, `suspend fun execute(): Result<Response>` tells you this can fail. `suspend fun execute(): Response` lies by omission.

Trade-off: callers who want throw-on-error behavior can use `.getOrThrow()`. This is opt-in, not the default.

Why not `kotlin.Result`? The stdlib `Result` has restrictions (can't be used as a return type in some contexts) and doesn't carry a structured error — just a `Throwable`. Our `Result<T>` is purpose-built for API semantics.

## Why `HttpEngine` Interface

The client doesn't depend on OkHttp, Ktor, or any HTTP library. It defines a minimal `HttpEngine` interface and ships with a `MockHttpEngine`.

Rationale:
- No transitive dependency conflicts. If the caller's app uses OkHttp 4 and some other SDK pins OkHttp 3, this SDK doesn't make it worse.
- Testability. Callers inject a fake engine in tests without mocking frameworks.
- Flexibility. Production code can use OkHttp, Ktor, or even `HttpURLConnection`. The SDK doesn't care.

In a production SDK, you'd ship a `OkHttpEngine` and a `KtorEngine` as separate optional artifacts.

## Open Questions

- Should `RequestBuilder` support pagination natively, or leave it to callers?
- The retry policy retries on all 5xx errors. Should 503 (rate limit) use a different backoff strategy with `Retry-After` header support?
- Should `ApiClient` expose a `close()` / `Closeable` interface for cleaning up engine resources?
