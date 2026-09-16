# D｜分时图经验规则功能实现方案（基于B研究成果，用户5问已确认）

**记录人**：D（本文档及对应代码改动以"D"署名）
**认领任务**：`B-分时图经验规则研究与实施方案.md` 第7节建议实施顺序中的**步骤3（止损真假破位精细化）**

> **【2026-09-14 认领范围修正】** 本文档最初写的是"认领第7节全部4步"，这不符合本项目多AI协作的规矩——等于把B的活也一起占了。现修正为：D只认领**步骤3（止损精细化）**。
>
> 认领前已核实B的实际进度：`B-分时图经验规则研究与实施方案.md` 状态仍为"研究+方案阶段，未写任何代码"；代码侧`TradingRuleConfig.java`无任何分时相关配置项、`AIContextBuilder.java`无`buildIntradayPatternContext`，确认B尚未开始任何一步实现。因此剩余步骤**全部处于未认领状态**，D只取其一，其余明确让出：
>
> | 步骤 | 状态 | 认领人 |
> |---|---|---|
> | 1. Layer1 `IntradayPatternAnalyzer` | ✅已完成 | D |
> | 2. `WisdomManager`话术种子（通道A） | ✅已完成 | D |
> | 3. 止损真假破位精细化 | ✅已完成 | D |
> | **4. 水下买入通知路径** | **本次认领，进行中（用户直接指定）** | **D** |
> | 5. `AIContextBuilder`+`LocalAIAgent` Context Block（通道B） | 未认领，**留给B** | — |
> | 6. 涨停巨量新检测点 | 未认领，**留给B** | — |
>
> 【2026-09-15追加】用户直接指示步骤4由D接手，步骤5/6仍留给B，不是D自己重新评估后扩大认领范围——记录清楚避免误解协作规矩。
>
> 给B的交接提示：步骤1的`IntradayPatternAnalyzer`三个方法（`detectVReversal`/`checkBreakoutVolume`/`checkVolumeSurgeAtLimit`）已可直接调用，输入输出字段见该类注释；步骤6需要的换手率数据**拿不到**（流通股本无公开访问入口，`MarketDataManager.getStockCap`是private），已按本文档第1.3节降级为只算成交量倍数，不要再花时间找这个数据。

**状态**：方案已完成，代码尚未开始（写完本文档后立即按顺序开始）
**依据**：`B-分时图经验规则研究与实施方案.md`（研究结论+架构设计）、该文档第5节用户"S:"批注（5个问题的确认答案）

---

## 0. 先说清楚：这份方案跟B原方案的3处不同

B的研究（第1-3节）和三层架构（第3节）D完全照搬，没有异议。但用户在第5节的"S:"批注里，有3处比B自己的倾向性建议更具体，直接决定了实现方式，必须先摆清楚：

1. **问题1（尖角反转水下买入）**：B的倾向是"先不让它独立触发买入动作，只作为AI解读现有信号时的参考维度"。但用户的实际批注是——**这是一条独立的信息推送，不是给现有信号"加分"**：要专门推送一条决策通知，把昨日最低价、水线价格、当前分时均价这些关键数字全部列出来，还要有本地AI针对当前分时图的完整分析，让用户自己看着这些信息判断要不要水下买入。这比"参考维度"更具体：需要新建一条独立的通知+决策日志路径，而不是塞进已有信号的AI补充分析里。
2. **问题4（是否单独开配置分组）**：用户明确否决了B"单独开一个配置分组方便调参"的倾向，理由是"配置里的东西我基本不用"，要求这套逻辑直接并入现有买卖逻辑，一起共存。这意味着新增的配置项就放进`TradingRuleConfig.java`跟`vwapDualRefSwitchMinutes`这些字段平级，不单独起一个JSON分组。
3. **问题5（AI如何学习）**：用户要求"话术相关"的学习方式——这明确指向项目里已经存在的`WisdomManager`"话术学习"机制（`learnWisdom`/`buildInjectBlock`），不是简单地把研究结论硬写进system prompt了事。D读了`LocalAIAgent.java`/`WisdomManager.java`全文确认了这套机制的具体规则（见第4节）。

---

## 1. Layer 1：`IntradayPatternAnalyzer.java`（新建，纯计算）

对应B方案第3节Layer1，D把输出结构具体到字段级：

