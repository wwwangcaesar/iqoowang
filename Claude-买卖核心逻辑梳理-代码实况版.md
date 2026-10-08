# 买卖核心逻辑梳理（代码实况版）

> 日期：2026-09-28
> 依据：`TradingRuleEngine` / `RealtimeMonitorService` / `WatchlistManager` / `IntradayPatternAnalyzer` / `MarketDataManager` / `TradingRuleConfig` / `trading_rules.json` / `StockBridge`；前端 `index.html` 只核对了确认/忽略流程与参数面板。
> 原则：**以代码为准**。与《操盘手经验终版.md》的出入见第 9 节，已确认的实现问题见第 10 节。
> 参数均为**默认值**（`assets/trading_rules.json` + `TradingRuleConfig` 内置默认）。设备上若有 `trading_rules_override.json`（App 参数面板保存后生成），以覆盖值为准。
> 未查：`LocalAIAgent` / `AIContextBuilder`（AI 提示词内容）。

---

## 0. 总览

```
盘后：下载日K → 选股公式入池（WATCHING，固化形态日OHLC）
                     │
盘中监控（交易日 9:30-11:30 / 13:00-15:00，默认每 120 秒一轮）
                     ▼
  WATCHING ──底仓信号──▶ PENDING_STARTER ──确认──▶ STARTER
  STARTER  ──加仓信号──▶ PENDING_ADD     ──确认──▶ ADDED
  STARTER/ADDED ─满仓信号─▶ PENDING_FULL ──确认──▶ FULL
  任一持仓态 ─抛压预警─▶ PENDING_WARN ──确认──▶ 仅更新备注（见 10.1）
  任一持仓态 ─止损信号─▶ PENDING_STOP ──确认──▶ STOPPED（退出监控）
  忽略(dismiss) → 回到触发前状态
```

- **规则引擎是唯一触发者**：命中 → 立即 `markPending` + 推送通知 + 写决策日志；本地 AI 只异步补充"支持/存疑"，不阻塞、不决定是否推送。
- **App 不自动下单**：确认只更新候选池状态；买卖需在持仓页手动录入（模拟账户）。
- **每轮先评估卖出**（仅持仓态），命中即返回，本轮不再评估买入。

---

## 1. 基础量与前置条件

| 项 | 定义 |
|---|---|
| 水线 | 前一交易日收盘价（昨收） |
| 前一交易日 | 日K缓存中日期≠今天的最后一根。缓存最新日期 < 预期最近交易日 → **陈旧数据拦截**，本轮不产生任何买卖信号 |
| VWAP（分时均价） | 最后一个分时点的 `avgPrice`；缺失则 `成交额 / (成交量×100)` |
| 昨日全天真实VWAP | 异步抓取（腾讯历史分时）并落库（`prev_day_vwap` + 日期）；日期必须与"前一交易日"一致才使用；未取到时低开路径本轮跳过 |
| 日量比 dayRatio | 今日累计成交量 ÷ 5 日均量（取缓存倒数第 2～第 6 根日K，排除缓存最后一根） |
| 近5分钟量比 | 最近 5 个分时点成交量 ÷（今日分钟均量×5） |
| 量比阈值 | 1.8；开盘后 60 分钟内、收盘前 20 分钟内 ×1.5 = 2.7 |
| 放量 confirmed | 日量比 ≥ 阈值，或 近5分钟量比 ≥ 阈值 |
| 缩量 shrinkBreak | 日量比 < 0.6×阈值，且 近5分钟量比 < 0.6×阈值 |
| 涨跌停幅度 | 主板 10%；创业板/科创板 20%；北交所 30% |
| 监控时段 | 交易日 9:30–11:30、13:00–15:00（`TradingCalendar` 排除节假日）；其余时段不拉行情、不评估 |
| 待确认状态 | PENDING_* 不再跑规则引擎，只刷新水线/VWAP/量比/前低与分时缓存 |

**必须**：盘后先下载当日日K，否则次日全部信号被陈旧拦截。

---

## 2. 选股入池（买入前置）

入口：`MarketDataManager.runRealScreener → runTDXFormula`；结果由 `StockBridge.runRealScreener` 写入候选池（`addIfAbsent`，状态 WATCHING），同时固化形态日 OHLC（`pattern*`）供满仓判断使用。

