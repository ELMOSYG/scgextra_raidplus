# SCG Extra: Raid Plus

> A Forge 1.20.1 add-on that overhauls **SCG Extra**'s wave raids: a real raid center (where the flare lands),
> ring spawning around it, raiders that converge when they have nothing to shoot at, maids as valid targets,
> health-based progress, a health bar per boss, and a between-wave countdown.

给 **SCG Extra (scgextra) 的波次袭击系统**补五样东西：一个真正的袭击中心、围着中心刷怪、怪会朝中心靠拢、目标池加上女仆、新怪带迅捷；
再收三个尾巴（都是「和原版村庄袭击不一样」的地方）：区块卸载不再被当成怪死了（原来的「玩家死亡自动过波」就来自这里）、进度条按总血量算、关底 boss 有血条；
最后改一处节奏：一波清完不再「下一波马上贴脸刷出来」，而是像原版袭击那样等一会儿，倒计时直接画在那条 bar 上。

- 加载器：Forge 1.20.1（47.x）
- 依赖：Scorched Guns 2 `0.5.5+`、SCG Extra `3.1.0+`（本地按 **3.1.3** 编译与验证）；Touhou Little Maid 可选
- 附属 mod，不改 scgextra 本体；所有改动都能用配置逐项关掉

## 八个改动

| # | 问题（scgextra 原实现） | 本 mod 的做法 |
|---|---|---|
| 1 | 袭击中心是**发射信号弹时玩家的位置**（`WaveRaidManager.startRaid` 里的 `player.position()`），而且 `WaveRaidState.updateRaiders()` 每 20 刻把中心重算成**所有袭击怪位置的平均值** —— 那不是中心，是个跟着怪（和被追的玩家）漂的质心 | 中心改成**信号弹的落点**（`RaidFlareEntity.performBurst` 时信号弹自己的坐标），并且**锁住**不再重算 |
| 2 | 刷怪环虽然画在中心外 30~44 格，但候选点还要满足「离玩家 32~48 格」，而且**每波都拿当时最近的玩家重算** —— 环实际跟着玩家跑。另外「中心上方 12 格不是空气」时直接返回 `null`，`startRaid` 拿到 null 就**静默放弃**（洞穴/下界/树林里打信号弹可能完全没反应） | 改成以袭击中心为圆心的**环带**（默认 32~48 格），只做地形校验；40 次尝试都失败就退回中心地表，**永远不会静默失败** |
| 3 | 袭击怪是纯目标驱动 AI（`GetCloseToTarget` / `WalkUpToIdealRange` 之类都要 `ATTACK_TARGET`），刷怪时给的那个指向玩家的 `WalkTarget` 只活 200 刻。目标一丢就**原地发呆**，玩家得一个个去找 | 补上原版 `PathfindToRaidGoal` 那一环：没有可打目标的怪、离中心超过 `converge_distance` 时，朝中心走一小步。**正在追击的怪完全不碰** |
| 4 | 目标池里**只有玩家**：SC2 的怪 `registerGoals` 里只写 `NearestAttackableTargetGoal(this, Player.class, true)`；scgextra 自己的 `VarRangePlayerSensor` 也只往 `NEAREST_VISIBLE_TARGETABLE_PLAYER` 塞玩家，再由 `StartAttacking` 转成 `ATTACK_TARGET`。女仆只会因为先开枪才被反击 | 定期重选目标，**候选池 = 玩家 + TLM 女仆**，谁近打谁。先占住 `ATTACK_TARGET`（原版 `StartAttacking` 要求该记忆为空才运行）就没人会把女仆目标改回玩家（**兜底性质**，见下方注） |
| 5 | 刷怪环在 32~48 格外，怪慢悠悠走过来 | 新刷出的袭击怪带**迅捷 I / 15 秒**（`spawn_buff`，可关、可改时长等级） |
| 6 | 名单的「死亡」判据把**区块没加载**也算进去：`WaveRaidState.updateRaiders()` 里 `level.getEntity(uuid)` 解析不到就直接删，`isRemoved()`（含 `UNLOADED_TO_CHUNK`）也删。玩家死亡后在远处复活 / 玩家单纯跑远 → 袭击区区块卸载 → 整波被判死 → `raidersLeft()==0` → **自动过波**，一路把剩余波次烧完，最后还按「击退」发战利品 | 解析不到、或者 `isRemoved()` 但原因是 `UNLOADED_TO_CHUNK` 的怪一律**保留**；只有「能解析到且已 removed（或血量 ≤ 0）」才算死（`roster`） |
| 7 | 进度条是 `raidersLeft() / totalWaveSpawned()` —— 分子分母都是「还剩几只」；原版村庄袭击是「活着的袭击怪总血量 / 累计总血量」 | 刷怪时按 `getMaxHealth()` 累加本波血量上限，过波清零；进度 = Σ存活怪当前血量 / 本波上限，未加载的怪按出生上限计（`progress`） |
| 8 | 关底 boss 没有血条：全 scgextra 只有 `WaveRaidManager.bossBar` 那一条波次条，BOSS 档的怪（例如 `scgextra:fac_tank`）身上什么都没有（对比 SC2 自己的 boss `ScampTankEntity` 是带 `bossEvent` 的）。另外超级袭击的终波是 **`boss: 2`**（`*_super` 全是 `infantry 6 + elite 2 + boss 2`），一条 bar 也装不下两只 | 终波刷出 BOSS 档怪后：**第一只**把那条 bar 换成 boss 血条（名字 = boss 名、颜色 = 紫、进度 = 它自己的血量），**第 2..N 只**由本 mod 另外开条（`extra_boss_bars`）—— 屏幕上的条数正好等于 boss 数量；一只死了剩下的自动补位，全死光退回波次条。**自带血条的 boss 不接管**（`detect_own_boss_bar`）：`scgextra:wrecker_dozer`（扫荡者推土机）自己就是 `ServerBossEvent + startSeenByPlayer/stopSeenByPlayer` 那一套，再挂一条会重复；判定是沿实体类继承链找有没有 `ServerBossEvent` 类型的字段 |
| 9 | 波与波之间**没有停顿**：`WaveRaidManager.NEXT_WAVE_DELAY = 30`（1.5 秒）是写死的，一波清完下一波马上在同一个中心刷出来，没有补子弹/换位置/救女仆的窗口 | 波次之间的等待做成配置（`wave_delay`，默认 5 秒）：这一波清完、又不是终波时，把 scgextra 私有的 `nextWaveDelay` 按住到倒计时走完，并把倒计时画在那条 bar 上（「FAC Raid Wave 2 · 下一波 8 秒」，进度 = 剩余比例） |

