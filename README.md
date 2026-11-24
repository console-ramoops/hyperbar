# HyperBar

Bring Xiaomi's HyperIsland to One UI by replacing the native Now Bar.

HyperBar is a Java/Rust LSPosed module targeting Samsung One UI on Android 16.
The package is `com.alpha269.hyperisland`, and the current development version is
`0.1.0`. This repository contains the current source snapshot. It is not a release
APK, and live One UI integration still needs validation.

## Source layout

| Path | Purpose |
| --- | --- |
| `src/` | Settings, SystemUI hooks, card rendering, media, calls, Bluetooth and torch |
| `native/src/` | Rust motion, queue ownership, swipe geometry, blur, palette and shader drivers |
| `app/` | Module manifest, settings strings, scopes and Xposed entry point |
| `compile-stubs/de/` | Compile-only Xposed API signatures; excluded from application DEX |
| `tests/fixtures/motion/` | Measured numerical reference vectors for native regression tests |
| `tests/android-root/` | Owned Android rendering, touch and lifecycle probes |
| `tools/` | Source compilation, resource packaging and probe runners |

The LSPosed scopes are SystemUI, Samsung InCallUI and Samsung Clock. The Clock
bridge obtains exact alarm timestamps from Clock's own source rows. Private
alarm action capabilities and their dispatch guards are still pending.

## Check the source

Rust tests need Rust/Cargo and access to crates.io on the first run:

```sh
cargo test --locked --manifest-path native/Cargo.toml
```

The 60 native tests include 198 flashlight interpolation packets. The test inputs
are reconstructed from measured quartile vectors; no original JSON resource is
needed to run them.

Java compilation needs Python 3, a JDK with `javac`/`jar`, `aapt2`, and an Android
API 35 or newer platform JAR:

```sh
export HYPERBAR_ANDROID_JAR=/absolute/path/to/android.jar
python3 tools/check_source.py
```

## Build an internal Android package

The full package build currently supports Android arm64 in Termux, with
Rust/Cargo, Clang/LLD, Python 3, a JDK, `aapt2` and a D8-capable JAR. The custom
linker keeps the native library compatible with Android loading and 16 KiB pages.
The Android linker flags disable relaxation because it caused native test startup
failures on the development device.

```sh
export HYPERBAR_ANDROID_JAR=/absolute/path/to/android.jar
export HYPERBAR_D8_JAR=/absolute/path/to/r8.jar
export HYPERBAR_PRIVATE_ROOT=/absolute/path/to/private-inputs
cargo fetch --locked --manifest-path native/Cargo.toml
python3 tools/build_source.py
```

The resource-backed renderer needs separately supplied inputs. Vendor APKs,
fonts, shaders, runtime DEX, native binaries, credentials and signing keys are
excluded from this repository. The expected private input layout is:

```text
private-inputs/
  MIUISystemUIPlugin.apk
  MiuiSystemUI.apk
  InCallUI.apk
  libMiMainColor.so
  material_blend.sksl
  glass_normal.sksl
  glass_material.sksl
  glass_press_mask.sksl
  smooth_corner_mips.bin
  lottie-runtime.dex
  fonts/
    MiSansVF.ttf
    MiSansLatinVF.ttf
    MiSansCondensed2T.ttf
    mi_fonts_customization.xml
  glow/
    light_foreground.agsl
    light_background.agsl
    light_texture.agsl
  constraint-library/
    classes.jar
    constraintlayout-core-1.1.0.jar
    resource-bindings.jar
```

`classes.jar` is the ConstraintLayout 2.2.0 library. `resource-bindings.jar`
contains its matching generated resource identifiers. `lottie-runtime.dex` is
the isolated renderer closure used by the resource-backed animation views.
`tools/build_assets.py` records the exact required APK SHA-256 identities and
rejects mismatches. It packages only resource members from those APKs; application
classes are not copied from them.

The default output is `build/source-app/HyperBar-source-unsigned.test-package`.
Optional internal signing uses `HYPERBAR_KEYSTORE`, `HYPERBAR_KEY_ALIAS` and
`HYPERBAR_KEYSTORE_PASSWORD`; `HYPERBAR_KEY_PASSWORD` defaults to the store password.
Provide your own values through the environment. Signed output is
`HyperBar-source-unreleased.test-package`. Neither build path installs anything.

## Android probes

After an internal build, the owned rendering probes require `su` on Android:

```sh
python3 tools/run_root_probe.py '' rendered-torch torch
python3 tools/run_root_probe.py '' rendered-queue queue
python3 tools/run_root_probe.py '' rendered-cards
python3 tools/run_alarm_data_probe.py
```

The torch fixture uses a fake camera backend and does not change the physical
flashlight. The latest development check passed 75 assertions covering strength,
the lamp/beam pixels, off-state rendering and shared shader lifecycle. These
fixtures do not establish live LSPosed hook reliability.
The separate alarm metadata/Parcel probe passed 23 assertions; it does not
dispatch alarms or install Clock hooks.

Remaining work includes incoming-call ripple, compact charging morphs, private
alarm controls, remaining geometry/material checks and live tests for Now Bar
replacement, calls, Bluetooth and media.
