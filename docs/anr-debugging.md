# ANR Debugging

## Problem

Application Not Responding — the system kills your process (or shows a dialog) when the main thread is blocked for 5+ seconds on input events, or 10+ seconds on BroadcastReceiver / Service.

In production, ANR rates above 0.47% directly impact Play Store visibility.

## Common Causes

**Main thread blocking**
- `SharedPreferences.apply()` flushing synchronously during `Activity.onStop()` — the `QueuedWork` waitToFinish trap
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

## How to Debug

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

## What Actually Works

- **Move `SharedPreferences` to async**: use `DataStore` or at minimum wrap `apply()` paths — but the real fix is avoiding `QueuedWork.waitToFinish()` by using `commit()` off main thread instead of `apply()` on main thread
- **Audit every ContentProvider**: each one runs `onCreate()` on the main thread before your Application. Merge or defer with `App Startup` library
- **Binder thread monitoring**: track binder thread pool utilization; when pool is full, main thread binder calls will ANR
- **Background-restrict heavy receivers**: move work to `JobIntentService` / `WorkManager`, return from `onReceive()` immediately
- **Cold path profiling**: ANRs often cluster on specific device/OS combos — segment your ANR data by `Build.MANUFACTURER` and `Build.VERSION.SDK_INT`

## Notes

- `traces.txt` is overwritten on each ANR — if you need history, collect them via a background service or use a crash reporting SDK that captures ANR traces
- Pre-Android 11: ANR dialog shown to user. Android 11+: silent ANR kill for background processes — means your ANR rate may be higher than what users report
- Google Play Vitals uses a different ANR detection mechanism than `traces.txt` — rates won't match exactly
- OEM skins (MIUI, OneUI, ColorOS) add their own ANR thresholds and behaviors — always test on stock AOSP as baseline, then validate on top OEM devices
