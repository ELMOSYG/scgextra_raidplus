# 测试反馈记录

> 这里只放「观察到但还没决定要不要改」的东西，避免下次反馈时重新推一遍。
> 状态栏是权威：`待决定 / 已修 / 不修`。

---

## 2026-09-14 · 女仆在一场 FAC 常规袭击里用了 5 次绀珠之药

**测试条件**（用户提供）：100 血量、钻石护甲 + 盾牌、武器 `scguns:greaser_smg` 的女仆，打 FAC 常规信号弹袭击（4 波）。

**结果**：4 波里女仆触发 5 次绀珠之药 = 死了 5 次。

**测试目的（用户补充）**：测**女仆单刷袭击的能力（不依赖玩家）** —— 不是「玩家+女仆」的正常打法，而是「女仆一个人扛一整场」。

**状态：待决定 —— 用户选择「先不改，当作记录」（2026-09-14）。**

### 0. 「不依赖玩家」这个前提

scgextra 刷怪时**初始目标**的来源是玩家：

- `startRaid`：第一波把所有怪的目标设成**发信号弹的玩家**（`player`）
- `tickRaid` 后续波次：`WaveRaidUtil.findNearestPlayer(level, center, 512)`，**没找到玩家就是 `null`**
- `WaveRaidState.spawnCurrentWaveMobs(..., target)`：`target == null` 时走 `mob.setTarget(null)` → 怪**出生时没有目标**

但「出生没有目标」**不等于会发呆**：怪自己的 AI 会重新索敌，而且**不依赖玩家**——
见 §3 更正：scgextra 的脑怪用 `StartAttacking(findNearestAttackableFactionEnemy)`，
被调整过的 SC2 怪用 `NearestAttackableTargetGoal(LivingEntity.class, ... Faction.isEnemies)`。
在装了 `scg2_maid_compat`（女仆属于 `player` 阵营）的情况下，女仆本身就是它们的合法敌人目标。

结论：**5 次复活 ≈ 单刷一场玩家量级袭击（23 只枪手 + 1 个 boss）的代价**，不是数值坏掉；
而「女仆被主动攻击」是 `SCG2_TLM` 那边阵营设计的既有结果，不是本 mod 带来的。

### 0.1 不用改代码就能做的对照实验（建议下次这样喂数据）

`config/scgextra_raidplus-common.toml` 里逐项开关，跑同一场 FAC 常规，记复活次数：

| 实验 | 配置 | 想测什么 |
|---|---|---|
| 基线 | `targeting_enabled=false` + `converge_enabled=false` + `spawn_buff_enabled=false` | 纯 scgextra + 阵营设计下女仆单刷什么水平（预计：照样会打她，差异在「出生第一目标」和推进速度） |
| 只开索敌 | `targeting_enabled=true`，其余关 | 「谁近打谁」单独贡献多少 |
| 只开迅捷 | `spawn_buff_enabled=true`，其余关 | 迅捷 II 单独贡献多少（预计这条最明显） |
| 全开 | 全默认 | 当前实测的 5 次 |

这四条一比，就能判断该不该动 §5 里的杆子，而不是凭感觉调。

### 1. 算术：这场袭击一共 23 只怪

`data/scgextra/raids/fac.json`（每点 `value` 默认 1 = 1 只）：

| 波 | 组成 | 数量 |
|---|---|---|
| 1 | infantry 8 | 8 |
| 2 | infantry 5 + elite 2 | 7 |
| 3 | infantry 3 + elite 3 + miniboss 1 | 7 |
| 4 | boss 1（`fac_tank`） | 1 |
| | | **23** |

全部持 SC2 枪械。所以「死 5 次」的量级 = 100 血的女仆对上 23 条枪，不是某个数值坏掉。

### 2. 放大机制：scgextra 自己的「阵营警报」把单体仇恨变成全体仇恨

证据（本次从 scgextra 3.1.3 的反编译件里核出来的）：

- `CheckShouldAlert`（`net.zincstudios.scgextra.entity.common.brain.CheckShouldAlert`）
  - 记忆要求：`ATTACK_TARGET` 必须存在、`TO_ALERT` 必须不存在 → **有目标时**才运行
  - 扫描 `entity.getBoundingBox().inflate(radius, radius/2, radius)`，**radius 默认 64 格**（`CheckShouldAlert(int alertDuration)` → `this(alertDuration, 100, 64.0F)`）
  - 收进 `toAlert` 的条件：同阵营（`Faction.isFriendlies`）且**自己当前没有目标**（brain 的 `ATTACK_TARGET` VALUE_ABSENT，或 `mob.getTarget() == null`）
  - 冷却：`alertDuration(10) + alertCooldown(100)` 刻
