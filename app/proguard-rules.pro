# Keep ONNX Runtime / TFLite native bindings.
-keep class ai.onnxruntime.** { *; }
-keep class org.tensorflow.lite.** { *; }
-keep class org.opencv.** { *; }
# Keep our JNI bridge to the Qualcomm QNN runtime.
-keep class com.adasedge.app.inference.qnn.** { *; }
