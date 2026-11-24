# HyperBar

Bring Xiaomi's HyperIsland to One UI by replacing the native Now Bar.

HyperBar is a Java/Rust LSPosed module targeting Samsung One UI on Android 16.
The package is `com.alpha269.hyperisland`, and the current development version is
`0.1.0`. This repository contains the Java/Rust source and build tools. Vendor
resource packs, fonts, shaders, and signing keys are intentionally excluded.

## Highlights

- **Universal HyperOS charging capsule**: Replaces One UI's unlocked battery-icon plug-in animation (`SecPowerUI` / `SystemStatusAnimationSchedulerImpl`) with HyperOS's glowing bolt `ChargerView`, hides competing islands temporarily for full solo width, shows calibrated real hardware fuel-gauge decimals (`charge_counter / charge_full`) on fast charging with clean integer-percentage fallback if fuel-gauge telemetry is unavailable, and suppresses duplicate popups on late fast-charge negotiation.
- **Notification shade Now Bar & media parity**: Renders HyperOS live cards and ENR media players in the One UI notification panel while filtering duplicated `MediaOngoingActivity` and ongoing summary rows.
- **3+ concurrent activities & smooth gestures**: Coexists as Card + Top Big + Top Small when expanded, supports cross-expanding and horizontal swipe cycling across 2+ activities, and includes capsule-to-activity launch morphing with exit-bridge catch-bounce (`absorb()`).
- **Screen recording & ongoing call integration**: Supports Samsung Screen Recorder (`smartcapture`) with compact waveform icon, Stop action, and dead-chip cleanup; leaves incoming calls to One UI while handling active ongoing calls.
- **Adaptive light blur, opt-in liquid glass & Miuix-styled Settings**: Uses native `SemBlurInfo` compositor blur when supported, falls back to 1/4-res captured box blur on mid-range One UI devices, and provides toggles for Enable HyperBar, Bluetooth cards, Island light blur, Liquid glass refraction, and HyperOS charging animation.
- **Compact pruned asset packaging**: `tools/build_assets.py` computes a 2-pass transitive `aapt2` resource closure per donor carrier APK and subsets `MiSansVF.ttf` via `fontTools` (with automatic fallbacks), shrinking the packaged APK from ~36 MB to ~7 MB while preserving 100% of active resources and tests.

## Source layout

| Path | Purpose |
| --- | --- |
| `src/` | Settings UI, SystemUI hooks, shade integration, charging/media/calls/Bluetooth/torch/recording sources, and island views |
| `native/src/` | Rust motion, queue ownership, swipe geometry, wave shader clock, blur, palette, and shader drivers |
| `app/` | Module manifest, settings strings, scopes, and Xposed entry point |
| `compile-stubs/de/` | Compile-only Xposed API signatures; excluded from application DEX |
| `tests/fixtures/motion/` | Measured numerical reference vectors for native regression tests |
| `tests/android-root/` | Owned Android rendering, touch, effects, and lifecycle probes |
| `tools/` | Source compilation, pruned asset packaging, and probe runners |

The LSPosed scopes are SystemUI and Samsung Clock.

## Check the source

Rust tests need Rust/Cargo (62 native unit tests):

```sh
cargo test --locked --manifest-path native/Cargo.toml
```

Java compilation needs Python 3, a JDK with `javac`/`jar`, `aapt2`, and an Android
API 35 or newer platform JAR:

```sh
export HYPERBAR_ANDROID_JAR=/absolute/path/to/android.jar
python3 tools/check_source.py
```

## Build an internal Android package

The full package build supports Android arm64 in Termux (or Linux cross-build),
with Rust/Cargo, Clang/LLD, Python 3 (optionally `fonttools` for font subsetting),
a JDK, `aapt2`, and a D8-capable JAR:

```sh
export HYPERBAR_ANDROID_JAR=/absolute/path/to/android.jar
export HYPERBAR_D8_JAR=/absolute/path/to/r8.jar
export HYPERBAR_PRIVATE_ROOT=/absolute/path/to/private-inputs
cargo fetch --locked --manifest-path native/Cargo.toml
python3 tools/build_source.py
```

The resource-backed renderer needs separately supplied inputs under
`HYPERBAR_PRIVATE_ROOT` (vendor APKs, fonts, shaders, `lottie-runtime.dex`,
`libMiMainColor.so`, and ConstraintLayout jars). `tools/build_assets.py` verifies
exact donor SHA-256 identities, prunes unused carrier members via 2-pass `aapt2`
closure (falling back to full `res/` if `HYPERBAR_FULL_ASSETS=1` or `aapt2` is
unavailable), and subsets `MiSansVF.ttf` (falling back cleanly if `fontTools` is
absent or `HYPERBAR_FULL_FONTS=1`).

The default output is `build/source-app/HyperBar-source-unsigned.test-package`.
Optional signing uses `HYPERBAR_KEYSTORE`, `HYPERBAR_KEY_ALIAS`, and
`HYPERBAR_KEYSTORE_PASSWORD` (`HYPERBAR_KEY_PASSWORD` defaults to the store
password), producing `HyperBar-source-unreleased.test-package`.

## Android probes

After building a package, the owned Android probes run via `su`:

```sh
python3 tools/run_root_probe.py '' rendered-fixed effects
python3 tools/run_root_probe.py '' rendered-fixed torch
python3 tools/run_root_probe.py '' rendered-fixed queue
python3 tools/run_root_probe.py '' rendered-fixed press
python3 tools/run_root_probe.py '' rendered-fixed swipe
python3 tools/run_root_probe.py '' rendered-fixed
```

Verified on the pruned ~7 MB build: 62 Rust tests, 35 effects checks, 75 torch
checks, 110 queue checks, 49 press checks, 29 swipe checks, and all 37 composed
cards rendered with zero text clipping (`clipped: []`).
