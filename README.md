# android-performance-notes

Production-level notes on Android performance and stability — from debugging ANRs in apps serving millions of users to reducing cold start time by hundreds of milliseconds.

This is not a tutorial. It's a collection of patterns, checklists, and hard-won lessons from 10+ years of shipping and maintaining large-scale Android applications.

## Focus Areas

- **ANR debugging** — main thread stalls, binder contention, broadcast timeouts, input dispatching
- **Crash investigation** — native tombstones, Java exceptions, deobfuscation, vendor-specific quirks
- **Startup optimization** — cold start profiling, ContentProvider costs, lazy initialization, I/O deferral
- **Memory & stability** — OOM patterns, leak detection, bitmap pooling, large heap trade-offs
- **SDK design** — Kotlin-first API client with coroutines, DSL builders, and structured error handling

## Philosophy

- Every note comes from a real production incident or optimization effort
- No rewriting of official docs — only things that aren't obvious until you hit them
- Emphasis on _what actually works_ over _what should work in theory_
- Compact format: problem → causes → how to debug → what worked

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

See [`sdk/`](sdk/) for the full implementation.

## Why This Repo Exists

Most Android performance knowledge lives in private postmortems, internal wikis, and Slack threads that disappear. This repo extracts the reusable parts — the debugging workflows, the non-obvious causes, the things that took hours to find and seconds to fix.

It's meant to be useful to engineers who already know Android well but need a reference when they're deep in a production issue at 2am.

## Structure

```
docs/
├── anr-debugging.md
├── startup-optimization.md
└── crash-investigation.md

sdk/
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

## License

MIT
