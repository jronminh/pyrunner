#!/usr/bin/env bash
set -euo pipefail

HERE="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
OUT_DIR="${OUT_DIR:-$HERE/out}"
BUILD="$HERE/build"
ANDROID_JAR="${ANDROID_JAR:-/data/data/com.termux/files/home/termux-adb-bridge/build/adbwifi-helper/android.jar}"
KEYSTORE="${KEYSTORE:-/data/data/com.termux/files/home/termux-adb-bridge/build/adbwifi-helper/debug.keystore}"
NATIVE="$BUILD/lib/arm64-v8a"
ASSETS="$HERE/assets"

if [ ! -f "$ANDROID_JAR" ]; then
    echo "Missing $ANDROID_JAR" >&2
    echo "Download one matching your device's API level, e.g.:" >&2
    echo "  curl -sL -o '$ANDROID_JAR' \\" >&2
    echo "    https://raw.githubusercontent.com/Reginer/aosp-android-jar/main/android-34/android.jar" >&2
    exit 1
fi

rm -rf "$BUILD"
mkdir -p "$BUILD/obj" "$NATIVE" "$ASSETS" "$OUT_DIR"

echo "Staging executable..."
cp "$PREFIX/bin/python3.14" "$NATIVE/libpython3.so"
chmod 755 "$NATIVE/libpython3.so"
patchelf --set-rpath '$ORIGIN' "$NATIVE/libpython3.so"

echo "Computing native lib closure..."
CLOSURE=$($PREFIX/bin/python3 -c "
import subprocess, os, sys

skip = {'libc.so','libm.so','libdl.so','liblog.so','libz.so','libandroid.so','libstdc++.so','libc++_shared.so','libc++abi.so'}
seen = set()
result = []

def needed(file):
    import re
    out = subprocess.check_output(['readelf','-d',file], stderr=subprocess.DEVNULL, text=True)
    deps = []
    for line in out.splitlines():
        if 'NEEDED' in line:
            m = re.search(r'\[([^\]]+)\]', line)
            if m:
                deps.append(m.group(1))
    return deps

def resolve(lib):
    if lib in skip or lib in seen:
        return
    seen.add(lib)
    path = os.path.join('$PREFIX/lib', lib)
    if os.path.exists(path):
        result.append(lib)
        for d in needed(path):
            resolve(d)

for f in ['$PREFIX/bin/python3.14', '$PREFIX/lib/libpython3.14.so']:
    for d in needed(f):
        resolve(d)

for dyn in os.listdir('$PREFIX/lib/python3.14/lib-dynload'):
    if dyn.endswith('.so'):
        f = os.path.join('$PREFIX/lib/python3.14/lib-dynload', dyn)
        for d in needed(f):
            resolve(d)

for r in sorted(result):
    print(r)
")

echo "Building runtime zip..."
RUNTIME="$BUILD/runtime"
rm -rf "$RUNTIME"
mkdir -p "$RUNTIME/lib"
cp -a "$PREFIX/lib/python3.14" "$RUNTIME/lib/python3.14"
rm -rf "$RUNTIME/lib/python3.14/site-packages"
rm -rf "$RUNTIME/lib/python3.14/__pycache__"
rm -rf "$RUNTIME/lib/python3.14/ensurepip"
rm -rf "$RUNTIME/lib/python3.14/pydoc_data"
rm -rf "$RUNTIME/lib/python3.14/idlelib"
rm -rf "$RUNTIME/lib/python3.14/tkinter"
rm -rf "$RUNTIME/lib/python3.14/test"
rm -rf "$RUNTIME/lib/python3.14/config-"*
find "$RUNTIME/lib/python3.14" -name __pycache__ -type d -prune -exec rm -rf {} +
for n in $CLOSURE; do
    cp "$PREFIX/lib/$n" "$RUNTIME/lib/$n"
done

echo "Zipping pyruntime..."
rm -f "$ASSETS/pyruntime.zip"
$PREFIX/bin/python3 - "$RUNTIME" "$ASSETS/pyruntime.zip" <<'PYEOF'
import os, sys, zipfile
root, out = sys.argv[1], sys.argv[2]
with zipfile.ZipFile(out, "w", zipfile.ZIP_DEFLATED) as z:
    for base, dirs, files in os.walk(root):
        for f in files:
            p = os.path.join(base, f)
            z.write(p, os.path.relpath(p, root))
PYEOF

echo "Compiling Java..."
javac -cp "$ANDROID_JAR" -d "$BUILD/obj" $(find "$HERE/src" -name '*.java')

echo "Converting to dex..."
d8 --lib "$ANDROID_JAR" --output "$BUILD" $(find "$BUILD/obj" -name '*.class')

echo "Packaging unsigned APK..."
aapt package -f -A "$ASSETS" -M "$HERE/AndroidManifest.xml" -I "$ANDROID_JAR" -F "$BUILD/app-unsigned.apk"
cp "$BUILD/app-unsigned.apk" "$BUILD/app-with-dex.apk"
(cd "$BUILD" && aapt add app-with-dex.apk classes.dex lib/arm64-v8a/libpython3.so)

echo "Signing..."
apksigner sign --ks "$KEYSTORE" --ks-pass pass:android --key-pass pass:android \
    --out "$OUT_DIR/pyrunner.apk" "$BUILD/app-with-dex.apk"

echo "Built: $OUT_DIR/pyrunner.apk"
