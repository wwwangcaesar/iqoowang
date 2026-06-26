/**
 * llm_jni.cpp — 动态符号查找版本
 *
 * 不依赖 llm.hpp 头文件，通过 dlopen/dlsym 在运行时查找
 * MNN libllm.so 的实际导出符号。
 *
 * 这样可以适配 MNN 不同版本的符号名称变化。
 */

#include <jni.h>
#include <string>
#include <sstream>
#include <iostream>         // 👈 添加这行：为了使用 std::cout
#include <dlfcn.h>          // dlopen / dlsym
#include <android/log.h>


#define TAG "SM_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

// ── LLM 对象指针（void*，避免依赖头文件类型）──
static void* gLlmHandle  = nullptr;   // libllm.so 句柄
static void* gLlmObj     = nullptr;   // Llm 对象指针
static JavaVM* gJvm      = nullptr;

// ── 函数指针类型定义（MNN 3.x 接口）──
// Llm* Llm::createLLM(const std::string& configPath)
typedef void* (*FnCreateLLM)(const std::string&);
// void Llm::load()
typedef void  (*FnLoad)(void*);
// std::string Llm::response(const std::string&, std::ostream*, const char*)
typedef std::string (*FnResponse)(void*, const std::string&, std::ostream*, const char*);
// void Llm::reset()
typedef void  (*FnReset)(void*);
// void Llm::~Llm()
typedef void  (*FnDestroy)(void*);

static FnCreateLLM fn_createLLM = nullptr;
static FnLoad      fn_load      = nullptr;
static FnResponse  fn_response  = nullptr;
static FnReset     fn_reset     = nullptr;

// ── 尝试多种可能的符号名称 ──
static void* findSymbol(void* handle, const char** names, int count) {
    for (int i = 0; i < count; i++) {
        void* sym = dlsym(handle, names[i]);
        if (sym) {
            LOGI("找到符号: %s", names[i]);
            return sym;
        }
    }
    return nullptr;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    LOGI("JNI_OnLoad OK");
    return JNI_VERSION_1_6;
}

