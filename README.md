# Android Performance Optimization Notes (Production Cases)

These notes summarize the kind of performance work I have done on production Android apps and SDKs from 2023 to 2025. The focus is practical: reducing ANRs, keeping startup predictable, avoiding OOMs, and removing waste from hot paths without turning the codebase into a science project.

The format is simple on purpose:

- Problem
- Root Cause
- Solution
- Result

## 1. Startup Optimization & ANR Reduction

### Problem

- Cold start was unstable on low-end devices.
- ANRs were showing up during startup and app backgrounding.
- Third-party SDK initialization was adding visible delay on the main thread.
- Disk reads in the startup path were competing with first-frame work on the main `Looper`.

### Root Cause

- `SharedPreferences` was part of the hot path during cold start and SDK init.
- Some initialization work triggered synchronous I/O and XML parsing before the UI was ready.
- OAID/GID, analytics, browser components, and ad SDK startup all competed for the main thread.
- Main-thread message queue pressure was higher than expected because several tasks were small individually but expensive in aggregate.
- Some countdown and splash-related work stayed on the UI thread longer than it needed to.

### Solution

- Migrated storage access from `SharedPreferences` to MMKV for the hot configuration path.
- Designed staged reads during cold start instead of loading everything up front.
- Merged and simplified config tables so startup reads touched fewer sources.
- Kept MMKV as the primary source and fell back to GreenDao only when needed.
- Moved OAID/GID, analytics SDKs, and browser component initialization onto background threads.
- Pulled ad SDK `init` and `start` work off the main thread where integration constraints allowed it.
- Used `MessageQueue.IdleHandler` for non-critical startup tasks that could wait until the first burst of work was done.
- Cached ad views ahead of display to reduce UI-thread work during splash and first-screen rendering.
- Cached `DisplayMetrics` instead of repeatedly querying framework services.
- Moved splash countdown handling to a dedicated `HandlerThread` to isolate timing work from UI rendering.
- Let the host app handle main-process checks instead of repeating that work inside the SDK.

### Result

- Configuration loading improved by about 60x on the critical startup path.
- Startup work became easier to reason about because tasks were split into blocking vs non-blocking phases.
- Main-thread stalls during cold start were reduced by removing avoidable I/O, binder calls, and queue pressure.
- ANR risk dropped because fewer startup tasks were fighting for the UI thread at once.

## 2. Memory Management & OOM Prevention

### Problem

- Large responses and heavy pages caused unnecessary allocations.
- OOM risk increased under memory pressure, especially on low-RAM devices.
- Some crash paths were tied to WebView and Binder transaction limits.
- Destroyed screens sometimes held onto view references longer than expected.

### Root Cause

- `Retrofit` response handling used `bytes()`, which materialized the full payload in memory before parsing.
- Work continued even when heap usage was already too high.
- Binder transactions could fail when large state moved across process boundaries.
- View hierarchies were not always detached cleanly after `Activity` or `Fragment` teardown, which increased leak risk.
- GC pressure was coming from large temporary objects rather than steady-state usage alone.

### Solution

- Replaced `bytes()` with streamed parsing from `source()` to avoid large one-shot allocations.
- Added an OOM circuit breaker:
  - Stop non-essential tasks when app heap usage exceeds 85%.
  - Stop non-essential tasks when system free memory drops below 50 MB.
- Treated memory protection as a scheduling problem, not just a parsing problem.
- Added a defensive workaround for WebView-related `TransactionTooLargeException` by bypassing the Binder-heavy path with a static holder strategy.
- Recursively cleared the view tree up to three levels deep during `Activity` and `Fragment` destruction to reduce retained references in known leak-prone flows.

### Result

- Large payload handling became more stable because parsing no longer depended on one big byte array.
- Peak allocation pressure dropped in response-heavy paths.
- Memory pressure handling became proactive instead of waiting for the process to crash.
- OOM and large-transaction risk was reduced in the flows that previously failed under stress.

## 3. APK Size & Network Optimization

### Problem

- Package size was higher than it needed to be.
- Startup and ad loading were paying for assets and network work that were not always needed.
- Connection setup added avoidable latency before the real request started.

