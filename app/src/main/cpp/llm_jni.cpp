#include <jni.h>
#include <string>
#include <sstream>
#include <functional>
#include <android/log.h>

// MNN LLM 头文件（mnn_include/llm/llm.hpp）
#include "llm/llm.hpp"

#define TAG "SM_JNI"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO,  TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, TAG, __VA_ARGS__)
#define LOGW(...) __android_log_print(ANDROID_LOG_WARN,  TAG, __VA_ARGS__)

using namespace MNN::Transformer;

// ── 全局 LLM 实例（常驻内存，避免重复加载）──
static Llm* gLlm = nullptr;
static JavaVM* gJvm = nullptr;

extern "C" {

JNIEXPORT jint JNI_OnLoad(JavaVM* vm, void*) {
    gJvm = vm;
    LOGI("JNI_OnLoad OK — StockMaster LLM Bridge");
    return JNI_VERSION_1_6;
}

// ════════════════════════════════════════
// nativeInit：加载模型（子线程调用）
// ════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeInit(
        JNIEnv* env, jobject, jstring jModelDir) {
    if (gLlm) { LOGI("LLM already loaded"); return JNI_TRUE; }

    const char* dir = env->GetStringUTFChars(jModelDir, nullptr);
    std::string configPath = std::string(dir) + "/config.json";
    env->ReleaseStringUTFChars(jModelDir, dir);

    LOGI("Loading model: %s", configPath.c_str());
    try {
        gLlm = Llm::createLLM(configPath);
        if (!gLlm) { LOGE("createLLM returned null"); return JNI_FALSE; }
        gLlm->load();
        LOGI("Model loaded OK");
        return JNI_TRUE;
    } catch (const std::exception& e) {
        LOGE("Load exception: %s", e.what());
        return JNI_FALSE;
    } catch (...) {
        LOGE("Load unknown exception");
        return JNI_FALSE;
    }
}

// ════════════════════════════════════════
// nativeChatStream：流式推理（主要接口）
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeChatStream(
        JNIEnv* env, jobject, jstring jPrompt, jobject callback) {
    if (!gLlm) {
        LOGE("nativeChatStream: not initialized");
        return;
    }

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onTokenMid  = env->GetMethodID(cbClass, "onToken",  "(Ljava/lang/String;)V");
    jmethodID onFinishMid = env->GetMethodID(cbClass, "onFinish", "(Ljava/lang/String;)V");

    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    // 保存全局引用（本函数在子线程中同步执行）
    jobject globalCb = env->NewGlobalRef(callback);

    std::string fullText;
    bool success = false;

//    try {
//        // ── 方式1：使用 responseWithCallback（MNN 3.x 流式）──
//        fullText = gLlm->responseWithCallback(
//                prompt,
//                [&](const std::string& token) -> bool {
//                    // 此处已在同一线程（子线程），可直接调用 JNI
//                    jstring jtoken = env->NewStringUTF(token.c_str());
//                    env->CallVoidMethod(globalCb, onTokenMid, jtoken);
//                    env->DeleteLocalRef(jtoken);
//                    return true; // 返回 false 可中断
//                },
//                ""
//        );
//        success = true;
//    } catch (...) {
//        LOGW("responseWithCallback failed, fallback to sync");
//    }

    // ── 方式2降级：同步 response，劫持 cout ──
    if (!success) {
        try {
            std::ostringstream oss;
            gLlm->response(prompt, &oss, "\n");
            fullText = oss.str();

            // 把整段文本按标点分批回调（模拟流式）
            std::string chunk;
            for (unsigned char c : fullText) {
                chunk += c;
                // UTF-8完整字符 + 标点触发回调
                bool isPunct = (c == 0xE3 || c == 0xEF ||  // 中文标点前缀
                                c == ',' || c == '.' ||
                                c == '\n' || chunk.size() >= 6);
                if (isPunct && !chunk.empty()) {
                    jstring jt = env->NewStringUTF(chunk.c_str());
                    env->CallVoidMethod(globalCb, onTokenMid, jt);
                    env->DeleteLocalRef(jt);
                    chunk.clear();
                }
            }
            if (!chunk.empty()) {
                jstring jt = env->NewStringUTF(chunk.c_str());
                env->CallVoidMethod(globalCb, onTokenMid, jt);
                env->DeleteLocalRef(jt);
            }
        } catch (const std::exception& e) {
            LOGE("sync response exception: %s", e.what());
            fullText = "[推理异常，请重试]";
        }
    }

    // 完成回调
    jstring jFull = env->NewStringUTF(fullText.c_str());
    env->CallVoidMethod(globalCb, onFinishMid, jFull);
    env->DeleteLocalRef(jFull);
    env->DeleteGlobalRef(globalCb);
}

// ════════════════════════════════════════
// nativeChat：同步推理（简单场景用）
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeChat(
        JNIEnv* env, jobject, jstring jPrompt, jobject callback) {
    if (!gLlm) return;

    jclass cbClass = env->GetObjectClass(callback);
    jmethodID onFinishMid = env->GetMethodID(cbClass, "onFinish", "(Ljava/lang/String;)V");

    const char* p = env->GetStringUTFChars(jPrompt, nullptr);
    std::string prompt(p);
    env->ReleaseStringUTFChars(jPrompt, p);

    std::string result;
    try {
        std::ostringstream oss;
        gLlm->response(prompt, &oss, "\n");
        result = oss.str();
    } catch (...) {
        result = "[推理失败]";
    }

    jstring jResult = env->NewStringUTF(result.c_str());
    jobject globalCb = env->NewGlobalRef(callback);
    env->CallVoidMethod(globalCb, onFinishMid, jResult);
    env->DeleteLocalRef(jResult);
    env->DeleteGlobalRef(globalCb);
}

// ════════════════════════════════════════
// nativeReset：清空 KV Cache
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeReset(JNIEnv*, jobject) {
    if (gLlm) { gLlm->reset(); LOGI("KV Cache reset"); }
}

// ════════════════════════════════════════
// nativeIsReady
// ════════════════════════════════════════
JNIEXPORT jboolean JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeIsReady(JNIEnv*, jobject) {
    return gLlm != nullptr ? JNI_TRUE : JNI_FALSE;
}

// ════════════════════════════════════════
// nativeDestroy
// ════════════════════════════════════════
JNIEXPORT void JNICALL
Java_com_stockmaster_agent_LlmEngine_nativeDestroy(JNIEnv*, jobject) {
    if (gLlm) {
        delete gLlm;
        gLlm = nullptr;
        LOGI("LLM destroyed");
    }
}

} // extern "C"
