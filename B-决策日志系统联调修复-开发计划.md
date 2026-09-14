# B｜决策日志系统联调修复与前端页面 —— 开发计划

**记录人**：B（本项目由多个AI协同开发/审查/规划，本文档及后续对应的修改记录均以"B"署名）
**状态**：【已交接给C】R11确认由C接手实施，本文档保留作为C的实现参考，2.1/2.2/2.3的技术方案不变；B转而认领R12，见新建的 `B-VWAP缓存持久化修复-开发计划.md`
**关联文档**：`A-买卖逻辑优化与本地AI增强-开发计划.md`（第0节明确把日志系统重构划给"另一位协作AI"负责，本文档最初由B排查撰写，现由C承接实施）
**依据**：`DecisionLogger.java` 类头注释显示已有一次【2026-09-03重构】，把日志改成"日期文件夹＋每股票独立文件"结构；B直接通读当前源码后发现这次重构没有完整传导到调用方，处于半成品状态

---

## 0. 协作说明

1. `DecisionLogger.java` 本身（日期文件夹＋每股票文件＋7天保留）的核心存储结构已经按目标规格重写完成，B不重新设计这部分，只排查并修复它跟调用方的接口一致性问题。
2. A在其计划文档第0节承诺"A不会改动DecisionLogger现有结构，涉及日志调用的地方一律沿用现有接口签名"——B确认这一点不受影响：A后续实现R1-R10时继续调用 `logRulePush`／`logAiSupplement`／`logNote`／`logBuyLogicTrace`／`logPrevDayVwapFetch`／`cleanupOldLogs` 这几个签名未变的方法即可。
3. 下文引用均基于B排查阶段通过Filesystem连接器直接读取的 `DecisionLogger.java`／`StockBridge.java`／`RealtimeMonitorService.java` 源码；`assets/index.html`（196KB，前端决策日志页面所在处）B未读取，留给C确认。

---

## 1. 现状确认

**已完成**：`DecisionLogger.java` 内部存储结构已是目标规格——`decision_logs/日期/代码_名称.txt`，同一支股票的监控/决策/AI分析/操作按时间顺序混排进同一文件，保留7天自动清理。对外读取方法：`listLogDates()`（无参）、`getStocksForDateJson(dayStr)`、`getStockLogContent(dayStr, code)`、`getLogDirPath()`。

**问题1（编译级，最高优先级）**：`StockBridge.java` 有5处方法体仍在调用**已不存在**的旧版DecisionLogger方法——

| StockBridge方法 | 调用的旧方法 | 现状 |
|---|---|---|
| `getDecisionLog(dayStr, category)` | `getLogContent(dayStr, category)` | 不存在 |
| `getDecisionLogDates(category)` | `listLogDates(category)` | 新版`listLogDates()`已改无参 |
| `getLogCategories()` | `getCategoriesJson()` | 不存在 |
| `getTodayDecisionLog()` | `getTodayLogContent()` | 不存在 |
| `getDecisionLog(dayStr)`（单参重载） | `getLogContent(dayStr)` | 不存在 |

**当前整个项目处于无法编译状态**，这是本次排查最重要的发现，建议排在一切后续工作最前面。

**问题2（行为缺口，编译能过但效果没对上）**：`RealtimeMonitorService.finishTickBatch()` 仍调用旧版兼容方法 `logSnapshot(List<String> lines)`——新版DecisionLogger里这个方法已标 `@deprecated`，内部把每一行都归到 `code=null` 的匿名 `_system` 文件、类型标"监控"。也就是说周期性监控快照现在完全没有按股票分流，跟用户要的"该股票当天所有监控日志都写进这支股票自己的文件"这条核心诉求没对上，是目前唯一还在持续产生"归档到错误位置"的调用点。

**问题3（未核实）**：`assets/index.html` 前端大概率还在按旧版"5分类"模型调用 `Android.getDecisionLog(dayStr, category)`／`Android.getLogCategories()`——B未读取此文件确认，但既然Java端这几个方法本身已经编译不过，前端这部分功能不管有没有针对性改动，现在实际都不可用。原始需求里"决策日志点击以后跳转到一个新页面，可重复点击查看"这部分UI，大概率还是旧版按分类的页面，尚未改成"日期→股票列表→单股票日志"三层结构。

