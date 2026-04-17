# Android Performance Optimization Notes (Production Cases)

Production-focused notes from Android performance work on real apps and SDKs between 2023 and 2025. This is not a generic checklist. It is a compact record of the problems I have debugged, the changes I made, and the results those changes produced in startup, ANR, memory, networking, and SDK-heavy environments.

## Impact

- Reduced cold start latency by restructuring initialization and moving non-critical SDK work off the main thread.
- Lowered ANR risk by removing main-thread I/O, isolating timing work, and reducing startup queue pressure on the main `Looper`.
- Improved memory stability by switching large-response handling from full buffering to streamed parsing.
- Added memory guardrails that stop non-essential work before heap pressure turns into OOM.
- Reduced APK size by removing unnecessary serialization dependencies and converting assets to lighter formats.
- Improved network efficiency by warming connections early and skipping unneeded ad resources.
- Built thread-pool isolation for config, ads, I/O, and scheduled work so unrelated tasks stop blocking each other.

## Who This Is For

- Android engineers working on production apps, not toy projects.
- Engineers debugging ANRs, cold start regressions, crash spikes, or OOM issues.
- Mobile engineers maintaining apps with heavy SDK integration.
- Engineers building SDKs or shared mobile infrastructure used across multiple apps or teams.

## Real Examples

### Example 1: Startup ANR Caused by Storage and SDK Initialization

**Problem**

- Cold start was unstable.
- Startup ANRs were tied to storage reads and third-party SDK init.

**What caused it**

- `SharedPreferences` and SDK initialization were both sitting on the critical startup path.
- Several small startup tasks were competing on the main thread, which increased message queue pressure and delayed first-frame work.

**What I changed**

- Migrated hot config reads from `SharedPreferences` to MMKV.
- Split cold-start reads into stages instead of loading everything up front.
- Moved OAID/GID, analytics, browser components, and ad SDK startup off the main thread where integration rules allowed it.
- Used `MessageQueue.IdleHandler` for work that could wait until the first burst of startup work finished.
- Moved splash countdown handling to a `HandlerThread` so timing work stopped competing with rendering.

**Result**

- Configuration loading on the critical path improved by about 60x.
- Startup became more predictable because blocking work and deferred work were separated clearly.
- Main-thread stalls dropped because cold start no longer mixed storage, SDK init, and timing work in one lane.

### Example 2: OOM Risk from Large Response Parsing

**Problem**

- Large responses created avoidable memory pressure and increased OOM risk on low-memory devices.

**What caused it**

- Response parsing used `bytes()`, which loaded the full payload into memory before parsing started.
- Work kept running even when heap usage was already close to the limit.

**What I changed**

- Replaced `bytes()` with streamed parsing from `source()`.
- Added an OOM circuit breaker to stop non-essential work when heap usage exceeded 85% or system free memory dropped below 50 MB.
- Treated memory protection as part of task scheduling, not only as a parsing fix.

**Result**

- Peak allocation pressure dropped in large-response flows.
- Memory handling became more defensive under stress instead of failing late.
- The risky paths became more stable on low-end and low-memory devices.

## 1. Startup Optimization & ANR Reduction

### Problem

- Cold start had too much work on the critical path.
- ANRs were showing up during startup and app backgrounding.
- Third-party SDK integration added I/O and initialization cost at the worst possible time.

### Root Cause

- `SharedPreferences` reads were blocking the startup path.
- OAID/GID, analytics, browser components, and ad startup all competed on the main thread.
- Repeated framework queries and splash-related timing logic added avoidable work to the main `Looper`.

### Solution

- Replaced hot-path `SharedPreferences` access with MMKV to reduce storage overhead during startup.
- Split startup config reads into phases so only required data loaded before first screen.
- Merged config sources to reduce read amplification during cold start.
- Moved OAID/GID, analytics, and browser initialization onto background threads.
- Shifted ad SDK `init` and `start` work off the main thread where possible.
- Deferred non-critical startup work with `MessageQueue.IdleHandler`.
- Cached ad views before display to reduce UI-thread work during splash rendering.
- Cached `DisplayMetrics` to avoid repeated framework lookups.
- Moved splash countdown scheduling to a dedicated `HandlerThread`.
- Let the host app perform main-process checks to avoid repeating that work inside the SDK.

### Result

- Reduced startup blocking by cutting I/O and SDK work from the first-frame path.
- Lowered ANR risk by reducing contention on the main thread.
- Made startup behavior easier to debug because initialization now had clear phases and ownership.

## 2. Memory Management & OOM Prevention

### Problem

- Large payloads created temporary objects that pushed the app toward OOM.
- Some crash paths were tied to memory pressure and oversized Binder transactions.
- Destroyed screens could retain more view state than they should.

### Root Cause

- `Retrofit` parsing used `bytes()`, which forced full in-memory buffering.
- Background work kept running even when the process was already under memory pressure.
- WebView-related flows could hit `TransactionTooLargeException`.
- View hierarchies were not always cleared aggressively enough on teardown.

