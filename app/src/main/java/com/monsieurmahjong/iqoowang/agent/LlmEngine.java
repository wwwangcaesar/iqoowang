package com.monsieurmahjong.iqoowang.agent;

import android.content.Context;
import android.util.Log;

import java.io.File;
import java.lang.reflect.Method;

/**
 * LlmEngine v2 — 双路径策略
 *
 * 路径1（优先）：反射调用 MNN 官方 mnn_chat App 里的 Java API
 *   来自你解包的 mnn_chat_0_7_1.apk 里的 classes.dex
 *   包名：com.alibaba.mnn.llm 或 com.taobao.android.mnnchat
 *
 * 路径2（备用）：我们自己编译的 libstockmaster_jni.so
 *
 * 路径3（最终降级）：标记 jniAvailable=false，交给专家规则系统
 */
public class LlmEngine {

    private static final String TAG = "LlmEngine";

    // .so 加载状态
    private static boolean sJniLoaded   = false;
    private static String  sLoadError   = "";

    // 反射缓存
    private Object  mLlmInstance  = null;   // MNN Llm对象
    private Method  mResponseMethod = null;
    private Method  mResetMethod    = null;
    private boolean mReflectMode    = false; // 使用反射路径

    static {
        StringBuilder errLog = new StringBuilder();
        boolean loaded = false;

        // ── 关键说明 ──
        // libc++_shared.so 不能用 System.loadLibrary()，
        // 因为 Android 动态链接器从 app 的 nativeLibraryDir 加载时，
        // libc++_shared 是作为隐式依赖被拉入的，不需要显式加载。
        // 直接从 MNN 开始加载即可。

        // 方式1：直接加载主要库（Android自动处理libc++_shared依赖）
        try {
            System.loadLibrary("MNN");
            System.loadLibrary("MNN_Express");
            System.loadLibrary("llm");
            System.loadLibrary("stockmaster_jni");
            loaded = true;
            Log.i(TAG, "✅ .so加载成功（方式1）");
        } catch (UnsatisfiedLinkError e1) {
            errLog.append(shortErr(e1)).append(" | ");
            Log.w(TAG, "方式1失败: " + e1.getMessage());

            // 方式2：加 mnncore
            try {
                System.loadLibrary("mnncore");
                System.loadLibrary("MNN");
                System.loadLibrary("MNN_Express");
                System.loadLibrary("llm");
                System.loadLibrary("stockmaster_jni");
                loaded = true;
                Log.i(TAG, "✅ .so加载成功（方式2，含mnncore）");
            } catch (UnsatisfiedLinkError e2) {
                errLog.append(shortErr(e2)).append(" | ");
                Log.w(TAG, "方式2失败: " + e2.getMessage());

                // 方式3：加 GPU 库
                try {
                    System.loadLibrary("mnncore");
                    System.loadLibrary("MNN");
                    System.loadLibrary("MNN_CL");
                    System.loadLibrary("MNN_Vulkan");
                    System.loadLibrary("MNN_Express");
                    System.loadLibrary("llm");
                    System.loadLibrary("stockmaster_jni");
                    loaded = true;
                    Log.i(TAG, "✅ .so加载成功（方式3，含GPU）");
                } catch (UnsatisfiedLinkError e3) {
                    errLog.append(shortErr(e3)).append(" | ");
                    Log.w(TAG, "方式3失败: " + e3.getMessage());

                    // 方式4：只加载 llm 和 stockmaster_jni
                    try {
                        System.loadLibrary("llm");
                        System.loadLibrary("stockmaster_jni");
                        loaded = true;
                        Log.i(TAG, "✅ .so加载成功（方式4，最简）");
                    } catch (UnsatisfiedLinkError e4) {
                        errLog.append(shortErr(e4));
                        Log.e(TAG, "❌ 所有方式均失败: " + e4.getMessage());
                    }
                }
            }
        }

        sJniLoaded = loaded;
        sLoadError = errLog.toString();
        Log.i(TAG, "JNI状态: loaded=" + loaded + " err=" + sLoadError);
    }

    private static String shortErr(UnsatisfiedLinkError e) {
        String msg = e.getMessage();
        if (msg == null) return "null";
        int idx = msg.lastIndexOf('"');
        if (idx > 0) {
            int start2 = msg.lastIndexOf('"', idx-1);
            if (start2 >= 0) return msg.substring(start2+1, idx);
        }
        return msg.length() > 60 ? msg.substring(msg.length()-60) : msg;
    }


    public interface Callback {
        void onToken(String token);
        void onFinish(String fullText);
    }

    private boolean mReady = false;
    private static LlmEngine sInstance;

    public static LlmEngine get() {
        if (sInstance == null) sInstance = new LlmEngine();
        return sInstance;
    }
    private LlmEngine() {}

    public static String getLoadError() { return sLoadError; }
    public static boolean isJniLoaded() { return sJniLoaded; }