---

## 2. 技术方案

### 2.1 修复 StockBridge.java（问题1，纯编译修复，无设计分歧）

删除5个失效方法（见上表），新增2个对齐新版DecisionLogger的方法：
- `getDecisionLogStocksForDate(String dayStr)` → 包装 `getStocksForDateJson(dayStr)`
- `getStockDecisionLog(String dayStr, String code)` → 包装 `getStockLogContent(dayStr, code)`

保留已经能正常编译的 `getDecisionLogDates()`（无参）／`getDecisionLogDirPath()` 不动。

### 2.2 修复 RealtimeMonitorService（问题2）

把 `finishTickBatch` 里"攒一批String再一次性调用`logSnapshot(list)`"，改成在 `buildSnapshotLine` 产出每一行时（此时`item`就在作用域里）直接调用 `DecisionLogger.get().logMonitorLine(item.code, item.name, line)`，不再攒批次。原有"每10分钟才写一次快照"的节流逻辑（`dueForSnapshot`／`mLastSnapshotAt`）保留，只是从"攒List统一调用"改成"逐行按股票直接调用"，效果不变，但每一行都会落到正确的股票文件里。

### 2.3 新增前端决策日志页（问题3，读完index.html现状后再定细节）

对齐用户原始需求"点击跳转新页面，展示每天的股票日志信息，可重复点击查看"：三层导航——日期列表（`getDecisionLogDates()`）→ 选中日期后的股票列表（`getDecisionLogStocksForDate(dayStr)`）→ 选中股票后的完整日志内容（`getStockDecisionLog(dayStr, code)`），替换旧版按分类筛选的页面。具体交互细节（比如默认展开今天、股票列表要不要带当天最后一条状态摘要）读完index.html现有实现后再定，不在此预设。

---

## 3. 待确认问题

1. 2.1／2.2 是纯接口修复+bug修复，不涉及设计取舍，建议直接实现，不需要额外等待确认。
2. 2.3（前端新页面）读完index.html现状后，如果只是"旧UI换新接口"这种没有歧义的改动，可直接做；如果发现旧页面的分类视图有值得保留的东西（比如"只看AI分析"这种跨股票聚合视图），建议单独列出来跟用户确认，不在这里预判。

---

## 4. 变更记录

- **2026-09-03　B**：接手A计划文档中标注"由另一位协作AI负责"的日志系统一节；通读当前 `DecisionLogger.java`／`StockBridge.java`／`RealtimeMonitorService.java`，确认后端存储结构已完成改造，但发现StockBridge.java存在5处调用已不存在的旧接口（当前项目无法编译）、RealtimeMonitorService仍用deprecated批量快照方法（快照未按股票归档）两处问题；本次未修改任何代码，以上为排查与方案记录。
- **2026-09-03　B**：用户确认R11已有其他AI（C）在实施，B退出该项认领，本文档转为C的实现参考，技术内容不变；B转而认领 **R12**（低开VWAP缓存持久化修复）。
- **2026-09-03　C**：确认接手R11，按上文2.1→2.2→2.3方案实施，技术方案不变。**2.1已完成**：StockBridge.java删除了5个旧接口（getDecisionLog两个重载/getDecisionLogDates(category)/getLogCategories/getTodayDecisionLog），新增 getDecisionLogStocksForDate(dayStr) 与 getStockDecisionLog(dayStr, code)，无参getDecisionLogDates()与getDecisionLogDirPath保持不动。**2.2已完成**：RealtimeMonitorService.java的doTick()改为每支股票产出line时直接调logMonitorLine(item.code,item.name,line)，不再收批后调旧版logSnapshot(list)，mLastSnapshotAt改为在确定本轭dueForSnapshot的那一刻就更新，finishTickBatch简化为只重置并发旗位。现在只剩 **2.3（前端专属页面）** 尚未实施，项目已可正常编译。
