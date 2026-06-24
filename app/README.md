# 操盘大师 StockMaster — 集成说明

## 项目架构

```
StockMaster_Android/
├── app/src/main/
│   ├── java/com/stockmaster/
│   │   ├── MainActivity.java           # 全屏WebView宿主，沉浸式配置
│   │   ├── db/
│   │   │   ├── TradeRecord.java        # GreenDAO实体：交易记录
│   │   │   ├── Position.java           # GreenDAO实体：持仓
│   │   │   ├── DailyAsset.java         # GreenDAO实体：每日资产快照
│   │   │   └── DatabaseManager.java    # 数据库单例 + 所有操作接口
│   │   ├── api/
│   │   │   └── EastMoneyApi.java       # 东方财富行情接口（K线/快照/批量）
│   │   ├── bridge/
│   │   │   └── StockBridge.java        # JS⇌Java双向桥（@JavascriptInterface）
│   │   └── agent/
│   │       └── LocalAIAgent.java       # 本地AI（MNN+Qwen2.5 / 专家规则降级）
│   ├── assets/
│   │   └── index.html                  # 前端（K线手势+真实行情+本地AI流式）
│   ├── AndroidManifest.xml
│   └── build.gradle
```

---

## 第一步：集成GreenDAO

build.gradle (project级) 添加：
```groovy
classpath 'org.greenrobot:greendao-gradle-plugin:3.3.0'
```

执行一次 Build → Make Project，GreenDAO自动生成：
- `DaoMaster.java`
- `DaoSession.java`
- `TradeRecordDao.java`
- `PositionDao.java`
- `DailyAssetDao.java`

以上5个文件生成在 `com.stockmaster.db` 包下，勿手动编辑。

---

## 第二步：集成MNN-LLM（端侧AI）

### 下载模型（一次性，约900MB）
```
模型：Qwen2.5-1.5B-Instruct-MNN (INT4量化)
地址：https://huggingface.co/taobao-mnn/Qwen2.5-1.5B-Instruct-MNN
```

推送到设备：
```bash
adb push Qwen2.5-1.5B-Instruct-MNN /sdcard/Android/data/com.stockmaster/files/qwen2.5-1.5b-instruct-int4/
```

### 下载MNN-LLM AAR
```
地址：https://github.com/alibaba/MNN/releases
选择：MNN-LLM-Android-2.x.x.zip，解压得到 .aar 文件
放入：app/libs/
```

### 取消LocalAIAgent.java中的注释
```java
// 找到这行并取消注释：
LLM llm = LLM.create(modelDir.getAbsolutePath());
llm.load();
mLLM = llm;

// 以及推理部分：
llm.chat(prompt, new LLMCallback() { ... });
```

### iQOO 11s 性能参数
| 配置 | 数值 |
|------|------|
| 模型加载时间 | ~6-8秒（冷启动，之后常驻内存） |
| 推理速度 | 20-40 tokens/s（Hexagon NPU加速） |
| 内存占用 | ~1.2GB（16GB RAM完全够用） |
| 数据安全 | 完全本地，0字节上云 |

---

## 第三步：东方财富行情接口

**无需API Key**，直接使用公开接口。

AndroidManifest.xml已配置 `usesCleartextTraffic=true`（允许HTTP）。

如需精细控制，在 `res/xml/network_security_config.xml` 中配置：
```xml
<network-security-config>
    <domain-config cleartextTrafficPermitted="true">
        <domain includeSubdomains="true">push2.eastmoney.com</domain>
        <domain includeSubdomains="true">push2his.eastmoney.com</domain>
    </domain-config>
</network-security-config>
```

---

## 第四步：WebView桥接调用关系

### JS → Java（前端调用Native）
```javascript
Android.fetchKline("000001", "日K", 120)   // 拉K线
Android.fetchStockList("sh", 1)             // 拉选股股票池
Android.recordTrade(code, name, "BUY", ...) // 记录交易到GreenDAO
Android.aiChat(message, historyJson)        // 本地AI对话
Android.aiAnalyzeScreenResult(jsonStr)      // AI分析选股结果
Android.getPositions()                      // 获取持仓JSON
Android.vibrate(30)                         // 震动反馈
```

### Java → JS（Native推送到前端）
```javascript
window.onKlineData(jsonStr, name, code)    // K线数据推送
window.onPriceUpdate(jsonStr)              // 实时价格刷新
window.onAiToken(token)                    // AI流式输出token
window.onAiAnalysis(text)                  // AI分析完成
window.onAiChatReply(text)                 // AI对话回复
window.initPositions(jsonStr)              // 页面加载后初始化持仓
window.initAssetHistory(jsonStr)           // 初始化资产历史
```

---

## 第五步：K线手势说明

| 手势 | 效果 |
|------|------|
| 单指左右滑动 | 平移K线视口（带惯性） |
| 双指捏合/展开 | 缩放（10根～120根） |
| 长按 | 显示十字线+OHLCV详情 |
| 点击周期按钮 | 切换日K/周K/分时，自动请求真实数据 |

---

## 数据安全说明

- **持仓数据**：GreenDAO本地SQLite，路径 `/data/data/com.stockmaster/databases/stockmaster.db`
- **AI推理**：完全本地Qwen2.5，提示词和结果均不发送到任何服务器
- **行情数据**：从东方财富公开接口拉取（单向读取，无账户信息）
- **无任何遥测/埋点**

---

## 常见问题

**Q: 首次启动AI很慢？**
A: 正常，模型冷加载需6-8秒。MainActivity已调用 `Android.warmupAI()` 在后台预热，第二次请求即时响应。

**Q: 行情数据不更新？**
A: 东方财富接口非交易时段返回昨日收盘数据，属正常。交易时段（9:30-15:00）3秒刷新一次。

**Q: GreenDAO编译报错？**
A: 确认project级build.gradle已加插件 `classpath 'org.greenrobot:greendao-gradle-plugin:3.3.0'`，然后Clean → Rebuild。
