# android-performance-notes

Production-level notes on Android performance and stability. Debugging ANRs in apps serving millions of users, reducing cold start by hundreds of milliseconds, and building developer-facing SDKs that don't make callers hate you.

Not a tutorial. Not a blog. This is the stuff that took hours to figure out and seconds to fix — extracted from 10+ years of working on large-scale Android applications so it's reusable next time.

## Focus Areas

- **ANR debugging** — main thread stalls, binder contention, broadcast timeouts, input dispatching
- **Crash investigation** — native tombstones, Java exceptions, deobfuscation, vendor-specific quirks
- **Startup optimization** — cold start profiling, ContentProvider costs, lazy initialization, I/O deferral
- **Memory & stability** — OOM patterns, leak detection, bitmap pooling, large heap trade-offs
- **SDK design** — Kotlin-first API client with coroutines, DSL builders, and structured error handling

## Who This Is For

Senior Android engineers who already know the platform well but need a quick reference when they're knee-deep in a production issue. If you've ever stared at a `traces.txt` at 2am or tried to explain to product why cold start is 400ms slower on Samsung devices — this is for you.

This is not beginner-friendly on purpose. It assumes you know what a binder transaction is, what R8 does, and why `SharedPreferences.apply()` isn't always async.

## Philosophy

- Every note comes from a real production incident or optimization effort
- No rewriting of official docs — only things that aren't obvious until you hit them
- Emphasis on _what actually works_ over _what should work in theory_
- Compact format: problem → symptoms → causes → how to debug → what worked

## Topics

| Area | Examples |
|------|----------|
| ANR | Binder thread exhaustion, SharedPreferences `apply()` blocking commit, `ContentProvider.onCreate()` on main thread |
| Crash | Signal 11 in vendor GPU drivers, `RemoteServiceException` on specific OEM ROMs, R8 mapping gaps |
| Startup | Multidex cold start penalty, background thread contention during init, `AppStartup` library pitfalls |
| Memory | Bitmap decode OOM on low-RAM devices, Fragment backstack leaks, `onTrimMemory` response strategies |

## SDK Sample

A minimal Kotlin API client that demonstrates SDK design patterns — the kind of developer-facing library you'd ship for a REST backend like Supabase or Firebase.

```kotlin
val client = ApiClient("https://api.example.com") {
    apiKey = "sk_live_..."
    timeout = 10.seconds
    retry { maxAttempts = 3 }
}

val result = client.from("users")
    .select("id", "name")
    .eq("status", "active")
    .limit(20)
    .execute()

result
    .onSuccess { println(it.body) }
    .onFailure { println("${it.code}: ${it.message}") }
```

**Design principles:**

- Coroutine-first — every I/O call is `suspend`, no blocking
- Minimal API surface — chainable builder, sealed `Result<T>`, structured errors
- Type-safe — no stringly-typed APIs, errors carry `ApiError` with code and context
- Extensible — plug in any HTTP engine via `HttpEngine` interface

See [`sdk/`](sdk/) for implementation and [`sdk/DESIGN.md`](sdk/DESIGN.md) for design rationale.

## Why This Matters

There's a gap between "I read the Android docs" and "I've shipped fixes for this exact problem in production." The docs tell you what `StrictMode` does. They don't tell you that `SharedPreferences.apply()` silently blocks on `Activity.onStop()` through `QueuedWork.waitToFinish()`, or that 6 ContentProviders can add 300ms to cold start on a budget Snapdragon device before your `Application.onCreate()` even runs.

The SDK sample exists for a similar reason. Lots of people can write Kotlin. Fewer people think about what happens when the caller's coroutine scope gets cancelled mid-request, or why `Result<T>` should carry a structured error instead of just a string.

This repo is where that kind of production thinking gets written down.

## Structure

```
docs/
├── anr-debugging.md
├── startup-optimization.md
└── crash-investigation.md

sdk/
├── DESIGN.md              ← design decisions and trade-offs
├── client/
│   ├── ApiClient.kt
│   ├── RequestBuilder.kt
│   └── Response.kt
├── auth/
│   └── AuthManager.kt
├── core/
│   ├── Result.kt
│   ├── ApiError.kt
│   └── RetryPolicy.kt
└── example/
    └── Usage.kt
```

## Contributing

This is primarily a personal reference, but if you've hit something similar in production and have a correction or addition, issues and PRs are welcome.

## License

MIT