```java
public class IntradayPatternAnalyzer {
    public static class VReversal {
        public boolean detected;
        public String direction;      // "BOTTOM"（尖角反转向上，对应买入方向）或 "TOP"（冲高回落）
        public int extremeIndex;      // 极值点在points里的下标
        public double extremePrice;
        public double volRatio;       // 反转点量能 / 触发点前5-10分钟均量
        public int sustainMinutes;    // 反转后价格没有跌破极值/未再新低（顶部则是没有再创新高）已经维持的分钟数
        public boolean strongConfirm; // 是否已放量突破当日高点（顶部形态无此概念，恒为false）
    }
    public static class BreakoutCheck {
        public boolean isRealBreak;   // true=真突破/真破位，false=量能不够，视为假突破
        public double instantVolRatio;// 穿越那一刻附近量能 / 前5-10分钟均量
        public int confirmWindowMinutes; // 用户确认的3分钟——假突破需要等这么久看是否被拉回
    }
    public static class VolumeSurgeCheck {
        public double multipleVsHistoryAvg; // 今日累计成交量 ÷ 近5-10日日均成交量
        public double turnoverPct;          // 今日换手率（需要流通股本，见1.3节数据来源说明）
        public String zone;                 // "ACCUMULATION"(低位) / "DISTRIBUTION"(高位) / "NEUTRAL"
    }

    public VReversal detectVReversal(List<MinutePoint> points) {...}
    public BreakoutCheck checkBreakoutVolume(List<MinutePoint> points, double keyLevel, int confirmWindowMinutes) {...}
    public VolumeSurgeCheck checkVolumeSurgeAtLimit(long todayCumVolume, double[] recentDailyVolumes, double floatShares) {...}
}
```

**1.1 尖角反转检测算法**：在`points`里找局部极值（连续3个点方向反转即认定为极值候选，避免单点噪声），极值点量能 = 该分钟`volume`（必要时并入前后1-2分钟平滑），基准均量 = 极值点之前5-10分钟`volume`的平均值，量能比≥1.5倍且**连续3-5根量能柱都放量**（不是单根）才算弱确认；之后逐分钟检查价格是否跌破极值（顶部则是新高）来累计`sustainMinutes`；`strongConfirm`额外检查是否放量突破当日high。三级确认节奏（弱/中/强）直接对应B研究1.1节，不额外增减条件。

**1.2 真假突破量能判据**：给定关键价位`keyLevel`（比如前一交易日最低价），找`points`里价格穿越这个价位的那个分钟，算该分钟量能相对前5-10分钟均量的倍数，≥1.5-2倍视为真突破/真破位；不足则`isRealBreak=false`，同时把用户确认的`confirmWindowMinutes=3`带出去，供调用方（见第2节）决定是否要等3分钟确认窗口。

**1.3 涨停放巨量倍数**：`multipleVsHistoryAvg`直接算，不需要盘口数据。`turnoverPct`（换手率）需要流通股本数据——**这里有个数据缺口要如实说明**：项目现有的`EastMoneyApi`/`MarketDataManager`选股结果里带流通市值（`cap`字段，已转换为亿），但流通股本≠流通市值/股价这么简单换算（还要考虑不同时点股价），需要确认`MarketDataManager.getCachedKline()`或选股结果JSON里是否已经有可直接用的流通股本字段，如果没有，换手率这一项先降级为"不计算，只展示成交量倍数"，不能拿市值除股价拼凑一个不准的换手率出来（详见第7节风险清单，实现前会先去确认这个数据源问题，不会先假设有数据就写代码）。

---

## 2. Layer 2：`evaluateStopLoss()`精细化 + 涨停巨量新检测点

对应B方案第3节Layer2，用户已确认问题2/3。

**2.1 真假破位精细化**：`evaluateStopLoss()`里"独立破位"（跌破前阳线最低价）和"分歧K线最低点"两个分支，触发跌破的那一刻，除了现有的`vol.shrinkBreak`（全天口径），**新增**调用`IntradayPatternAnalyzer.checkBreakoutVolume(minutePoints, keyLevel, 3)`。两个信号是**互补而非互斥**关系（B方案原话，D完全同意）：
- 全天量比正常（`!vol.shrinkBreak`）+ 瞬时量能比也达标 → 维持现状，直接触发止损，不额外等待
- 全天量比正常，但瞬时量能比不达标（这一下跌破本身是缩量完成的）→ **新增**：不立即触发，改为记一条"待确认破位"状态，等`confirmWindowMinutes`(3分钟)后重新检查价格是否仍在参照价下方；3分钟后仍破位才真正触发止损，3分钟内收回则视为假破位，继续持有观察（对应用户举的例子）。
- 全天量比本来就是缩量（`vol.shrinkBreak`为true）→ 沿用现有逻辑（已经是观察级别，不改动）。

