# Native QNN bridge for the ADAS detector/lane runtime.
# NOT wired into the Gradle build by default so the APK builds without the QNN
# SDK. To enable: set QNN_SDK_ROOT and add to app/build.gradle.kts:
#
#   android { defaultConfig { externalNativeBuild { cmake { arguments += "-DQNN_SDK_ROOT=${'$'}{System.getenv("QNN_SDK_ROOT")}" } } }
#             externalNativeBuild { cmake { path = file("src/main/cpp/CMakeLists.txt") } } }
#
cmake_minimum_required(VERSION 3.22)
project(adas_qnn)

if(NOT DEFINED QNN_SDK_ROOT)
  message(FATAL_ERROR "QNN_SDK_ROOT not set — point it at your Qualcomm QNN SDK")
endif()

add_library(adas_qnn SHARED adas_qnn.cpp)

target_include_directories(adas_qnn PRIVATE
  ${QNN_SDK_ROOT}/include/QNN
  ${QNN_SDK_ROOT}/include)

# Link the QNN System lib for context-binary loading; libQnnHtp.so is dlopen'd
# at runtime from jniLibs/arm64-v8a.
find_library(log-lib log)
target_link_libraries(adas_qnn ${log-lib})
