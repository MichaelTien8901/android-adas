// JNI bridge between QnnNative.kt and the Qualcomm AI Engine Direct (QNN) HTP
// runtime. This is a REFERENCE SKELETON: the exact QNN System/Context/Graph
// calls are SDK-version specific. Fill the TODOs against your QNN SDK's
// SampleApp (qnn-sample-app) which demonstrates retrieve-context + execute.
//
// Responsibilities:
//   loadContext  -> dlopen libQnnHtp.so, create QnnSystemContext, load the
//                   pre-built context binary, look up the graph + I/O tensors.
//   run          -> copy input into the input tensor, qnn graph execute,
//                   copy outputs out.
//   outputShapes -> report each output tensor's dims.
//   release      -> free graph/context/system handles.

#include <jni.h>
#include <android/log.h>
#include <vector>
#include <cstring>

#define LOG_TAG "adas_qnn"
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

namespace {
struct QnnModel {
  // TODO: hold QnnBackend_Handle, QnnContext_Handle, Qnn_GraphHandle_t,
  //       input/output Qnn_Tensor_t arrays, and the dlopen'd interface table.
  std::vector<std::vector<int>> outputShapes;
};
}  // namespace

extern "C" JNIEXPORT jlong JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_loadContext(
    JNIEnv* env, jobject, jstring jPath, jint socId, jstring jArch) {
  const char* path = env->GetStringUTFChars(jPath, nullptr);
  // TODO: QnnSystemContext_create -> QnnContext_createFromBinary(path) ->
  //       QnnContext_getGraph -> cache I/O tensor metadata into QnnModel.
  LOGE("loadContext stub: path=%s socId=%d (implement against QNN SDK)", path, socId);
  env->ReleaseStringUTFChars(jPath, path);
  return 0;  // 0 => Kotlin falls back (ORT/LiteRT). Return (jlong)model when real.
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_run(
    JNIEnv* env, jobject, jlong handle, jfloatArray input) {
  (void)handle; (void)input;
  // TODO: write input into the input tensor, QnnGraph_execute(...), read outputs.
  return env->NewObjectArray(0, env->FindClass("[F"), nullptr);
}

extern "C" JNIEXPORT jobjectArray JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_outputShapes(
    JNIEnv* env, jobject, jlong handle) {
  (void)handle;
  return env->NewObjectArray(0, env->FindClass("[I"), nullptr);
}

extern "C" JNIEXPORT void JNICALL
Java_com_adasedge_app_inference_qnn_QnnNative_release(JNIEnv*, jobject, jlong handle) {
  auto* model = reinterpret_cast<QnnModel*>(handle);
  delete model;  // TODO: also free QNN graph/context/system handles first.
}