- **基础过滤**：收盘价与流通市值区间（参数传入，默认 3–50 元 / 20–320 亿）；可排除创业板(30*)/科创板(68*)/ST；可排除涨跌幅≥19.9%；至少 26 根日K。
- **指标**：N=EMA(收,2)；N1=11 层 EMA(…,2)；N2=MA25+STD25；SAR(0.02, 0.2)。
- **condA（信号名"放量突破"）**：N≥N1 且 N≥N2 且 收>昨收 且 收>开 且 量≥前一日量×volMulti（默认 2.0）且 (高+低)/2 ≥ 收。
- **condB（信号名"缩量持续"）**：N≥N1 且 N≥N2 且 收[i]>收[i-1] 且 量[i]×0.75≥量[i-1] 且 收[i-2]>收[i-1] 且 量[i-2]×0.75≥量[i-1] 且 收[i-2]>收[i-3] 且 量[i-2]×0.75≥量[i-3]。即：i-2 日放量上涨 → i-1 日缩量回调（≤75%）→ i 日放量收阳。
- **基础条件**：收 ≥ SAR 且 收 ≥ 3；(condA 或 condB) 且基础条件成立才入选。
- **仙人指路**：上影线 ≥ 实体×2 且 量 ≥ 5 日均量×volMulti。默认只展示，勾选 `requireXianRenZhiLu` 才作为硬门槛。
- **评分**：60 + condA 25 + condB 15 +（N>N1×1.005）5 +（流通市值<100亿）5，封顶 99，降序。

---

## 3. 买入逻辑

买入侧**全部不要求放量**（2026-09-13 起，用户明确要求）；量比数据仍写进提示文案，仅作背景。

### 3.1 底仓（BUY_STARTER，仅 WATCHING）

按今开 vs 昨收分两条路径。

**低开路径（今开 < 昨收）**
1. 需要昨日全天真实VWAP；没取到 → 本轮不评估，下一轮重试。
2. 参照价 ref：开盘后 30 分钟内（或当日VWAP无效）= 昨日VWAP；30 分钟后 = min(昨日VWAP, 当日VWAP)。
3. 现价 ≥ ref → 立即 BUY_STARTER（无持续时长要求）；否则不介入。

**高开/平开路径（今开 ≥ 昨收）** — "重点监听"状态机（`focusWatchStatus`：NONE / WATCHING / CONFIRMED，落库）
- 条件 pullbackHold：最近 ≤10 个分时点最低价 ≥ VWAP×0.999，且 现价 ≥ VWAP。（不要求真的发生回踩，只要近 10 分钟没跌破VWAP）
- NONE → WATCHING：首次满足 pullbackHold；记录起始时间戳；推一条轻量通知（低优先级通道，不占 PENDING）；本轮无买入信号。
- WATCHING：累计"实际交易分钟"（剔除 11:30–13:00 午休）。**中途跌破VWAP不重置计时**（2026-09-13 起）。累计 ≥ 60 分钟，且**当前 tick 仍满足 pullbackHold** → BUY_STARTER，状态转 CONFIRMED。
- 所以是"起点和终点两个 tick 满足条件"，中间不要求持续站稳。
- 跨日：起始时间戳不是今天 → 重置为 NONE。当天凑不满 60 分钟就收盘的，次日从头计。

### 3.2 加仓（ADD_HALF，仅 STARTER）

满足任一即触发：
1. **突破水线**：现价 > 昨收。
2. **底仓后回踩双VWAP不破**（仅在未突破水线时判断）：addRef = max(昨日VWAP, 当日VWAP)（昨日VWAP缺失则只用当日VWAP）；最近 ≤10 个分时点最低价 ≥ addRef×0.999 且 现价 ≥ addRef。
3. **重点监听毕业加仓**：底仓是今天靠"重点监听 60 分钟"确认的（CONFIRMED 且为今日），且进入收盘前 15 分钟，现价仍 ≥ 当日VWAP。

### 3.3 满仓（BUY_FULL，STARTER / ADDED；优先于加仓判断）

需要选股形态日 OHLC（手动持仓同步进来的票没有，不参与满仓判断）。逐关：
1. 形态日上影线长度 = 最高 − max(开, 收) > 0。
2. 吃影线比例 = (现价 − max(开, 收)) ÷ 上影线长度 ≥ 70%。
3. 现价 > 昨收。
4. 现价 ≥ 当日VWAP。
5. 连续站上"分时均价线"（每个分时点与自己的 avgPrice 比）的分钟数 ≥ max(vwapConfirmMinutes 5, fullConfirmMinutes 45) = **45 分钟**。