中心的用途很广，所以问题 1 修好之后这些一起跟着正常了：袭击公告半径、Boss 血条名单、战利品落点、下一波刷怪原点、掉落的最近玩家判定。

> **关于第 4 项的范围**：整合包里如果装了 `scg2_maid_compat`，它把女仆放进了 `scgextra:factions/player` 阵营标签，
> 那么 scgextra 的怪（脑怪走 `StartAttacking(findNearestAttackableFactionEnemy)`；被 `EntityAdjustments` 调整过的 SC2 怪走
> `NearestAttackableTargetGoal(LivingEntity.class, ... Faction.isEnemies)`）**本来就会主动打女仆**。
> 这种配置下第 4 项是**兜底**，主要给「没有阵营标签」或「手动关掉 `enable_player_faction` 但仍想让袭击怪打过来」的整合包用。
> 证据与待决定的选项见 `docs/TEST_FEEDBACK.md`。

## 配置

`config/scgextra_raidplus-common.toml`

| 键 | 默认 | 说明 |
|---|---|---|
| `raid_center.center_from_flare` | `true` | 中心 = 信号弹落点；关掉 = 退回「发射时的玩家位置」 |
| `raid_center.center_locked` | `true` | 锁住中心，阻止质心漂移；关掉 = 恢复原行为 |
| `spawn_ring.spawn_ring_enabled` | `true` | 以袭击中心为圆心刷怪；关掉 = 保留原来的「离玩家 32~48 格」筛选（圆心仍是袭击中心） |
| `spawn_ring.spawn_ring_min` / `_max` | `32.0` / `48.0` | 环带内外半径 |
| `spawn_ring.spawn_min_player_distance` | `20.0` | 候选点离任意玩家的最小水平距离，防止贴脸刷怪；`0` = 不限制 |
| `converge.converge_enabled` | `true` | 靠拢总开关 |
| `converge.converge_distance` | `24.0` | 离中心多少格以内不再驱赶 |
| `converge.converge_speed` | `1.0` | 走路速度倍率 |
| `converge.converge_interval` | `20` | 每多少刻重下一次走路指令 |
| `converge.converge_idle_only` | `true` | 只驱赶没有可打目标的怪（正在追你的怪不碰）。改成 `false` 就是「所有怪都退向中心」，变成守点打法 |
| `converge.converge_debug` | `false` | 把每次驱赶写进日志 |
| `targeting.targeting_enabled` | `true` | 给袭击怪重选目标（玩家 + 女仆） |
| `targeting.target_players` | `true` | 候选池包含玩家 |
| `targeting.target_maids` | `true` | 候选池包含 TLM 女仆（没装 TLM 自动失效） |
| `targeting.targeting_range` | `32.0` | 索敌半径，实际还会被怪自己的 `FOLLOW_RANGE` 压一次（SC2 的怪一般是 24~32） |
| `targeting.targeting_interval` | `20` | 每隔多少刻重选一次 |
| `targeting.targeting_require_los` | `true` | 要求视线可见（关掉就是穿墙索敌） |
| `targeting.targeting_switch_margin` | `4.0` | 新目标要比当前目标近这么多格才换，防止玩家和女仆之间横跳 |
| `targeting.targeting_debug` | `false` | 把每次改目标写进日志 |
| `spawn_buff.spawn_buff_enabled` | `true` | 新刷出的袭击怪带迅捷 |
| `spawn_buff.spawn_buff_duration` | `300` | 持续时间（刻），300 = 15 秒 |
| `spawn_buff.spawn_buff_amplifier` | `0` | `0` = 迅捷 I，`1` = 迅捷 II |
| `spawn_buff.spawn_buff_particles` | `false` | 是否显示药水粒子 |
| `roster.keep_unloaded_raiders` | `true` | 区块卸载的袭击怪不算死（关掉 = 原判据，会重现「玩家死亡自动过波」） |
| `roster.wave_debug` | `false` | 调试：把名单剪枝结果写进日志（`确认死亡 X 只，未加载保留 Y 只，名单里还有 Z 只`） |
| `progress.progress_by_health` | `true` | 进度按总血量算（关掉 = 原来的按数量） |
| `boss_bar.boss_bar_enabled` | `true` | 关底 boss 血条（关掉 = 那条 bar 永远是波次条） |
| `boss_bar.extra_boss_bars` | `true` | 超级袭击第 2..N 只 boss 各开一条血条（关掉 = 只显示第一只） |
| `boss_bar.detect_own_boss_bar` | `true` | 自带血条的 boss 不接管（关掉 = 一律由本 mod 接管，扫荡者推土机会出现两条） |
| `wave_delay.wave_delay_enabled` | `true` | 波与波之间加等待（关掉 = 恢复 scgextra 原来的 1.5 秒） |
| `wave_delay.wave_delay_ticks` | `100` | 等待时长（刻），100 = 5 秒；`0` = 不额外等待 |