- `AlertNearbyFactionMobs`（同包）
  - 对 `TO_ALERT` 里每一个怪调用 `BrainUtils.setTarget(other, target)` —— **把发警报那只怪的目标原样发出去**
  - `BrainUtils.setTarget`：`ATTACK_TARGET` 为空就写记忆，否则 `mob.setTarget`

结论：只要**一只**袭击怪盯上女仆，64 格内所有还在发呆的同阵营怪会**一次性**一起打女仆，不需要视线、不需要自己发现。

### 3. 更正：女仆被集火主要是**既有设计**，不是本 mod 的改动造成的

用户指出 `SCG2_TLM`（女仆兼容 mod）里已经给玩家和女仆做了**独立阵营**，SCG 的怪本来就会主动攻击女仆。核实后确认成立：

- 部署的 `scg2_maid_compat-1.2.0.jar` 里有 `data/scgextra/tags/entity_types/factions/player.json`：
  `["minecraft:player", "touhou_little_maid:maid"]` → 女仆属于 `player` 阵营（`Faction.isEnemies` 需要双方都有阵营且不同，袭击怪的阵营是 `fac` 等 → 判定为敌）
- scgextra 给**自己的脑怪**装了：`BrainCommons.initIdleActivity` →
  `StartAttacking.create(BrainUtils::findNearestAttackableFactionEnemy)`（`f_148205_` = `NEAREST_VISIBLE_LIVING_ENTITIES` 里按 `Faction.isEnemies` 筛）
- scgextra 还给 **SC2 的怪**（袭击里用到的 `scguns:cog_knight` / `cog_minion` / `sky_carrier` / `trauma_unit` / `adjudicator` / `dissident` / `praetor` / `subjugator` 全在表里）在 `EntityAdjustments.onEntityJoin` 时加了
  `NearestAttackableTargetGoal(mob, LivingEntity.class, true, entity -> Faction.isEnemies(mob, entity))`，并移除了原生 `HurtByTargetGoal`

所以**在这套配置下**，23 只 FAC 怪本来就会主动锁定女仆，跟 `RaidTargeting` 无关。之前记的「本 mod 的目标改动放大了集火」对这个测试**不成立**，在此更正。

本 mod 在这个测试里真正改变的是：

- 把「初始目标 = 玩家」换成「谁近打谁（含女仆，带 4 格切换余量）」—— 行为变了，但在 faction 系统下结果趋同
- `spawn_buff` 迅捷 II：怪更快贴脸，火力窗口更长（**这条是实打实的加成**）
- 刷怪环 + 向中心靠拢：怪几乎同时从四周到位，集火更集中（之前是围着玩家散落刷）

### 3.1 由此产生的待决定：`RaidTargeting` 还要不要

- 在「装了 scg2_maid_compat 且 `enable_player_faction=true`（默认）」的配置下，它是**冗余**的
- 它还会和那个开关**打架**：`enable_player_faction=false` 时玩家+女仆变 `NO_FACTION`，faction 系统不再敌对（这正是那个开关的用途），但 `RaidTargeting` 仍会强行给袭击怪塞目标
- 它唯一还有价值的场景：没装 scg2_maid_compat（没有 faction 标签）、或用户手动关掉了玩家阵营却仍希望袭击怪打女仆/玩家

**状态：待决定 —— 用户选择「先不动，继续记录」（2026-09-14）。**

若之后要动，注意两点：

- **只满足「空目标时补位」是不够的**：faction 系统自己就会分配目标，想做仇恨上限必须能**主动把超额的怪改派走**（等于覆盖 scgextra 的分配）
- 真正决定「她是一只被打还是被 23 只一起打」的是 `CheckShouldAlert` 那条 **64 格阵营警报通报**（§2），不是索敌本身

### 4. 同一场的追加观察：盾牌 + 钻石护甲耐久被打空

**观察**（用户提供）：这一场里女仆携带的盾牌和钻石护甲都被打空了耐久。

**怎么读这个结果**：耐久被打空 = 这场袭击打出了**几百次有效命中**的量级，而不是几次大伤害。