全过 → BUY_FULL。每一关没过，note 里都会写出具体差距。

### 3.4 仅提示、不产生状态变化的买入类参考信号

- **水下V型反转**：仅 WATCHING 且现价 < 昨收；`detectVReversal` 为 BOTTOM 且"中确认"；同一反转点去重；轻量通知 + 决策日志 + AI 完整分析。不产生 Action。
- **下跌途中放量尖角（BOTTOM）**：仅 WATCHING；当天最大单分钟量 ≥ 前 5–10 分钟均量 2.5 倍，随后 2 分钟量 ≤ 尖角量 60%，形状为局部低点，此前约 15 分钟整体下跌；**不要求站上VWAP/水线**；高优先级通知 + 震动。不产生 Action。

### 3.5 买入信号后处理

- 一律追加"成交后该部分份额 T+1 前不可卖"。
- 现价触及涨停：追加"注意排队风险"；一字板或近 5 分钟量 < 峰值 5% → 追加"疑似封板，可能无法买入"。
- **没有"同日保护"**：底仓与加仓/满仓可以在同一天先后触发。

---

## 4. 卖出 / 止损逻辑

仅持仓态（STARTER / ADDED / FULL）评估。

### 4.1 评估顺序

| 序 | 规则 | 触发条件 | 结果 | 推送时机 |
|---|---|---|---|---|
| 1 | 独立破位 | 现价 < 前一交易日最低价 | 见 4.2 | 见 4.2 |
| 2 | 三级：分歧K线最低点 | 现价 < divKLow | STOP_LOSS | 立即，无量能闸门 |
| 3 | 二级：分歧K线中点 | 现价 < 中点 | 缩量 → WARN_PRESSURE；否则 STOP_LOSS | WARN 立即；STOP 仅收盘前 30 分钟 |
| 4 | 一级：峰值回撤 | 当日峰值涨幅 > 0.5%，且当前涨幅 ≤ 峰值×50%，且比峰值低 > 0.3 个百分点 | WARN_PRESSURE | 立即 |
| 5 | 涨停放巨量出货 | 现价涨停，今日累计量 ≥ 近 10 日（排除今日）均量×3 | WARN_PRESSURE | 立即 |

- 第 1 条：只要现价 < 参照价，无论结果如何都直接返回，不再落到 2/3 条。
- 第 1～4 条命中任一，本轮不再评估加仓/满仓。第 5 条只在前 4 条都没命中时检查。
- 中点取值：`divergenceMidMode` = KLINE_MID（默认）用 (分歧K线高+低)/2；RETRACE_MID 用 昨收×(1+峰值涨幅×50%)。

### 4.2 独立破位（YANG_BREAK）

参照价 = **前一交易日最低价**（2026-09-13 起，不再取 max(形态日最低价, 前一交易日最低价)）。现价 < 参照价时依次过三道闸门：

1. **全天量能闸门**：shrinkBreak → 降级 WARN_PRESSURE（立即推送）。
2. **瞬时量能闸门**：找到最近一次向下穿越参照价的分时点，该分钟量 < 前 5–10 分钟均量×1.5 → 视为缩量破位，进入"待确认破位"，窗口 3 分钟（`intradayBreakConfirmMinutes`）：
   - 窗口内不产生信号，只记录；
   - 现价收回参照价之上 → 取消（假破位已验证）；
   - 窗口满仍在参照价下方 → 确认真破位，进入第 3 关；
   - 跨日、或参照价变了 → 清除；
   - 没找到穿越点（如低开直接在下方）或放量穿越 → 按真破位处理。
3. **收盘前确认闸门**：STOP_LOSS 仅在收盘前 **15 分钟**（`patternLowStopNotifyMinutes`）内才推送并 `markPending`；之前只更新备注"盘中瞬间跌破不算…"。

### 4.3 分歧K线识别（决定二级/三级止损位）

持仓期间每轮用实时 open/high/low/price 重构当天K线。同时满足：
- 放量 confirmed；
- 实体/振幅 ≤ 0.35（滞涨/十字星），或 上影线/振幅 > 0.35。

则记录：divKHigh / divKLow = 当日最高/最低（此后随当日高低点变化继续刷新）；divMidKline = (高+低)/2；divMidRetrace = 昨收×(1+峰值涨幅×50%)。没有失效/过期机制，直到出现新的分歧K线覆盖。

### 4.4 卖出侧后处理

