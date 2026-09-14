# D｜R2+R3 底仓/加仓VWAP双参照统一 ＋ 高开平开重点监听计时确认 —— 修改内容文档

**记录人**：D（协作AI，本文档及对应代码改动均以"D"署名）
**认领任务**：`A-买卖逻辑优化与本地AI增强-开发计划.md` 第2节 R2、R3（第3节"建议实施顺序"排第5项，R12/R8/R1/R4均已有人处理，本项是当前最靠前的未认领项）
**状态**：方案已完成、待用户确认后再动手改代码（本文档写完时代码尚未改动）
**关联文档**：`A-买卖逻辑优化与本地AI增强-开发计划.md`（第2节R2/R3、第4节已确认的开放问题、附录用户需求原文）、`操盘手经验终版.md`

---

## 0. 任务范围与协作说明

1. 本文档只覆盖 R2、R3 两项，不涉及 R1/R6/R7/R10（尚未认领）、R11（C负责，不改动）、R12/R8/R4（已实现，本次只读不改）。
2. 下文所有代码引用均基于D本次通过 Filesystem 连接器直接读取的当前源码全文：`TradingRuleEngine.java`、`TradingRuleConfig.java`、`WatchlistManager.java`、`RealtimeMonitorService.java`，并与A文档、用户需求原文附录做过交叉核对。
3. **在读代码过程中发现一个需要额外说明的情况**：A文档第5节"变更记录"目前只记到 R11/R12/R8 的认领与实现，但源码里 R4（止损参照价选项C）和 R8（日志明细增强）实际上都已经在 `TradingRuleEngine.java` 里落地了（R4改动注释标注日期为2026-09-08），只是没有对应的变更记录条目、也不清楚是谁做的。这件事D已经在认领任务前的对话里向用户提出，用户尚未回复具体是谁做的，本文档不代D处理这个遗留问题，仅在此重申，避免后续协作者误以为R4/R8仍是"待实现"。

---

## 1. 现状复核（在A文档基础上，D直接读源码补充确认的细节）

### 1.1 R2 相关

- `TradingRuleConfig.java` 目前**没有** `vwapDualRefSwitchMinutes` 字段，确认R2尚未实现。
- `TradingRuleEngine.evaluateStarterGapDown()` 现状：全程只用 `prevDay.prevAvgPrice`（昨日VWAP）一个参照，不区分开盘后多久，也完全不看当日VWAP。
- `TradingRuleEngine.evaluateAddHalf()` 现状：`pullbackHold` 分支只用当日 `vwap` 一个参照，方法签名里虽然已经有 `PrevDayRef prevDay` 参数（大概率是历史遗留/预留），但方法体内完全没有使用 `prevDay.prevAvgPrice`——这对D是个有利条件：改造R2时**不需要改方法签名**，只需要改方法体逻辑。

### 1.2 R3 相关

- `WatchlistManager` 的 `watchlist` 表（`DbHelper`，当前 `DB_VERSION=6`）没有任何"重点监听"相关列，`WatchlistItem`/`DivergenceState` 也没有对应字段，确认R3尚未实现。
- `TradingRuleEngine.evaluateStarterGapUpOrFlat()` 现状：瞬时判断——只看最近10个分时点的最低价（`minRecent`）有没有跌破 `VWAP×0.999`，一旦这一刻满足就直接给 `BUY_STARTER`，命中放量确认即推送。没有任何"进入监听状态"“计时”的概念，跟A文档描述一致。
- `RealtimeMonitorService.evaluateAndAct()` 确认：`result.stateUpdate` 只要非空就会在每个tick后立即调用 `WatchlistManager.saveTrackState()` 落库；而 `evaluate()` 主分支对WATCHING状态固定执行 `starter.stateUpdate = state;`（不管 `starter` 本身的 `action`/`note` 是什么都会执行这一行）。**这意味着D只要在 `evaluateStarterGapUpOrFlat` 内部对同一个 `state` 对象做修改，不需要额外改动 `evaluate()` 或 `RealtimeMonitorService` 的持久化调用链，新状态就会自动随每个tick落库**——这是本次改造复杂度能控制住的关键前提，已通过读源码而非猜测确认。
- `RealtimeMonitorService.shouldNotifyNow()` 对 `BUY_STARTER` 一律返回 `true`（只对 `STOP_LOSS` 特殊判断），确认一旦本次改造在某个tick把 `result.action` 置为 `BUY_STARTER`，会立即推送，不需要额外接入推送判断逻辑。
- **发现一个需要新增能力的点**：用户原文"当出现高开，并且回落到分时均价线，进行推送提示"要求**进入重点监听这一步本身也要推送通知**，但现有架构里只有 `result.action != NONE` 才会走 `markPending`→`fireAlert` 推送链路，而"进入重点监听"不是一个需要用户确认/忽略的买卖决策（不应该占用PENDING_STARTER这类需要人工确认的状态位），所以不能直接复用现有的 `Action` 机制。方案见下文2.4节。