## 实现（mixin 挂点）

| 类 | 挂点 | 作用 |
|---|---|---|
| `RaidFlareEntityMixin` | `RaidFlareEntity.performBurst` HEAD | 记下信号弹落点 |
| `WaveRaidManagerMixin` | `WaveRaidManager.startRaid` HEAD | 决定本次袭击中心 |
| `WaveRaidManagerMixin` | `startRaid` / `tickRaid` 里的 `WaveRaidUtil.findWaveSpawnLocation` 调用（`@Redirect`） | 换成中心环带 |
| `WaveRaidManagerMixin` | `WaveRaidManager.tick` TAIL | 靠拢 |
| `WaveRaidManagerMixin` | `WaveRaidManager.tick` TAIL | 重选目标（玩家 + 女仆） |
| `WaveRaidManagerMixin` | `WaveRaidManager.endRaid` HEAD / `load` RETURN | 清理 / 读档时补回锁定中心 |
| `WaveRaidStateMixin` | `WaveRaidState.updateRaiders` TAIL | 把质心改回锁定中心 |
| `WaveRaidStateMixin` | `WaveRaidState.addRaider` TAIL | 新怪上迅捷 |
| `WaveRaidStateMixin` | `WaveRaidState.updateRaiders` 里的 `Set.removeIf` 调用（`@Redirect`） | 名单剪枝：未加载 ≠ 死亡 |
| `WaveRaidStateMixin` | `WaveRaidState.addRaider` TAIL | 记录本波血量上限、认出 BOSS 档怪 |
| `WaveRaidStateMixin` | `WaveRaidState.advanceWave` TAIL | 过波时清零本波血量累计 |
| `WaveRaidManagerMixin` | `WaveRaidManager.tickBossBar` TAIL | 覆盖进度（按血量）/ 终波换成 boss 血条 / 等下一波时画倒计时 |
| `WaveRaidManagerMixin` | `WaveRaidManager.tickRaid` TAIL | 按住 `nextWaveDelay` 做「波与波之间的等待」 |
| `WaveRaidStateAccessor` | `spawnCenter` / `level` / `raiders` | 私有字段入口 |

