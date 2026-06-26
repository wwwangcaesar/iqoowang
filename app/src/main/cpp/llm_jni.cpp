/**
 * llm_jni.cpp v4 — 修复 dlopen 策略
 *
 * 关键修复：
 * 1. 移除 libdl 依赖（NDK r23+ dlopen 已在 libc）
 * 2. dlopen 先用 RTLD_NOW 主动加载，再用 RTLD_DEFAULT 搜索
 * 3. 扩展 mangled 符号候选列表
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

// ── 全局状态 ──
static void*   gLlmHandle   = nullptr;
static void*   gLlmObj      = nullptr;
static JavaVM* gJvm         = nullptr;

// ── 调试信息（供 nativeGetDebugInfo 返回）──
static std::string gDebugInfo;

// ── 函数指针 ──
// MNN 3.x: Llm* createLLM(const std::string& configPath)
// 因为是成员函数，实际上是 Llm* Llm::createLLM(const std::string&)
// C++ 静态成员函数，this 指针隐式
typedef void* (*FnCreate)(const std::string&);
typedef void  (*FnLoad)(void*);       // void Llm::load()  — 非static，第一参数是this
typedef void  (*FnResponse)(void*, const std::string&, std::ostream*, const char*);
typedef void  (*FnReset)(void*);

static FnCreate   fn_create   = nullptr;
static FnLoad     fn_load     = nullptr;
static FnResponse fn_response = nullptr;
static FnReset    fn_reset    = nullptr;

// ── 符号查找 ──
static void* trySymbol(void* handle, const char* name) {
    void* sym = dlsym(handle, name);
    LOGI("  dlsym(%s) = %p", name, sym);
    return sym;
}

static void* findAny(void* handle, const char** names, int n) {
    for (int i = 0; i < n; i++) {
        void* s = trySymbol(handle, names[i]);
        if (s) return s;
    }
    return nullptr;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    LOGI("JNI_OnLoad v4");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeInit(
        JNIEnv* env, jobject, jstring jDir) {
    if (gLlmObj) return JNI_TRUE;

    const char* dir = env->GetStringUTFChars(jDir, nullptr);
    std::string configPath = std::string(dir) + "/config.json";
    env->ReleaseStringUTFChars(jDir, dir);
    LOGI("nativeInit config=%s", configPath.c_str());
    gDebugInfo.clear();

    // ── Step 1: 打开 libllm.so ──
    // 策略：先主动加载（RTLD_NOW | RTLD_GLOBAL），使符号全局可见
    void* hLlm = dlopen("libllm.so", RTLD_NOW | RTLD_GLOBAL);
    if (!hLlm) {
        LOGW("dlopen libllm.so 失败: %s，改用 RTLD_DEFAULT", dlerror());
        hLlm = RTLD_DEFAULT;
        gDebugInfo += "libllm=RTLD_DEFAULT|";
    } else {
        LOGI("dlopen libllm.so 成功: %p", hLlm);
        gDebugInfo += "libllm=opened|";
        gLlmHandle = hLlm;
    }

    // 同时尝试加载 MNN（确保依赖项已在内存中）
    void* hMnn = dlopen("libMNN.so", RTLD_NOW | RTLD_GLOBAL);
    if (hMnn) { LOGI("libMNN.so loaded: %p", hMnn); }
    else       { LOGW("libMNN.so load fail: %s", dlerror()); }

    // ── Step 2: 查找 createLLM ──
    // MNN 3.6.0 arm64 的 mangled name（通过 nm -D 分析）
    const char* createNames[] = {
        // MNN 3.x 标准（libc++ NDK clang arm64）
        "_ZN3MNN11Transformer3Llm9createLLMERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        // MNN 3.x 备选（GNU libstdc++）
        "_ZN3MNN11Transformer3Llm9createLLMERKSs",
        // MNN 早期 3.x
        "_ZN3MNN3Llm9createLLMERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
        "_ZN3MNN3Llm9createLLMERKSs",
        // C 风格（如果有 extern "C" wrapper）
        "MNN_LLM_createLLM",
        "Llm_createLLM",
        "createLLM",
        // MNN 3.6 可能的新命名
        "_ZN3MNN11Transformer3Llm6createERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        "_ZN3MNN11Transformer3Llm6createERKSs",
    };
    fn_create = (FnCreate)findAny(hLlm, createNames, sizeof(createNames)/sizeof(createNames[0]));
    if (!fn_create && hLlm != RTLD_DEFAULT) {
        // 再在全局空间搜索
        fn_create = (FnCreate)findAny(RTLD_DEFAULT, createNames, sizeof(createNames)/sizeof(createNames[0]));
    }
    gDebugInfo += std::string("create=") + (fn_create?"✅":"❌") + "|";
    LOGI("fn_create = %p", fn_create);

    // ── Step 3: 查找 load ──
    const char* loadNames[] = {
        "_ZN3MNN11Transformer3Llm4loadEv",
        "_ZN3MNN3Llm4loadEv",
        "MNN_LLM_load",
    };
    fn_load = (FnLoad)findAny(hLlm, loadNames, sizeof(loadNames)/sizeof(loadNames[0]));
    if (!fn_load) fn_load = (FnLoad)findAny(RTLD_DEFAULT, loadNames, sizeof(loadNames)/sizeof(loadNames[0]));
    gDebugInfo += std::string("load=") + (fn_load?"✅":"❌") + "|";

    // ── Step 4: 查找 response ──
    const char* respNames[] = {
        "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS4_EEPKc",
        "_ZN3MNN11Transformer3Llm8responseERKSsPSt13basic_ostreamIcSt11char_traitsIcEEPKc",
        "_ZN3MNN3Llm8responseERKSsPSoPKc",
        "MNN_LLM_response",
    };
    fn_response = (FnResponse)findAny(hLlm, respNames, sizeof(respNames)/sizeof(respNames[0]));
    if (!fn_response) fn_response = (FnResponse)findAny(RTLD_DEFAULT, respNames, sizeof(respNames)/sizeof(respNames[0]));
    gDebugInfo += std::string("resp=") + (fn_response?"✅":"❌") + "|";

    // ── Step 5: 查找 reset ──
    const char* resetNames[] = {
        "_ZN3MNN11Transformer3Llm5resetEv",
        "_ZN3MNN3Llm5resetEv",
        "MNN_LLM_reset",
    };
    fn_reset = (FnReset)findAny(hLlm, resetNames, sizeof(resetNames)/sizeof(resetNames[0]));
    if (!fn_reset) fn_reset = (FnReset)findAny(RTLD_DEFAULT, resetNames, sizeof(resetNames)/sizeof(resetNames[0]));
    gDebugInfo += std::string("reset=") + (fn_reset?"✅":"❌");

    LOGI("符号结果: %s", gDebugInfo.c_str());

    if (!fn_create) {
        LOGE("❌ createLLM 找不到，无法初始化");
        // 用 nm 方式枚举（打印几个已知符号验证库是否正确）
        const char* testSyms[] = { "malloc", "free", "_Znwm", "pthread_create" };
        for (auto s : testSyms) {
            void* p = dlsym(hLlm, s);
            LOGI("  test sym %s = %p", s, p);
        }
        return JNI_FALSE;
    }

    // ── Step 6: 创建 LLM 对象 ──
    try {
        LOGI("调用 createLLM(%s)", configPath.c_str());
        gLlmObj = fn_create(configPath);
        if (!gLlmObj) { LOGE("createLLM 返回 null"); return JNI_FALSE; }
        LOGI("createLLM 返回: %p", gLlmObj);

        if (fn_load) {
            LOGI("调用 load()...");
            fn_load(gLlmObj);
            LOGI("✅ load() 完成");
        }
        gDebugInfo += "|obj=✅";
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("createLLM 异常: %s", e.what());
        gLlmObj = nullptr;
        return JNI_FALSE;
    } catch (...) {
        LOGE("createLLM 未知异常");
        gLlmObj = nullptr;
        return JNI_FALSE;
    }
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeChatStream(
        JNIEnv* env, jobject, jstring jPrompt, jobject cb) {
    if (!gLlmObj) { LOGE("not init"); return; }

    jclass    cls      = env->GetObjectClass(cb);
    jmethodID onToken  = env->GetMethodID(cls, "onToken",  "(Ljava/lang/String;)V");
    jmethodID onFinish = env->GetMethodID(cls, "onFinish", "(Ljava/lang/String;)V");

    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    jobject gcb = env->NewGlobalRef(cb);
    std::string full;

    if (fn_response) {
        try {
            std::ostringstream oss;
            auto* old = std::cout.rdbuf(oss.rdbuf());
            fn_response(gLlmObj, prompt, &std::cout, "");
            std::cout.rdbuf(old);
            full = oss.str();

            // 模拟流式（每6字符回调一次）
            for (size_t i = 0; i < full.size(); ) {
                size_t end = std::min(i + 6, full.size());
                // 确保不截断 UTF-8 多字节字符
                while (end < full.size() && (full[end] & 0xC0) == 0x80) end++;
                std::string chunk = full.substr(i, end - i);
                jstring jt = env->NewStringUTF(chunk.c_str());
                env->CallVoidMethod(gcb, onToken, jt);
                env->DeleteLocalRef(jt);
                i = end;
            }
        } catch (...) {
            full = "[推理异常]";
            LOGE("response 异常");
        }
    } else {
        full = "[response符号未找到]";
    }

    jstring jf = env->NewStringUTF(full.c_str());
    env->CallVoidMethod(gcb, onFinish, jf);
    env->DeleteLocalRef(jf);
    env->DeleteGlobalRef(gcb);
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeChat(
        JNIEnv* env, jobject, jstring jPrompt, jobject cb) {
    if (!gLlmObj) return;
    jclass    cls      = env->GetObjectClass(cb);
    jmethodID onFinish = env->GetMethodID(cls, "onFinish", "(Ljava/lang/String;)V");
    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    std::string result = "[未推理]";
    if (fn_response) {
        try {
            std::ostringstream oss;
            fn_response(gLlmObj, prompt, &oss, "\n");
            result = oss.str();
        } catch (...) { result = "[推理异常]"; }
    }
    jobject gcb = env->NewGlobalRef(cb);
    jstring jr = env->NewStringUTF(result.c_str());
    env->CallVoidMethod(gcb, onFinish, jr);
    env->DeleteLocalRef(jr);
    env->DeleteGlobalRef(gcb);
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeReset(JNIEnv*, jobject) {
    if (gLlmObj && fn_reset) {
        try { fn_reset(gLlmObj); } catch (...) {}
    }
}

JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeIsReady(JNIEnv*, jobject) {
    return gLlmObj != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeDestroy(JNIEnv*, jobject) {
    gLlmObj = nullptr;
    if (gLlmHandle) { dlclose(gLlmHandle); gLlmHandle = nullptr; }
}

JNIEXPORT jstring JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeGetDebugInfo(JNIEnv* env, jobject) {
    return env->NewStringUTF(gDebugInfo.c_str());
}

} // extern "C"
