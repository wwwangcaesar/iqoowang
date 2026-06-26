cmake_minimum_required(VERSION 3.22.1)
project("stockmaster_llm")

# ── dlopen/dlsym 版本，无需 llm.hpp ──
# NDK r23+ 已将 dl 函数并入 libc，不需要单独链接 libdl

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs)

# 导入预编译 .so
add_library(MNN         SHARED IMPORTED)
add_library(MNN_Express SHARED IMPORTED)
add_library(MNN_CL      SHARED IMPORTED)
add_library(MNN_Vulkan  SHARED IMPORTED)
add_library(llm         SHARED IMPORTED)
add_library(mnncore     SHARED IMPORTED)

set_target_properties(MNN         PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN.so)
set_target_properties(MNN_Express PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_Express.so)
set_target_properties(MNN_CL      PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_CL.so)
set_target_properties(MNN_Vulkan  PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libMNN_Vulkan.so)
set_target_properties(llm         PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libllm.so)
set_target_properties(mnncore     PROPERTIES IMPORTED_LOCATION ${JNI_LIBS_DIR}/${ANDROID_ABI}/libmnncore.so)

add_library(stockmaster_jni SHARED llm_jni.cpp)

find_library(log-lib log)
find_library(android-lib android)
# ★ 不链接 dl — NDK r23+ 中 dlopen 已在 libc 里，
#   链接 libdl.a 反而报 "is a stub --- use libdl.so" 错误

target_link_libraries(
    stockmaster_jni
    llm          # 确保 libllm.so 被打包进 APK 并在加载时可用
    MNN_Express
    MNN_CL
    MNN_Vulkan
    MNN
    mnncore
    ${log-lib}
    ${android-lib}
    # 不加 dl — dlopen/dlsym 在 NDK r23+ 中无需显式链接
)