---

## 2. R2 实现方案：底仓/加仓VWAP双参照统一

严格对应A文档R2(a)(b)(c)三点（用户已确认"三个点都按照A的方案实现"），不额外增减判断逻辑。

### 2.1 新增配置项

`TradingRuleConfig.java` 新增：

```java
/** 【R2】低开路径"当日VWAP是否已具备统计意义"的开盘后分钟数分界。开盘X分钟内底仓只参照
 *  昨日VWAP；X分钟后底仓改为参照 min(昨日VWAP, 当日VWAP)（取较松的一个）。与放量门槛用的
 *  earlyWindowMinutes(60)是两个独立概念，不复用。默认30，对应A文档R2(b)。 */
public int vwapDualRefSwitchMinutes = 30;
```

（加仓的 `max(昨日VWAP, 当日VWAP)` 不需要新增分钟数配置，因为它不区分开盘早晚，全程统一要求两者都站上——见2.3节。）

### 2.2 `evaluateStarterGapDown` 改造（对应R2(b)前半句：底仓判断）

**替换**（对应R2(a)拍板：替换而不是叠加）现有的"只看昨日VWAP"为分时段判断：

- 开盘 `vwapDualRefSwitchMinutes`（30）分钟内：参照价＝`prevDay.prevAvgPrice`（与现状相同，不变）。
- 30分钟后：参照价＝`Math.min(prevDay.prevAvgPrice, 当日vwap)`。若当日vwap尚未有效（`vwap<=0`，比如分时数据本轮没拿到），退化为只用 `prevDay.prevAvgPrice`，不因为拿不到当日数据就卡住整条判断。

方法签名新增 `double vwap, int hour, int minute` 三个入参（调用方 `evaluateStarter` 本身已持有这三个值，改动量很小）。判断逻辑框架（不含原有的放量确认、异步获取兜底等既有代码，那些保持不变）：

```java
int minsFromOpen = hour * 60 + minute - (9 * 60 + 30);
boolean withinSwitchWindow = minsFromOpen >= 0 && minsFromOpen < mCfg.vwapDualRefSwitchMinutes;
double ref;
String refLabel;
if (withinSwitchWindow || vwap <= 0) {
    ref = prevDay.prevAvgPrice;
    refLabel = "昨日全天真实均价¥" + fmt(ref);
} else {
    ref = Math.min(prevDay.prevAvgPrice, vwap);
    refLabel = (ref == prevDay.prevAvgPrice)
        ? ("昨日全天真实均价¥" + fmt(ref) + "（低于当日VWAP¥" + fmt(vwap) + "）")
        : ("当日VWAP¥" + fmt(ref) + "（低于昨日均价¥" + fmt(prevDay.prevAvgPrice) + "）");
}
if (quote.price < ref) {
    // 未站上参照价，继续观察（note需写清楚当前用的是哪个参照、处在开盘后第几分钟）
}
```

其余部分（放量确认判断、通知文案的其它字段、trigger逻辑）沿用现有结构，不改动判断顺序。

### 2.3 `evaluateAddHalf` 改造（对应R2(b)后半句：加仓判断，统一适用于低开/高开两条路径）