- 耐久池的量级：钻石全套 ≈ 363(头) + 528(胸) + 495(腿) + 429(鞋) = **1815**，盾牌 **336** → 合计约 **2150**
- 护甲：每次命中每件掉 `max(1, 伤害/4)` 点耐久（`LivingEntity.hurtArmor`）→ 想磨掉 1815 点，需要**几百次命中**
- 盾牌：每格挡一次掉 `1 + floor(本次伤害)`（`hurtCurrentlyUsedShield`，伤害 ≥3 时才扣）→ SC2 子弹单发伤害高，
  **336 耐久的盾大概只够挡二三十发**，在自动武器齐射下几十秒就没了
- 如果装备上有 Unbreaking III（等效耐久约 ×4/×3），实际命中次数还要再翻几倍，上不封顶

**结论**：

- 女仆的减伤**是在工作的**（盾牌确实在格挡、护甲确实在吸收），不是被一发秒 —— 死 5 次更可能是**装备磨穿之后**才发生的
- 这是**消耗战/命中数量**问题，不是数值爆表问题 → 和 §2 的阵营警报（23 只同时压上来）指向同一个根因，
  也和 §5 的 C（限制 64 格通报）／D（缩小波次）方向一致

**TLM 侧的机制提醒（Mending 追不上）** —— 证据（TLM 1.5.3 反编译件，`EntityMaid.java :: pickupXPOrb`）：

```java
ItemStack itemstack = this.getRandomItemWithMendingEnchantments(allItems);   // 只挑带 Mending 的
int i = Math.min((int)(orb.f_20770_ * itemstack.getXpRepairRatio()), itemstack.m_41773_());
orb.f_20770_ -= i / 2;                    // 球的 XP 还要减半
itemstack.m_41721_(itemstack.m_41773_() - i);
```

- 前提是女仆**拾取开关开着**（`isPickup()`，`MAID_PICKUP_RANGE` 范围内）
- 每个经验球**只随机修一件**带 Mending 的装备，且修复量要先减半
- 所以：女仆的装备在袭击里基本是**消耗品** —— 23 条自动武器的持续命中，靠几个经验球补不回来

**不用改代码就能试的两个方向**：

1. `spawn_ring_min` / `spawn_ring_max` 拉大（例如 48 / 64）→ 怪分批到场，而不是同时压上来，降低每秒命中数
2. `spawn_buff_enabled=false` → 去掉迅捷 II，同样降低单位时间命中数

**状态：待决定 —— 用户选择「先不动，继续记录」（2026-09-14）。**

### 5. 备选的调节杆（都还没做）

| | 做法 | 代价 |
|---|---|---|
| **C（现在看最关键）** | 削弱阵营警报：mixin `CheckShouldAlert`，限制那 64 格通报半径／要求通报对象自己看得见目标／整条关掉 | 动 scgextra 本体行为；但它才是「23 只同时压一个人」的直接原因 |
| **A** | 仇恨上限：`max_attackers_per_target`（默认 4） | **注意**：faction 系统会自己分配目标，所以必须能主动改派已锁定的怪，不能只补空位；等于覆盖 scgextra 的分配逻辑 |
| **B** | 玩家优先：范围内有可打玩家时不选女仆（女仆变备选） | 与「女仆要能被打」的需求相反，等于半回退；而且 faction 系统那条路不受它控制 |
| **D** | 缩小波次：`wave_size_scale`（0.5 = 波次价值点减半，23 → 约 12 只） | 需要 mixin `WaveRaidData.generateRaiders`；最直接，也顺带提升性能 |

**当时给的建议**：原先推荐 A + C；在更正了「faction 系统已接管索敌」之后，**C 才是重点**，A 的实现成本比原先估计的高。

### 6. 下次反馈如果有这些数据会更好定位

- **这场她到底刷完了没有**：23 只全清（袭击胜利）/ 10 分钟超时 / 还是玩家后来帮忙收尾 —— 「单刷能力」的核心指标其实是这个，不是死了几次
- 女仆是被**集火**打死的，还是被**某一只**（比如第 4 波的 `fac_tank`）打死的 → 开 `targeting.targeting_debug=true` 看日志里同时有多少只怪锁定女仆
- 死的时候护盾有没有生效、有没有被 SC2 的破甲/爆头机制吃穿
- 女仆自己的 TLM 战斗任务/回避有没有在工作（`greaser_smg` 是 SMG，交火距离很近）
- 单刷这一场大概花了多少时间（4 波 × 30 刻间隔 + 清场时间）

---

