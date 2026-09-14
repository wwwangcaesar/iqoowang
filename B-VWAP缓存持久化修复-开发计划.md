# B｜R12 低开VWAP缓存持久化修复 —— 开发记录

**记录人**：B（本项目由多个AI协同开发/审查/规划，本文档及后续对应的修改记录均以"B"署名）
**状态**：已实现（源码改动完成，未做Gradle编译验证，建议用户下一次构建时留意）
**关联文档**：`A-买卖逻辑优化与本地AI增强-开发计划.md`（R12一节，A的现状排查与建议修复方案）
**依据**：R11已确认由C接手，B重新认领A文档中标记"可直接实现、无需等待用户/审查AI拍板"的R12

---

## 1. 现状确认（复核A的排查结论）

B独立通读 `WatchlistManager.java`／`RealtimeQuoteManager.java` 源码，结论与A一致：

- `RealtimeQuoteManager.fetchPrevDayVwap()` 本身没问题——3次重试+失败后用日K缓存兜底估算，设计是健壮的。
- 问题出在拿到值以后存去哪：`WatchlistManager` 用两个 `private static final ConcurrentHashMap`（`sPrevDayVwapCache`／`sPrevDayVwapDateCache`）存"昨日全天真实VWAP"及其对应交易日，代码注释自己也写着"不落库，重启App后自然清空"。候选池里其它所有状态（`pattern_*`／`div_k_*`／`peak_gain_pct`等）都写进了 `watchlist.db` 这张真正的SQLite表，唯独这一项是例外。
- 后台监控服务被系统回收进程、重启是常态，一旦重启这两个Map清零，低开候选股当天已经成功抓到过的VWAP也会丢，下一轮tick要重新走一遍"异步获取"，如果重启频率接近或高于tick间隔，用户看到的就是"一直在获取、一直没结果"的持续状态。

---

## 2. 修复内容（已落地到 `WatchlistManager.java`）

1. `DB_VERSION` 5→6；`DbHelper.onCreate` 的 `CREATE TABLE` 与新增的 `onUpgrade` `if (o < 6)` 分支都加了两个字段：`prev_day_vwap REAL`／`prev_day_vwap_date TEXT`，写法照抄现有 `pattern_open`等字段的加列方式。
2. `getPrevDayVwapIfMatches(code, expectedPrevDate)`／`savePrevDayVwap(code, vwap, date)` 两个方法**签名完全不变**，内部实现从读写static Map改成读写这两个新列（`getByCode()`查询／`ContentValues`+`mDb.update()`写入）——`RealtimeQuoteManager`／`TradingRuleEngine` 里调这两个方法的地方不需要跟着改。
3. 删除了两个不再需要的 `static ConcurrentHashMap` 字段（原本是private，确认项目内无其它引用）。
4. `WatchlistItem` 新增 `prevDayVwap`／`prevDayVwapDate` 两个字段，`fromCursor()` 里按现有的 `getDoubleOrZero`/`getStringOrNull` 惯例填充。
5. 顺带把这两个值加进了 `toJson()`（跟 `patternHigh`／`divKLow` 等其它已持久化字段一视同仁，方便前端候选池卡片以后需要时直接读到）——这一步不是bug修复本身要求的，是B为了跟同一张表里其它字段的处理方式保持一致顺手加的，在此注明供审查。

**未改动**：`RealtimeQuoteManager.fetchPrevDayVwap()`、`TradingRuleEngine` 里调用这两个方法的地方，均未触碰。

---

## 3. 验证建议

B目前只有文件读写权限，没有Gradle/编译/设备访问能力，以下未做验证，建议用户或有编译环境的AI确认：

- Android Studio里 `File → Sync` 或直接跑一次build，确认 `onUpgrade` 分支语法没有问题（新增列都用了`try/catch(Exception ignored)`包裹，理论上即使某些极端情况下`ALTER TABLE`失败也不会崩溃，只是那台设备会退化回内存态）。
- 设备上跑一次，观察日志里 `WatchlistManager initialized` 之后，低开候选股不应该再反复出现"正在异步获取昨日全天真实均价数据"这条提示（如果App本身没有被系统重启杀过进程，这个bug本来就不一定会触发，需要真实观察一段时间、或者手动杀后台进程模拟重启来复现验证）。

---

## 4. 变更记录

- **2026-09-03　B**：读取 `WatchlistManager.java`／`RealtimeQuoteManager.java` 复核A对R12的现状排查（结论一致）；实现修复——`prev_day_vwap`/`prev_day_vwap_date` 落库（`DB_VERSION`5→6），`getPrevDayVwapIfMatches`/`savePrevDayVwap` 内部改为读写数据库，方法签名不变；已写入 `WatchlistManager.java`，未做编译验证。
