#!/usr/bin/env bash
# Vendor the Qualcomm QNN SDK pieces the native bridge needs into the app.
# These are Qualcomm-licensed and git-ignored; run this once after setting
# QNN_SDK_ROOT (see tools/README_qnn.md §0a) to enable the on-device NPU path.
#
#   export QNN_SDK_ROOT=~/qairt/<version>
#   tools/vendor_qnn_sources.sh
#
# Copies: SampleApp helper sources (cpp/qnn/), the arm64 CPU runtime libs
# (jniLibs/), and the v69 Hexagon skel (assets/models/dsp/). The detector_v69.bin /
# lane_v69.bin context binaries are produced separately (tools/README_qnn.md §2).
set -euo pipefail

: "${QNN_SDK_ROOT:?set QNN_SDK_ROOT to your QAIRT/QNN SDK (see tools/README_qnn.md)}"
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
APP="$QNN_SDK_ROOT/examples/QNN/SampleApp"
[ -d "$APP/src/Utils" ] || APP="$APP/SampleApp"   # QAIRT >= 2.4x nests it one deeper
DST="$ROOT/app/src/main/cpp/qnn"

echo ">> Vendoring SampleApp helper sources -> $DST"
rm -rf "$DST"; mkdir -p "$DST/PAL/src/common" "$DST/PAL/src/linux" "$DST/PAL/include"
cp -r "$APP/src/Utils" "$APP/src/WrapperUtils" "$APP/src/Log" "$DST/"
cp "$APP/src/PAL/src/common/"*.cpp "$DST/PAL/src/common/"
cp "$APP/src/PAL/src/linux/"*.cpp  "$DST/PAL/src/linux/"
cp -r "$APP/src/PAL/include/"* "$DST/PAL/include/"
cp "$APP/src/"*.hpp "$DST/"
# Re-expose IOTensor's private quant/dequant helpers for the bridge.
if ! grep -q "floatToNative" "$DST/Utils/IOTensor.hpp"; then
  sed -i 's/^ public:/ public:\n  StatusCode floatToNative(float *f, Qnn_Tensor_t *t) { return copyFromFloatToNative(f, t); }\n  StatusCode nativeToFloat(float **o, Qnn_Tensor_t *t) { return convertToFloat(o, t); }/' "$DST/Utils/IOTensor.hpp"
fi

echo ">> Vendoring arm64 runtime libs -> jniLibs/arm64-v8a (v69 S22+, v81 S26)"
mkdir -p "$ROOT/app/src/main/jniLibs/arm64-v8a"
for f in libQnnHtp.so libQnnSystem.so libQnnHtpV69Stub.so libQnnHtpV81Stub.so; do
  cp "$QNN_SDK_ROOT/lib/aarch64-android/$f" "$ROOT/app/src/main/jniLibs/arm64-v8a/" 2>/dev/null || \
    echo "   (skip $f — not in this SDK)"
done

echo ">> Vendoring Hexagon skels -> assets/models/dsp (v69 S22+, v81 S26)"
mkdir -p "$ROOT/app/src/main/assets/models/dsp"
cp "$QNN_SDK_ROOT/lib/hexagon-v69/unsigned/libQnnHtpV69Skel.so" "$ROOT/app/src/main/assets/models/dsp/"
cp "$QNN_SDK_ROOT/lib/hexagon-v81/unsigned/libQnnHtpV81Skel.so" "$ROOT/app/src/main/assets/models/dsp/" 2>/dev/null || \
  echo "   (skip v81 skel — needs QAIRT >= 2.47)"

echo ">> 16 KB-aligned libc++_shared.so (overrides OpenCV's 4 KB one for Android 15+/16"
echo "   16 KB-page devices like the S26). Pulled from an NDK r27+ sysroot."
NDK_SEARCH="${ANDROID_NDK_ROOT:-} ${ANDROID_HOME:-$HOME/Android/Sdk}/ndk ${ANDROID_SDK_ROOT:-}/ndk"
LIBCPP=""
for base in $NDK_SEARCH; do
  for f in $(find "$base" -path "*aarch64-linux-android/libc++_shared.so" 2>/dev/null); do
    if readelf -lW "$f" 2>/dev/null | awk '/LOAD/{exit ($NF=="0x4000")?0:1}'; then LIBCPP="$f"; break 2; fi
  done
done
if [ -n "$LIBCPP" ]; then
  cp "$LIBCPP" "$ROOT/app/src/main/jniLibs/arm64-v8a/libc++_shared.so"
  echo "   copied $LIBCPP"
else
  echo "   WARNING: no 16 KB libc++_shared.so found — install NDK r27+ and copy its"
  echo "   .../sysroot/usr/lib/aarch64-linux-android/libc++_shared.so into jniLibs."
fi

echo ">> Done. Place the *.bin context binaries in app/src/main/assets/models/,"
echo "   then build with QNN_SDK_ROOT set to compile libadas_qnn.so."