现有 `pullbackHold` 计算方式不变（仍是"最近10个分时点最低价 ≥ 参照价×0.999 且现价 ≥ 参照价"），**只改参照价本身**：

```java
double addRef;
if (prevDay.prevAvgPrice > 0) {
    addRef = Math.max(prevDay.prevAvgPrice, vwap); // 两者都要站上，取较高（更严格）的一个
} else {
    addRef = vwap; // 昨日均价还没抓到时，退化为只看当日VWAP，与改造前行为一致，不阻塞加仓判断
}
```

`pullbackHold`、`minRecent` 相关判断改用 `addRef` 替换原先单一的 `vwap`；`breakWater`（突破水线/前收）这条独立的加仓路径完全不动。note文案同步补充双参照数值，方便复核（延续R8"打印中间值"的风格）。

### 2.4 R2(c) 处理

A文档R2(c)已经把"高开/平开场景下昨日VWAP这道门槛形同虚设、核心改动在R3"写成理解确认项，用户已同意。因此 `evaluateStarterGapUpOrFlat` 的底仓判断**不引入昨日VWAP**，只用当日VWAP（细节见第3节）——不是遗漏，是按A文档已确认的分工。

---

## 3. R3 实现方案：高开/平开重点监听 + 计时确认

### 3.1 新增配置项

```java
/** 【R3】重点监听计时确认所需的"实际交易分钟数"（剔除11:30-13:00午休）。A文档正文明确
 *  写的是"1小时"，但未单独列配置项名——D这里按本项目一贯做法（几乎所有阈值都在
 *  TradingRuleConfig里可调）新增一个字段外部化，不是新加判断条件，只是把文档里已经
 *  写死的"1小时"这个数字变成可调参数，行为本身与文档描述完全一致。默认60。 */
public int focusWatchConfirmMinutes = 60;

/** 【R3】收盘前多少分钟内，重点监听中的股票若仍未跌破分时均价，直接确认（不再等满1小时）。
 *  与止损用的 patternLowStopNotifyMinutes 相互独立，互不影响。默认15，对应A文档R3(4)。 */
public int gapUpAddConfirmMinutesBeforeClose = 15;
```

### 3.2 新增持久化字段（`WatchlistManager` / `DivergenceState`）

- `DivergenceState` 新增：`long focusWatchStartTime`（0＝未在监听）、`String focusWatchStatus`（`"NONE"`/`"WATCHING"`/`"CONFIRMED"`，默认`"NONE"`）。
- `watchlist` 表新增两列：`focus_watch_start_time INTEGER`、`focus_watch_status TEXT DEFAULT 'NONE'`，`DB_VERSION` 6→7，写法照搬R12那次 `prev_day_vwap` 升级（`onCreate`直接建列＋`onUpgrade`里`if (oldVersion < 7)`补`ALTER TABLE`）。
- `saveTrackState`/`loadTrackState`/`WatchlistItem`/`fromCursor` 按现有 `div_k_high` 等字段的写法平行补上这两个。
- `toJson()` 同步导出这两个字段，方便以后前端展示进度（本次不改 `assets/index.html`，只做后端数据暴露，UI留待后续，做法与R11"先做后端、前端另行确认"的节奏保持一致）。

### 3.3 `evaluateStarterGapUpOrFlat` 状态机改造

核心状态：`focusWatchStatus`，三态：

- **`NONE`**：未进入重点监听。每个tick检查"是否回踩当日VWAP不破"（判定方式与现有代码完全一致：`minRecent≥vwap×0.999` 且 `quote.price≥vwap`）。
  - 不满足 → 维持NONE，note维持现有"尚未回踩确认，继续观察"文案。
  - 满足 → 转入 `WATCHING`，记录 `focusWatchStartTime=当前时间戳`，note改为"进入重点监听，开始计时"，**这一tick触发一次通知（见3.4节），但不产生买卖 `Action`**（`result.action`仍为`NONE`，不占用PENDING状态位）。