## 2026-09-18 · scg-extra 波次袭击的三个问题（用户报告）—— 已定位并修

**用户报告**：① 玩家死亡会自动过波；② 关底 boss 没有血条；③ 袭击进度条是按敌人数量算的（原版村庄袭击是总血量）。

**状态：已修（编译 + 部署，2026-09-18 17:02 覆盖 `mods\scgextra_raidplus-1.0.0.jar`，36578 B / SHA-256 `A5B86D90…`），待游戏内验证。**

### 1. 「自动过波」= 把「区块没加载」判成了「怪死了」

过波只有一条路：`WaveRaidManager.tickRaid` 里 `raidState.raidersLeft() == 0`（= `raiders.size()`）。
名单靠 `WaveRaidState.updateRaiders()` 每刻 `removeIf` 剪枝，原判据两条：

```java
if (entry.getValue() == null) {
   if (!(this.level.getEntity(entry.getKey()) instanceof LivingEntity living)) return true;   // ① 解析不到 → 删
   entry.setValue(living);
}
return entry.getValue().isRemoved();                                                           // ② removed → 删
```

**区块卸载时这两条同时成立**：实体被 `setRemoved(RemovalReason.UNLOADED_TO_CHUNK)`，
同时从 `ServerLevel.getEntity` 的查找表里消失。于是「怪还在，只是没加载」被判成「怪死了」。

后果链条：名单瞬间清空 → 30 刻后 `advanceWave()` → 下一波在同一个（已经没人加载的）中心刷出来
→ 再被清空 → 把剩余波次一路烧完 → `endRaid(success=true)` 还发战利品。
玩家死亡后在床上/世界出生点复活（离袭击中心超过加载距离）是最容易触发它的操作。

**排除项**：怪本身是持久化的 —— `spawnCurrentWaveMobs` 里调了 `mob.m_21530_()`，
按 `forge_gradle` 里的 `client_mappings.txt` 核对，`m_21530_` = **`setPersistenceRequired`**。
所以不是「怪 despawn 了」，是「没加载被判死」。

**修法**（`RaidRoster` + `WaveRaidStateMixin` 对 `Set.removeIf` 的 `@Redirect`）：
解析不到 / `UNLOADED_TO_CHUNK` → 保留；只有「能解析到且已 removed」或「血量 ≤ 0」才算死。
开关 `roster.keep_unloaded_raiders`（关掉 = 原判据）；`roster.wave_debug` 能在日志里看到
`确认死亡 X 只，未加载保留 Y 只，名单里还有 Z 只`。

### 2. 进度条按数量

```java
this.bossBar.setProgress((float)this.raidState.raidersLeft() / (float)this.raidState.getTotalWaveSpawned());
```

分子分母都是「还剩几只」：`totalWaveSpawned` 在 `addRaider` 里被写成 `raiders.size()`、过波清零。
原版 `Raid` 是「活着的袭击怪总血量 / 累计总血量」。
数据侧没问题：`raider.max_health` 缺省 -1 时不加血上限（`RaiderEntry.createEntity` 里只有 `maxHealth > 0` 才加），
所以刷怪时的 `getMaxHealth()` 是权威值，可以逐只累加。

**修法**（`RaidBar`）：`addRaider` 时累加本波血量上限，进度 = Σ存活怪当前血量 / 本波上限；
未加载的怪按出生上限计（区块卸载不该让进度凭空掉一截）。开关 `progress.progress_by_health`。
读档后本波没走过 `addRaider`（没有累计值）时返回 -1，交给原来的计数公式，不会出现除零。

### 3. 关底 boss 没有血条

全 scgextra 只有一处 `ServerBossEvent`：`WaveRaidManager.bossBar`（RED / NOTCHED_10 的波次条）。
BOSS 档的实体自己不带 bossEvent（`FacTankEntity` 就是普通 `Monster` 那套），
对比 SC2 自己的 boss：`ScampTankEntity` 自带 `bossEvent`（YELLOW / PROGRESS、按血量变色、玩家进出条）。

**修法**（`RaidBar` + `WaveRaidManagerMixin.tickBossBar` TAIL）：`addRaider` 时按数据包 boss 名单
（`WaveRaidData.getRaiderEntries(Rank.BOSS)` 比对实体类型）认 boss；
它活着时把那条 bar 的标题换成 boss 名、颜色换 PURPLE、进度换成它自己的血量；它一死自动退回波次条。
开关 `boss_bar.boss_bar_enabled`。