全部 `remap = false`（目标都是 mod 方法；唯一一个非 mod 目标是 JDK 的 `java/util/Set.removeIf`，同样不需要重映射），refmap 为空。
mixin 类里**不出现任何 MC 成员名**：所有 MC 调用都放在 helper 类（`RaidRoster` / `RaidBar` / `RaidCenter` / `RaidConvergence` / `RaidTargeting` / `RaidSpawnHelper`），
由 `reobfJar` 按常规重映射 —— 这也是 refmap 一直是空的原因。

> `@Inject` / `@Redirect` 的 handler **static 修饰符必须和「被注入的那个方法」一致**，
> 跟被重定向的调用本身是不是静态无关。`raidplus$findWaveSpawn` 一开始写成 static，
> 启动时直接崩在 APPLY 阶段 —— 记在这里免得再踩。

## 女仆识别（TLM 是可选依赖）

`MaidSupport` 里**一个 TLM 的类都没有直接引用**，两条识别路径：

1. 实体注册名 `touhou_little_maid:maid`（走注册表，不碰类加载，主路径）
2. 类名 `com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid` 反射解析后 `isInstance`（覆盖子类，失败也不影响主路径）

不能简单按命名空间前缀匹配 —— TLM 还有 `fairy` / `sit` / `chair` / `tombstone` / `danmaku` 等实体。

## 已验证

- 编译依赖与实装 jar **逐类 SHA-256 一致**：
  - `ScorchedGuns-0.5.5-1.20.1.jar` 与 `curse.maven:scorched-guns-2-802940:7232063` 的 `RaidFlareEntity.class` 完全相同（所以 `performBurst` 这个注入点对得上）
  - `scgextra-forge-3.1.3.jar`（`libs/`）与 mods 里的同名 jar 的 `WaveRaidManager` / `WaveRaidState` / `WaveRaidUtil` 完全相同
- 每个注入点都在 scgextra 3.1.3 的反编译源码里核对过存在且唯一
- 新增挂点用 `javap` 对着实装 jar 核过字节码：
  - `WaveRaidState.updateRaiders` 开头就是 `raiders.entrySet()` → `invokedynamic(Predicate)` → `INVOKEINTERFACE java/util/Set.removeIf`，
    所以名单剪枝的 `@Redirect` 挂在 `Set.removeIf` 上是唯一（也是最小）的挂点
  - `WaveRaidManager.tickBossBar` / `advanceWave` / `addRaider(Mob)` / 私有字段 `bossBar` 都存在且签名一致
