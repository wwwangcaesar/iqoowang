# 操盘大师 — 快速上手（5步跑起来）

## 当前项目文件状态（全部完成 ✅）

```
StockMaster_Android/
├── build.gradle                    ✅ project级
├── settings.gradle                 ✅
├── MNN_SO_INTEGRATION.md          ✅ 集成详细说明
├── QUICK_START.md                 ← 本文件
└── app/
    ├── build.gradle               ✅ OkHttp + GreenDAO + CMake
    ├── proguard-rules.pro         ✅
    └── src/main/
        ├── AndroidManifest.xml    ✅
        ├── assets/
        │   └── index.html         ✅ 前端（K线手势+行情+AI）
        ├── cpp/
        │   ├── CMakeLists.txt     ✅ 使用 libllm.so
        │   ├── llm_jni.cpp        ✅ JNI桥接
        │   └── mnn_include/
        │       └── llm/
        │           └── llm.hpp    ✅ 已提供
        ├── java/com/stockmaster/
        │   ├── StockMasterApp.java     ✅ Application
        │   ├── MainActivity.java       ✅ WebView宿主
        │   ├── agent/
        │   │   ├── LlmEngine.java      ✅ .so加载+JNI
        │   │   └── LocalAIAgent.java   ✅ AI推理+专家降级
        │   ├── api/
        │   │   └── EastMoneyApi.java   ✅ OkHttp行情
        │   ├── bridge/
        │   │   └── StockBridge.java    ✅ JS⇌Java桥
        │   ├── db/
        │   │   ├── TradeRecord.java    ✅ GreenDAO实体
        │   │   ├── Position.java       ✅
        │   │   ├── DailyAsset.java     ✅
        │   │   └── DatabaseManager.java ✅
        │   └── worker/
        │       ├── DailySnapshotWorker.java ✅ 每日快照
        │       └── BootReceiver.java         ✅ 开机恢复
        ├── jniLibs/arm64-v8a/     ← 需要你放 .so（见步骤1）
        └── res/
            ├── values/
            │   ├── styles.xml     ✅
            │   ├── colors.xml     ✅
            │   └── strings.xml    ✅
            └── xml/
                └── network_security_config.xml ✅
```

---

## 步骤1：复制文件（10分钟）

### 1-A：头文件（从 MNN-3.6.0 源码包）

```
从 MNN-3.6.0/include/MNN/    →  复制整个MNN文件夹
到 app/src/main/cpp/mnn_include/MNN/

从 MNN-3.6.0/transformers/llm/llm.hpp   →  复制此单文件
到 app/src/main/cpp/mnn_include/llm/llm.hpp
   （已有生成版，可替换为官方版）
```

### 1-B：.so 文件（你已有 zip 包里的）

```
全部复制到：app/src/main/jniLibs/arm64-v8a/

需要的文件（从你 zip 包里拿）：
  libc++_shared.so    ✅ 你已有
  libllm.so           ✅ 你已有（LLM核心）
  libMNN.so           ✅ 你已有
  libMNN_CL.so        ✅ 你已有
  libMNN_Express.so   ✅ 你已有
  libMNN_Vulkan.so    ✅ 你已有
  libmnncore.so       ✅ 你已有

不需要的（可以不复制，不影响编译）：
  libMNNAudio.so      — 本项目不用
  libMNNOpenCV.so     — 本项目不用
```

### 1-C：GreenDAO 图标占位（临时）

```
res/mipmap-xxxhdpi/ 目录创建两个占位图标文件：
  ic_launcher.png
  ic_launcher_round.png

可以用任意小图片替代，只要能编译过去即可。
后期再换成正式图标。
```

---

## 步骤2：GreenDAO 代码生成（3分钟）

1. Android Studio → **Build → Make Project**
2. GreenDAO 插件自动生成以下文件到 `com/stockmaster/db/`：
   ```
   DaoMaster.java
   DaoSession.java
   TradeRecordDao.java
   PositionDao.java
   DailyAssetDao.java
   ```
3. 如果没有自动生成：Build → Clean Project → Rebuild Project

---

## 步骤3：下载模型（20分钟，下载速度取决于网络）

