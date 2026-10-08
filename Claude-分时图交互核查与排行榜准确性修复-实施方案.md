# 分时图交互核查与排行榜准确性修复——实施方案

**执笔：Claude（协作AI，本次通过MCP filesystem工具直接读取本项目源码）**
**日期：2026-09-27**

> **实施状态更新（2026-09-27）**：用户已确认并要求直接实施第三、四、五部分，代码已修改完成（**尚未编译验证**，需要重新构建 Gradle 工程后才能生效）：
> - 三（GENERAL话术过滤bug）✅ 已修复：`WisdomManager.buildInjectBlock()` 把 "GENERAL" 视同不过滤，四处调用点不用改
> - 四（PENDING指标冻结bug）✅ 已修复：`TradingRuleEngine.computeMetricsOnly()` 返回类型由 String 改为 `MetricsSnapshot` 结构体，同步修改了 `RealtimeMonitorService.doTick()` 里的两处调用点（日志快照行取 `.summaryText`；每轮tick回写 `updateLiveMetrics()` 时改用新鲜的水线/VWAP/量比）
> - 五（排行榜prompt方法论）✅ 已完成：`LocalAIAgent.buildRankPrompt()` 在硬性要求前插入了「打分参考维度」；`WisdomManager` 新增 `ensureRankingWisdomSeeded()` 补种2条排行对比话术（按 summary 前缀「【排行对比】」查重，老安装也会补种）
> - 二（手动刷新按钮）、六（9.23日志问题）本轮未处理，仍按下文状态

## 背景

本文档承接对话中用户提出的一批分时图交互需求，以及两个具体现象反馈（"万丰股份排行榜数据疑似与实际不符""分时放量尖角校验感觉没做到位"）。核查过程中完整重读了 `index.html`（前端，约5000行）、`TradingRuleEngine.java`、`RealtimeMonitorService.java`、`StockBridge.java`、`FenshiHistoryManager.java`、`WisdomManager.java`、`LocalAIAgent.java`。

**核查结果跟预期不太一样**：用户列出的多数前端需求（搜索联想框、成交量展示、多日缓存切换日期）其实已经在近期（代码注释显示多为"2026-09-23新增"）被实现，而且实现得比较仔细。真正需要新写代码的，是本文档第二部分起的4件事。第一部分是核查证据，方便你对照App实际表现核实。

---

## 一、核查结论：以下功能已完整实现，无需重新开发

### 1.1 首页搜索联想框
`index.html` 第3996行起：`onSearchFocus()` 在搜索框获得焦点且未输入内容时调用 `renderMonitoredStockDropdown()`；数据源 `getMonitoredStockList()` 合并 `STATE.watchlistItems`（候选池）+ `STATE.positions`（持仓），每项显示状态标签。HTML input（第634-638行）已正确绑定 `onfocus`/`onblur`/`oninput`，`onblur` 有180ms延迟确保点击能先触发选中。**结论：完整实现。**

### 1.2 分时图成交量展示
`drawFenshiVolume()`（第3340行）在独立 `fenshiVolCanvas` 画布上常驻绘制红涨绿跌成交量柱状图；长按后的十字线提示框 `showFenshiCrosshair()` 额外显示该分钟精确成交量数字。**结论：完整实现，比原始需求更完整——常驻柱状图+长按精确数字并存。**

### 1.3 长按手势与页面滑动的冲突处理
`bindFenshiGestures()`（第3379行起）：`touchstart` 用 `passive:true` 不拦截，记录起点后启动400ms定时器；`touchmove` 中若移动超过10px阈值就取消定时器、判定为滑动意图、全程不 `preventDefault`；只有按住不动满400ms才进入十字线模式并开始 `preventDefault`。注释明确写着这是与K线手势（K线需要横向平移缩放，可无条件拦截）刻意做的区分。**结论：完整实现，且正是你担心的"页面滑动交互"这一点被专门处理过。**

