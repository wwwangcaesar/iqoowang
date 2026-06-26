cmake_minimum_required(VERSION 3.22.1)
project("stockmaster_llm")

# ══════════════════════════════════════════════════════════
# v3 — dlopen/dlsym 版本，不依赖 llm.hpp 头文件
#
# llm_jni.cpp 通过运行时动态查找符号，完全绕开头文件版本问题。
# 只需要系统头文件（jni.h, dlfcn.h, android/log.h），
# 这些都来自 Android NDK，不需要任何外部依赖。
# ══════════════════════════════════════════════════════════

set(JNI_LIBS_DIR ${CMAKE_SOURCE_DIR}/../jniLibs)

# ── 只需要系统头文件，不需要 MNN 头文件 ──
# （dlopen版本在运行时查找符号，无编译期依赖）

# ── 导入预编译 .so（告知链接器位置，运行时由系统加载）──
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

# ── 我们自己的 JNI 桥 ──
add_library(stockmaster_jni SHARED llm_jni.cpp)

# 系统库
find_library(log-lib     log)
find_library(android-lib android)
find_library(dl-lib      dl)    # dlopen/dlsym 需要

target_link_libraries(
    stockmaster_jni
    # dlopen版本：只需要 dl 库在编译期链接
    # MNN库在运行时通过 dlopen 查找，但仍然链接以确保被打包进APK
    llm
    MNN_Express
    MNN_CL
    MNN_Vulkan
    MNN
    mnncore
    ${dl-lib}       # dlopen/dlsym/dlclose
    ${log-lib}      # __android_log_print
    ${android-lib}
)
