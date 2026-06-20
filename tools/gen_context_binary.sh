#!/usr/bin/env bash
# Generate the per-device HTP context binary from a quantized QNN model
# (tasks 4.4 / 4.5). One ONNX + one quantized graph -> re-contexted per device;
# only this step is device-specific (doc/project_draft).
#
#   QNN_SDK_ROOT=/opt/qcom/aistack/qnn TARGET_DEVICE=s22plus \
#     tools/gen_context_binary.sh detector
# Produces app/src/main/assets/models/detector_<HTP_ARCH>.bin
set -euo pipefail

BASE="${1:?usage: gen_context_binary.sh <basename>}"
: "${QNN_SDK_ROOT:?set QNN_SDK_ROOT}"
# shellcheck disable=SC1091
source "$(dirname "$0")/target.env"

if [[ "${SOC_ID}" == "TBD" || "${PLATFORM}" == "TBD" ]]; then
  echo "ERROR: SOC_ID/PLATFORM are TBD for ${TARGET_DEVICE}." >&2
  echo "Read them on the device first:  adb shell getprop ro.board.platform ; adb shell getprop ro.soc.model" >&2
  exit 2
fi

OUT="build/${BASE}"
ASSETS="app/src/main/assets/models"
mkdir -p "${ASSETS}"

# HTP backend config pinning SoC + DSP arch (htp_config.json template).
cat > "${OUT}/htp_config.json" <<JSON
{
  "graphs": [{ "graph_names": ["${BASE}"] }],
  "devices": [{
    "soc_id": ${SOC_ID},
    "dsp_arch": "${HTP_ARCH}",
    "pd_session": "unsigned"
  }]
}
JSON

"${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-context-binary-generator" \
  --model "${OUT}/libs/x86_64-linux-clang/lib${BASE}.so" \
  --backend "${QNN_SDK_ROOT}/lib/x86_64-linux-clang/libQnnHtp.so" \
  --config_file "${OUT}/htp_config.json" \
  --binary_file "${BASE}_${HTP_ARCH}" \
  --output_dir "${ASSETS}"

echo "context binary -> ${ASSETS}/${BASE}_${HTP_ARCH}.bin  (bundled as an app asset)"
