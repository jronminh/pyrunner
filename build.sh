#!/usr/bin/env bash
# Builds pyrunner.apk with no Gradle/Android Studio: javac -> d8 -> aapt ->
# apksigner, plus a CPython runtime lifted from a Termux prefix and bundled as
# assets. PREFIX's python is only ever copied, never executed, so an aarch64
# APK cross-builds fine from a non-ARM host. Needs: python3 (host), openjdk
# (javac/keytool), android build-tools (aapt/d8/apksigner), patchelf, readelf.
#
# Env overrides:
#   PREFIX       Termux prefix holding python (default: $PREFIX)
#   ANDROID_JAR  android.jar to compile against (default: SDK platform, then ./android.jar)
#   KEYSTORE     signing keystore (generated on first run if absent)
#   OUT_DIR      where pyrunner.apk lands (default: ./out)
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/out}"
BUILD="$HERE/build"
ASSETS="$HERE/assets"
NATIVE="$BUILD/lib/arm64-v8a"
RUNTIME="$BUILD/runtime"

PREFIX="${PREFIX:?set PREFIX to a Termux prefix containing python}"
KEYSTORE="${KEYSTORE:-$HERE/debug.keystore}"
CC="${CC:-clang}"

if [ -z "${ANDROID_JAR:-}" ]; then
    for candidate in \
        "${ANDROID_HOME:-}/platforms/android-34/android.jar" \
        "${ANDROID_SDK_ROOT:-}/platforms/android-34/android.jar" \
        "$HERE/android.jar"; do
        [ -f "$candidate" ] && ANDROID_JAR="$candidate" && break
    done
fi
if [ -z "${ANDROID_JAR:-}" ] || [ ! -f "$ANDROID_JAR" ]; then
    echo "No android.jar found. Set ANDROID_JAR, e.g. from" >&2
    echo "  https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar" >&2
    exit 1
fi

for tool in javac keytool patchelf readelf d8 aapt apksigner "$CC"; do
    command -v "$tool" >/dev/null 2>&1 || { echo "Missing tool: $tool" >&2; exit 1; }
done

PY_BIN="$(ls -1 "$PREFIX"/bin/python3.* 2>/dev/null | grep -v -- -config | head -1 || true)"
if [ -z "$PY_BIN" ] || [ ! -x "$PY_BIN" ]; then
    echo "No python3.x executable in $PREFIX/bin" >&2
    exit 1
fi
PYVER="$(basename "$PY_BIN" | sed 's/^python//')"
STDLIB="$PREFIX/lib/python$PYVER"
if [ ! -d "$STDLIB" ]; then
    echo "No stdlib at $STDLIB" >&2
    exit 1
fi
echo "Python: $PYVER ($PY_BIN)"

if [ ! -f "$KEYSTORE" ]; then
    echo "Generating keystore $KEYSTORE"
    keytool -genkeypair -keystore "$KEYSTORE" -alias pyrunner \
        -storepass android -keypass android -keyalg RSA -keysize 2048 \
        -validity 10000 -dname "CN=PyRunner"
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$NATIVE" "$ASSETS" "$OUT_DIR"

echo "Staging interpreter..."
cp "$PY_BIN" "$NATIVE/libpython3.so"
chmod 755 "$NATIVE/libpython3.so"
patchelf --set-rpath '$ORIGIN' "$NATIVE/libpython3.so"

echo "Building PTY shim..."
JNI_H="$(find "$(dirname "$(dirname "$(command -v javac)")")" -name jni.h 2>/dev/null | head -1)"
if [ -z "$JNI_H" ]; then
    echo "jni.h not found next to javac" >&2
    exit 1
fi
JNI_INC="$(dirname "$JNI_H")"
"$CC" -shared -fPIC -O2 -I"$JNI_INC" -I"$JNI_INC/linux" \
    -o "$NATIVE/libpty.so" "$HERE/jni/pty.c"

echo "Computing shared-lib closure..."
CLOSURE="$(python3 - "$PREFIX" "$PY_BIN" "$PYVER" "$STDLIB" <<'PY'
import os, re, subprocess, sys

prefix, py_bin, pyver, stdlib = sys.argv[1:5]
# Present in every Android app sandbox already; never bundle these.
system = {"libc.so", "libm.so", "libdl.so", "liblog.so", "libz.so",
          "libandroid.so", "libstdc++.so", "libc++_shared.so", "libc++abi.so"}


def needed(path):
    out = subprocess.run(["readelf", "-d", path], capture_output=True, text=True).stdout
    return re.findall(r"\(NEEDED\).*?\[([^\]]+)\]", out)


roots = [py_bin, os.path.join(prefix, "lib", "libpython%s.so" % pyver)]
dynload = os.path.join(stdlib, "lib-dynload")
roots += [os.path.join(dynload, f) for f in os.listdir(dynload) if f.endswith(".so")]

seen, queue = set(), list(roots)
while queue:
    for name in needed(queue.pop()):
        if name in system or name in seen:
            continue
        path = os.path.join(prefix, "lib", name)
        if os.path.exists(path):
            seen.add(name)
            queue.append(path)

for name in sorted(seen):
    print(name)
PY
)"

echo "Staging runtime..."
mkdir -p "$RUNTIME/lib"
cp -a "$STDLIB" "$RUNTIME/lib/python$PYVER"
rm -rf "$RUNTIME/lib/python$PYVER"/site-packages \
       "$RUNTIME/lib/python$PYVER"/ensurepip \
       "$RUNTIME/lib/python$PYVER"/pydoc_data \
       "$RUNTIME/lib/python$PYVER"/idlelib \
       "$RUNTIME/lib/python$PYVER"/tkinter \
       "$RUNTIME/lib/python$PYVER"/test \
       "$RUNTIME/lib/python$PYVER"/config-*
find "$RUNTIME/lib/python$PYVER" -name __pycache__ -type d -prune -exec rm -rf {} +
for name in $CLOSURE; do
    cp "$PREFIX/lib/$name" "$RUNTIME/lib/$name"
    patchelf --set-rpath '$ORIGIN' "$RUNTIME/lib/$name"
done

echo "Packing assets..."
rm -f "$ASSETS/pyruntime.zip"
python3 - "$RUNTIME" "$ASSETS/pyruntime.zip" <<'PY'
import os, sys, zipfile

root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, _dirs, files in os.walk(root):
        for name in files:
            path = os.path.join(base, name)
            z.write(path, os.path.relpath(path, root))
PY

echo "Compiling..."
javac -cp "$ANDROID_JAR" -d "$BUILD/obj" $(find "$HERE/src" -name '*.java')
d8 --lib "$ANDROID_JAR" --output "$BUILD" $(find "$BUILD/obj" -name '*.class')

echo "Packaging..."
aapt package -f -A "$ASSETS" -S "$HERE/res" -M "$HERE/AndroidManifest.xml" \
    -I "$ANDROID_JAR" -F "$BUILD/app-unsigned.apk"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
(cd "$BUILD" && aapt add app-with-dex.apk classes.dex lib/arm64-v8a/libpython3.so lib/arm64-v8a/libpty.so)

echo "Signing..."
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_DIR/pyrunner.apk" "$BUILD/app-with-dex.apk"

echo "Built: $OUT_DIR/pyrunner.apk"
