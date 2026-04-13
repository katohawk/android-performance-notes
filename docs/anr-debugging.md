# ANR Debugging

## Problem

Application Not Responding — the system kills your process (or shows a dialog) when the main thread is blocked for 5+ seconds on input events, or 10+ seconds on BroadcastReceiver / Service.

In production, ANR rates above 0.47% directly impact Play Store visibility. Unlike crashes, users often don't report ANRs — they just leave.

## Symptoms

- Play Console shows elevated ANR rate, but crash rate is unchanged
- User reviews mention "freezing" or "hanging" without specific repro steps
- Backend logs show requests that never arrived (user gave up during ANR)
- `ActivityManager: ANR in com.yourapp` in system logcat on test devices
- Spike in ANR rate correlated with a specific device/OS segment, not a code change

## Root Causes

**Main thread blocking**
- `SharedPreferences.apply()` flushing synchronously during `Activity.onStop()` — the `QueuedWork.waitToFinish()` trap
- `ContentProvider.onCreate()` running heavy init before `Application.onCreate()` finishes
- Database queries or disk I/O on the main thread (often hidden behind "fast" Room calls)
- `View.post()` queuing work that blocks behind a stalled message

**Binder contention**
- All binder threads occupied — any subsequent IPC call from main thread hangs
- `ActivityManagerService` lock contention under heavy system load
- `getSystemService()` calls that trigger cross-process binder transactions

**Broadcast receiver timeouts**
- Ordered broadcast processing exceeding 10s
- `onReceive()` doing network or disk I/O directly
- Implicit broadcast storms after boot or connectivity changes

**Input dispatching**
- Window not consuming input events within 5s
- Stalled `onTouchEvent` or `dispatchTouchEvent` chains
- Custom `View` measurement/layout taking too long during user interaction

## Debugging Steps

**From bug reports / traces**
- Pull `/data/anr/traces.txt` (or `bugreport`) — look at main thread stack
- Check `"main" prio=5 tid=1` block — the stack trace tells you exactly where it's stuck
- Look for `MONITOR` or `WAIT` states — indicates lock contention
- Check CPU usage in ANR header: if `100% TOTAL` with `0.1% user` → I/O wait; if high user% → CPU-bound

**Systrace / Perfetto**
- Capture with `atrace` categories: `sched`, `am`, `view`, `dalvik`
- Look for gaps in the main thread — long stretches with no slices = blocked
- Check `binder_driver` tracks for cross-process stalls
- Perfetto SQL: query `thread_state` table for main thread `Uninterruptible Sleep` durations

**In production**
- `ActivityManager.getProcessInErrorState()` for programmatic detection
- ANR watchdog (background thread posting to main looper, checking if it executes within threshold)
- `StrictMode` with custom `penaltyListener` (API 28+) for disk/network on main thread

## What Actually Worked

- **Move `SharedPreferences` to async**: use `DataStore` or at minimum wrap `apply()` paths — but the real fix is avoiding `QueuedWork.waitToFinish()` by using `commit()` off main thread instead of `apply()` on main thread
- **Audit every ContentProvider**: each one runs `onCreate()` on the main thread before your Application. Merge or defer with `App Startup` library
- **Binder thread monitoring**: track binder thread pool utilization; when pool is full, main thread binder calls will ANR
- **Background-restrict heavy receivers**: move work to `JobIntentService` / `WorkManager`, return from `onReceive()` immediately
- **Cold path profiling**: ANRs often cluster on specific device/OS combos — segment your ANR data by `Build.MANUFACTURER` and `Build.VERSION.SDK_INT`

## Real Example: SharedPreferences ANR on Activity Stop

We had a 0.6% ANR rate that appeared only on Android 8.0–8.1 devices. Stack traces all showed `QueuedWork.waitToFinish()` called from `ActivityThread.handleStopActivity()`.

What happened: `SharedPreferences.apply()` was being called from multiple places during normal usage. On API 26–27, `waitToFinish()` drains the pending queue synchronously on `onStop()`. With 3–4 pending writes, this could take 200–800ms. Combined with a slow eMMC on budget devices, it crossed the 5s threshold.

Fix: replaced the hot-path `apply()` calls with `commit()` on a background dispatcher. Counter-intuitive — `commit()` is the "blocking" API — but by running it off main thread, we avoided the `waitToFinish()` trap entirely. ANR rate dropped to 0.15%.

## Anti-Pattern: "Just Move Everything to a Background Thread"

Wrapping every main-thread operation in `withContext(Dispatchers.IO)` seems like an easy fix, but it creates new problems:
- View state reads/writes from background threads cause race conditions
- `Dispatchers.IO` has a 64-thread limit — under heavy load, you get thread starvation and a different kind of ANR
- Some framework APIs (e.g., `SharedPreferences.Editor.apply()`) are designed to be called on main thread and do their own async handling — double-wrapping them doesn't help and can cause ordering bugs

The right approach is targeted: identify the specific blocking call from `traces.txt`, understand _why_ it's blocking, and fix that specific path.

## Notes

- `traces.txt` is overwritten on each ANR — if you need history, collect them via a background service or use a crash reporting SDK that captures ANR traces
- Pre-Android 11: ANR dialog shown to user. Android 11+: silent ANR kill for background processes — your ANR rate is higher than what users report
- Google Play Vitals uses a different ANR detection mechanism than `traces.txt` — rates won't match exactly
- OEM skins (MIUI, OneUI, ColorOS) add their own ANR thresholds and behaviors — always test on stock AOSP as baseline, then validate on top OEM devices

## Edge Cases

- **ANRs during configuration change**: if `onSaveInstanceState` triggers a large `Parcelable` serialization, the main thread can block long enough to ANR. This won't show up as a typical "disk I/O" issue
- **ANRs from `JobScheduler` constraints**: on Android 11+, a `JobService` that doesn't call `jobFinished()` within the timeout will trigger a background ANR — these are invisible to users but count in Play Vitals
- **False positives from system load**: during OTA updates or heavy Google Play background activity, system-wide CPU contention can cause ANRs in your app that have nothing to do with your code. Segment by `ActivityManager.RunningAppProcessInfo.importance` to filter these
