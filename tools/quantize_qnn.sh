#!/usr/bin/env bash
# INT8 post-training quantization of an ONNX model with the Qualcomm QNN SDK
# (task 4.2). Requires QNN_SDK_ROOT set and a calibration set of representative
# road frames (raw NCHW float32, listed in input_list.txt).
#
#   QNN_SDK_ROOT=/opt/qcom/aistack/qnn  TARGET_DEVICE=s22plus \
#     tools/quantize_qnn.sh build/detector.onnx detector
set -euo pipefail

ONNX="${1:?usage: quantize_qnn.sh <model.onnx> <basename>}"
BASE="${2:?missing output basename (e.g. detector|lane)}"
: "${QNN_SDK_ROOT:?set QNN_SDK_ROOT to your QNN SDK}"
: "${CALIB_LIST:=calibration/input_list.txt}"
OUT="build/${BASE}"
mkdir -p "${OUT}"

source "${QNN_SDK_ROOT}/bin/envsetup.sh"

# 1) ONNX -> QNN model (.cpp/.bin) with INT8 quantization from the calibration set.
"${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-onnx-converter" \
  --input_network "${ONNX}" \
  --input_list "${CALIB_LIST}" \
  --output_path "${OUT}/${BASE}.cpp" \
  --act_bitwidth 8 --weights_bitwidth 8 \
  --bias_bitwidth 8 --float_bias_bitwidth 32 \
  --quantization_overrides "" \
  --use_per_channel_quantization

# 2) Build the model .so the context-binary generator consumes.
"${QNN_SDK_ROOT}/bin/x86_64-linux-clang/qnn-model-lib-generator" \
  -c "${OUT}/${BASE}.cpp" -b "${OUT}/${BASE}.bin" \
  -t x86_64-linux-clang -o "${OUT}/libs"

echo "quantized model lib -> ${OUT}/libs"
echo "next: tools/gen_context_binary.sh ${BASE}"