// ════════════════════════════════════════
// nativeInit
// ════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeInit(
        JNIEnv* env, jobject, jstring jModelDir) {
    if (gLlmObj) { LOGI("LLM已加载"); return JNI_TRUE; }

    const char* dir = env->GetStringUTFChars(jModelDir, nullptr);
    std::string configPath = std::string(dir) + "/config.json";
    env->ReleaseStringUTFChars(jModelDir, dir);
    LOGI("加载模型: %s", configPath.c_str());

    // ── Step1: 打开 libllm.so ──
    // 优先用已加载的（RTLD_DEFAULT 搜索所有已加载库）
    gLlmHandle = RTLD_DEFAULT;

    // ── Step2: 查找 createLLM 符号 ──
    // MNN 3.x 的 C++ mangled name（不同编译器可能不同）
    const char* createSymbols[] = {
        // MNN 3.x arm64 clang (NDK r25+)
        "_ZN3MNN11Transformer3Llm9createLLMERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        // 带 std::string 变体
        "_ZN3MNN11Transformer3Llm9createLLMERKSs",
        // 简化版（部分版本）
        "_ZN3MNN3Llm9createLLMERKSs",
        // C 风格（如果有 extern "C"）
        "MNN_LLM_createLLM",
        "createLLM",
    };
    fn_createLLM = (FnCreateLLM)findSymbol(gLlmHandle, createSymbols,
                                            sizeof(createSymbols)/sizeof(createSymbols[0]));

    if (!fn_createLLM) {
        // 尝试显式打开 libllm.so
        gLlmHandle = dlopen("libllm.so", RTLD_NOW | RTLD_GLOBAL);
        if (!gLlmHandle) {
            LOGE("dlopen libllm.so 失败: %s", dlerror());
            // 再试一次 MNN.so
            gLlmHandle = dlopen("libMNN.so", RTLD_NOW | RTLD_NOLOAD);
        }
        if (gLlmHandle) {
            fn_createLLM = (FnCreateLLM)findSymbol(gLlmHandle, createSymbols,
                                                    sizeof(createSymbols)/sizeof(createSymbols[0]));
        }
    }

    if (!fn_createLLM) {
        // 列出 libllm.so 的所有导出符号帮助调试
        LOGE("❌ 找不到 createLLM，列举可用符号:");
        void* h = dlopen("libllm.so", RTLD_NOW | RTLD_NOLOAD);
        if (!h) h = gLlmHandle;
        // 无法直接枚举 dlsym，但可以输出 dlerror
        LOGE("dlerror: %s", dlerror() ? dlerror() : "none");
        return JNI_FALSE;
    }

    // ── Step3: 查找 load 方法 ──
    const char* loadSymbols[] = {
        "_ZN3MNN11Transformer3Llm4loadEv",
        "_ZN3MNN3Llm4loadEv",
        "MNN_LLM_load",
    };
    fn_load = (FnLoad)findSymbol(gLlmHandle, loadSymbols,
                                  sizeof(loadSymbols)/sizeof(loadSymbols[0]));

    // ── Step4: 查找 response 方法 ──
    const char* responseSymbols[] = {
        "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS4_EEPKc",
        "_ZN3MNN3Llm8responseERKSsPSoPKc",
        "_ZN3MNN11Transformer3Llm8responseERKSsPSoPKc",
        "MNN_LLM_response",
    };
    fn_response = (FnResponse)findSymbol(gLlmHandle, responseSymbols,
                                          sizeof(responseSymbols)/sizeof(responseSymbols[0]));

    // ── Step5: 查找 reset ──
    const char* resetSymbols[] = {
        "_ZN3MNN11Transformer3Llm5resetEv",
        "_ZN3MNN3Llm5resetEv",
        "MNN_LLM_reset",
    };
    fn_reset = (FnReset)findSymbol(gLlmHandle, resetSymbols,
                                    sizeof(resetSymbols)/sizeof(resetSymbols[0]));

    LOGI("符号查找结果: createLLM=%p load=%p response=%p reset=%p",
         fn_createLLM, fn_load, fn_response, fn_reset);

    if (!fn_createLLM) {
        LOGE("❌ createLLM 符号缺失，无法初始化");
        return JNI_FALSE;
    }

    // ── Step6: 创建 LLM 对象 ──
    try {
        gLlmObj = fn_createLLM(configPath);
        if (!gLlmObj) { LOGE("❌ createLLM 返回 null"); return JNI_FALSE; }
        LOGI("createLLM 成功: %p", gLlmObj);

        if (fn_load) {
            fn_load(gLlmObj);
            LOGI("✅ load() 完成");
        } else {
            LOGW("⚠️ load 符号未找到，跳过（可能已在createLLM内自动load）");
        }
        return JNI_TRUE;
    } catch (...) {
        LOGE("❌ createLLM/load 抛出异常");
        gLlmObj = nullptr;
        return JNI_FALSE;
    }
}

