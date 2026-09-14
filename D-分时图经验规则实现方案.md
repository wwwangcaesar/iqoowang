# D｜分时图经验规则功能实现方案（基于B研究成果，用户5问已确认）

**记录人**：D（本文档及对应代码改动以"D"署名）
**认领任务**：`B-分时图经验规则研究与实施方案.md` 第7节建议实施顺序，全部4步
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

- **2026-09-14　D**：创建本文档，认领B方案第7节全部实施步骤。通读`LocalAIAgent.java`/`WisdomManager.java`全文确认"话术学习"机制具体规则；明确用户第5节3处批注（问题1/4/5）比B自己的倾向性建议更具体，本方案按用户批注而非B的倾向执行。尚未开始写代码，下一步先去确认第5节的数据源问题，再开始Layer1。