- **`WATCHING``**：已进入监听，计时中。每个tick：
  1. 先检查跨日安全：若 `focusWatchStartTime` 不是今天（比较日历日期），说明是隔夜遗留状态（比如那天没到收盘前就没能确认、次日字段还没来得及清），直接重置回 `NONE` 再按NONE分支重新判断，不能带着昨天的计时误判今天。
  2. 重新检查"回踩不破"条件是否仍然成立：
     - **不再成立**（现价跌破当日VWAP，即用户原文"完全跌破"）→ 重置回 `NONE`，清空 `focusWatchStartTime`，note说明"此前进入的重点监听已取消（现价跌破VWAP）"。不推送通知（对应用户原文"如果完全跌破是不可以购买的"这一条规则本身，取消是状态回退，不是新的买卖决策，不需要单独推送打扰）。
     - **仍然成立**：计算 `elapsedMinutes = 剔除午休的累计交易分钟数(focusWatchStartTime, 当前时间)`，并检查是否处于收盘前 `gapUpAddConfirmMinutesBeforeClose` 分钟窗口内（直接复用现有的 `isStopNotifyWindowMinutes()` 方法，同一套"收盘前N分钟"判断逻辑，不重复造轮子）。
       - 两个确认条件都不满足 → 维持WATCHING，note写明已监听多久、还差多久。
       - 满足其一 → 检查放量确认（`vol.confirmed`，这是现有代码里 `evaluateStarterGapUpOrFlat` 触发买入前本来就有的门槛，R3没有提出要取消它，D保留而非新增，详见第4.2节说明）：
         - 放量未确认 → 维持WATCHING，note说明"计时已满/已到收盘前窗口，等待放量确认"。
         - 放量已确认 → `result.action = BUY_STARTER`，`focusWatchStatus` 置为 `CONFIRMED`，note注明具体是"满1小时交易时间"还是"到收盘前N分钟"触发。这一tick走现有的 `markPending`→`fireAlert` 正常推送链路，不需要额外处理。
- **`CONFIRMED`**：已经确认过（对应候选股已经不再是WATCHING状态，之后不会再进这个方法）。此状态本身不需要额外分支处理，仅用于语义完整性和便于日后排查。

### 3.4 新增"进入重点监听"轻量通知

`TradingRuleEngine.RuleResult` 新增 `boolean focusWatchJustEntered = false;` 字段，仅在3.3节"NONE→WATCHING"这一次转变时置为`true`。

`RealtimeMonitorService.evaluateAndAct()` 现有 `Action.NONE` 分支内，在 `updateNote(...)` 之后新增判断：若 `result.focusWatchJustEntered`，调用新方法 `fireFocusWatchNotification(code, name, note, price)` 推送一条轻量通知。这个通知**不走** `markPending`（不改变候选股状态、不要求用户确认/忽略），只是告知；样式上用比买卖信号更低的优先级（`PRIORITY_DEFAULT`）和更短的单次震动，跟"🔔待确认"（三连震动）、"✅AI已确认"（单长震动）区分开，用"👀"开头的标题，复用同一个通知ID（`code.hashCode()`）以保持同一支股票的通知在通知栏里是同一条、不断刷屏。

### 3.5 轮询频率问题（A文档R3(3)，用户已同意A的倾向）

按A文档已确认的方向：**不改动 `RealtimeMonitorService` 的全局tick节奏**，计时全部基于时间戳（`System.currentTimeMillis()`）而非tick次数，现有2分钟一次的节奏对"是否满1小时"这个量级的判断精度完全足够。A文档里提到的"可选的局部快轮询补充"本次**不实现**——原文本身把它描述为可选项，且用户的"(S:同意)"批注没有额外要求一定要加，D按R9原则"不额外加码"，只做被明确要求的部分，如果后续实测发现计时确认不够灵敏，可以再单独讨论加这个补充。

---

## 4. 需要向用户说明的两个判断点（非A文档已有定论，D的解释性决定）

这两点A文档原文本身没有完全说清楚，D按最贴近字面需求、改动面最小的方向做了决定，写在这里方便用户核对，如果理解有偏差可以随时纠正：

### 4.1 "收盘前15分钟仍不跌破→加仓"具体指什么（✅用户已拍板，此节以下为定案后实现记录）

用户确认采用A文档变更记录里提出的备选读法：**先满1小时确认给底仓，如果一路撑到收盘前15分钟还没破，再追加一次加仓（两次先后独立动作）**，而不是D原方案里两个入口都指向同一个底仓结果。实现上对应两处改动（已完成）：

1. `evaluateStarterGapUpOrFlat` 只保留"满1小时实际交易时间"这一个确认入口触发`BUY_STARTER`，原来"或已到收盘前N分钟"那条备选触发路径已删除。若一支股票进入重点监听时已太晚、当天凑不满1小时就收盘，当天不会有底仓信号，次日经跨日安全重置回`NONE`，从头判断，不强行给一个"迟到的底仓"。
2. `evaluateAddHalf` 新增独立的 `focusWatchGraduation` 判断（与现有`breakWater`/`pullbackHold`两条路径是"任一满足即可"的关系）：`state.focusWatchStatus=="CONFIRMED"` 且同日、且已进入收盘前`gapUpAddConfirmMinutesBeforeClose`窗口、且现价仍≥当日VWAP，才触发。这里参照的是当日VWAP本身（`vwap`参数），不是R2的双参照`addRef`——保持这支股票从进入监听到底仓、加仓全程参照基准一致。是否需要放量确认：仍需要（`vol.confirmed`检查对三条路径统一生效，没有为这条新路径单独开放量确认的例外，这样改动面更小、也不产生新的特例）。因为`ADD_HALF`触发后状态会离开STARTER（变PENDING_ADD），`evaluateAddHalf`不会再被调用，不存在同一支股票被重复触发的风险。`TradingRuleConfig.gapUpAddConfirmMinutesBeforeClose`字段注释已同步更新说明新含义。

### 4.2 是否需要放量确认

A文档R3的技术方案部分（"需要新增"1-4点）没有提到放量确认，但D翻查改造前的原始代码，`evaluateStarterGapUpOrFlat` 在触发 `BUY_STARTER` 之前本来就有 `vol.confirmed` 这道门槛（跟低开路径、满仓路径是同一套约定）。D理解为R3只是在改造"什么时候检查回踩条件"（从瞬时判断改成计时确认），并没有要求连带取消原有就存在的放量门槛，所以本次改造**保留**这道检查，不是D新加的条件。如果用户认为重点监听本身已经是一种时间维度的确认、不需要再叠加放量条件，这里可以去掉。

---

## 5. 具体改动文件清单

| 文件 | 改动内容 |
|---|---|
| `TradingRuleConfig.java` | 新增 `vwapDualRefSwitchMinutes`(30)、`focusWatchConfirmMinutes`(60)、`gapUpAddConfirmMinutesBeforeClose`(15) 三个字段，`applyUpdates()`/`toJson()` 同步补上 |
| `WatchlistManager.java` | `DB_VERSION` 6→7；`watchlist`表新增`focus_watch_start_time`/`focus_watch_status`两列；`WatchlistItem`、`fromCursor`、`saveTrackState`、`loadTrackState`、`toJson` 同步补上这两个字段 |
| `TradingRuleEngine.java` | `DivergenceState`新增两字段并同步进`copyState()`；`RuleResult`新增`focusWatchJustEntered`；新增`computeTradingMinutesExcludingLunch()`、`isSameDay()`两个工具方法；`evaluateStarter`/`evaluateStarterGapDown`/`evaluateStarterGapUpOrFlat`/`evaluateAddHalf`按第2、3节方案改造 |
| `RealtimeMonitorService.java` | 新增`fireFocusWatchNotification()`方法；`evaluateAndAct()`的`Action.NONE`分支里补上对`focusWatchJustEntered`的判断和调用 |

不涉及 `assets/index.html`、`DecisionLogger.java`（沿用现有 `logRulePush` 等签名不变的约定，与R11无冲突）、`DatabaseManager.java`。

## 6. 风险与验证方式说明

D没有Gradle/编译环境，本次改动只能保证语义正确、类型匹配、方法签名互相对齐（这点已逐处核对现有代码风格与命名习惯），**无法做实际编译验证**，与B此前R12/R8的情况相同。建议改完后在Android Studio里先跑一次编译，再用模拟盘观察至少一个完整交易日（尤其留意跨午休的计时是否符合预期、进入/取消重点监听的通知是否符合预期）。

---

## 变更记录（D）

- **2026-09-10　D**：创建本文档，认领A文档R2+R3；完整读取相关源码（`TradingRuleEngine.java`/`TradingRuleConfig.java`/`WatchlistManager.java`/`RealtimeMonitorService.java`全文）并与A文档、用户需求原文附录交叉核对，形成本方案；尚未开始改代码。
- **2026-09-10　D**：代码实现完成。改动`TradingRuleConfig.java`（新增3个配置项）、`WatchlistManager.java`（`DB_VERSION`6→7，新增`focus_watch_start_time`/`focus_watch_status`两列及配套读写）、`TradingRuleEngine.java`（`DivergenceState`/`RuleResult`新增字段，`evaluateStarterGapDown`/`evaluateStarterGapUpOrFlat`/`evaluateAddHalf`按本文档方案改造，新增`computeTradingMinutesExcludingLunch`/`isSameDay`两个工具方法，`copyState`同步新字段）、`RealtimeMonitorService.java`（新增`CHANNEL_ID_FOCUS_WATCH`低优先级通道、`fireFocusWatchNotification()`方法，`evaluateAndAct()`接入`focusWatchJustEntered`判断）。四个文件均完整重读验证过最终状态，逻辑自洽一致，但**未做Gradle编译验证**。A文档同步更新过（R2/R3节状态行、第3节实施顺序、变更记录）。本文档第4.1节的解释性判断（"收盘前15分钟加仓"读法）已收到A的同样成立的备选读法（见A在A文档变更记录里的2026-09-10条目），仍待用户最终确认。
- **2026-09-10　D**：用户确认采用"先底仓后加仓两次先后动作"读法。D回头准备改代码时发现`TradingRuleEngine.java`里对应改动已经存在且逻辑正确（`evaluateStarterGapUpOrFlat`只保留"满1小时"触发入口，`evaluateAddHalf`新增`focusWatchGraduation`判断），A文档的R3节状态行也已同步更新——推断是A在并行会话里收到了用户同样的确认并先一步实现。D逐处核对过这段代码，确认与D自己会写的版本逻辑一致（包括focusWatchGraduation参照当日VWAP而非R2双参照、vol.confirmed对三条路径统一生效这两个细节），未重复改动，只补上了`TradingRuleConfig.java`里一处还没来得及同步的字段注释（`gapUpAddConfirmMinutesBeforeClose`）以及本文档第4.1节的定案记录。至此R2+R3全部完成，无待确认项。
- **2026-09-10　D**：应用户要求对R2+R3代码做了一轮完整复查。修复一处：`evaluateAddHalf`里`focusWatchGraduation`判断补上`vwap > 0`前置条件（原先若vwap恰好为0，`quote.price >= vwap`会恒为真，低概率但不应该发生）。另排查了两个看似问题但确认不是回归的点：1)用户dismiss重点监听触发的信号后会不会立即重新弹出——会，但这是整个dismiss机制的通性特征（低开底仓/招压预警同样如此），非R2/R3新引入，未单独修改；2)高开股跨天变低开时focus_watch_status会不会残留脏数据——不会，未来若再次高开/平开会被跨日安全重置正确清除。另发现一个比R2/R3范围更根本的现象："回踩不破"判断只验证"最近没跌破参照价"，并未真正验证价格确实回落到参照价附近过，与用户原始需求里"不追高，只有回跌到均价才补仓"的精神不完全一致，但这是R2/R3改造前就有的老逻辑（evaluateStarterGapUpOrFlat和evaluateAddHalf共用），未在本轮改动，已另行告知用户，待确认是否需要单独立项处理。
