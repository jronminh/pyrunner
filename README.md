# PyRunner

Run Python scripts on Android. Tap a `.py` file in any file manager and
PyRunner opens a console with its output, plus a stdin line so `input()` works.

The APK bundles CPython 3.14 taken from Termux, so nothing else needs to be
installed. No Gradle, no Android Studio: the build is `javac` → `d8` → `aapt`
→ `apksigner`, with the interpreter and its shared-library closure shipped as
assets and extracted on first run.

## Install

Grab `pyrunner-*.apk` from [Releases](../../releases) and install it
(arm64-v8a, Android 7.0+).

## Build

```sh
ci/fetch-termux-prefix.sh ./prefix
PREFIX=./prefix ANDROID_JAR=/path/to/android.jar bash build.sh
```

`ci/fetch-termux-prefix.sh` pulls Termux's aarch64 python and the shared
libraries its stdlib needs. `build.sh` only ever copies that interpreter, never
executes it, so the APK cross-builds on any host. On Termux itself, skip the
first step and run `bash build.sh` directly — `$PREFIX` is already correct.

## Releases

`.github/workflows/release.yml` builds on every `v*` tag and attaches the APK
to a GitHub Release.

## License

GPL-3.0-or-later — see [LICENSE](LICENSE).