### 1.4 分时图多日缓存 + 清仓自动删除 + 切换日期查看
- 后端：`FenshiHistoryManager`（独立SQLite表，主键code+日期）+ `RealtimeMonitorService.doTick()` 每轮拉到分时数据都落一份 + `StockBridge.recordTrade()` 卖出后仓位归零时调用 `deleteHistoryForCode()`
- 桥接：`getFenshiHistoryDates(code)` / `getFenshiHistoryByDate(code,date)`
- 前端：`fenshiDateSelect` 下拉框（HTML第726行）绑定 `onFenshiDateChange()`；切股票/冷启动时 `loadFenshiDateOptions()` 刷新可选日期并重置回"今日"；选中历史日期后 `loadFenshiHistoryForDate()` 取数据，复用同一个 `drawFenshiChart()` 渲染；`STATE.fenshiViewDate` 确保查看历史时不被后台"今日实时"刷新打断。

**结论：前后端全链路完整实现。已知限制（代码自己注明）：只有"先持仓后清仓"才触发删除，纯观察、从未买入过的候选股历史会一直累积——这是有意的范围限定，不是bug，如果要覆盖这种情况需要额外讨论触发时机（比如候选股被移出候选池时一并清理）。**

### 1.5 分时图自动刷新
`startRealTimeRefresh()` 里有一个独立于价格ticker之外的节流器：每30秒重绘一次分时图（`loadFenshiChart`），读本地缓存、不发网络请求。注释解释了30秒是刻意跟后端2分钟tick节奏错开选的。**结论：已实现自动重绘，但数据新鲜度上限仍是后端2分钟tick——这正是第二部分"手动刷新"存在的意义。**

---

## 二、需要新增：分时图"立即刷新"按钮

### 现状缺口
现有30秒自动重绘只是"重新读一遍缓存"，不会主动发网络请求；缓存更新依赖 `RealtimeMonitorService` 的2分钟tick（且仅覆盖候选池/持仓范围内的股票）。结合你"感觉放量尖角校验没做到位"的怀疑，这个按钮的真实诉求应该是：**跳过等待，立即从服务器重新拉一次这支股票的最新分时数据**，而不只是重画缓存。

### 方案
**后端（StockBridge.java）** 新增桥接方法：
```java
@JavascriptInterface
public void forceRefreshMinuteChart(String code) {
    // 复用 getMinuteChartData() 内部"缓存为空时"已有的补拉逻辑，去掉"仅缓存为空才触发"
    // 这个前置条件，改为无条件触发一次。成功/失败复用已有的
    // window.onMinuteChartDataReady / window.onMinuteChartError，前端不需要新回调。
    // 需要一个简单的"上一次请求还没返回就忽略新请求"防抖（如果现有补拉逻辑还没有的话）。
}
```
**前端（index.html）**：
1. "分时图"卡片标题栏（第718-731行，跟日期下拉框同一行）加一个刷新图标按钮，`onclick="onForceRefreshFenshi()"`
2. 新增：
```js
function onForceRefreshFenshi() {
  if (!IS_ANDROID || !STATE.currentKlineCode) return;
  if (STATE.fenshiViewDate && STATE.fenshiViewDate !== 'today') { showToast('请先切回"今日"再刷新'); return; }
  const btn = document.getElementById('fenshiRefreshBtn');
  if (btn) btn.classList.add('spinning');
  Android.forceRefreshMinuteChart(STATE.currentKlineCode);
  setTimeout(() => { if (btn) btn.classList.remove('spinning'); }, 1500);
}
```
3. 查看历史日期时禁用/隐藏该按钮

### 验收标准
- 点击后即使距上次tick未满2分钟，也能看到数据变化（如果该分钟确有新成交）
- 网络失败时走已有 `onMinuteChartError` 提示，不会一直转圈
- 连续快速点击不应触发重复并发请求

---

## 三、Bug修复①：GENERAL类actionKey导致7/9条话术被静默过滤

