# MNN .so 集成指南（无AAR版本）

## 你遇到的问题

// com_monsieurmahjong_iqoowang_agent包名的问题，需要替换这个到.cpp 文件中

MNN **从未发布过 AAR 包**，GitHub Release 里只有 `.so` + 头文件的 zip 压缩包。
这是正确的，官方 MnnLlmChat App 也是这么集成的。

---

## 第一步：确认 Release 包内容

从 GitHub Releases 下载的 zip（如 `mnn_x.x.x_android_armv8_cpu_opencl.zip`），解压后结构：

```
mnn_release/
├── include/
│   ├── MNN/           ← MNN核心头文件
│   │   ├── Interpreter.hpp
│   │   ├── Tensor.hpp
│   │   └── ...
│   └── llm/           ← LLM头文件（关键！）
│       └── llm.hpp    ← 如果没有这个，说明这个包不含LLM模块
├── arm64-v8a/
│   ├── libMNN.so
│   ├── libMNN_CL.so   ← OpenCL（可选，骁龙GPU加速）
│   ├── libMNN_Express.so
│   └── libMNNLLM.so   ← 这个是LLM推理核心，必须有！
└── ...
```

### ⚠️ 重要：确认 libMNNLLM.so 存在

普通的 MNN Release 包**不含** LLM 模块，需要下载专门带 LLM 的版本，或者自行编译：

```
下载地址：https://github.com/alibaba/MNN/releases
找含有 "llm" 字样的 Release，例如：
  mnn_x.x.x_android_armv8_cpu_opencl_llm.zip
```

如果 Release 里没有带 LLM 的包，需要自行编译（见下方）。

---

## 第二步：放置文件

```
app/src/main/
├── cpp/
│   ├── CMakeLists.txt       ← 已提供
│   ├── llm_jni.cpp          ← 已提供
│   └── mnn_include/         ← 把 include/ 目录复制到这里，改名 mnn_include
│       ├── MNN/
│       │   └── *.hpp
│       └── llm/
│           └── llm.hpp
└── jniLibs/
    └── arm64-v8a/           ← 把 .so 文件放这里
        ├── libMNN.so
        ├── libMNN_CL.so
        ├── libMNN_Express.so
        └── libMNNLLM.so
```

---

## 第三步：如果没有 libMNNLLM.so — 自行编译（约20分钟）

```bash
# 克隆MNN源码
git clone https://github.com/alibaba/MNN.git
cd MNN

# 进入Android编译目录
cd project/android
mkdir build_64 && cd build_64

# 执行编译脚本（关键参数：MNN_BUILD_LLM=true）
../build_64.sh "-DMNN_LOW_MEMORY=true \
  -DMNN_BUILD_LLM=true \
  -DMNN_CPU_WEIGHT_DEQUANT_GEMM=true \
  -DMNN_SUPPORT_TRANSFORMER_FUSE=true \
  -DMNN_ARM82=true \
  -DMNN_OPENCL=true \
  -DMNN_SEP_BUILD=OFF \
  -DCMAKE_INSTALL_PREFIX=."

make install
```

编译完成后，在 `build_64/` 目录找到 `.so` 文件，复制到 `jniLibs/arm64-v8a/`。

---

## 第四步：Build & 验证

1. Android Studio → Build → Make Project
2. 查看 Build Output，确认：
   - `stockmaster_jni` cmake task 成功
   - 无 `undefined reference to 'MNN::Transformer::Llm::createLLM'` 报错
3. 安装到 iQOO 11s，查看 Logcat：
   ```
   I/StockMaster_JNI: JNI_OnLoad: MNN LLM JNI bridge ready
   I/LlmEngine: All MNN .so loaded successfully
   ```

---

## 第五步：推送模型文件

```bash
# 从 HuggingFace 下载（约900MB）
# https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN

# 推送到设备
adb push Qwen2.5-1.5B-Instruct-MNN /sdcard/Android/data/com.stockmaster/files/qwen2.5-1.5b-instruct-int4/
```

---

## OkHttp 变更说明

原 `HttpURLConnection` 已全部替换为 `OkHttp 4.12.0`：

| 功能 | 原来 | 现在 |
|------|------|------|
| 网络请求 | HttpURLConnection（手动线程池） | OkHttp 异步 enqueue |
| 超时配置 | 分散在每个连接 | OkHttpClient 统一配置 |
| 请求头 | 每次手动设置 | Interceptor 统一注入 |
| 错误处理 | try-catch | onFailure 回调 |
| 连接复用 | 无 | OkHttp 连接池自动复用 |

---

## proguard-rules.pro 补充

```proguard
# OkHttp
-dontwarn okhttp3.**
-keep class okhttp3.** { *; }
-keepnames class okhttp3.internal.publicsuffix.PublicSuffixDatabase

# GreenDAO
-keepclassmembers class * extends org.greenrobot.greendao.AbstractDao {
    public static java.lang.String TABLENAME;
}
-keep class **$Properties { *; }

# MNN JNI
-keep class com.stockmaster.agent.LlmEngine { *; }
-keep class com.stockmaster.agent.LlmEngine$Callback { *; }
```