- **T+1**：持仓中有今日买入份额 → note 标注可卖/锁定股数；全部锁定 → 走静默低优先级通道（不震动）。
- 一级预警追加"你的实际持仓成本与浮盈亏"。
- 触及跌停且疑似封板 → 提示可能顺延到下一交易日开盘。
- **高位放量尖角（TOP）**：仅持仓时检测，条件同 3.4（方向相反）；高优先级通知 + 震动，**不产生 Action**，只作卖出参考。

---

## 5. 状态机与人工确认

- `markPending`：`prev_status` = 当前状态；`status` = PENDING_*；`ai_status` = PENDING；立即推送；入 AI 复核队列。
- `confirmPending`：BUY_STARTER → STARTER；ADD_HALF → ADDED；BUY_FULL → FULL；STOP_LOSS → STOPPED（退出监控）；WARN_PRESSURE → 只写备注。
- `dismissPending`：回到 `prev_status`（无则 WATCHING）。**无冷却机制**：条件仍成立则下一轮再次推送。
- 前端：候选卡上的"确认执行"= 标记已处理（不下单）；点卡片本身 → 预填买/卖弹窗，成交成功后自动 `confirmSignal`。
- 手动持仓同步：持仓 qty>0 而候选池状态是 WATCHING / STOPPED / MANUAL_REMOVED / 不存在 → 自动纳入并 `markStarter`（无形态日数据 → 不参与满仓判断）。
- STOPPED / MANUAL_REMOVED 不在监控范围。PENDING 状态没有超时，挂到用户处理为止。

---

## 6. AI 层（不参与触发）

- 规则命中 → 按股票代码去重入队（先进先出，单流水线，外层超时 180 秒）→ `LocalAIAgent.verifySignal` → 回填 `ai_status`（CONFIRMED / DOUBTED）与理由。
- AI 明确支持 → 追加「✅AI已确认」通知（覆盖原通知，单次长震动）；存疑不补通知；超时/出错记为存疑并写日志，规则推送仍有效。
- 其他 AI 调用（均不改变规则结果）：分时形态"判断存疑"校验（同一只 15 分钟节流）；水下反转/放量尖角的完整解读；每交易日 14:40 起（一天一次）候选池横向打分排行榜；前端"AI分析审核"手动串行复核。
- 决策日志：每股每日一个文件，保留 14 天（`DecisionLogger`）。

---

## 7. 候选池自动清理

仅 WATCHING、无持仓、本轮无信号时检查，命中任一 → `autoRemoveStale`：
1. 现价 < 昨收×(1−8%)。
2. 现价 < 前阳低（近 10 根缓存日K中最近一根阳线的最低价）。
3. 观察交易日数 ≥ 10（含入池日，按 `TradingCalendar` 数交易日）。

---

## 8. 参数表

| 参数 | 默认 | 来源 | 作用 / 实际状态 |
|---|---|---|---|
| volumeRatioThreshold | 1.8 | json | 量比阈值 |
| volumeMaDays | 5 | json | 均量天数 |
| earlyWindowMinutes / lateWindowMinutes / earlyWindowVolumeMultiplier | 60 / 20 / 1.5 | json | 开盘/收盘窗口阈值上调 |
| shadowEatRatio | 0.70 | json | 满仓吃影线比例 |
| fullConfirmMinutes | 45 | json | 满仓连续站稳VWAP分钟数 |
| vwapConfirmMinutes | 5 | json | 只参与 max(5,45)，对底仓已无作用 |
| peakRetraceRatio | 0.50 | json | 一级预警；RETRACE_MID 模式的中点 |
| divergenceBodyMaxRatio | 0.35 | json | 分歧K线滞涨判定 |
| divergenceMidMode | KLINE_MID | json | 中点算法 |
| stopNotifyMinutesBeforeClose | 30 | json | 二级止损推送窗口 |
| sellObserveMinutes | 10 | json | 仅出现在提示文案，无实际计时逻辑 |
| marketCloseHour / marketCloseMinute | 15 / 0 | json | 收盘时间 |
| mainboardLimitPct / gemStarLimitPct / bjExchangeLimitPct | 0.10 / 0.20 / 0.30 | json | 涨跌停检测 |
| candidateDeepBreakPct / candidateMaxObserveDays | 0.08 / 10 | json | 候选池清理 |
| starterPositionPct / addHalfPositionPct | 0.30 / 0.50 | json | 引擎、StockBridge、前端均无引用，仅面板展示 |
| patternLowStopNotifyMinutes | 15 | 仅代码默认 | 独立破位止损推送窗口（面板不可改） |
| vwapDualRefSwitchMinutes | 30 | 仅代码默认 | 低开路径参照价切换（面板不可改） |
| focusWatchConfirmMinutes | 60 | 仅代码默认 | 重点监听确认（面板不可改） |
| gapUpAddConfirmMinutesBeforeClose | 15 | 仅代码默认 | 重点监听毕业加仓窗口（面板不可改） |
| intradayBreakConfirmMinutes | 3 | 仅代码默认 | 缩量破位确认窗口（面板不可改） |
| fourGridDeviationPct | 5.0 | 仅代码默认 | 前端无调用，"四格原则"提醒未实现 |