这是**对现有触发条件的精细化，不是新增独立信号**，符合用户问题4"直接并入现有逻辑"的要求。

**2.2 涨停巨量新检测点**：在`RealtimeMonitorService`的tick循环里，对HOLDING状态且**今日涨停**的股票，新增调用`IntradayPatternAnalyzer.checkVolumeSurgeAtLimit()`，倍数落在B研究1.3节的"出货"区间（≥3-5倍均量）时，触发**WARN_PRESSURE**级别（用户已确认，不是STOP_LOSS）。这条走现有WARN_PRESSURE的`markPending`正常确认流程，不是新的信息展示类型。

---

## 3. Q1：水下买入信息展示（独立于Layer2，新增一条通知+日志路径）

这是本次改动里**唯一需要新建通知路径**的部分，仿照R3阶段`fireFocusWatchNotification`的模式，但内容更丰富：

- 触发条件：WATCHING状态候选股，`IntradayPatternAnalyzer.detectVReversal()`检测到`BOTTOM`方向反转且达到"中确认"（站稳分时均价不再回落）及以上，**且**现价仍低于现有底仓判断要求的参照价（"水下"）——注意这条**不经过、也不修改**现有的`evaluateStarterGapDown`/`evaluateStarterGapUpOrFlat`，是完全独立的检测分支，不会让水下的股票绕过现有规则拿到`BUY_STARTER`。
- 触发后：
  1. 调用`LocalAIAgent`新增方法（类似`verifySignal`但不产出"确认/不确认"二元判断，只产出解读文本）对当前分时形态做完整分析
  2. 决策日志写入：昨日最低价、水线（昨收）、当前分时均价、反转量能比、AI完整分析文本，全部落进`DecisionLogger`（复用现有`logNote`或新增一个专门方法，签名不变的原则见D4）
  3. 推送轻量通知（复用R3那套独立低优先级通道的思路，新开一个专用小通道或直接复用`CHANNEL_ID_FOCUS_WATCH`——两者都是"信息展示、非交易动作"性质，D倾向直接复用避免再开一个通道，减少改动面）
- 用户看到通知/日志后，是否要买，通过App**现有的**"模拟买入"手动录入流程自己操作，系统不提供快捷确认按钮（避免看起来像是系统在替用户做水下买入的决定）。

---

## 4. Layer 3：AI经验注入——两条互相独立的通道

D读完`LocalAIAgent.java`/`WisdomManager.java`全文，确认现有"话术学习"机制的具体规则后，把用户问题5的要求拆成两条**性质不同、都要做**的通道，不能只做一条：

**通道A（一次性写入，长期复用）——用`WisdomManager`机制**：`WisdomManager`已有`ensureDefaultWisdom()`在库为空时预置默认话术，D新增一个平行方法`ensureIntradayPatternWisdomSeeded()`，**不受`hasAny()`限制**（否则已经用过的老安装永远不会拿到这批新知识），改成检测"是否已经写过这一批特定内容"（比如查有没有summary以固定前缀开头的记录）来判断是否需要补种。把B研究结论里可靠性高、能转成客观规则的三条（V型反转三级确认、真假突破量能判据、高位巨量出货判据），각각拆成简短条目、打上对应`category`（尖角反转→GENERAL或BUY_STARTER，真假破位→STOP_LOSS，涨停巨量→WARN_PRESSURE），调用`addEntry()`写入。写入后自动进入现有`buildInjectBlock(actionKey)`按类型过滤注入的流程，不用改注入逻辑本身——这正是"适配本地AI思考能力"的现成机制：按判断类型过滤+条数/字符数双重上限（`MAX_INJECT_ENTRIES=15`/`MAX_INJECT_CHARS=1200`），不会因为新增这几条把prompt拖长到超出4B模型能力。

