# B｜R8 规则引擎日志明细增强 —— 开发记录

**记录人**：B（本项目由多个AI协同开发/审查/规划，本文档及后续对应的修改记录均以"B"署名）
**状态**：已实现（源码改动完成，未做Gradle编译验证）
**关联文档**：`A-买卖逻辑优化与本地AI增强-开发计划.md`（R8一节）
**依据**：A文档举的例子——五洲交通 现价¥4.38＝VWAP¥4.38，却提示"尚未回踩确认"，原因是`minRecent`（最近10个分时点最低价）没打印出来，看不出具体卡在哪

---

## 1. 现状确认

通读 `TradingRuleEngine.java` 后发现，"结论句没有中间值"这个问题不止A举的`evaluateStarterGapUpOrFlat`一处，凡是"未触发"的分支基本都只留一句笼统结论，甚至部分方法完全不写note：

- `evaluateStarterGapUpOrFlat`：`minRecent`只在if内部局部变量，不满足条件时note里没有它——A举的例子。
- `evaluateAddHalf`：跟上面同款计算模式，`!breakWater && !pullbackHold`时**直接return空note**，外层`evaluate()`只能给"未满足加仓/满仓条件，继续观察"这种通用兜底话。
- `evaluateFullPosition`：一共6道关卡（形态日数据/影线长度/吃影线比例/水线/VWAP/放量/站稳时长），**全部6个提前return分支都是空note**，是全文件里最"沉默"的方法。
- `detectDivergenceKline`：`void`方法，纯副作用，从始至终没有任何日志痕迹——分歧K线一旦被识别就直接决定后续止损位，但识别这一刻完全无迹可查。

## 2. 修复内容（已落地到 `TradingRuleEngine.java`）

1. `evaluateStarterGapUpOrFlat`：把`minRecent`/`look`提到条件判断外面，"尚未回踩确认"的note里加上VWAP确认阈值(¥VWAP×0.999)和最近N个分时点最低价的具体数值。
2. `evaluateAddHalf`：同款处理，`!breakWater && !pullbackHold`的静默分支改为输出具体差距（水线差多少、VWAP确认阈值、minRecent）；量比未确认的note里补上现价/水线/VWAP三个数字（之前只有量比detail）。
3. `evaluateFullPosition`：6道关卡各自给出具体原因（比如"已吃掉上影线72%达标且突破水线，但未站上VWAP"这种能直接定位卡在哪一步的话），不再是清一色空note。
4. `evaluate()`：`evaluateFullPosition`/`evaluateAddHalf`未触发时，之前是**直接丢弃**它们的note、用一句通用兜底话代替——现在把这两个方法内部算好的具体原因拼进最终的note里（`[满仓]...[加仓]...`），使用者不用去翻代码就能看出满仓和加仓分别卡在哪。
5. `detectDivergenceKline`：新识别到/K线发生变化时，调用`DecisionLogger.get().logBuyLogicTrace(...)`记一条trace，把实体/振幅比例、上影线占比、命中的具体形态（滞涨/长上影/两者皆有）、算出的中点和最低点都打出来；同一根K线反复满足条件不会重复刷屏。

**未改动**：以上5处均只是"多打印中间值/多留一条trace"，判断顺序、阈值、任何一条触发条件本身一律未动。

## 3. 验证建议

同R12，B目前没有编译/设备访问能力，建议用户下次构建时留意：
- Android Studio里跑一次build，确认没有语法问题。
- 找一支之前反复出现"未回踩确认"提示的股票，观察新版note是否真的打出了`minRecent`等数值，看数值是否符合预期（比如是否确实曾跌破过VWAP×0.999）。

## 4. 变更记录

- **2026-09-03　B**：读取 `TradingRuleEngine.java`，确认"结论句缺中间值"问题覆盖`evaluateStarterGapUpOrFlat`／`evaluateAddHalf`／`evaluateFullPosition`／`detectDivergenceKline`四处（比A原文举的例子范围更广）；已实现修复，写入 `TradingRuleEngine.java`（写入过程中曾遇到一次Filesystem MCP超时，已确认该次超时未写入任何内容、文件未受影响，随后重试成功）。未做Gradle编译验证。
