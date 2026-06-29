/**
 * llm_jni.cpp v5
 *
 * createLLM 已找到（模型加载成功）
 * 本版重点：扩展 response 符号候选 + 添加符号枚举辅助
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
static void*       gLlmHandle  = nullptr;
static void*       gLlmObj     = nullptr;
static JavaVM*     gJvm        = nullptr;
static std::string gDebugInfo;

// ── 函数指针 ──
typedef void*       (*FnCreate)(const std::string&);
typedef void        (*FnLoad)(void*);

// response 方法有多种签名，逐一尝试
// 签名1: string response(string, ostream*, char*)  — 带返回值
typedef std::string (*FnResp1)(void*, const std::string&, std::ostream*, const char*);
// 签名2: void response(string, ostream*, char*)    — 无返回值
typedef void        (*FnResp2)(void*, const std::string&, std::ostream*, const char*);
// 签名3: string response(string)                   — 只有输入
typedef std::string (*FnResp3)(void*, const std::string&);
// 签名4: void response(string)
typedef void        (*FnResp4)(void*, const std::string&);

typedef void  (*FnReset)(void*);

static FnCreate  fn_create    = nullptr;
static FnLoad    fn_load      = nullptr;
static void*     fn_resp_ptr  = nullptr;  // 原始指针，调用时根据签名选择
static int       fn_resp_sig  = 0;        // 1-4 对应上面四种签名
static FnReset   fn_reset     = nullptr;

// ── 工具函数 ──
static void* trySymbol(void* h, const char* name) {
    void* s = dlsym(h, name);
    if (s) LOGI("  ✅ %s", name);
    return s;
}

static void* findAny(void* h, const char** names, int n) {
    for (int i = 0; i < n; i++) {
        void* s = trySymbol(h, names[i]);
        if (s) return s;
    }
    return nullptr;
}

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    LOGI("JNI_OnLoad v5");
    return JNI_VERSION_1_6;
}

JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeInit(
        JNIEnv* env, jobject, jstring jDir) {
    if (gLlmObj) return JNI_TRUE;

    const char* dir = env->GetStringUTFChars(jDir, nullptr);
    std::string configPath = std::string(dir) + "/config.json";
    env->ReleaseStringUTFChars(jDir, dir);
    LOGI("nativeInit: %s", configPath.c_str());
    gDebugInfo.clear();

    // ── 打开 libllm.so ──
    void* hLlm = dlopen("libllm.so", RTLD_NOW | RTLD_GLOBAL);
    if (!hLlm) {
        LOGW("dlopen libllm.so 失败: %s，用 RTLD_DEFAULT", dlerror());
        hLlm = RTLD_DEFAULT;
        gDebugInfo += "lib=DEFAULT|";
    } else {
        LOGI("libllm.so opened: %p", hLlm);
        gLlmHandle = hLlm;
        gDebugInfo += "lib=OK|";
    }
    // 确保 MNN 也已加载
    void* hMnn = dlopen("libMNN.so", RTLD_NOW | RTLD_GLOBAL);
    LOGI("libMNN.so: %p", hMnn);

    // ── createLLM ──
    const char* createNames[] = {
        "_ZN3MNN11Transformer3Llm9createLLMERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        "_ZN3MNN11Transformer3Llm9createLLMERKSs",
        "_ZN3MNN3Llm9createLLMERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEE",
        "_ZN3MNN3Llm9createLLMERKSs",
        "_ZN3MNN11Transformer3Llm6createERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE",
        "MNN_LLM_createLLM", "createLLM",
    };
    fn_create = (FnCreate)findAny(hLlm, createNames, sizeof(createNames)/sizeof(createNames[0]));
    if (!fn_create) fn_create = (FnCreate)findAny(RTLD_DEFAULT, createNames, sizeof(createNames)/sizeof(createNames[0]));
    gDebugInfo += std::string("create=") + (fn_create?"✅":"❌") + "|";

    // ── load ──
    const char* loadNames[] = {
        "_ZN3MNN11Transformer3Llm4loadEv",
        "_ZN3MNN3Llm4loadEv",
        "MNN_LLM_load",
    };
    fn_load = (FnLoad)findAny(hLlm, loadNames, sizeof(loadNames)/sizeof(loadNames[0]));
    if (!fn_load) fn_load = (FnLoad)findAny(RTLD_DEFAULT, loadNames, sizeof(loadNames)/sizeof(loadNames[0]));
    gDebugInfo += std::string("load=") + (fn_load?"✅":"❌") + "|";

    // ── response — 大量候选，覆盖各种 MNN 版本 ──
    LOGI("=== 查找 response 符号 ===");

    // 组1: MNN::Transformer::Llm::response (3.x 主线)
    const char* r1 = "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS4_EEPKc";
    const char* r2 = "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPSt13basic_ostreamIcSt11char_traitsIcEEPKc";
    // 组2: 无 ostream 参数版本
    const char* r3 = "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEE";
    const char* r4 = "_ZN3MNN11Transformer3Llm8responseERKSs";
    // 组3: MNN::Llm::response
    const char* r5 = "_ZN3MNN3Llm8responseERKNSt6__ndk112basic_stringIcNS1_11char_traitsIcEENS1_9allocatorIcEEEEPNS1_13basic_ostreamIcS3_EEPKc";
    const char* r6 = "_ZN3MNN3Llm8responseERKSsPSoPKc";
    const char* r7 = "_ZN3MNN3Llm8responseERKSs";
    // 组4: C 风格
    const char* r8  = "MNN_LLM_response";
    const char* r9  = "Llm_response";
    // 组5: 带 _ZNK（const成员）
    const char* r10 = "_ZNK3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS4_EEPKc";
    // 组6: 带 std:: (GNU)
    const char* r11 = "_ZN3MNN11Transformer3Llm8responseERKSsPSt13basic_ostreamIcSt11char_traitsIcEEPKc";
    const char* r12 = "_ZN3MNN11Transformer3Llm8responseERKSt6__ndk112basic_stringIcS1_11char_traitsIcES1_9allocatorIcEEPSt13basic_ostreamIcSt11char_traitsIcEEPKc";

    // 尝试带 ostream 参数的（签名1/2）
    const char* respWithOs[] = {r1, r2, r5, r6, r10, r11, r12};
    for (const char* name : respWithOs) {
        void* s = trySymbol(hLlm, name);
        if (!s) s = trySymbol(RTLD_DEFAULT, name);
        if (s) { fn_resp_ptr = s; fn_resp_sig = 1; break; }
    }

    // 尝试不带 ostream 参数的（签名3/4）
    if (!fn_resp_ptr) {
        const char* respNoOs[] = {r3, r4, r7, r8, r9};
        for (const char* name : respNoOs) {
            void* s = trySymbol(hLlm, name);
            if (!s) s = trySymbol(RTLD_DEFAULT, name);
            if (s) { fn_resp_ptr = s; fn_resp_sig = 3; break; }
        }
    }

    gDebugInfo += std::string("resp=") + (fn_resp_ptr ? ("✅sig"+std::to_string(fn_resp_sig)) : "❌") + "|";
    LOGI("response: ptr=%p sig=%d", fn_resp_ptr, fn_resp_sig);

    // ── reset ──
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
        LOGE("❌ createLLM 未找到");
        return JNI_FALSE;
    }

    // ── 创建对象 ──
    try {
        LOGI("调用 createLLM...");
        gLlmObj = fn_create(configPath);
        if (!gLlmObj) { LOGE("createLLM 返回 null"); return JNI_FALSE; }
        LOGI("createLLM OK: %p", gLlmObj);

        if (fn_load) {
            LOGI("调用 load()...");
            fn_load(gLlmObj);
            LOGI("load OK");
        }

        // ── 如果 response 还没找到，在加载后再搜索一次（某些符号延迟加载）──
        if (!fn_resp_ptr) {
            LOGI("=== load后再次搜索 response ===");
            const char* retryNames[] = {
                "_ZN3MNN11Transformer3Llm8responseERKNSt6__ndk112basic_stringIcNS2_11char_traitsIcEENS2_9allocatorIcEEEEPNS2_13basic_ostreamIcS4_EEPKc",
                "_ZN3MNN11Transformer3Llm8responseERKSsPSt13basic_ostreamIcSt11char_traitsIcEEPKc",
                "_ZN3MNN11Transformer3Llm8responseERKSs",
                "_ZN3MNN3Llm8responseERKSsPSoPKc",
                "MNN_LLM_response",
            };
            for (const char* name : retryNames) {
                void* s = dlsym(RTLD_DEFAULT, name);
                if (s) {
                    LOGI("  延迟找到: %s", name);
                    fn_resp_ptr = s;
                    fn_resp_sig = 1;
                    break;
                }
            }
            gDebugInfo += std::string("|retry_resp=") + (fn_resp_ptr?"✅":"❌");
        }

        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("异常: %s", e.what());
        gLlmObj = nullptr;
        return JNI_FALSE;
    } catch (...) {
        LOGE("未知异常");
        gLlmObj = nullptr;
        return JNI_FALSE;
    }
}

// ── 推理辅助 ──
static std::string callResponse(const std::string& prompt) {
    if (!fn_resp_ptr || !gLlmObj) return "[response未就绪]";
    std::ostringstream oss;
    try {
        if (fn_resp_sig == 1) {
            // string response(this, string, ostream*, char*)
            auto fn = (FnResp1)fn_resp_ptr;
            return fn(gLlmObj, prompt, &oss, "");
        } else if (fn_resp_sig == 2) {
            auto fn = (FnResp2)fn_resp_ptr;
            auto* old = std::cout.rdbuf(oss.rdbuf());
            fn(gLlmObj, prompt, &std::cout, "");
            std::cout.rdbuf(old);
            return oss.str();
        } else if (fn_resp_sig == 3) {
            auto fn = (FnResp3)fn_resp_ptr;
            return fn(gLlmObj, prompt);
        } else if (fn_resp_sig == 4) {
            auto fn = (FnResp4)fn_resp_ptr;
            auto* old = std::cout.rdbuf(oss.rdbuf());
            fn(gLlmObj, prompt);
            std::cout.rdbuf(old);
            return oss.str();
        }
    } catch (const std::exception& e) {
        LOGE("response 异常: %s", e.what());
        return std::string("[推理异常: ") + e.what() + "]";
    } catch (...) {
        LOGE("response 未知异常");
        return "[推理未知异常]";
    }
    return "[签名未知]";
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
    LOGI("chatStream: len=%zu sig=%d", prompt.size(), fn_resp_sig);

    std::string full = callResponse(prompt);
    LOGI("response 完成，长度=%zu", full.size());

    // 流式回调（按UTF-8字符分批）
    for (size_t i = 0; i < full.size(); ) {
        size_t end = std::min(i + 6, full.size());
        while (end < full.size() && (full[end] & 0xC0) == 0x80) end++;
        std::string chunk = full.substr(i, end - i);
        jstring jt = env->NewStringUTF(chunk.c_str());
        env->CallVoidMethod(gcb, onToken, jt);
        env->DeleteLocalRef(jt);
        i = end;
    }

    jstring jf = env->NewStringUTF(full.c_str());
    env->CallVoidMethod(gcb, onFinish, jf);
    env->DeleteLocalRef(jf);
    env->DeleteGlobalRef(gcb);
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeChat(
        JNIEnv* env, jobject, jstring jPrompt, jobject cb) {
    jclass    cls      = env->GetObjectClass(cb);
    jmethodID onFinish = env->GetMethodID(cls, "onFinish", "(Ljava/lang/String;)V");
    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string result = callResponse(std::string(p));
    env->ReleaseStringUTFChars(jPrompt, p);
    jobject gcb = env->NewGlobalRef(cb);
    jstring jr  = env->NewStringUTF(result.c_str());
    env->CallVoidMethod(gcb, onFinish, jr);
    env->DeleteLocalRef(jr);
    env->DeleteGlobalRef(gcb);
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeReset(JNIEnv*, jobject) {
    if (gLlmObj && fn_reset) try { fn_reset(gLlmObj); } catch (...) {}
}

JNIEXPORT jboolean JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeIsReady(JNIEnv*, jobject) {
    return gLlmObj != nullptr ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT void JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeDestroy(JNIEnv*, jobject) {
    gLlmObj = nullptr;
    if (gLlmHandle) { dlclose(gLlmHandle); gLlmHandle = nullptr; }
    LOGI("destroyed");
}

JNIEXPORT jstring JNICALL
Java_com_monsieurmahjong_iqoowang_agent_LlmEngine_nativeGetDebugInfo(JNIEnv* env, jobject) {
    std::string info = gDebugInfo + "|respSig=" + std::to_string(fn_resp_sig);
    return env->NewStringUTF(info.c_str());
}

} // extern "C"