**通道B（每次判断时的具体数字，不是知识）——新增Context Block**：`AIContextBuilder`新增`buildIntradayPatternContext(code)`，把`IntradayPatternAnalyzer`对**当前这一刻**算出的客观数字（尖角反转是否检测到/量能比/维持分钟数，破位量能比等）格式化成文本块，接入`verifySignal`的`buildVerifyPrompt()`（现有"量化指标："那一行之后新增一行"分时形态："）。这是B方案原有的Layer3设计，D没有改动，只是明确它和通道A是两回事——通道A是"AI要记住的规则"，通道B是"AI这一刻看到的具体现象"，跟现有`metrics`(量化指标)和`WisdomManager`话术注入两者本来就分开的架构完全一致，不是D新发明的区分。

---

## 5. 数据来源确认（动手前必须先查，不能假设）

- `points.volume`（分钟增量）/`avgPrice`（分时VWAP）：已具备，D此前修复过取值bug，B已核实换算逻辑可靠。
- 近5-10日日均成交量：`MarketDataManager.getCachedKline()`的日K数据，需要确认实际存不存这份缓存、字段名是什么——**这是D在写Layer1代码前第一步要去读的文件**，方案里先不假设字段名。
- 流通股本（算换手率用）：如第1.3节所述，如果找不到可靠数据源，换手率这一项会明确降级/跳过，不编造。

> **B核实结果（2026-09-14，发现D尚未开始写Layer1代码，直接把这一步预先查完，避免D重复投入时间去查同一件事）**：
> 通读了 EastMoneyApi.java 全文和 MarketDataManager.java 全文，结论分两层：
> 1. 直接的换手率字段确认拿不到。MarketDataManager.KlineBar 确实有 turnRate 字段，数据库schema也有 turn_rate 列，但 insertKlineFromTencent/insertKlineFromSina 写入时都把它硬编码成零，没有任何地方写过真实值进去，所以取回来的turnRate永远是0。
> 2. 但可以推算出一个近似值：stock_list_cache表同时存了cap（流通市值，亩）和price（同一行的快照价），两者相除就能反推出流通股本。股本变化很慢，即使快照旧一点也不影响准确度，只是依赖用户跑过至少一次盘后下载，没跑过的代码查不到这张表，需要优雅降级。
> 3. 额外发现一个名字陷阱：EastMoneyApi.QuoteData里叫做turnover的字段，实际存进去的是成交额而不是换手率，建议避开，不要沿用。
>
> 结论：如果要实现换手率，用第2条的推算方式从 stock_list_cache 推算 floatShares，拿不到就传0让调用方跳过换手率计算。

---

---

## 2.1-详 【本次认领】止损真假破位精细化——实现方案

### A. 要解决的问题

用户原话（B研究1.2节引用）：昨日涨幅不大、今日跌破昨日最低价，不该无脑触发卖出。现有`evaluateStopLoss()`已经有一个相关但**粗糙**的机制：`vol.shrinkBreak`用的是**全天**口径的量比（日量比/5分钟量比），会被全天其他时段的量能稀释掉——一支股全天成交活跃、但就跌破那一下是缩量砸下来的，现有机制完全看不出来。

### B. 与现有机制的关系：互补，不是替换

这一点按B研究第3节Layer2原话执行，D完全同意，不做任何改动：`vol.shrinkBreak`（全天口径）**保留不动**，新增的瞬时量能比只是在它之外**多一道检查**。三种组合的处理：

| 全天量比(`vol.shrinkBreak`) | 瞬时量能比(新增) | 处理 |
|---|---|---|
| 正常（非缩量） | ≥1.5倍，达标 | **真破位**，维持现状直接触发止损，不额外等待 |
| 正常（非缩量） | <1.5倍，不达标 | **新增分支**：这一下是缩量砸下来的，进入待确认状态，等3分钟后再看 |
| 已缩量 | 任意 | 沿用现有逻辑（本来就已经降级为观察），不改动 |

### C. 待确认状态的具体流转

这是本步骤的核心，需要**跨tick记住**"几分钟前发生过一次缩量破位"，机制照搬 R3 那次`focus_watch_start_time`的写法（同一种模式，D熟悉其边界）：

