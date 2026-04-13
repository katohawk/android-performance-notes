# Startup Optimization

## Problem

Cold start time directly impacts retention. Users perceive anything over ~1s as slow. Google Play Vitals flags excessive startup time, and it's a ranking signal.

Cold start = process creation → `Application.onCreate()` → activity created → first frame drawn. Every millisecond in that path is on the critical path.

## Common Causes

**Application.onCreate() overload**
- SDK initialization: analytics, crash reporting, A/B testing, push — each adding 50-200ms
- Eager singleton creation that triggers disk I/O or network setup
- Dagger/Hilt component initialization with large object graphs
- `MultiDex.install()` on pre-API 21 (still relevant for some apps)

**ContentProvider init cost**
- Every `ContentProvider` declared in the manifest runs `onCreate()` before `Application.onCreate()`
- Firebase, WorkManager, Lifecycle libraries each register their own ContentProvider
- On low-end devices, 6+ ContentProviders can add 200-400ms before your code even runs

**Main thread I/O**
- `SharedPreferences` loading full XML file on first `getSharedPreferences()` call
- Reading config/feature flags from disk synchronously
- Font loading, asset decoding, or theme resolution triggering disk reads
- SQLite database open/upgrade on main thread

**Layout & rendering**
- Deep view hierarchies in the first activity — measure/layout pass taking 100ms+
- Custom views doing allocation-heavy work in constructors or `onMeasure`
- Large background drawables decoded at full resolution
- `WebView` first-time init (~100-300ms for Chromium setup)

## How to Debug

**Macrobenchmark**
- Use `StartupTimingMetric` with `StartupMode.COLD` / `WARM` / `HOT`
- Run on a locked-clock physical device — emulators and unlocked clocks produce noisy data
- Compare P50 and P95 — optimizations that help P50 but not P95 often miss the real problem

**Systrace / Perfetto**
- `reportFullyDrawn()` sets an explicit marker in the trace
- Look for `bindApplication`, `activityStart`, `activityResume`, `Choreographer#doFrame`
- Check for long `Binder` blocks between zygote fork and first draw
- Perfetto: `SELECT dur, name FROM slice WHERE track_id = (main thread) ORDER BY dur DESC`

**Baseline Profile**
- Generate with Macrobenchmark rule, bundle in AAB
- Reduces JIT compilation on first launch — typically 20-40% improvement on cold start
- Verify it's actually being applied: check `logcat` for `ProfileInstaller` messages

**Logging**
- Instrument `Application.onCreate()`, each init block, `Activity.onCreate()`, first `onDraw()`
- Use `System.nanoTime()` deltas, not `SystemClock` — you want wall clock for startup
- Report startup time to your analytics backend, segmented by device tier

## What Actually Works

- **Defer everything that isn't needed for first frame**: use `IdleHandler` or `ContentProvider`-free `App Startup` library to schedule post-first-frame init
- **Merge ContentProviders**: use Jetpack `App Startup` to collapse multiple library inits into a single provider
- **Async init with dependency graph**: build an init task DAG — parallelize independent SDK inits on background threads, block only for tasks the first activity actually needs
- **Baseline Profiles**: single highest-ROI change for most apps. Generate, ship in AAB, verify
- **Lazy singletons**: replace eager `@Singleton` components with `Provider<T>` or `Lazy<T>` — only pay init cost when first used
- **Placeholder UI**: show a real layout (not a splash screen) immediately via `windowBackground` theme attribute — this doesn't reduce actual startup time but changes perceived performance
- **Remove or merge SharedPreferences files**: each file is a full XML parse. Consolidate into fewer files, or migrate to `DataStore`
- **Profile-guided layout**: flatten first-activity layout. Replace nested `LinearLayout` with `ConstraintLayout`. Use `ViewStub` for below-fold content.

## Notes

- `reportFullyDrawn()` is what Google Play Vitals uses to measure "time to full display" — call it when your first meaningful content is rendered, not when the activity starts
- Startup time is bimodal: warm cache vs cold cache. Profile both — your 90th percentile user has a cold disk cache
- On low-RAM devices (`ActivityManager.isLowRamDevice()`), startup is 2-5x slower. Always have a low-end test device
- Android 12+ splash screen API changes the startup UX — test with and without `SplashScreen` compat library
- R8 full mode (`android.enableR8.fullMode=true`) can shave class loading time by removing more unused code — but test thoroughly for reflection breakage
