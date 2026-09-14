# B｜R1-R9/R12 完成度系统审查报告

**记录人**：B
**审查范围**：A文档标记"已完成"的全部条目（R1/R2/R3/R4/R6/R7/R8/R9/R12），不含仍在进行中的R11（C）和仅完成设计的R10（A）
**审查方法**：逐文件通读现有代码（非增量diff，每个文件都完整重读一遍当前磁盘上的真实内容），沿调用链手工走查交互路径，不依赖任何一份变更记录里"我改了什么"的自述
**审查结论（先说结论）**：**代码层面高度一致、逻辑自洽，未发现功能性bug**。但**全程无人做过Gradle编译验证**，这是当前最大的风险点，见第3节。

---

## 1. 审查过的文件与结论

| 文件 | 涉及项 | 结论 |
|---|---|---|
| `WatchlistManager.java` | R12(B)、R3(D) | 通过。`DB_VERSION` 5→6→7 两次升级衔接正确，`onUpgrade`分支互不覆盖；`focus_watch_*`/`prev_day_vwap*`字段的存(`saveTrackState`)/取(`loadTrackState`/`fromCursor`)/导出(`toJson`)三处全部对齐，新增的`getLongOrZero`辅助方法与既有的`getDoubleOrZero`/`getStringOrNull`风格一致 |
| `TradingRuleConfig.java` | R2/R3(D) | 通过。3个新配置项(`vwapDualRefSwitchMinutes`/`focusWatchConfirmMinutes`/`gapUpAddConfirmMinutesBeforeClose`)在声明/`applyUpdates`(读)/`toJson`(写)三处都已补全，不会出现"能改却存不上"或"存的字段读不出来"的半截实现 |
| `TradingRuleEngine.java` | R4(A)、R2/R3(D)、R3读法修正(B)、R8(B) | 通过。逐条验证了：R4的`max(形态日最低价,前一交易日最低价)`口径；R2的双VWAP参照(`addRef`)只用在底仓/加仓、正确排除了满仓判断(满仓有自己独立的操盘手方法论依据，不应引入双参照)；R3改成"先底仓后加仓"读法后，`evaluateStarterGapUpOrFlat`只保留1小时确认入口，`evaluateAddHalf`新增的`focusWatchGraduation`分支有`isSameDay`守卫，不会跨日误触发；gap-down路径的`state.focusWatchStatus`永远是初始值NONE，两条底仓路径互不干扰 |
| `RealtimeMonitorService.java` | R1(B)、R3通知(D)、R6/R7(A) | 通过。三方改动分别落在`isWithinTradingHours`/`computeObserveDays`(B)、`createChannel`新增`CHANNEL_ID_FOCUS_WATCH`+`fireFocusWatchNotification`(D)、`applyCostBasisNote`+`fireQuietAlert`+`fullyT1Locked`分支(A)三处不同位置，`evaluateAndAct`里的调用顺序（T1标注→成本标注→推送窗口判断→选常规/静默通道）没有互相踩踏 |
| `MarketDataManager.java` | R1(B) | 通过，`computeExpectedTradeDate()`按预期用`TradingCalendar`循环回退，未被其他改动触及 |
| `DatabaseManager.java` | 无改动，被R6/R7引用 | 通过，`getSellableQuantity`/`getPositionByCode`签名与调用方期望一致 |
| `StockMasterApp.java`／`MainActivity.java`／`TradingCalendar.java`／`trading_calendar.json` | R1(B) | 通过，初始化顺序（TradingCalendar先于MarketDataManager）正确，年底提醒逻辑独立不影响主流程 |

## 2. 顺带发现的1处文档性瑕疵（不影响功能）

`DatabaseManager.clearAllTradingData()` 的注释仍写着"决策日志...有自己独立的14天保留机制"——这是R11改造前的旧数字，R11已经把保留期改成7天。纯注释问题，不影响任何实际行为，下次有人经过这个方法顺手改一下即可，不需要专门排期。

## 3. 最大的风险点：全程无编译验证

R1/R4/R6/R7/R8/R12全部标注"未做Gradle编译验证"，R2/R3也没有任何一条变更记录提到编译过。本次审查是**通篇人工读代码**做的逻辑走查，能发现调用链、字段一致性、时序这类问题，但**读代码不能100%替代编译**——尤其是：
- 跨文件的方法签名变更（比如B给`evaluateAddHalf`加了`DivergenceState state`参数）如果有遗漏的调用点，人工审查可能漏看，编译器不会
- import遗漏、拼写错误这类纯语法问题

**建议**：找一个能跑Gradle的环境（Android Studio，或者Claude Code接管这个项目）编译一次。这一步我这边的Filesystem连接器做不了，只能读写文件，不能执行命令。

---

## 4. 变更记录

- **2026-09-10　B**：受用户委托对R1/R2/R3/R4/R6/R7/R8/R9/R12做系统审查，逐文件重读现有代码（非依赖变更记录自述），走查关键调用链，未发现功能性bug；发现1处不影响功能的过期注释；标记出全程未做编译验证这一最大风险点，建议下一步编译验证。