1. **首次检测到缩量破位**：记`pendingBreakStartTime=当前时间戳`、`pendingBreakRef=本次破的参照价`，**不触发止损**，note写清楚"缩量跌破，给N分钟确认窗口观察是否被拉回"。
2. **后续每个tick**：
   - 先做**跨日安全重置**（`pendingBreakStartTime`不是今天就清掉重新判）——跟R3同样的坑，不能带着昨天的计时误判今天。
   - 再做**参照价变更检查**：若`pendingBreakRef`跟当前算出的参照价不一致（比如形态日变了、R4的`max(形态日最低,前一交易日最低)`变了），说明这是另一回事，清掉重计，不能拿旧参照价的计时结果套到新参照价上。
   - 未满`intradayBreakConfirmMinutes`（3分钟，用户已确认）：维持待确认，note写明已等多久。
   - 满3分钟：看现价
     - 已收回参照价**之上** → **假破位**，清除状态，不触发止损，继续持有（这正是用户举的那个例子要的效果）
     - 仍在参照价**之下** → **真破位确认**，正常触发止损

注意这3分钟计时用**时间戳差**而不是tick次数（tick间隔2分钟，数次数会很不准）；也**不**复用R3那个剔除午休的`computeTradingMinutesExcludingLunch`——这3分钟是"看盘口会不会马上拉回来"的短窗口，跨午休本身就意味着盘口已经断了，继续计时没意义，跨午休的情况直接当过期处理即可（实现上自然满足：午休期间无tick，13:00后第一个tick时时间差已远超3分钟，会直接进入确认分支按当时实际价格判断，符合预期）。

### D. 作用范围：只给"独立破位"，不给分歧K线最后防线

`evaluateStopLoss()`里有多个止损分支，D**只在"独立破位"（跌破形态日/前一交易日最低价，R4方案C口径）这一支上接入**，分歧K线中点/最低点那几支**不动**。理由：
- 用户原始需求举的例子（跌破昨日最低价）对应的就是独立破位这一支，诉求明确。
- 分歧K线最低点在《操盘手经验终版》里的定位是"最后防线"，给它加3分钟缓冲与"最后防线"的语义相悖，风险偏高。这属于扩大适用范围，不在用户确认的范围内，D不自行加码（R9原则）。
- 如果以后实盘发现最后防线也需要这个缓冲，再单独提出确认，不在本次顺手做。

### E. 具体改动

| 文件 | 改动 |
|---|---|
| `TradingRuleConfig.java` | 新增`intradayBreakConfirmMinutes=3`（跟现有字段平级，不单开分组，对应用户问题4批注），`applyUpdates`/`toJson`同步 |
| `WatchlistManager.java` | `DB_VERSION` 7→8，`watchlist`表新增`pending_break_start_time INTEGER`/`pending_break_ref REAL`两列，配套`WatchlistItem`/`fromCursor`/`saveTrackState`/`loadTrackState`/`toJson` |
| `TradingRuleEngine.java` | `DivergenceState`新增两字段并同步进`copyState()`；`evaluateStopLoss()`独立破位分支接入`IntradayPatternAnalyzer.checkBreakoutVolume()`与待确认状态机 |

不改`RealtimeMonitorService`：`stateUpdate`每软tick会自动落库（R3时已验证过这条链路），新状态字段搭这个顺风车即可。

### F. 与B待做步骤的边界

本步骤**不**碰`evaluateStarter*`系列、**不**碰通知链路、**不**碰`AIContextBuilder`/`LocalAIAgent`——这三块分别是B的步骤4/5的地盘，避免冲突。唯一可能重叠的是`TradingRuleConfig`和`WatchlistManager`的DB版本号：如果B也需要加列，请在D这次`DB_VERSION=8`的基础上继续往上加，不要并行改同一个版本号。

---

## 3-详 【本次认领】水下买入通知路径——实现方案

### A. 精确对应用户原话

"水下买入的逻辑触发决策通知和信息展示，要将所有关键信息都列出来，比如昨日最低价，水线价格，现在的分时均价价格，并将本地ai针对当前分时图的分析内容完整展示,S通过这些分析自主判断是否水下买入"——三个要点：①独立的通知+信息展示，不是塞进现有信号的参考维度；②必须列出昨日最低价/水线/分时均价这几个具体数字；③本地AI要给"完整分析"而不是"支持/存疑"这种二元结论，用户自己看着判断，系统不代做买卖决策。

### B. 触发条件

