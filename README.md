# android-performance-notes

Production-level notes on Android performance and stability — from debugging ANRs in apps serving millions of users to reducing cold start time by hundreds of milliseconds.

This is not a tutorial. It's a collection of patterns, checklists, and hard-won lessons from 10+ years of shipping and maintaining large-scale Android applications.

## Focus Areas

- **ANR debugging** — main thread stalls, binder contention, broadcast timeouts, input dispatching
- **Crash investigation** — native tombstones, Java exceptions, deobfuscation, vendor-specific quirks
- **Startup optimization** — cold start profiling, ContentProvider costs, lazy initialization, I/O deferral
- **Memory & stability** — OOM patterns, leak detection, bitmap pooling, large heap trade-offs

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

## Why This Repo Exists

Most Android performance knowledge lives in private postmortems, internal wikis, and Slack threads that disappear. This repo extracts the reusable parts — the debugging workflows, the non-obvious causes, the things that took hours to find and seconds to fix.

It's meant to be useful to engineers who already know Android well but need a reference when they're deep in a production issue at 2am.

## Structure

```
docs/
├── anr-debugging.md
├── startup-optimization.md
└── crash-investigation.md
```

## License

MIT