### ModelScope 国内直连（推荐）

```bash
pip install modelscope
python -c "
from modelscope import snapshot_download
snapshot_download(
    'taobao-mnn/Qwen2.5-1.5B-Instruct-MNN',
    local_dir='./qwen2.5-1.5b-instruct-int4'
)
"
```

### 推送到手机

```bash
# 先确认目录存在
adb shell mkdir -p /sdcard/Android/data/com.stockmaster/files/

# 推送模型（约900MB，需要3-5分钟）
adb push ./qwen2.5-1.5b-instruct-int4 \
    /sdcard/Android/data/com.stockmaster/files/qwen2.5-1.5b-instruct-int4

# 验证
adb shell ls /sdcard/Android/data/com.stockmaster/files/qwen2.5-1.5b-instruct-int4/
# 应看到：config.json  llm.mnn  tokenizer.mtok 等文件
```

---

## 步骤4：编译运行

1. 选择设备：iQOO 11s（确保 USB 调试已开启）
2. Run → Run 'app'
3. 等待编译（首次约3-5分钟，NDK编译稍慢）

### Logcat 验证（搜索关键词）

```
搜索：SM_JNI
期望：
  I/SM_JNI: JNI_OnLoad OK — StockMaster LLM Bridge
  I/SM_JNI: Loading model: /sdcard/.../config.json
  I/SM_JNI: Model loaded OK

搜索：LlmEngine
期望：
  I/LlmEngine: All .so loaded OK

搜索：DatabaseManager
期望：
  I/DatabaseManager: GreenDAO initialized: stockmaster.db
```

---

## 步骤5：验证功能

1. **行情K线**：首页应显示上证指数日K，可左右滑动、双指缩放
2. **选股**：点击"选股"页 → 运行选股 → 应显示筛选结果
3. **AI对话**：点击"AI大脑"→ 输入"SAR是什么" → 应有流式回复
4. **模拟交易**：点击选股结果 → 弹出买入弹窗 → 确认买入 → 持仓页显示

---

## 常见编译问题

### ❌ `undefined reference to 'MNN::Transformer::Llm::createLLM'`
**原因**：llm.hpp 版本与 libllm.so 不匹配
**解决**：把 MNN-3.6.0/transformers/llm/llm.hpp 替换掉项目里生成的版本

### ❌ `error: 'Llm' is not a member of 'MNN::Transformer'`
**原因**：include 路径没找到 llm.hpp
**解决**：检查 CMakeLists.txt 里 `include_directories(${MNN_INCLUDE_DIR})` 是否正确

### ❌ GreenDAO 找不到 DaoSession
**原因**：代码没有生成
**解决**：Build → Clean → Rebuild，或者检查 project/build.gradle 里有没有 greendao 插件

### ❌ `java.lang.UnsatisfiedLinkError: libllm.so`
**原因**：.so 没有放在 jniLibs/arm64-v8a/
**解决**：确认路径，然后 Build → Clean → Rebuild

### ❌ 图标资源找不到 `@mipmap/ic_launcher`
**原因**：缺少 mipmap 目录
**解决**：任意图片 rename 为 ic_launcher.png 放入 res/mipmap-xxxhdpi/

---

## 升级到 Qwen3-4B（完成后再做）

只需要改 1 行代码 + 换模型文件：

```java
// LocalAIAgent.java 第 XX 行
// 改这一行：
private static final String MODEL_DIR = "qwen2.5-1.5b-instruct-int4";
// 改为：
private static final String MODEL_DIR = "qwen3-4b-instruct-int4";
```

```bash
# 下载 Qwen3-4B
python -c "
from modelscope import snapshot_download
snapshot_download('taobao-mnn/Qwen3-4B-Instruct-MNN',
                  local_dir='./qwen3-4b-instruct-int4')
"
adb push ./qwen3-4b-instruct-int4 \
    /sdcard/Android/data/com.stockmaster/files/qwen3-4b-instruct-int4
```

iQOO 11s 16GB RAM 完全支持 Qwen3-4B，速度约 10-20 tok/s，分析质量大幅提升。
