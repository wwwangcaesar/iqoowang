package com.monsieurmahjong.iqoowang.agent;

import android.util.Log;


/**
 * LlmEngine — MNN LLM JNI封装
 *
 * 对应你 jniLibs/arm64-v8a/ 里的实际文件：
 *   libllm.so        ← LLM推理核心（不是 libMNNLLM.so）
 *   libMNN.so
 *   libMNN_CL.so     ← OpenCL，Adreno740
 *   libMNN_Express.so
 *   libMNN_Vulkan.so ← Vulkan，骁龙8 Gen2
 *   libmnncore.so
 *   libc++_shared.so ← C++运行时（必须最先加载）
 */
public class LlmEngine {

    private static final String TAG = "LlmEngine";

    static {
        try {
            // ★ 加载顺序严格：被依赖的先加载
            System.loadLibrary("c++_shared");    // C++ 运行时
            System.loadLibrary("mnncore");        // MNN核心基础
            System.loadLibrary("MNN");            // MNN主库
            System.loadLibrary("MNN_Express");    // Express接口
            System.loadLibrary("MNN_CL");         // OpenCL GPU
            System.loadLibrary("MNN_Vulkan");     // Vulkan GPU
            System.loadLibrary("llm");            // LLM推理核心
            System.loadLibrary("stockmaster_jni");// 我们的JNI桥
            Log.i(TAG, "All .so loaded OK");
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "Load error: " + e.getMessage());
            // MNN_CL / MNN_Vulkan 可能不存在，降级重试
            try {
                System.loadLibrary("c++_shared");
                System.loadLibrary("mnncore");
                System.loadLibrary("MNN");
                System.loadLibrary("MNN_Express");
                System.loadLibrary("llm");
                System.loadLibrary("stockmaster_jni");
                Log.i(TAG, "Loaded in CPU-only fallback mode");
            } catch (UnsatisfiedLinkError e2) {
                Log.e(TAG, "Critical: cannot load MNN: " + e2.getMessage());
            }
        }
    }

    // ── Native 方法（对应 llm_jni.cpp）──
    private native boolean nativeInit(String modelDir);
    private native void    nativeChat(String prompt, Callback callback);
    private native void    nativeChatStream(String prompt, Callback callback);
    private native void    nativeReset();
    private native boolean nativeIsReady();
    private native void    nativeDestroy();

    // ── 回调接口（C++ JNI 直接调用）──
    public interface Callback {
        void onToken(String token);    // 每个 token（流式）
        void onFinish(String fullText);// 完成
    }

    // ── 单例 ──
    private boolean mReady = false;
    private static LlmEngine sInstance;
    public static LlmEngine get() {
        if (sInstance == null) sInstance = new LlmEngine();
        return sInstance;
    }
    private LlmEngine() {}

    /**
     * 初始化（在子线程调用，耗时约6-8秒）
     * @param modelDir 模型目录完整路径
     *   例：/sdcard/Android/data/com.stockmaster/files/qwen2.5-1.5b-instruct-int4
     */
    public boolean init(String modelDir) {
        if (mReady) return true;
        try {
            boolean ok = nativeInit(modelDir);
            mReady = ok;
            Log.i(TAG, "init: " + (ok ? "SUCCESS" : "FAILED"));
            return ok;
        } catch (Throwable t) {
            Log.e(TAG, "init exception", t);
            return false;
        }
    }

    public boolean isReady() {
        try { return mReady && nativeIsReady(); }
        catch (Throwable t) { return false; }
    }

    /** 流式推理（推荐，token级回调） */
    public void chatStream(String prompt, Callback cb) {
        if (!mReady) { cb.onFinish("[模型未就绪]"); return; }
        try {
            nativeChatStream(prompt, cb);
        } catch (Throwable t) {
            Log.e(TAG, "chatStream", t);
            cb.onFinish("[推理异常: " + t.getMessage() + "]");
        }
    }

    /** 同步推理 */
    public void chat(String prompt, Callback cb) {
        if (!mReady) { cb.onFinish("[模型未就绪]"); return; }
        try {
            nativeChat(prompt, cb);
        } catch (Throwable t) {
            cb.onFinish("[推理异常]");
        }
    }

    /** 清空对话历史 */
    public void reset() {
        if (mReady) try { nativeReset(); } catch (Throwable ignored) {}
    }

    public void destroy() {
        if (mReady) {
            try { nativeDestroy(); } catch (Throwable ignored) {}
            mReady = false;
        }
    }
}
