# Crash Investigation

## Problem

Crashes in production are noisy. The challenge isn't catching them — every crash SDK does that. The challenge is finding the _actual_ root cause when you have 50 variants of the same stack trace across 200 device models, half of them obfuscated, and 10% caused by OEM-modified framework code.

## Common Causes

**Java / Kotlin exceptions**
- `NullPointerException` after async callback returns to a destroyed `Activity` or detached `Fragment`
- `IllegalStateException: Can not perform this action after onSaveInstanceState` — Fragment transactions after lifecycle state change
- `DeadObjectException` on IPC calls when the remote process has been killed
- `TransactionTooLargeException` — `Bundle` payload exceeding 1MB binder limit (saved instance state, intent extras)
- `BadTokenException` — showing a `Dialog` or `Toast` after the window's activity is finishing

**Native crashes (signal-based)**
- SIGSEGV (signal 11) — null pointer dereference or use-after-free in native code
- SIGABRT (signal 6) — assertion failure in native libs, often from JNI or third-party SDKs
- Vendor GPU driver crashes — specific to chipset + driver version combos (Adreno, Mali, PowerVR)
- NDK memory corruption — buffer overflows in JNI code, double-free, heap corruption

**OEM / OS fragmentation**
- `RemoteServiceException: Bad notification` on Samsung OneUI specific versions
- `Resources$NotFoundException` on Xiaomi devices with custom theme engine
- `SecurityException` on Huawei devices with aggressive permission enforcement
- System-initiated kills reported as crashes by some SDKs (not real crashes, but pollute data)

**R8 / ProGuard issues**
- Missing `-keep` rules causing reflection-based code to be stripped or renamed
- Enum switch maps broken by obfuscation
- `ClassNotFoundException` in dynamically loaded code paths
- Callback interfaces removed because R8 determined they were unused (incorrect tree shaking)

## How to Debug

**Deobfuscation**
- Upload ProGuard/R8 `mapping.txt` per build to your crash reporting tool
- For retraced stack: `retrace mapping.txt stacktrace.txt`
- Native crashes: need unstripped `.so` files matching exact build — keep your symbol archives per release
- For `ndk-stack`: `adb logcat | ndk-stack -sym obj/local/armeabi-v7a/`

**Crash clustering**
- Group by normalized stack trace (top N frames after deobfuscation)
- Segment by: OS version, device manufacturer, app version, install source
- Compare crash rate between releases — a spike after a release is your code; a spike across all versions is OS or backend
- Look at crash-free users percentage, not absolute crash count — 1000 crashes from 10 users is different from 1000 crashes from 1000 users

**Native crash analysis**
- Read the tombstone: `signal`, `fault addr`, `backtrace`, `registers`, `memory map`
- `fault addr 0x0` = null dereference. `fault addr` in code segment = corrupted function pointer
- Check `build id` in memory map against your symbol server
- For GPU crashes: match `ro.board.platform` and `ro.hardware` to known driver bugs

**Reproduction**
- Build a crash-specific test matrix: exact device + OS version from top crash clusters
- For lifecycle crashes: use `Don't keep activities` developer option + aggressive `ProcessLifecycleOwner` testing
- For `TransactionTooLargeException`: use `TooLargeTool` library to monitor bundle sizes in debug builds
- For native: ASan builds catch most memory errors — run your critical user flows under ASan on CI

## What Actually Works

- **Lifecycle-safe callbacks**: use `LifecycleCoroutineScope` or `repeatOnLifecycle` to cancel async work when the component is destroyed — eliminates the largest class of production NPEs
- **Strict bundle size monitoring**: log `Bundle` sizes on `onSaveInstanceState`, alert on anything > 500KB. Replace large payloads with `ViewModel` or persistent storage
- **Vendor crash allowlists**: maintain a list of known OEM-caused crash signatures and exclude them from your crash-free rate calculations — otherwise you'll chase bugs you can't fix
- **Staged rollouts + crash rate gates**: 1% → 5% → 25% → 100% with automatic halt if crash-free rate drops below threshold
- **Symbol server**: automated pipeline that archives `mapping.txt` + unstripped `.so` for every release build. Tied to git commit SHA. Non-negotiable for any team doing native development
- **Pre-release crash testing**: run full regression on Firebase Test Lab across top 20 device models before release. Catches ~60% of device-specific crashes

## Notes

- Crash-free rate targets: 99.5%+ for Java, 99.9%+ for native. Below these thresholds, Play Store ranking is affected
- `Thread.setDefaultUncaughtExceptionHandler()` — if you use multiple crash SDKs, they chain. Order matters. Test that all of them actually receive the crash
- ANRs are not crashes but are often worse for user experience. Track them together
- Some crashes are intentional: `System.exit()`, `Process.killProcess()`. Filter these from your crash pipeline
- Firebase Crashlytics, Bugsnag, Sentry all have slightly different stack grouping algorithms — the same crash will have different "issue" counts across tools. Pick one as source of truth