### Root Cause

- The serialization stack pulled in more transitive code than the app needed.
- PNG assets were larger than necessary for production delivery.
- Some resource downloads were unconditional, even when the UI path would never display them.
- Network requests paid the cost of DNS, TCP, and TLS setup too late in the flow.

### Solution

- Replaced Avro usage with Gson in the relevant path and removed Jackson from the dependency graph.
- Converted suitable PNG resources to WebP.
- Added preconnect using an HTTP `HEAD` request to establish the connection before the real payload request.
- Tightened download logic for video ads so image resources were skipped when the flow only needed video content.

### Result

- APK size dropped by 25% after removing Jackson-related overhead.
- Asset delivery became leaner with smaller image resources.
- Network warm-up reduced avoidable connection setup latency on critical paths.
- Resource fetching became more precise, which lowered wasted bandwidth and background work.

## 4. Concurrency & Threading Model

### Problem

- A single shared thread pool created interference between unrelated work.
- Handler-heavy scheduling added overhead and made execution order harder to reason about.
- Time-sensitive tasks and blocking I/O could delay each other.

### Root Cause

- Config loading, ad tasks, file I/O, and scheduled jobs all shared the same executor.
- Queue contention made latency unpredictable even when individual tasks were small.
- Some work still used `Handler`-based dispatch where a regular executor was simpler and cheaper.
- Message creation overhead was small but repeated often enough to matter in hot paths.

### Solution

- Split one general-purpose thread pool into isolated executors by workload:
  - configuration
  - ads
  - I/O
  - scheduled tasks
- Simplified scheduling logic to remove extra `execute()` indirection where it was not buying anything.
- Replaced selected `Handler` usage with thread-pool execution for non-UI work.
- Reused message objects with `Message.obtain()` in hot message paths.
- Treated isolation as a stability feature, not only a throughput feature.

### Result

- Cross-task interference dropped because high-latency I/O no longer blocked unrelated work.
- Scheduling behavior became easier to predict and debug.
- Background execution was better aligned with Android’s threading model: UI work stayed on the main `Looper`, blocking work moved out, and timed work ran in dedicated lanes.

## 5. Data Structure & Performance Optimization

### Problem

- Several hot paths were paying unnecessary CPU and allocation costs.
- Reflection, regex, and small-object churn were adding up in tight loops.
- Database and cache behavior was not tuned for repeated access patterns.

### Root Cause

- Generic JSON serialization relied on reflection where the schema was already stable.
- Regex was used for simple character checks that did not need it.
- Database writes were too granular.
- Cache strategy was not layered enough for real production access patterns.

### Solution

- Replaced Gson-based parsing with hand-written JSON parsing in the hottest paths where the payload format was controlled and stable.
- Switched simple regex checks to ASCII-based character validation.
- Used GreenDao batch operations instead of many small writes.
- Built a multi-level cache strategy with:
  - local memory cache
  - LRU eviction
  - server-side policy coordination
- Only applied low-level optimizations in paths that had real call frequency or trace evidence behind them.

### Result

- Hot-path CPU work became more predictable.
- Allocation overhead dropped in parsing and validation code.
- Database access became cheaper under bursty workloads.
- Cache hit behavior improved because local and server-side strategy were aligned.

## Key Takeaways

- I treat performance work as systems work. Startup, memory, Binder, GC, storage, and scheduling all interact.
- I avoid blanket fixes. The first step is always to identify the blocking path, allocation source, or queue bottleneck.
- I prefer changes that reduce contention on the main `Looper` and lower peak memory pressure at the same time.
- Trade-offs matter. Some fixes improve latency but increase complexity, so I keep hot-path code explicit and narrow.
- The best performance work is measurable, reversible, and easy for the next engineer to maintain.

## Topics Covered

- ANR reduction
- Startup optimization
- Main-thread scheduling
- Looper and message queue behavior
- Memory management
- OOM prevention
- GC pressure reduction
- Binder transaction limits
- WebView stability
- APK size reduction
- Network warm-up and download control
- Thread pool design
- Data structure and cache optimization
- Crash debugging