### Solution

- Switched from `bytes()` to `source()` so parsing could stream instead of buffering the whole response.
- Added an OOM circuit breaker that stops non-essential tasks above 85% heap usage or below 50 MB of free system memory.
- Used a static-holder workaround in the WebView path to avoid Binder-heavy state transfer.
- Recursively removed view references up to three levels deep during `Activity` and `Fragment` destruction.

### Result

- Reduced large-object allocation in response parsing.
- Lowered the chance that memory spikes would turn into process death.
- Improved stability in heavy-page and WebView-related flows.
- Reduced leak-prone retained references during lifecycle teardown.

## 3. APK Size & Network Optimization

### Problem

- The app carried dependency and asset weight it did not need.
- Network flows downloaded resources that were not always used.
- Connection setup cost showed up too late in user-critical flows.

### Root Cause

- Serialization dependencies pulled in extra code and transitive weight.
- PNG assets were heavier than necessary.
- Resource download logic was not strict enough for media-specific ad flows.
- DNS, TCP, and TLS setup happened only when the real request started.

### Solution

- Replaced the Avro-based path with Gson where the simpler stack was sufficient.
- Removed Jackson from the dependency graph to cut package size.
- Converted suitable PNG assets to WebP.
- Added preconnect with HTTP `HEAD` to establish connections earlier.
- Skipped image downloads for video ads when the flow only required video resources.

### Result

- Reduced APK size by 25% after removing Jackson-related overhead.
- Reduced unnecessary bandwidth use by tightening resource download rules.
- Improved request readiness by warming network connections before the critical request.

## 4. Concurrency & Threading Model

### Problem

- One shared executor made unrelated tasks block each other.
- Handler-heavy scheduling made task flow harder to reason about.
- Time-sensitive work and blocking I/O were mixed together.

### Root Cause

- Config, ads, I/O, and scheduled jobs all shared the same thread-pool lane.
- Queue contention increased latency even when individual tasks were small.
- Some non-UI work still depended on `Handler` dispatch instead of simpler executor-based scheduling.

### Solution

- Split the single thread pool into isolated executors for config, ads, I/O, and scheduled work.
- Removed extra scheduling indirection where `execute()` calls were stacked without real value.
- Replaced selected `Handler` usage with thread-pool execution for non-UI tasks.
- Reused message instances with `Message.obtain()` in hot paths.

### Result

- Reduced cross-task interference between I/O, config, and ad work.
- Made execution order more predictable and easier to debug.
- Kept UI work on the main `Looper` and moved blocking work into dedicated background lanes.

## 5. Data Structure & Performance Optimization

### Problem

- Several hot paths were doing more CPU and allocation work than necessary.
- Reflection and regex added overhead in repeated operations.
- Database and cache behavior did not match real production access patterns.

### Root Cause

- Gson-based parsing depended on reflection in stable, high-frequency paths.
- Regex was used for checks that only needed simple ASCII validation.
- Database writes were too fragmented.
- Cache policy was not layered enough for local and server-side behavior.

### Solution

- Replaced Gson with hand-written JSON parsing in the hottest controlled-schema paths.
- Replaced simple regex checks with ASCII-based validation.
- Switched GreenDao operations to batch writes where possible.
- Built a multi-level cache with local memory, LRU eviction, and server-side policy coordination.

### Result

- Reduced CPU overhead in parsing and validation hot paths.
- Reduced allocation churn caused by reflection-heavy parsing.
- Improved database efficiency under bursty write patterns.
- Improved cache hit behavior by aligning local and server-side strategy.

## SDK Design Notes

This work also reflects production SDK design, not only app-side optimization.

- Built around a coroutine-first model so network and storage work stay off the main thread by default.
- Used structured error handling so callers receive typed failures instead of vague strings.
- Kept the architecture extensible so transport, auth, and retry behavior can evolve without rewriting the API surface.
- Designed integrations for production usage, where cancellation, host-app constraints, and backward compatibility matter as much as clean syntax.
- Reduced host-app overhead by avoiding repeated process checks and unnecessary framework lookups inside SDK code.
- Treated startup cost as an SDK design problem, not only an app integration problem.

## Key Takeaways

- Remove work from the main thread first, then decide what truly belongs in startup.
- Prefer streaming over full buffering when payload size is unpredictable.
- Design for low-end devices and memory pressure, not only for modern flagship phones.
- Isolate unrelated workloads so one slow lane does not stall the rest of the app.
- Keep performance fixes measurable, narrow, and maintainable.

## Topics Covered

- Startup optimization
- ANR reduction
- Main-thread debugging
- `Looper` and message queue behavior
- Memory management
- OOM prevention
- Binder transaction limits
- WebView stability
- APK size reduction
- Network warm-up
- Resource download control
- Thread-pool design
- Data structure optimization
- Cache strategy
- SDK design
- Crash debugging
