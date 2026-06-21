# Native QNN bridge for the ADAS detector/lane runtime (libadas_qnn.so).
# Builds adas_qnn.cpp + the vendored QNN SampleApp helpers against the QNN
# headers. Wired into Gradle via externalNativeBuild; QNN_SDK_ROOT is passed
# from build.gradle.kts. The vendored helpers under cpp/qnn/ are fetched from the
# SDK by tools/vendor_qnn_sources.sh (git-ignored — Qualcomm-licensed).
cmake_minimum_required(VERSION 3.22)
project(adas_qnn LANGUAGES CXX)

if(NOT DEFINED QNN_SDK_ROOT)
  message(FATAL_ERROR "QNN_SDK_ROOT not set — point it at your Qualcomm QNN SDK")
endif()

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(QNN_DIR ${CMAKE_CURRENT_SOURCE_DIR}/qnn)

# Vendored SampleApp helper sources (skip QnnSampleApp.cpp — we provide our own
# controller; keep only the reusable utils + PAL/Log).
file(GLOB QNN_HELPER_SRC
  ${QNN_DIR}/Utils/*.cpp
  ${QNN_DIR}/WrapperUtils/*.cpp
  ${QNN_DIR}/Log/*.cpp
  ${QNN_DIR}/PAL/src/common/*.cpp
  ${QNN_DIR}/PAL/src/linux/*.cpp)

add_library(adas_qnn SHARED adas_qnn.cpp ${QNN_HELPER_SRC})

target_include_directories(adas_qnn PRIVATE
  ${QNN_DIR}
  ${QNN_DIR}/Utils
  ${QNN_DIR}/WrapperUtils
  ${QNN_DIR}/Log
  ${QNN_DIR}/PAL/include
  ${QNN_SDK_ROOT}/include/QNN)

target_compile_options(adas_qnn PRIVATE -fexceptions -frtti -Wno-unused-parameter)

# 16 KB page alignment — required by Android 15+/16 devices (e.g. S26 / 8 Elite
# Gen 5) that use 16 KB memory pages, else "LOAD segment not aligned" at load.
target_link_options(adas_qnn PRIVATE
  "-Wl,-z,max-page-size=16384"
  "-Wl,-z,common-page-size=16384")

find_library(log-lib log)
# libQnnHtp.so / libQnnSystem.so are dlopen'd at runtime from jniLibs/arm64-v8a.
target_link_libraries(adas_qnn ${log-lib} ${CMAKE_DL_LIBS})
