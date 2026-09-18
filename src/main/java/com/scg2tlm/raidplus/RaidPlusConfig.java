package com.scg2tlm.raidplus;

import net.minecraftforge.common.ForgeConfigSpec;

/** 全部开关与数值。默认值 = 我们建议的「像原版村庄袭击」那套。 */
public final class RaidPlusConfig {
    public static final ForgeConfigSpec SPEC;

    public static final ForgeConfigSpec.BooleanValue CENTER_FROM_FLARE;
    public static final ForgeConfigSpec.BooleanValue CENTER_LOCKED;

    public static final ForgeConfigSpec.BooleanValue SPAWN_RING_ENABLED;
    public static final ForgeConfigSpec.DoubleValue SPAWN_RING_MIN;
    public static final ForgeConfigSpec.DoubleValue SPAWN_RING_MAX;
    public static final ForgeConfigSpec.DoubleValue SPAWN_MIN_PLAYER_DISTANCE;

    public static final ForgeConfigSpec.BooleanValue CONVERGE_ENABLED;
    public static final ForgeConfigSpec.DoubleValue CONVERGE_DISTANCE;
    public static final ForgeConfigSpec.DoubleValue CONVERGE_SPEED;
    public static final ForgeConfigSpec.IntValue CONVERGE_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue CONVERGE_IDLE_ONLY;
    public static final ForgeConfigSpec.BooleanValue CONVERGE_DEBUG;

    public static final ForgeConfigSpec.BooleanValue TARGETING_ENABLED;
    public static final ForgeConfigSpec.BooleanValue TARGET_PLAYERS;
    public static final ForgeConfigSpec.BooleanValue TARGET_MAIDS;
    public static final ForgeConfigSpec.DoubleValue TARGETING_RANGE;
    public static final ForgeConfigSpec.IntValue TARGETING_INTERVAL;
    public static final ForgeConfigSpec.BooleanValue TARGETING_REQUIRE_LOS;
    public static final ForgeConfigSpec.DoubleValue TARGETING_SWITCH_MARGIN;
    public static final ForgeConfigSpec.BooleanValue TARGETING_DEBUG;

    public static final ForgeConfigSpec.BooleanValue SPAWN_BUFF_ENABLED;
    public static final ForgeConfigSpec.IntValue SPAWN_BUFF_DURATION;
    public static final ForgeConfigSpec.IntValue SPAWN_BUFF_AMPLIFIER;
    public static final ForgeConfigSpec.BooleanValue SPAWN_BUFF_PARTICLES;

    public static final ForgeConfigSpec.BooleanValue KEEP_UNLOADED_RAIDERS;
    public static final ForgeConfigSpec.BooleanValue WAVE_DEBUG;

    public static final ForgeConfigSpec.BooleanValue PROGRESS_BY_HEALTH;

    public static final ForgeConfigSpec.BooleanValue BOSS_BAR_ENABLED;
    public static final ForgeConfigSpec.BooleanValue EXTRA_BOSS_BARS;
    public static final ForgeConfigSpec.BooleanValue DETECT_OWN_BOSS_BAR;

    public static final ForgeConfigSpec.BooleanValue WAVE_DELAY_ENABLED;
    public static final ForgeConfigSpec.IntValue WAVE_DELAY_TICKS;