// ════════════════════════════════════════
// nativeChatStream — 流式推理
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeChatStream(
        JNIEnv* env, jobject, jstring jPrompt, jobject callback) {
    if (!gLlmObj) { LOGE("LLM未初始化"); return; }

    jclass  cbClass    = env->GetObjectClass(callback);
    jmethodID onToken  = env->GetMethodID(cbClass, "onToken",  "(Ljava/lang/String;)V");
    jmethodID onFinish = env->GetMethodID(cbClass, "onFinish", "(Ljava/lang/String;)V");

    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    jobject globalCb = env->NewGlobalRef(callback);
    std::string fullText;

    if (fn_response) {
        try {
            // 劫持 cout 获取输出
            std::ostringstream oss;
            auto* oldBuf = std::cout.rdbuf(oss.rdbuf());
            fn_response(gLlmObj, prompt, &std::cout, "");
            std::cout.rdbuf(oldBuf);
            fullText = oss.str();

            // 模拟流式（按标点分批回调）
            std::string chunk;
            for (size_t i = 0; i < fullText.size(); i++) {
                chunk += fullText[i];
                bool flush = (chunk.size() >= 6) ||
                             fullText[i] == '\n';
                if (flush && !chunk.empty()) {
                    jstring jt = env->NewStringUTF(chunk.c_str());
                    env->CallVoidMethod(globalCb, onToken, jt);
                    env->DeleteLocalRef(jt);
                    chunk.clear();
                }
            }
            if (!chunk.empty()) {
                jstring jt = env->NewStringUTF(chunk.c_str());
                env->CallVoidMethod(globalCb, onToken, jt);
                env->DeleteLocalRef(jt);
            }
        } catch (...) {
            fullText = "[推理异常]";
            LOGE("response() 抛出异常");
        }
    } else {
        fullText = "[response符号未找到，模型已加载但无法推理]";
        LOGE("fn_response 为 null");
    }

    jstring jFull = env->NewStringUTF(fullText.c_str());
    env->CallVoidMethod(globalCb, onFinish, jFull);
    env->DeleteLocalRef(jFull);
    env->DeleteGlobalRef(globalCb);
}

// ════════════════════════════════════════
// nativeChat — 同步推理
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeChat(
        JNIEnv* env, jobject, jstring jPrompt, jobject callback) {
    if (!gLlmObj) return;
    jclass  cbClass    = env->GetObjectClass(callback);
    jmethodID onFinish = env->GetMethodID(cbClass, "onFinish", "(Ljava/lang/String;)V");

    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    std::string result = "[未知]";
    if (fn_response) {
        try {
            std::ostringstream oss;
            fn_response(gLlmObj, prompt, &oss, "\n");
            result = oss.str();
        } catch (...) { result = "[推理异常]"; }
    }

    jobject globalCb = env->NewGlobalRef(callback);
    jstring jr = env->NewStringUTF(result.c_str());
    env->CallVoidMethod(globalCb, onFinish, jr);
    env->DeleteLocalRef(jr);
    env->DeleteGlobalRef(globalCb);
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeReset(JNIEnv*, jobject) {
    if (gLlmObj && fn_reset) {
        try { fn_reset(gLlmObj); LOGI("reset OK"); }
        catch (...) { LOGE("reset 异常"); }
    }
}

JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeIsReady(JNIEnv*, jobject) {
    return (gLlmObj != nullptr) ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeDestroy(JNIEnv*, jobject) {
    // 通过 delete 操作符销毁（需要析构符号，暂时置 null 让系统回收）
    gLlmObj = nullptr;
    if (gLlmHandle && gLlmHandle != RTLD_DEFAULT) {
        dlclose(gLlmHandle);
        gLlmHandle = nullptr;
    }
    LOGI("LLM destroyed");
}

} // extern "C"

// ════════════════════════════════════════
// nativeGetDebugInfo — 返回符号查找结果
// ════════════════════════════════════════
extern "C"
JNIEXPORT jstring JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeGetDebugInfo(JNIEnv* env, jobject) {
    std::ostringstream info;
    info << "createLLM=" << (fn_createLLM?"✅":"❌")
         << " load="     << (fn_load?"✅":"❌")
         << " response=" << (fn_response?"✅":"❌")
         << " reset="    << (fn_reset?"✅":"❌")
         << " llmObj="   << (gLlmObj?"✅":"❌");

    // 尝试查找符号以获取更多信息
    if (!fn_createLLM) {
        void* h = dlopen("libllm.so", RTLD_NOW | RTLD_NOLOAD);
        info << " | libllm_handle=" << (h ? "found" : "null");
        if (!h) info << " dlerr=" << (dlerror() ? dlerror() : "none");
    }

    return env->NewStringUTF(info.str().c_str());
}