### 3.1 超级袭击是两只 boss（`*_super` 的终波都是 `boss: 2`）

`fac_super` / `cog_super` / `rrc_super` / `whaler_super` / `wrecker_super` 的终波全是
`infantry 6 + elite 2 + boss 2`；`asgharian_super` 的 boss 名单还是**两种**
（`candle_fiend` / `soul_ripper`），按权重随机两只 —— 可能一火一魂，也可能同种两只。

一条 bar 装不下两只，所以：**第一只继续用 scgextra 那条 bar**，第 2..N 只由 `RaidBossBars`
另外开 `ServerBossEvent`（名字 = boss 名、紫色、自己的血量；玩家列表按「离袭击中心 512 格内的存活玩家」
每刻同步 —— 幂等，值没变不发包）。这样屏幕上的条数正好 = boss 数量；
终波本来没有独立的波次条，所以不会更挤。开关 `boss_bar.extra_boss_bars`。
一只 boss 死了就从列表里消失、剩下的自动补位；全死光退回波次条。

### 3.2 自带血条的 boss 不能重复挂（`scgextra:wrecker_dozer` 扫荡者推土机）

扫了 scgextra 3.1.3 里全部 class 的常量池，引用 `ServerBossEvent` 的只有两个：
`WaveRaidManager`（那条波次条）和 `WreckerDozerEntity`。后者是自己一套，和原版凋灵同模式：

```java
private final ServerBossEvent bossEvent = new ServerBossEvent(this.getDisplayName(), RED, PROGRESS);
public void tick() { bossEvent.setProgress(getHealth() / getMaxHealth()); }        // 每刻刷
public void startSeenByPlayer(ServerPlayer p) { bossEvent.addPlayer(p); }          // 玩家看到它就有条
public void stopSeenByPlayer(ServerPlayer p)  { bossEvent.removePlayer(p); }       // 看不到了就收
public void setCustomName(Component name)     { bossEvent.setName(getDisplayName()); }
```

所以 `wrecker_super`（终波 2 台推土机）如果我们也接管，会变成「我们那条 + 它自带 2 条」三条重复。

**修法**：自带血条的 boss 一律不接管（`RaidBar.usesOwnBossBar`），判定 = 沿实体类的继承链找有没有
`ServerBossEvent` 类型的字段（反射一次、按 `Class` 缓存）。用字段探测而不是写死实体名单：
数据包换 boss、或者以后 scgextra 给别的 boss 加血条，都不用改代码。

- 混合情况也覆盖：一只自带、一只不自带时，只有不自带的那只走我们的条
- 风险：某个实体「声明了 `ServerBossEvent` 字段但从不显示」会被误判成自带血条而没人管它 ——
  那种情况把 `boss_bar.detect_own_boss_bar` 关掉即可
- `wrecker_super` 终波的实际观感：波次条（按血量）+ 推土机自带的 2 条。如果觉得三条太挤，
  下一步可以加「终波时把那条波次条藏起来」，目前没做

> 代价：boss 模式下每刻会覆盖一次标题，而 scgextra 每刻发现「bar 上的名字 ≠ 波次名」又会写回波次名，
> 于是每刻多一个名字包（很小，画面也不会闪，因为同一刻内先写它再写我们）。
> 要彻底消掉得再 `@Redirect` 它的 `setName`，那就得在 mixin 里写 MC 成员的 SRG 名 ——
> 本项目的惯例是 mixin 里只出现 mod 成员、MC 调用全部放 helper 类，暂时不破例。

### 挂点核对（都在实装 jar 上做过）

- `javap -c` 确认 `WaveRaidState.updateRaiders` 开头是 `raiders.entrySet()` → `invokedynamic(Predicate)`
  → `INVOKEINTERFACE java/util/Set.removeIf`（`@Redirect` 挂这里）
- 编译后用 `javap -v` 确认：annotation 里的 `method` / `target` 字符串原样保留；
  `raidplus$pruneRoster` 是非 static 的 `(Set, Predicate)Z`；`@Shadow private ServerBossEvent bossBar` 在
- refmap 仍然是空的（目标全是 mod 成员 + JDK 的 `Set`，没有 MC 成员需要重映射）

---

## 2026-09-18（第二轮）· 打完一波不过波 + 波次之间的等待

**用户反馈**：① 怪全死了，但袭击条没走完、进不了下一波；② 打完一波下一波马上就刷出来，想要原版袭击那种等待进度。

