// JNI bridge: runs a pre-built QNN HTP context binary on the Hexagon NPU.
// Reuses the QNN SampleApp helpers (DynamicLoadUtil for dlopen+getProviders,
// IOTensor for INT8 quant/dequant, copyMetadataToGraphsInfo for graph I/O meta).
// Float NCHW in -> quantized graph -> float out, matching the Kotlin decoders.

#include <jni.h>
#include <android/log.h>
#include <fstream>
#include <vector>
#include <cstring>
#include <string>

#include "QnnBackend.h"
#include "QnnContext.h"
#include "QnnDevice.h"
#include "QnnGraph.h"
#include "QnnLog.h"
#include "System/QnnSystemContext.h"
#include <cstdarg>

#include "SampleApp.hpp"
#include "QnnTypeMacros.hpp"
#include "Utils/DynamicLoadUtil.hpp"
#include "Utils/IOTensor.hpp"
#include "Utils/QnnSampleAppUtils.hpp"
#include "WrapperUtils/QnnWrapperUtils.hpp"

#define LOG_TAG "adas_qnn"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

using namespace qnn::tools;

namespace {

void qnnLogCallback(const char* fmt, QnnLog_Level_t level, uint64_t, va_list argp) {
  int prio = (level == QNN_LOG_LEVEL_ERROR) ? ANDROID_LOG_ERROR
           : (level == QNN_LOG_LEVEL_WARN)  ? ANDROID_LOG_WARN : ANDROID_LOG_INFO;
  __android_log_vprint(prio, "adas_qnn_be", fmt, argp);
}

struct AdasModel {
  sample_app::QnnFunctionPointers fp{};
  void* backendLib = nullptr;
  void* systemLib = nullptr;
  Qnn_LogHandle_t logHandle = nullptr;
  Qnn_BackendHandle_t backend = nullptr;
  Qnn_DeviceHandle_t device = nullptr;
  Qnn_ContextHandle_t context = nullptr;
  qnn_wrapper_api::GraphInfo_t** graphsInfo = nullptr;
  uint32_t graphsCount = 0;
  iotensor::IOTensor ioTensor;
};

bool readFile(const std::string& path, std::vector<uint8_t>& buf) {
  std::ifstream f(path, std::ios::binary | std::ios::ate);
  if (!f.is_open()) return false;
  buf.resize(static_cast<size_t>(f.tellg()));
  f.seekg(0);
  f.read(reinterpret_cast<char*>(buf.data()), buf.size());
  return f.good() || f.eof();
}

}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_loadContext(
    JNIEnv* env, jobject, jstring jPath, jstring jNativeLibDir, jint /*socId*/, jstring /*jArch*/) {
  const char* cpath = env->GetStringUTFChars(jPath, nullptr);
  std::string binPath(cpath);
  env->ReleaseStringUTFChars(jPath, cpath);

  // The Hexagon skel (libQnnHtpV69Skel.so) is loaded by the cDSP, not the CPU
  // linker, so it must be on ADSP_LIBRARY_PATH. The app's native lib dir (where
  // jniLibs are extracted) plus the standard on-device DSP dirs.
  const char* nld = env->GetStringUTFChars(jNativeLibDir, nullptr);
  std::string adsp = std::string(nld) +
      ";/vendor/dsp/cdsp;/vendor/lib/rfsa/adsp;/system/lib/rfsa/adsp;/dsp";
  setenv("ADSP_LIBRARY_PATH", adsp.c_str(), 1);
  env->ReleaseStringUTFChars(jNativeLibDir, nld);

  auto* m = new AdasModel();

  // 1. Load backend (libQnnHtp.so) + system (libQnnSystem.so) interfaces.
  if (dynamicloadutil::StatusCode::SUCCESS !=
      dynamicloadutil::getQnnFunctionPointers(
          "libQnnHtp.so", "", &m->fp, &m->backendLib, /*loadModelLib=*/false, nullptr)) {
    LOGE("getQnnFunctionPointers(libQnnHtp.so) failed");
    delete m; return 0;
  }
  if (dynamicloadutil::StatusCode::SUCCESS !=
      dynamicloadutil::getQnnSystemFunctionPointers("libQnnSystem.so", &m->fp)) {
    LOGE("getQnnSystemFunctionPointers(libQnnSystem.so) failed");
    delete m; return 0;
  }

  auto& qi = m->fp.qnnInterface;
  auto& qsi = m->fp.qnnSystemInterface;

  // 2. Log handle (HTP requires a valid one), then backend + device.
  Qnn_ErrorHandle_t e;
  if (qi.logCreate) qi.logCreate(qnnLogCallback, QNN_LOG_LEVEL_WARN, &m->logHandle);
  if (!qi.backendCreate || QNN_BACKEND_NO_ERROR != (e = qi.backendCreate(m->logHandle, nullptr, &m->backend))) {
    LOGE("backendCreate failed (0x%llx)", (unsigned long long)e); delete m; return 0;
  }
  if (qi.deviceCreate) {
    e = qi.deviceCreate(m->logHandle, nullptr, &m->device);
    if (QNN_SUCCESS != e) {
      LOGE("deviceCreate failed (0x%llx) — continuing with null device", (unsigned long long)e);
      m->device = nullptr;
    } else {
      LOGI("deviceCreate ok");
    }
  } else {
    LOGE("deviceCreate fn is null");
  }

  // 3. Read graph I/O metadata from the binary via the system context.
  std::vector<uint8_t> bin;
  if (!readFile(binPath, bin) || bin.empty()) {
    LOGE("failed to read context binary: %s", binPath.c_str()); delete m; return 0;
  }
  QnnSystemContext_Handle_t sysCtx = nullptr;
  if (!qsi.systemContextCreate || QNN_SUCCESS != qsi.systemContextCreate(&sysCtx)) {
    LOGE("systemContextCreate failed"); delete m; return 0;
  }
  const QnnSystemContext_BinaryInfo_t* binaryInfo = nullptr;
  Qnn_ContextBinarySize_t binaryInfoSize = 0;
  if (QNN_SUCCESS != (e = qsi.systemContextGetBinaryInfo(
          sysCtx, static_cast<void*>(bin.data()), bin.size(), &binaryInfo, &binaryInfoSize))) {
    LOGE("systemContextGetBinaryInfo failed (0x%llx)", (unsigned long long)e);
    qsi.systemContextFree(sysCtx); delete m; return 0;
  }
  if (!sample_app::copyMetadataToGraphsInfo(binaryInfo, m->graphsInfo, m->graphsCount)) {
    LOGE("copyMetadataToGraphsInfo failed"); qsi.systemContextFree(sysCtx); delete m; return 0;
  }
  qsi.systemContextFree(sysCtx);

  // 4. Create the context from the binary, then retrieve each graph handle.
  if (!qi.contextCreateFromBinary) { LOGE("contextCreateFromBinary fn is null"); delete m; return 0; }
  e = qi.contextCreateFromBinary(m->backend, m->device, nullptr,
          static_cast<void*>(bin.data()), bin.size(), &m->context, nullptr);
  if (QNN_SUCCESS != e) {
    LOGE("contextCreateFromBinary failed (0x%llx) dev=%p binSize=%zu",
         (unsigned long long)e, (void*)m->device, bin.size());
    delete m; return 0;
  }
  for (uint32_t i = 0; i < m->graphsCount; i++) {
    auto& gi = (*m->graphsInfo)[i];
    if (QNN_SUCCESS != qi.graphRetrieve(m->context, gi.graphName, &gi.graph)) {
      LOGE("graphRetrieve failed for %s", gi.graphName); delete m; return 0;
    }
  }
  LOGI("QNN HTP context loaded: %u graph(s) from %s", m->graphsCount, binPath.c_str());
  return reinterpret_cast<jlong>(m);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_run(
    JNIEnv* env, jobject, jlong handle, jfloatArray jInput) {
  auto* m = reinterpret_cast<AdasModel*>(handle);
  jclass floatArrCls = env->FindClass("[F");
  if (!m || m->graphsCount == 0) return env->NewObjectArray(0, floatArrCls, nullptr);
  auto& gi = (*m->graphsInfo)[0];

  Qnn_Tensor_t* inputs = nullptr;
  Qnn_Tensor_t* outputs = nullptr;
  if (iotensor::StatusCode::SUCCESS !=
      m->ioTensor.setupInputAndOutputTensors(&inputs, &outputs, gi)) {
    LOGE("setupInputAndOutputTensors failed");
    return env->NewObjectArray(0, floatArrCls, nullptr);
  }

  // Populate the single input tensor from the float NCHW buffer (quantizes).
  jfloat* inData = env->GetFloatArrayElements(jInput, nullptr);
  iotensor::StatusCode pc = m->ioTensor.floatToNative(reinterpret_cast<float*>(inData), &inputs[0]);
  env->ReleaseFloatArrayElements(jInput, inData, JNI_ABORT);
  if (iotensor::StatusCode::SUCCESS != pc) {
    LOGE("floatToNative failed");
    m->ioTensor.tearDownInputAndOutputTensors(inputs, outputs, gi.numInputTensors, gi.numOutputTensors);
    return env->NewObjectArray(0, floatArrCls, nullptr);
  }

  if (QNN_GRAPH_NO_ERROR != m->fp.qnnInterface.graphExecute(
          gi.graph, inputs, gi.numInputTensors, outputs, gi.numOutputTensors,
          nullptr, nullptr)) {
    LOGE("graphExecute failed");
    m->ioTensor.tearDownInputAndOutputTensors(inputs, outputs, gi.numInputTensors, gi.numOutputTensors);
    return env->NewObjectArray(0, floatArrCls, nullptr);
  }

  jobjectArray result = env->NewObjectArray(gi.numOutputTensors, floatArrCls, nullptr);
  for (uint32_t o = 0; o < gi.numOutputTensors; o++) {
    float* fout = nullptr;
    if (iotensor::StatusCode::SUCCESS != m->ioTensor.nativeToFloat(&fout, &outputs[o]) || !fout) {
      continue;
    }
    // Element count = product of dims.
    uint32_t rank = QNN_TENSOR_GET_RANK(&outputs[o]);
    uint32_t* dims = QNN_TENSOR_GET_DIMENSIONS(&outputs[o]);
    size_t n = 1;
    for (uint32_t d = 0; d < rank; d++) n *= dims[d];
    jfloatArray arr = env->NewFloatArray(static_cast<jsize>(n));
    env->SetFloatArrayRegion(arr, 0, static_cast<jsize>(n), fout);
    env->SetObjectArrayElement(result, o, arr);
    env->DeleteLocalRef(arr);
    free(fout);
  }

  m->ioTensor.tearDownInputAndOutputTensors(inputs, outputs, gi.numInputTensors, gi.numOutputTensors);
  return result;
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_outputShapes(
    JNIEnv* env, jobject, jlong handle) {
  auto* m = reinterpret_cast<AdasModel*>(handle);
  jclass intArrCls = env->FindClass("[I");
  if (!m || m->graphsCount == 0) return env->NewObjectArray(0, intArrCls, nullptr);
  auto& gi = (*m->graphsInfo)[0];
  jobjectArray result = env->NewObjectArray(gi.numOutputTensors, intArrCls, nullptr);
  for (uint32_t o = 0; o < gi.numOutputTensors; o++) {
    uint32_t rank = QNN_TENSOR_GET_RANK(&gi.outputTensors[o]);
    uint32_t* dims = QNN_TENSOR_GET_DIMENSIONS(&gi.outputTensors[o]);
    jintArray arr = env->NewIntArray(static_cast<jsize>(rank));
    std::vector<jint> tmp(rank);
    for (uint32_t d = 0; d < rank; d++) tmp[d] = static_cast<jint>(dims[d]);
    env->SetIntArrayRegion(arr, 0, static_cast<jsize>(rank), tmp.data());
    env->SetObjectArrayElement(result, o, arr);
    env->DeleteLocalRef(arr);
  }
  return result;
}

extern "C" JNIEXPORT void JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_release(JNIEnv*, jobject, jlong handle) {
  auto* m = reinterpret_cast<AdasModel*>(handle);
  if (!m) return;
  if (m->context && m->fp.qnnInterface.contextFree) m->fp.qnnInterface.contextFree(m->context, nullptr);
  if (m->device && m->fp.qnnInterface.deviceFree) m->fp.qnnInterface.deviceFree(m->device);
  if (m->backend && m->fp.qnnInterface.backendFree) m->fp.qnnInterface.backendFree(m->backend);
  if (m->logHandle && m->fp.qnnInterface.logFree) m->fp.qnnInterface.logFree(m->logHandle);
  if (m->graphsInfo) qnn_wrapper_api::freeGraphsInfo(&m->graphsInfo, m->graphsCount);
  delete m;
}
