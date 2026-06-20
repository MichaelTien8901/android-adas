#!/usr/bin/env bash
# Sustained-load on-device NPU benchmark (tasks 4.7 / 9.4). Runs a long qnn-net-run
# loop on the connected phone and captures the FPS-over-time / thermal decay curve.
# The deploy decision (S26+ vs Ultra) is made on POST-THROTTLE sustained FPS, not
# the cold peak (research/03, design D2/Risks). Run with BOTH models pushed so the
# co-resident (detector + lane) thermal behavior is measured (design D5).
#
#   QNN_SDK_ROOT=/opt/qcom/aistack/qnn TARGET_DEVICE=s22plus \
#     tools/benchmark_sustained.sh detector 20000
set -euo pipefail

BASE="${1:?usage: benchmark_sustained.sh <basename> [num_inferences]}"
N="${2:-20000}"
: "${QNN_SDK_ROOT:?set QNN_SDK_ROOT}"
# shellcheck disable=SC1091
source "$(dirname "$0")/target.env"

DEV=/data/local/tmp/adas_bench
adb shell mkdir -p "${DEV}"

# Push runtime libs, context binary, and input list (prepare input_list.txt + raws).
adb push "${QNN_SDK_ROOT}/lib/aarch64-android/libQnnHtp.so" "${DEV}/" >/dev/null
adb push "app/src/main/assets/models/${BASE}_${HTP_ARCH}.bin" "${DEV}/" >/dev/null
adb push bench/input_list.txt "${DEV}/" >/dev/null
adb push bench/raws "${DEV}/raws" >/dev/null

echo "running ${N} inferences on ${TARGET_DEVICE} (${HTP_ARCH})..."
adb shell "cd ${DEV} && LD_LIBRARY_PATH=${DEV} ./qnn-net-run \
  --backend libQnnHtp.so --retrieve_context ${BASE}_${HTP_ARCH}.bin \
  --input_list input_list.txt --num_inferences ${N} \
  --profiling_level detailed --output_dir out_sustained"

adb pull "${DEV}/out_sustained" "bench/out_${TARGET_DEVICE}" >/dev/null
echo "pulled profile -> bench/out_${TARGET_DEVICE}"
echo "parse for: cold FPS, FPS@10min, throttle-knee time, p50/p99 latency"
echo "deploy decision uses POST-THROTTLE sustained FPS (must stay >= 15)."