硬编码：VWAP 容差 0.999；回看 10 个分时点；分歧K线上影线占比 0.35；瞬时量能倍数 1.5；放量尖角 2.5× / 淡出 60%；涨停出货 3×；一级预警峰值下限 0.5% 与回撤差 0.3。

---

## 9. 与《操盘手经验终版.md》的差异（以代码为准）

| 主题 | 文档 | 代码 |
|---|---|---|
| 底仓判据 | 水下 + 站上VWAP（持续 N 分钟） | 分低开 / 高开平开：低开=站上昨日全天VWAP即触发；高开平开=重点监听 60 分钟。不要求"水下" |
| 独立破位参照 | 前一根阳线最低价 | 前一交易日最低价（2026-09-13 用户明确要求） |
| 放量确认（9.3 列为最高优先级） | 加到所有触发条件 | 买入侧全部取消（2026-09-13）；卖出侧仍用于降级与分歧K线识别 |
| 满仓确认 | 渐进确认 30–60 分钟 | 连续站上VWAP 45 分钟 |
| 破位确认 | 放量/缩量分级 | 全天量能闸门 + 瞬时量能闸门（3 分钟窗口）+ 收盘前 15 分钟确认 |
| 二级止损 | 全仓卖出 | 非缩量时仅收盘前 30 分钟推送 |
| 正T / 同日保护（5.3①、6.1）、大盘过滤器、ATR 缓冲、移动止盈、止盈规则 | 建议项 | 引擎中未实现；仅 T+1 文案提示 |
| 仓位比例 30% / 50% | 建议值 | 引擎不产出数量；参数只在面板展示 |
| 形态二 | 推测性定义 | 以 condB 近似进入选股，买卖复用同一套逻辑，无独立参数 |

---

## 10. 已确认的实现问题

> **以下 10.1‑10.5、10.7、10.8第一条已于 2026-09-29 修复**，具体改了哪些文件、怎么改的、验证方式和局限见同目录下
> 《Claude-代码修复记录-2026-09-29.md》。本节正文保持不动（作为问题现象的历史记录），不再代表当前代码行为。
> 10.6 是设计取舍题，未改动代码；10.8 第二条（AI层是否引用starter/addHalfPositionPct）已核实为否，未作任何修改。

### 10.1 【严重】确认"抛压预警"后状态卡死在 PENDING_WARN 【2026-09-29已修复】
- `WatchlistManager.confirmPending` 的 WARN_PRESSURE 分支只 `updateNote`，不改 `status`；`clearPendingFields` 也不碰 `status`。
- 前端"确认执行"对所有 PENDING_* 都调 `confirmSignal`；从抛压卡片点开卖出弹窗成交后也会调。
- 结果：`status` 停在 PENDING_WARN（`pendingAction` 已为空）→ `RealtimeMonitorService.isPendingStatus` 为真 → 该票此后只刷新快照，**不再评估止损/加仓/满仓**；快照日志显示"待确认·?（触发价¥0.00…）"。
- 之后再点"忽略"：`prev_status` 已被清空 → 落到 WATCHING → 被手动持仓同步拉回 STARTER（丢失 ADDED/FULL 状态）。
- 修复点：WARN 分支在 `clearPendingFields` 之前把 `status` 恢复为 `cur.prevStatus`。

### 10.2 量能闸门口径偏严，STOP 难以走到（按代码推断，未用历史日志统计） 【2026-09-29已修复（时间进度折算部分）】
- 日量比 = 今日累计量 ÷ 全天均量，**未按时间进度折算**，盘中早段天然偏小，shrinkBreak 易成立。
- 独立破位的 STOP 只允许在收盘前 15 分钟推送，而该窗口正处于"收盘前 20 分钟阈值×1.5"内：阈值 2.7，shrinkBreak 线 1.62。两个窗口叠加，独立破位更多以 WARN_PRESSURE 出现，STOP_LOSS 需要重量日或尾盘放量才能走到。
- 文档 6.3 建议的"最近5分钟 vs 过去 N 日同一时段均量"这一口径未实现。