### 根因
`WisdomManager.buildInjectBlock(actionKey)` 用 `actionKey` 做字面量分类过滤；`LocalAIAgent` 里 `rankCandidatesBeforeClose`／`analyzeVolumeSpikeSignal`／`analyzeIntradayReversal`／`verifyIntradayPattern` 四处调用 `getSystemPrompt("GENERAL")` 传的是字面量字符串 `"GENERAL"`。但教话术时AI把某条分类为"通用"，写入端（`parseCategoryOrGuess`）会存成**空字符串**而非 `"GENERAL"`。库里没有一条话术分类字面量真的是 `"GENERAL"`，导致这四处只能命中2条空分类话术，其余7条（含最贴题的"真假破位看瞬时量能""涨停巨量看位置定性质"）被静默跳过。

### 修复方案（根治，一处改动）
```java
// WisdomManager.java, buildInjectBlock() 开头
boolean filterByType = actionKey != null && !actionKey.isEmpty()
        && !"GENERAL".equalsIgnoreCase(actionKey); // 新增：GENERAL视同"不过滤"
```
四处调用点不需要改（保留字面量 `"GENERAL"`，可读性上比传 `null` 更明确）。

### 回归验证点
1. 现有话术条数远小于 `MAX_INJECT_ENTRIES`(15)，修复后prompt变长但不会截断
2. 建议临时在 `rankCandidatesBeforeClose` 里把拼好的完整prompt打一次Log，确认9条话术都出现，验证完去掉

---

## 四、Bug修复②：候选池"待确认"状态期间VWAP/水线/量比冻结，排行榜引用旧数据

### 根因
`doTick()` 对PENDING状态的候选股跳过完整 `evaluate()`（避免重复触发信号判定），只在约10分钟一次的快照周期调用 `TradingRuleEngine.computeMetricsOnly()` 算一份新鲜文本，但这份结果**只进了决策日志文字**，从未回写 `WatchlistManager.updateLiveMetrics()`——候选池卡片、AI复核prompt、排行榜的 `buildCandidateSnapshotText()` 读的都是这份缓存，PENDING期间只有 `prevLow` 会更新，VWAP/水线/量比冻结在**最初触发信号那一刻**的旧值，可能是几小时前。这正是"排行榜说已站上VWAP19.92，但盯盘一小时看到的都是水下"这类症状的根本成因。

### 修复方案
`computeMetricsOnly()` 内部已经算出 `waterLine`/`vwap`/`vol.dayRatio`，只是没往外吐（我已重新确认过这个方法当前的完整实现，它是纯只读计算，不依赖PatternRef/持仓状态，PENDING场景下可以放心调用）。

**1) TradingRuleEngine.java** — 把返回类型从 `String` 换成一个小的结构化类型：
```java
public static class MetricsSnapshot {
    public String summaryText;
    public double waterLine, vwap, volRatio;
}

public MetricsSnapshot computeMetricsOnly(String code, RealtimeQuoteManager.Quote quote,
                                           List<RealtimeQuoteManager.MinutePoint> minutePoints,
                                           PrevDayRef prevDay) {
    MetricsSnapshot snap = new MetricsSnapshot();
    if (quote == null || prevDay == null || !prevDay.hasData) { snap.summaryText = ""; return snap; }
    snap.waterLine = prevDay.prevClose;
    snap.vwap = computeVwap(minutePoints, quote);
    // ……其余计算不变（checkVolume/summarize/summarizeFullDay）……
    snap.volRatio = vol.dayRatio;
    snap.summaryText = String.format(...);  // 原有格式串不变
    return snap;
}
```
**2) RealtimeMonitorService.java** — `doTick()` 的PENDING分支里，原来只更新 `prevLow` 的那次 `updateLiveMetrics()` 调用，改成把 `snap.waterLine`/`snap.vwap`/`snap.volRatio` 一并带上，不再读旧值原样回填：
```java
WatchlistManager.get().updateLiveMetrics(item.code, snap.waterLine, snap.vwap, snap.volRatio, pendingPrevDay.prevLow);
```
日志行原来用字符串 `pendingMetrics` 的地方，改用 `snap.summaryText`。