- `m_21530_`（`spawnCurrentWaveMobs` 里刷怪时那次调用）按 `forge_gradle` 里的 `client_mappings.txt` 是 **`setPersistenceRequired`**
  —— 也就是说袭击怪本来是持久化的，**不会**因为玩家离开而 despawn；
  原来的「自动过波」不是「怪消失了」，而是「区块卸载被 `updateRaiders` 判成死亡」

## 待游戏内验证

1. 站在平地上打一发袭击信号弹 → 日志出现 `[RaidPlus] 本次袭击中心锁定在 ...`，坐标应等于信号弹落点，而不是你开火时的位置
2. 打完之后自己跑到别处 → 中心不再跟着你漂（Boss 血条名单/公告范围不变）
3. 波次刷怪应出现在落点周围 32~48 格，而不是围着你
4. 躲起来让怪失去目标 → 它们应该朝落点走，而不是原地站着
5. 洞穴/树冠下打信号弹 → 不再「什么都没发生」
6. 把女仆放在袭击区里 → 怪应该会主动打女仆（`targeting_debug=true` 能看到日志）；
   开 `targeting_debug` 时若日志里从来没有女仆，先确认 `[RaidPlus] 已接入 Touhou Little Maid` 这行有没有出现在启动日志里
7. 新刷出的怪应该有迅捷 I（开 `spawn_buff_particles` 能直接看出来）
8. **过波那条**：开 `roster.wave_debug=true`，打一发袭击信号弹，然后**故意死在远处**（或者直接跑远到袭击区区块卸载）。
   日志里应该出现 `[RaidPlus] 名单剪枝：确认死亡 0 只，判定消失 0 只，未加载保留 N 只，名单里还有 N 只`，
   后面还会逐只打出「剩下：<怪名> 血量 x/y，坐标 (…)，离中心 N 格，区块已加载=…」——
   **如果哪次又卡住，这几行会直接告诉你剩的那只在哪里**，
   而且**波次不会自己推进**；跑回去之后怪还在原地等你（不是「怪被判死 → 过波 → 一发战利品」）
9. **进度条按血量**：本波怪掉血时进度条应该平滑地降，而不是「死一只掉一大截」；
   打伤一只精英和打死一只杂兵对进度的贡献应该不一样
10. **boss 血条**：终波刷出 `fac_tank` 之后，那条 bar 的标题应该变成 **FAC Siege Tank**、颜色变紫、
    进度是它自己的血量百分比；它一死，bar 应该立刻退回「FAC Raid · Final Wave」的波次条（或者随袭击结束消失）
11. **超级袭击的两只 boss**：打 `iron_super`（FAC 超级）→ 终波两台 `fac_tank`，屏幕上应该有**两条**「FAC Siege Tank」血条，
    各掉各的；打死一台，另一台那条继续在（`asgharian_super` 是一火一魂时，两条的名字应该分别是两个 boss 的名字）
12. **自带血条的 boss 不要重复**：打 `wrecker_super` → 终波两台扫荡者推土机，**只应该有它们自带的 2 条**，
    不能再多出本 mod 挂的第三条（开 `roster.wave_debug=true` 时日志里会写「自带血条 → 不接管」）
13. **波之间的等待**：清完一波之后那条 bar 应该变成 `FAC Raid Wave 2 · 下一波 8 秒` 这样的倒计时，
    进度条随秒数往回缩，约 5 秒后下一波才刷出来（`wave_delay_ticks` 调大调小可以直接看出来）；
    终波清完不会等，直接结束发战利品

## 已知问题

> 测试反馈与「观察到但还没决定改」的东西统一记在 `docs/TEST_FEEDBACK.md`。

**~~`未能加载有效的 ResourcePackInfo` / `Missing metadata in pack mod:scgextra_raidplus`~~ —— 已修（2026-09-14）**