**状态：两条都已修（编译 + 部署，47031 B / SHA-256 `C505D9999FDC2328…`），待游戏内验证。**

### 1. 「打完一波不过波」= 名单里握着「墓碑实体」

**证据链（从 MC 1.20.1 的源码反编译件里核出来的）**：

```java
// Entity：final，而且只在当前为 null 时才写入 —— 一旦标上就再也清不掉
public final void setRemoved(RemovalReason r) { if (this.removalReason == null) this.removalReason = r; ... }

// PersistentEntitySectionManager.m_157585_（区块卸载）：标记 + 摘掉 levelCallback，对象留在内存里当墓碑
private void m_157585_(EntityAccess e) {
   e.m_142467_(Entity.RemovalReason.UNLOADED_TO_CHUNK);   // setRemoved
   e.m_141960_(EntityInLevelCallback.f_156799_);
}

// PersistentEntitySectionManager.m_157582_（区块重新加载）：实体是**从存档新建的另一个对象**（同一 UUID）
private void m_157582_() {
   while ((entities = this.f_157500_.poll()) != null) {
      entities.m_156792_().forEach(e -> this.m_157538_(e, true));   // addEntity
   }
}
```

于是整条链是：怪被卸载过一次 → 名单里握着**旧对象**（永远 `UNLOADED_TO_CHUNK`、血量停在卸载那一刻）
→ 玩家把世界里的**新对象**打死 → 名单照样非空 → `raidersLeft()` 永远 &gt; 0 → 不过波；
那条 bar 也一直按旧对象的血量算进度，所以看起来「没走完」。

日志侧的证据：两次袭击开局间隔 ≈11 分钟（`17:24:37 → 17:35:30`），
正好是 `RAID_TIMEOUT_TICKS = 12000` —— 说明那一场是**卡到超时判失败**的。

**修法**（`RaidRoster`）：

- **每刻都用 `level.getEntity(uuid)` 重新解析**，解析到就换成世界里的那个对象（关键一步；
  上一版只在值为 null 时才解析，所以墓碑永远留在名单里）
- `isGone` 改成**先判血量**：`getHealth() <= 0` 直接算死，不管它有没有被标成 `UNLOADED_TO_CHUNK`
- 解析不到时保留（没加载不能算死）；但如果「它最后所在区块是加载着的、世界里却找不到它」，
  连续 60 刻确认后才判它没了（避开区块刚加载那一两刻实体还没进查找表的窗口）
- `wave_debug=true` 的日志加厚：除统计外还会逐只打出
  `剩下：<怪名> 血量 x/y，坐标 (…)，离中心 N 格，区块已加载=…` —— 下次再卡，一眼看出剩的那只在哪

### 2. 波与波之间的等待（原版袭击那种节奏）

scgextra 是写死的 `NEXT_WAVE_DELAY = 30`（1.5 秒）。RaidPlus 不去改它的常量，而是在
`tickRaid` 结尾把它私有的 `nextWaveDelay` **按住**：这一波清完、又不是终波、我们的倒计时还没到时，
就把值写回 30，`nextWaveDelay-- < 0` 永远不成立 → 波次不推进；倒计时到了就放手，
scgextra 自己会在约 31 刻后推进（这 31 刻算进倒计时里，所以显示的秒数对得上）。

倒计时画在那条 bar 上：`FAC Raid Wave 2 · 下一波 8 秒`，进度 = 剩余比例。
文案走本 mod 自己的 lang（`assets/scgextra_raidplus/lang/{zh_cn,en_us}.json`，
新增键 `scgextra_raidplus.raid.next_wave`；这也是 `assets/` 目录里第一次有文件）。
配置：`wave_delay.wave_delay_enabled` / `wave_delay.wave_delay_ticks`（默认 **100 = 5 秒**，上限 1200；
第一轮实测用的是 200 = 10 秒，日志里 4 次过波各留下 15 秒名单空窗，和配置对得上，用户随后要求缩到 5 秒）。

> 这个「按住」依赖 `@Shadow private int nextWaveDelay`（非 final，可写）。
> 以后如果 scgextra 换了推进逻辑（不再用这个字段），注入会因为 `defaultRequire = 1` 直接报错，
> 而不是静默失效 —— 看日志就知道要更新挂点。

---

## 其他待办

- `未能加载有效的 ResourcePackInfo`（jar 里缺 `pack.mcmeta`）：见 `README.md` 的「已知问题（暂不处理）」，用户要求等下次反馈。