### 需要你确认的一点
这个修复会让PENDING候选股的VWAP/水线/量比大约每10分钟刷新一次，而不是完全冻结。**这些数字只影响展示/排行榜/AI复核参考，不会让PENDING提前"确认"或触发新买卖信号**——`doTick()` 仍然不会对PENDING股票重新调用完整 `evaluate()`，状态机判定完全不受影响。风险很低，建议直接按此方案实施；如果你觉得10分钟还不够新鲜，可以再单独调整PENDING分支的快照周期，但那是独立的可调参数，不影响这次修复本身。

### 验收标准
让一支候选股挂在PENDING状态30分钟以上，对比decision log里同一支股票不同时间点的"水线/VWAP/量比"——修复前应完全不变，修复后应该每次快照都可能有变化。

---

## 五、排行榜prompt深度对比方法论升级

### 现状不足
现有 `buildRankPrompt()` 的硬性要求只写了"至少引用一个上面给出的具体数值"，没有告诉AI该按什么优先级/方法横向比较多支股票，没有引导它综合看全天走势形态、量能是否集中在尾盘、现价相对昨日VWAP的位置——即使这些数据（`techSummary`/`fullDaySummary`/昨日VWAP）已经在 `candidateSnapshotText` 里。加上③修复前，最贴题的两条话术根本传不到这里，双重原因导致排行榜"看起来像随口打分"。

### 建议改写（插在现有"硬性要求"之前，不替换）
```
【打分参考维度，按重要性排序，不要求每支都展开讲，但排名依据应该主要来自这几点】
1. 全天走势形态是否健康：优先看重"先抑后扬""低位震荡后企稳"，警惕"高位震荡""单边下跌"
2. 量能是否配合：尾盘缩量下跌或放量滞涨是风险信号；正常缩量整理不算风险
3. 现价相对参照价的位置：同时看当日VWAP和昨日VWAP两个参照，都站稳的比只站稳一个更可靠
4. 距离止损参照价（前一交易日最低价）的安全边际：边际越大，隔夜风险相对越小
评分不是看单一指标，是看这几点是否互相印证；只满足其中一两点、其余相互矛盾的股票，不应该给高分。
```

### 配套：建议补充的新话术条目（分类留空，确保排行榜/放量尖角解读都能吃到）
以下是我根据这次反馈起草的措辞，你可以先看看是否需要调整，确认后我再帮你通过"教AI"功能录入：
1. "横向比较候选股时，全天走势形态比单一时点的涨跌幅更可靠——先抑后扬、低位震荡后企稳的股票，即使当前涨幅不如单边冲高的股票，往往更值得留意；单边冲高但尾盘缩量的，要警惕获利盘出逃。"
2. "候选股如果处于'待确认'挂起状态较久，参考它的水线/VWAP数字时要留意数字新鲜度；判断时应更倾向相信现价和最近分时形态，而不是单独一个可能滞后的VWAP数字。"（这条是配合④的过渡期提示，④修复上线后可以考虑去掉）

---

## 六、待你补充信息：9.23那次的具体问题

手机/模拟器端的运行日志不在这个Mac项目目录里，"运行信息"文件夹里现有导出也只到8月13日。如果能把9.23前后的决策日志内容贴过来，或把日志文件复制到项目目录（比如"运行信息"文件夹）里，我可以结合日志和上面核查过的代码状态继续排查。

---

## 实施优先级建议
1. **P0（低风险，建议直接做）**：三、GENERAL话术过滤修复——一行代码，无副作用
2. **P0**：四、PENDING指标新鲜度修复——改动集中在2个文件，逻辑清楚，不影响现有状态机
3. **P1**：二、手动刷新按钮——纯增量功能，不影响现有逻辑
4. **P1**：五、排行榜prompt方法论升级+话术补充——文本类改动，可以先小范围试几天效果
5. **需要你**：六、9.23问题需要日志才能继续
