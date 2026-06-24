cmake_minimum_required(VERSION 3.22.1)
project("stockmaster_llm")

# ══════════════════════════════════════════════════════════
# 你的 jniLibs/arm64-v8a/ 中实际的 .so 文件（来自zip包）：
#   libc++_shared.so
#   libllm.so          ← LLM核心（不是 libMNNLLM.so！）
#   libMNN.so
#   libMNN_CL.so       ← OpenCL，Adreno740 GPU加速
#   libMNN_Express.so
#   libMNN_Vulkan.so   ← Vulkan，骁龙8 Gen2支持
#   libMNNAudio.so
#   libmnncore.so
#   libMNNOpenCV.so
# ══════════════════════════════════════════════════════════

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs)
set(MNN_INCLUDE_DIR ${CMAKE_SOURCE_DIR}/mnn_include)

include_directories(${MNN_INCLUDE_DIR})

# ── 导入预编译 .so ──
add_library(MNN         SHARED IMPORTED)
add_library(MNN_Express SHARED IMPORTED)
add_library(MNN_CL      SHARED IMPORTED)
add_library(MNN_Vulkan  SHARED IMPORTED)
add_library(llm         SHARED IMPORTED)  # libllm.so = LLM核心
add_library(mnncore     SHARED IMPORTED)

set_target_properties(MNN         PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN.so)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_Express.so)
set_target_properties(MNN_CL      PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_CL.so)
set_target_properties(MNN_Vulkan  PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_Vulkan.so)
set_target_properties(llm         PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libllm.so)
set_target_properties(mnncore     PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libmnncore.so)

# ── JNI桥接层（我们自己写的） ──
add_library(stockmaster_jni SHARED llm_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)

target_link_libraries(
        stockmaster_jni
        llm          # LLM推理核心
        MNN_Express
        MNN_CL
        MNN_Vulkan
        MNN
        mnncore
        ${log-lib}
        ${android-lib}
)