- 只针对`STATUS_WATCHING`（观察中，尚未买入）的候选股。
- `IntradayPatternAnalyzer.detectVReversal()`检测到`BOTTOM`方向反转，且达到"中确认"（价格站稳分时均价线不再回落）——弱确认太容易噪声误判，强确认（放量突破当日高点）门槛太高会经常等不到，中确认是"这事有点意思，值得让用户看一眼"的合理阈值。为此给`VReversal`加一个`midConfirm`字段（Layer1的小扩展，不影响B要用的另外两个方法）。
- **且**现价仍低于水线（`waterLine`＝昨收）——这是本方案对"水下"最朴素、跟项目里其它地方口径一致的定义，不借用低开路径那套更复杂的双参照逻辑。
- 这条检测**完全独立于**`evaluateStarterGapDown`/`evaluateStarterGapUpOrFlat`，不改、不读、不写这两个方法的任何状态，不会让水下的股票绕过现有规则拿到`BUY_STARTER`。

### C. 去重：同一次反转只通知一次

反转点在分时数据里的下标（`extremeIndex`）每个tick都会变（分时点列表一直在变长），不能拿下标去重。改用反转点自己的`time`字段（"10:15"这种，同一根K线时间不变）+日期拼一个key存进`DivergenceState`，跟"重点监听""待确认破位"用的是同一套持久化模式。

### D. 通知与AI分析的时序：先推送客观数字，AI分析异步补充

参照`markPending`→`updatePendingAiResult`的既有节奏：检测到中确认的那一刻**立即**推送轻量通知+写决策日志（昨日最低价/水线/当前分时均价/反转量能比这些客观数字，此时就都有了），不等AI；AI的"完整分析"异步跑，跑完后**追加**进同一支股票的决策日志（不新开一条，保持"点开一支股票看完整时间线"的体验），完成后可以再推一条"AI分析已就绪"的小提示（复用同一通知ID更新，不新增一条通知刷屏）。

### E. AI角色：解读，不是判断——需要一个新方法

现有`verifySignal()`产出的是"支持/存疑"二元结论，跟用户要的"完整分析、S自主判断"性质不同，不能直接复用。新增`LocalAIAgent.analyzeIntradayReversal(context, cb)`，prompt明确要求"只做形态解读，不要给买或不买的结论"，输出交给用户自己看。

### F. 通知渠道：复用R3那条，不新开

跟"进入重点监听"一样，这是信息展示，不是需要确认/忽略的买卖决策，不占用PENDING状态位。直接复用`CHANNEL_ID_FOCUS_WATCH`，不为这一个场景再开一条新通道——两者语义（低优先级、纯提示）一致，减少改动面。

### G. 具体改动

| 文件 | 改动 |
|---|---|
| `IntradayPatternAnalyzer.java` | `VReversal`新增`midConfirm`字段，`detectVReversal()`里算出来 |
| `WatchlistManager.java` | `DB_VERSION` 8→9，新增`underwater_reversal_notified_key TEXT`一列（去重用） |
| `TradingRuleEngine.java` | `DivergenceState`新增`underwaterReversalNotifiedKey`字段；`RuleResult`新增`underwaterReversal`（VReversal对象，null表示未检测到）；`evaluate()`的WATCHING分支里，调用`evaluateStarter`前后新增独立检测（不改动`evaluateStarter`本身） |
| `DecisionLogger.java` | 新增`logUnderwaterReversal(code, name, text)`（首次客观数字）+ 复用现有追加机制写入AI分析结果 |
| `agent/LocalAIAgent.java` | 新增`analyzeIntradayReversal(context, cb)` |
| `RealtimeMonitorService.java` | 检测到后：写日志→推送通知→异步起AI分析→AI完成后追加日志+更新通知 |

不碰`evaluateStopLoss`、不碰步骤5/6的地盘。

---

## 6. 具体改动清单

| 文件 | 改动性质 |
|---|---|
| `util/IntradayPatternAnalyzer.java` | 新建，Layer1 |
| `util/TradingRuleEngine.java` | `evaluateStopLoss()`接入瞬时量能比+3分钟确认窗口；新增水下V反转检测分支（不改动现有底仓/加仓方法） |
| `util/RealtimeMonitorService.java` | 涨停巨量检测点；水下反转通知 |
| `util/DecisionLogger.java` | 视情况新增一个专门的水下信号日志方法（签名不动现有方法） |
| `util/TradingRuleConfig.java` | 新增配置项，跟现有字段平级存放（不单独开分组），含`intradayConfirmWindowMinutes`(默认3)等 |
| `util/WisdomManager.java` | 新增`ensureIntradayPatternWisdomSeeded()` |
| `agent/AIContextBuilder.java` | 新增`buildIntradayPatternContext(code)` |
| `agent/LocalAIAgent.java` | `buildVerifyPrompt`接入分时形态context；新增水下反转专用分析方法 |