- 现象：游戏日志里
  `[Render thread/WARN] [net.minecraft.server.packs.repository.Pack/]: Missing metadata in pack mod:scgextra_raidplus`，
  mod 列表里也会提示这个 jar 没有有效的 ResourcePackInfo
- 原因：jar 里**没有 `pack.mcmeta`**（`assets/scgextra_raidplus/lang` 当时还是个空目录，
  所以整个 `assets/` 都没进 jar）—— Forge 给每个 mod 建内置资源包时读不到元数据
- 修法：新建 `src/main/resources/pack.mcmeta`（`pack_format` = 15，1.20.1；
  description 用 `${mod_name}`，由 `build.gradle` 的 `filesMatching(['META-INF/mods.toml', 'pack.mcmeta'])`
  在 `processResources` 阶段展开 → `"SCG Extra: Raid Plus resources"`）
- 验证：构建后 `build/resources/main/pack.mcmeta` 是合法 JSON、占位符已展开；
  部署的 jar 里能看到 `pack.mcmeta`

**~~打完一波不过波（袭击卡住直到 10 分钟超时）~~ —— 已修（2026-09-18，第二轮测试反馈）**

- 现象：怪全清光了，那条 bar 还留着进度、下一波不刷，最后整场袭击超时判失败
  （日志里两次开局间隔 ≈11 分钟，正好是 `RAID_TIMEOUT_TICKS = 12000`）
- 原因：**名单里握着的那只实体对象会变成「墓碑」**。MC 的 `Entity.setRemoved(RemovalReason)`
  是 `final`，而且「只在当前为 null 时才写入」、永远不会被清掉；区块卸载时
  `PersistentEntitySectionManager` 就是拿它把实体标成 `UNLOADED_TO_CHUNK` 留在内存里，
  而区块**重新加载**时世界里的那只怪是**从存档新建的另一个对象**（同一 UUID）
- 于是「区块卸载过」的怪，名单里永远是旧对象（`UNLOADED_TO_CHUNK`）→ 玩家把新对象打死，名单也清不掉
  → `raidersLeft()` 永远 &gt; 0 → 不过波
- 修法：`RaidRoster` 现在**每刻都重新解析**，解析到就换成世界里的那个对象；
  另外 `isGone` 改成**先判血量**（死就是死，不管有没有被标成区块卸载），
  并加了「区块加载着、世界里却找不到它」连续 60 刻的宽限判定（应付更极端的情况）
- 验证：见「待游戏内验证」第 8 条

## 构建

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'
.\gradlew.bat build --offline
```

- 产物：`build/libs/scgextra_raidplus-<版本>.jar`（已经过 reobf，能直接丢进 mods）
- 编译依赖：
  - Scorched Guns 2 / GeckoLib / Framework —— 走 CurseMaven 自动下载（`build.gradle` 里已声明）
  - **SCG Extra 3.1.3** —— 别人的 mod，**不随本仓库分发**：自己去 CurseForge 下
    `scgextra-forge-3.1.3.jar` 放进 `libs/`（`build.gradle` 用 `flatDir` 引用它，文件名要对上）。
    版本必须和整合包里实装的一致，否则 `WaveRaid*` 的注入点可能对不上。
- 本地部署（可选）：在 `~/.gradle/gradle.properties` 里写一行
  `mods_folder=D\:\\MCJAVA\\.minecraft\\versions\\1.20.1-Forge_47.4.21\\mods`，
  `build` 结束会自动把 jar 拷进那个目录；不写、或目录不存在就跳过（jar 只留在 `build/libs`）。
- `gradle.properties` 里的 `org.gradle.java.home` 是本机 JDK 17 路径，换机器构建时删掉或改成你自己的。
- **游戏运行时不要覆盖它已经加载的那个 jar**（会导致 zip index 失效 / NoClassDefFoundError）。

## 许可

GNU GPLv3，见 `LICENSE`。`libs/` 里放的是第三方 mod 的 jar，不在本仓库的许可范围内，也不随仓库分发。