### 10.3 忽略信号无冷却 【2026-09-29已修复】
`dismissPending` 只回退状态。条件仍成立时（低开路径现价 ≥ ref；高开路径 `focusWatchStatus` 仍为 CONFIRMED；止损仍在窗口内）下一轮 tick 会再次推送。

### 10.4 提示文案与实际条件不符 【2026-09-29已修复（文字部分），面板缺参5参数也已补上】
- 满仓 note 写"+放量(…)全部技术条件满足"、累计阶段写"技术条件（吃影线/水线/VWAP/放量）均已达标"，但满仓早已不检查放量；该 note 也是 AI 复核的输入。
- `TradingRuleEngine` 类头注释仍写"水下+站上VWAP→底仓""吃掉上影线+放量→满仓""全触发条件含放量验证"。
- `RuleResult.notifyImmediate` 字段注释（"破位/三级立即推送；二级中点仅收盘前30分钟"）与现行独立破位（收盘前 15 分钟）不符。
- 参数面板 `vwapConfirmMinutes` 的说明"底仓信号需要连续站上分时均价多少分钟"与现行底仓逻辑不符；面板缺少第 8 节标"面板不可改"的 5 个参数。

### 10.5 待确认破位窗口期间提示丢失 【2026-09-29已修复】
`evaluateStopLoss` 在窗口内返回的是 `Action.NONE` 的 pending 结果，`evaluate()` 只在 `action != NONE` 时采用 stop 结果，因此"确认窗口中：已等 N 秒…"的 note 被丢弃；只有进入窗口那一次写了 `logBuyLogicTrace`。

### 10.6 高开票底仓与加仓几乎无间隔 【设计取舍，2026-09-29未改动】
高开/平开票底仓要等 60 分钟确认，此时现价大概率已 > 昨收。确认底仓（STARTER）后的下一轮 tick，`breakWater` 天然成立 → 直接 PENDING_ADD。叠加 3.5 的"无同日保护"，底仓与加仓可能同日先后触发。

### 10.7 选股"排除涨跌停"阈值 【2026-09-29已修复】
`exLT` 用 `|涨跌幅| ≥ 19.9%`，对 10% 涨跌停的主板实际不会过滤任何票。

### 10.8 未核实 【第一条 2026-09-29已根据外部文档修复（仍建议实测对拍），第二条已核实为否】
- 新浪源日K（腾讯失败时的兜底）`volume` 字段单位若为"股"，而腾讯为"手"，则由新浪补齐的股票日量比会偏小 100 倍。未实测，可对比同一只票缓存日量与行情 `volume` 核对。
- `LocalAIAgent` / `AIContextBuilder` 是否引用 `starterPositionPct` / `addHalfPositionPct`。

---

## 附：关键代码位置

| 主题 | 位置 |
|---|---|
| 规则总入口 | `TradingRuleEngine.evaluate()` |
| 底仓 | `evaluateStarter` → `evaluateStarterGapDown` / `evaluateStarterGapUpOrFlat` |
| 加仓 / 满仓 | `evaluateAddHalf` / `evaluateFullPosition` |
| 止损 / 预警 | `evaluateStopLoss`、`buildStopLoss`、`buildWarnPressure`、`checkLimitUpVolumeSurge` |
| 分歧K线 | `detectDivergenceKline`、`computeStopMid` |
| 量能 | `checkVolume`、`TradingRuleConfig.effectiveVolumeThreshold` |
| 分时形态 | `IntradayPatternAnalyzer.detectVReversal` / `detectVolumeSpikeReversal` / `checkBreakoutVolume` / `summarize` / `summarizeFullDay` |
| 轮询与推送 | `RealtimeMonitorService.doTick` / `evaluateAndAct` / `doVerify` |
| 状态机 | `WatchlistManager.markPending` / `confirmPending` / `dismissPending` |
| 选股 | `MarketDataManager.runRealScreener` / `runTDXFormula` |
| 候选清理 | `RealtimeMonitorService.checkStaleCandidate` |
| 参数 | `TradingRuleConfig`、`assets/trading_rules.json`、`index.html` 的 `TRADING_CONFIG_FIELDS` |