    public boolean init(String modelDir) {
        if (mReady) return true;
        Log.i(TAG, "init() modelDir=" + modelDir + " jniLoaded=" + sJniLoaded);

        // 检查文件
        String[] check = {"config.json", "llm.mnn", "llm.mnn.weight", "tokenizer.mtok"};
        for (String f : check) {
            File file = new File(modelDir, f);
            Log.i(TAG, "  " + f + ": " + file.exists() + " (" + file.length()/1024 + "KB)");
        }

        if (!sJniLoaded) {
            Log.e(TAG, "❌ JNI未加载，无法初始化LLM。错误: " + sLoadError);
            return tryReflectInit(modelDir);
        }

        try {
            boolean ok = nativeInit(modelDir);
            mReady = ok;
            mReflectMode = false;
            if (ok) {
                Log.i(TAG, "✅ nativeInit成功");
                sLoadError = ""; // 清除之前的错误
            } else {
                // 获取 C++ 层的调试信息
                try {
                    String debugInfo = nativeGetDebugInfo();
                    Log.e(TAG, "❌ nativeInit失败，调试信息: " + debugInfo);
                    sLoadError = debugInfo;
                } catch (Throwable ignored) {
                    Log.e(TAG, "❌ nativeInit返回false");
                    sLoadError = "nativeInit返回false，dlopen符号查找失败";
                }
            }
            return ok;
        } catch (UnsatisfiedLinkError e) {
            Log.e(TAG, "❌ native方法未找到: " + e.getMessage());
            return tryReflectInit(modelDir);
        } catch (Throwable t) {
            Log.e(TAG, "❌ nativeInit异常: " + t.getMessage(), t);
            return tryReflectInit(modelDir);
        }
    }

    /**
     * 反射路径：尝试调用系统中已有的MNN Java API
     * 适用于 libllm.so 已加载但我们的 JNI 桥没编译成功的情况
     */
    private boolean tryReflectInit(String modelDir) {
        Log.i(TAG, "尝试反射路径...");
        // MNN官方的Java接口类名（不同版本可能不同）
        String[] classNames = {
            "com.alibaba.mnn.llm.Llm",
            "com.taobao.android.mnnchat.Llm",
            "MNN.Llm",
        };
        for (String className : classNames) {
            try {
                Class<?> llmClass = Class.forName(className);
                Method createMethod = llmClass.getMethod("createLLM", String.class);
                mLlmInstance = createMethod.invoke(null, modelDir + "/config.json");
                if (mLlmInstance != null) {
                    llmClass.getMethod("load").invoke(mLlmInstance);
                    mResponseMethod = llmClass.getMethod("response", String.class, java.io.OutputStream.class, String.class);
                    mResetMethod    = llmClass.getMethod("reset");
                    mReflectMode    = true;
                    mReady          = true;
                    Log.i(TAG, "✅ 反射路径成功: " + className);
                    return true;
                }
            } catch (Exception e) {
                Log.d(TAG, "反射失败 " + className + ": " + e.getMessage());
            }
        }
        Log.e(TAG, "❌ 所有路径均失败。需要检查：\n" +
            "1. libstockmaster_jni.so 是否编译成功\n" +
            "2. CMakeLists.txt 头文件路径是否正确\n" +
            "3. Android Studio NDK 版本是否匹配");
        return false;
    }

    public boolean isReady() {
        if (!mReady) return false;
        if (mReflectMode) return mLlmInstance != null;
        try { return nativeIsReady(); } catch (Throwable t) { return false; }
    }

    public void chatStream(String prompt, Callback cb) {
        if (!mReady) { cb.onFinish("[模型未就绪: " + sLoadError + "]"); return; }
        try {
            if (mReflectMode) {
                reflectChat(prompt, cb);
            } else {
                nativeChatStream(prompt, cb);
            }
        } catch (Throwable t) {
            Log.e(TAG, "chatStream error", t);
            cb.onFinish("[推理异常: " + t.getMessage() + "]");
        }
    }

    public void chat(String prompt, Callback cb) {
        if (!mReady) { cb.onFinish("[模型未就绪]"); return; }
        try {
            if (mReflectMode) reflectChat(prompt, cb);
            else nativeChat(prompt, cb);
        } catch (Throwable t) { cb.onFinish("[推理异常]"); }
    }

    private void reflectChat(String prompt, Callback cb) throws Exception {
        java.io.ByteArrayOutputStream baos = new java.io.ByteArrayOutputStream();
        mResponseMethod.invoke(mLlmInstance, prompt, baos, "\n");
        String result = baos.toString("UTF-8");
        // 模拟流式输出
        for (int i = 0; i < result.length(); i += 3) {
            cb.onToken(result.substring(i, Math.min(i+3, result.length())));
        }
        cb.onFinish(result);
    }

    public void reset() {
        if (!mReady) return;
        try {
            if (mReflectMode && mResetMethod != null) mResetMethod.invoke(mLlmInstance);
            else nativeReset();
        } catch (Throwable ignored) {}
    }

    public void destroy() {
        mReady = false; mLlmInstance = null;
        try { nativeDestroy(); } catch (Throwable ignored) {}
    }

    // Native 方法声明
    private native boolean nativeInit(String modelDir);
    private native void    nativeChat(String prompt, Callback callback);
    private native void    nativeChatStream(String prompt, Callback callback);
    private native void    nativeReset();
    private native boolean nativeIsReady();
    private native void    nativeDestroy();
    /** 返回 C++ 层的调试信息（符号查找结果等） */
    private native String  nativeGetDebugInfo();
}