    static {
        ForgeConfigSpec.Builder builder = new ForgeConfigSpec.Builder();

        builder.push("raid_center");
        builder.comment("把袭击中心改成信号弹的落点（也就是信号弹炸开的位置），而不是发射时玩家所在的位置。",
                "scgextra 原实现用的是 player.position()，而且每 20 刻还会把中心重算成所有袭击怪的平均位置。");
        CENTER_FROM_FLARE = builder.define("center_from_flare", true);

        builder.comment("锁住中心：阻止 scgextra 每 20 刻把中心重算成袭击怪的质心。",
                "关掉的话中心会重新开始跟着怪（也就是跟着被追的玩家）漂。");
        CENTER_LOCKED = builder.define("center_locked", true);
        builder.pop();

        builder.push("spawn_ring");
        builder.comment("波次刷怪改成以袭击中心为圆心的环带，并且不再按「离玩家 32~48 格」筛选。");
        SPAWN_RING_ENABLED = builder.define("spawn_ring_enabled", true);

        builder.comment("环带内半径（格）。怪实际会散布在这个环带附近 15 格的范围内。");
        SPAWN_RING_MIN = builder.defineInRange("spawn_ring_min", 32.0, 4.0, 160.0);

        builder.comment("环带外半径（格）。");
        SPAWN_RING_MAX = builder.defineInRange("spawn_ring_max", 48.0, 5.0, 200.0);

        builder.comment("候选点离任意玩家的最小水平距离（格）。0 = 不限制。",
                "去掉原来那个「必须离玩家 32~48 格」的筛选之后，玩家站在中心附近时怪有可能贴脸刷出来，这个值兜底。");
        SPAWN_MIN_PLAYER_DISTANCE = builder.defineInRange("spawn_min_player_distance", 20.0, 0.0, 128.0);
        builder.pop();

        builder.push("converge");
        builder.comment("让袭击怪在「没有可打的目标」时朝袭击中心靠拢（复刻原版袭击怪的 PathfindToRaidGoal）。",
                "有目标时完全不干预，让它们继续用 scgextra 自己的战斗 AI。");
        CONVERGE_ENABLED = builder.define("converge_enabled", true);

        builder.comment("离中心多少格以内就不再驱赶（让它们自由警戒/游走）。");
        CONVERGE_DISTANCE = builder.defineInRange("converge_distance", 24.0, 4.0, 128.0);

        builder.comment("靠拢时的移动速度倍率。");
        CONVERGE_SPEED = builder.defineInRange("converge_speed", 1.0, 0.2, 2.0);

        builder.comment("每隔多少刻重新下一次移动指令（太小会让路径反复重算）。");
        CONVERGE_INTERVAL = builder.defineInRange("converge_interval", 20, 5, 200);

        builder.comment("只驱赶「当前没有可打目标」的怪。正在追击玩家的怪完全不碰，避免和它们自己的战斗 AI 抢寻路。");
        CONVERGE_IDLE_ONLY = builder.define("converge_idle_only", true);

        builder.comment("调试：把每次靠拢驱赶写进日志。");
        CONVERGE_DEBUG = builder.define("converge_debug", false);
        builder.pop();

        builder.push("targeting");
        builder.comment("给袭击怪重新选目标，候选池 = 玩家 + 女仆（TLM）。",
                "原实现只会把「开火的那个玩家」或「最近的玩家」塞给怪；SC2/scgextra 自己那套 AI 也只认 Player.class，",
                "所以女仆永远不会被 raid 主动攻击（只会因为她们先开枪、被 HurtByTarget 反击）。",
                "注意 ATTACK_TARGET 是单值记忆，一个怪同一时间只能有一个目标 —— 这里是「谁近打谁」，不是同时挂两个。");
        TARGETING_ENABLED = builder.define("targeting_enabled", true);

        builder.comment("候选池里包含玩家。");
        TARGET_PLAYERS = builder.define("target_players", true);

        builder.comment("候选池里包含 TLM 女仆（没装 TLM 时自动失效）。");
        TARGET_MAIDS = builder.define("target_maids", true);

        builder.comment("索敌半径（格）。实际取值还会被怪自己的 FOLLOW_RANGE 压一次 ——",
                "超过跟随范围的目标准会被 scgextra 的 isTargetStillValid 判为无效然后被清掉。");
        TARGETING_RANGE = builder.defineInRange("targeting_range", 32.0, 4.0, 128.0);

        builder.comment("每隔多少刻重选一次目标。");
        TARGETING_INTERVAL = builder.defineInRange("targeting_interval", 20, 5, 200);

        builder.comment("要求视线可见才锁定（和原版/ scgextra 的传感器一致，避免隔墙点人）。",
                "关掉就是穿墙索敌。");
        TARGETING_REQUIRE_LOS = builder.define("targeting_require_los", true);

        builder.comment("换目标需要的「明显更近」幅度（格）。",
                "只在当前目标不是活着的可打对象、或者新目标比它近这么多格时才换，避免玩家和女仆距离接近时来回切目标。");
        TARGETING_SWITCH_MARGIN = builder.defineInRange("targeting_switch_margin", 4.0, 0.0, 32.0);

        builder.comment("调试：把每次改目标写进日志。");
        TARGETING_DEBUG = builder.define("targeting_debug", false);
        builder.pop();

        builder.push("spawn_buff");
        builder.comment("新刷出来的袭击怪带一段时间的迅捷，让它们从刷怪环上更快压过来。");
        SPAWN_BUFF_ENABLED = builder.define("spawn_buff_enabled", true);

        builder.comment("持续时间（刻）。300 = 15 秒。");
        SPAWN_BUFF_DURATION = builder.defineInRange("spawn_buff_duration", 300, 20, 24000);

        builder.comment("等级：0 = 迅捷 I，1 = 迅捷 II。");
        SPAWN_BUFF_AMPLIFIER = builder.defineInRange("spawn_buff_amplifier", 0, 0, 4);

        builder.comment("是否显示药水粒子。关掉画面干净些，但看不出谁被加速了。");
        SPAWN_BUFF_PARTICLES = builder.define("spawn_buff_particles", false);
        builder.pop();

        builder.push("roster");
        builder.comment("袭击怪名单的剪枝规则（决定「这只怪算不算死了 / 这一波算不算清完了」）。",
                "原实现把「区块没加载、ServerLevel.getEntity 解析不到」的怪也算成死了：",
                "玩家死亡后在远处复活（或者只是跑远），袭击区区块一卸载，整波怪就被判死，",
                "WaveRaidManager 看到名单为空就自动过波 —— 一路把剩余波次烧完，最后还按「击退」发战利品。",
                "开着的时候：解析不到、或者 RemovalReason = UNLOADED_TO_CHUNK 的怪一律保留，",
                "只有「能解析到且已 removed」或「血量 ≤ 0」才算死。");
        KEEP_UNLOADED_RAIDERS = builder.define("keep_unloaded_raiders", true);

        builder.comment("调试：把名单剪枝的结果写进日志（排查「怪还在但过波了」时打开）。");
        WAVE_DEBUG = builder.define("wave_debug", false);
        builder.pop();

        builder.push("progress");
        builder.comment("进度条按血量算，而不是按「还剩几只」。",
                "原实现是 raidersLeft / totalWaveSpawned（分子分母都是数量），",
                "原版村庄袭击是「活着的袭击怪总血量 / 累计总血量」。",
                "血量在每波刷怪时按 getMaxHealth() 累加、过波清零；",
                "未加载的怪按出生血量上限计，区块卸载不会让进度凭空掉一截。");
        PROGRESS_BY_HEALTH = builder.define("progress_by_health", true);
        builder.pop();

        builder.push("boss_bar");
        builder.comment("关底 boss 的血条：终波刷出 BOSS 档怪（按数据包的 boss 名单比对实体类型）之后，",
                "把那条 bar 的标题换成 boss 名、进度换成 boss 自己的血量百分比、颜色换成紫色；",
                "boss 死了自动退回原来的波次条。",
                "scgextra 本体只做了那条波次条，BOSS 档的怪（例如 scgextra:fac_tank）身上是没有血条的",
                "（对比 SC2 自己的 boss，ScampTankEntity 是带 bossEvent 的）。");
        BOSS_BAR_ENABLED = builder.define("boss_bar_enabled", true);

        builder.comment("超级袭击的终波是 boss: 2（fac_super / cog_super / rrc_super / whaler_super / wrecker_super / asgharian_super），",
                "一条 bar 装不下两只 boss。开着的时候：刷怪顺序里的第一只继续用上面那条 bar，第 2..N 只另外开条",
                "（玩家列表照 scgextra 的做法，按离袭击中心 512 格内的存活玩家同步）；关掉 = 只显示第一只。");
        EXTRA_BOSS_BARS = builder.define("extra_boss_bars", true);

        builder.comment("自带血条的 boss 不接管：scgextra:wrecker_dozer（扫荡者推土机）自己就有一套",
                "ServerBossEvent + startSeenByPlayer/stopSeenByPlayer（和原版凋灵一个模式），再给它挂一条就是重复。",
                "判定方式 = 看实体类里有没有 ServerBossEvent 类型的字段（数据包换 boss、以后 scgextra 给别的 boss 加血条都不用改代码）。",
                "关掉 = 一律由本 mod 接管（自带血条的 boss 身上会出现两条 bar）。");
        DETECT_OWN_BOSS_BAR = builder.define("detect_own_boss_bar", true);
        builder.pop();

        builder.push("wave_delay");
        builder.comment("波与波之间的等待（原版村庄袭击那种节奏）。",
                "scgextra 原实现是写死的 30 刻（1.5 秒）：一波清完，下一波马上就在同一个中心刷出来，",
                "中间没有补子弹/换位置/救女仆的窗口。",
                "开着的时候我们会把它按住到本配置的时长，并在那条 bar 上显示倒计时",
                "（名字后面接「下一波 N 秒」，进度 = 剩余比例）。关掉 = 恢复 scgextra 原来的 1.5 秒。");
        WAVE_DELAY_ENABLED = builder.define("wave_delay_enabled", true);

        builder.comment("等待时长（刻）。100 = 5 秒。0 = 不额外等待（只剩 scgextra 自己那 1.5 秒）。");
        WAVE_DELAY_TICKS = builder.defineInRange("wave_delay_ticks", 100, 0, 1200);
        builder.pop();

        SPEC = builder.build();
    }

    private RaidPlusConfig() {
    }
}