## 7. 建议实施顺序（沿用B第7节，仅第2步细化了确认窗口逻辑）

1. 确认数据源（第5节）→ `IntradayPatternAnalyzer`基础能力
2. `WisdomManager`话术种子（通道A）——纯新增，独立于其他步骤，可以和步骤1并行
3. 止损精细化（Layer2，2.1节）
4. 水下买入通知路径（第3节）
5. `AIContextBuilder`+`LocalAIAgent`的Context Block接入（通道B）
6. 涨停巨量新检测点（2.2节，最后做，依赖换手率数据源确认结果）

---

## 变更记录（D）

- **2026-09-14　D**：【认领范围修正】按用户要求重新按协作规矩划分分工。核实B进度（B文档状态仍为"未写任何代码"，代码侧`TradingRuleConfig`无分时配置项、`AIContextBuilder`无`buildIntradayPatternContext`，确认B尚未开始任何一步）后，把D的认领从"全部4步"收缩为**只认领步骤3（止损真假破位精细化）**，步骤4/5/6明确让出给B，并在文档开头给出了认领表和交接提示（含换手率数据拿不到这个已知结论，避免B重复踩坑）。选步骤3的理由：它需要跨tick维护"待确认破位"状态，与D在R3做过的`focus_watch_start_time`是同一种模式（DB加列+状态字段+跨日安全重置），D熟悉这套写法的边界；且它直接消费D刚写的`checkBreakoutVolume`，语义最清楚。本条写入时方案（第2.1-详节）已完成，代码尚未开始。
- **2026-09-14　D（较早）**：创建本文档。通读`LocalAIAgent.java`/`WisdomManager.java`全文确认"话术学习"机制规则；明确用户批注比B的倾向性建议更具体，按用户批注执行。随后完成步骤1（`IntradayPatternAnalyzer`）和步骤2（`WisdomManager.ensureIntradayPatternWisdomSeeded()`，注意不能用`hasAny()`判断否则老安装拿不到新知识，改用summary前缀查重）。通读`LocalAIAgent.java`/`WisdomManager.java`全文确认"话术学习"机制具体规则；明确用户第5节3处批注（问题1/4/5）比B自己的倾向性建议更具体，本方案按用户批注而非B的倾向执行。尚未开始写代码，下一步先去确认第5节的数据源问题，再开始Layer1。

## 变更记录（B）

- **2026-09-14　B**：D在第5节留了一个数据源问题（换手率/流通股本），当时D尚未开始写Layer1代码，B提前把这一步查完，结果写进了第5节的“B核实结果”批注里（turnRate硬编码成零拿不到，但stock_list_cache的cap+price可以推算出近似流通股本）。
- **2026-09-15　B**：认领本文档明确“留给B”的步骤5（AIContextBuilder+LocalAIAgent Context Block）、步骤6（涨停巨量新检测点），用户直接指定范围，不含步骤4。重读`IntradayPatternAnalyzer.java`确认步骤1四完整（`checkVolumeSurgeAtLimit`签名已是简化版，不接收流通股本）；重读`TradingRuleEngine.java`确认步骤3与步骤4都已实现完整（认领表写着步骤4“进行中”，但实际代码`checkUnderwaterReversal`/`RuleResult.underwaterReversal`已完整，写入B自己的实现记录作为现状确认，方便后续协作者对照）。实现步骤5：`AIContextBuilder.buildIntradayPatternContext()`+`LocalAIAgent.buildVerifyPrompt()`接入“分时形态：”一行。实现步骤6：`TradingRuleEngine.checkLimitUpVolumeSurge()`——放在`evaluate()`持仓止损区块内而非本文档建议的`RealtimeMonitorService`（因为判断今日涨停需要`evaluate()`内部已经算好的`LimitInfo`，放进`evaluate()`可以直接复用，不需要为这个检测点在两个类之间重新传递或重算涨停状态，功能上与D方案完全等价，只是host的文件不同）。换手率沿用D已确认的降级方案（只用成交量倍数判断），未揍造换手率。未碰`evaluateStopLoss`现有逻辑、未碰步骤4任何代码。未做Gradle编译验证。详见`B-分时图经验规则步骤5+6实现记录.md`。
