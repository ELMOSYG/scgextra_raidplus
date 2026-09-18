package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import com.scg2tlm.raidplus.RaidPlusMod;
import com.scg2tlm.raidplus.mixin.WaveRaidStateAccessor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import javax.annotation.Nullable;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.zincstudios.scgextra.raid.WaveRaidData;
import net.zincstudios.scgextra.raid.WaveRaidState;
import net.zincstudios.scgextra.raid.WaveRaidUtil;

/**
 * 那条袭击 Boss 条显示什么。
 *
 * <h3>原实现</h3>
 * <ul>
 *   <li>进度：{@code setProgress(raidersLeft() / getTotalWaveSpawned())} —— 分子分母都是「还剩几只」，
 *       而原版村庄袭击是「活着的袭击怪总血量 / 累计总血量」。</li>
 *   <li>关底 boss 没有自己的血条：全 scgextra 只有 {@code WaveRaidManager.bossBar} 这一条波次条，
 *       BOSS 档的怪（例如 {@code scgextra:fac_tank}）身上什么也没有
 *       （对比 SC2 自己的 boss 是有的：{@code ScampTankEntity.bossEvent}）。</li>
 * </ul>
 *
 * <h3>这里怎么做</h3>
 * <ul>
 *   <li>刷怪时（{@code WaveRaidState.addRaider}）按 {@code getMaxHealth()} 累加本波血量上限；
 *       进度 = Σ存活怪当前血量 / 本波累计上限，过波（{@code advanceWave}）清零。</li>
 *   <li>未加载 / 值还没解析回来的怪按「出生血量上限」计：区块卸载不该让进度凭空掉一截。</li>
 *   <li>刷出 BOSS 档怪（按数据包的 boss 名单比对实体类型）后按刷怪顺序记住它们的 UUID；
 *       <b>第一只</b>把 scgextra 那条 bar 变成它的血条（名字 = boss 名，颜色 = 紫，进度 = 它自己的血量），
 *       <b>第 2..N 只</b>（超级袭击的终波是 {@code boss: 2}）由 {@link RaidBossBars} 另外开条。
 *       一只 boss 死了就从列表里消失，剩下的自动补位；全死光就退回波次条。</li>
 *   <li><b>自带血条的 boss 不接管</b>：{@code scgextra:wrecker_dozer}（扫荡者推土机）自己是
 *       {@code new ServerBossEvent(...) + startSeenByPlayer/stopSeenByPlayer} 那一套（和原版凋灵一样），
 *       再给它挂一条就会重复。判定方式是看实体类里有没有 {@code ServerBossEvent} 类型的字段
 *       （{@link #usesOwnBossBar}），开关 {@code boss_bar.detect_own_boss_bar}。</li>
 * </ul>
 */
public final class RaidBar {
    /** 每次袭击一份（弱引用键，袭击结束后自动回收）。 */
    private static final Map<WaveRaidState, WaveStats> STATS = Collections.synchronizedMap(new WeakHashMap<>());

    /** 「这个实体类自带 boss 血条吗」的缓存。 */
    private static final Map<Class<?>, Boolean> OWN_BOSS_BAR = new ConcurrentHashMap<>();

    private RaidBar() {
    }

    private static final class WaveStats {
        /** 本波所有刷出来的怪的 {@code getMaxHealth()} 之和。 */
        private double waveTotal;
        /** 每只怪出生时的血量上限，未加载时用它兜底。 */
        private final Map<UUID, Double> spawnHealth = new HashMap<>();
        /** BOSS 档怪的 UUID，按刷怪顺序（普通袭击 1 只，超级袭击 2 只）。 */
        private final List<UUID> bossIds = new ArrayList<>();
    }

    /** 每刷出一只袭击怪调用一次（挂在 {@code WaveRaidState.addRaider} 结尾）。 */
    public static void onRaiderSpawn(WaveRaidState state, Mob mob) {
        WaveStats stats = STATS.computeIfAbsent(state, ignored -> new WaveStats());
        double maxHealth = mob.getMaxHealth();
        stats.waveTotal += maxHealth;
        stats.spawnHealth.put(mob.getUUID(), maxHealth);

        if (isBossRank(state, mob)) {
            stats.bossIds.add(mob.getUUID());
            if (RaidPlusConfig.WAVE_DEBUG.get()) {
                RaidPlusMod.LOGGER.info("[RaidPlus] 本波第 {} 只 boss：{}（{} 血{}）",
                        stats.bossIds.size(), mob.getDisplayName().getString(), (int) maxHealth,
                        usesOwnBossBar(mob) ? "，自带血条 → 不接管" : "");
            }
        }
    }

    /** 过波：本波累计清零（boss 记录也一起清，下一波刷出来的 boss 重新认）。 */
    public static void onWaveAdvanced(WaveRaidState state) {
        WaveStats stats = STATS.get(state);
        if (stats != null) {
            stats.waveTotal = 0.0;
            stats.spawnHealth.clear();
            stats.bossIds.clear();
        }
    }

    /** 袭击结束：清记录（额外的 boss 血条也一起收掉）。 */
    public static void forget(WaveRaidState state) {
        STATS.remove(state);
        RaidBossBars.clear(state);
    }

    /**
     * 把 bar 改成我们要的样子。挂在 {@code WaveRaidManager.tickBossBar} 结尾 ——
     * scgextra 已经先按数量算过一次进度、也写过波次名，这里覆盖掉。
     *
     * <p>boss 模式下每刻会覆盖一次名字：scgextra 每刻发现「bar 上的名字 ≠ 它的波次名」就会再写一次波次名，
     * 于是每刻多一个名字包。名字包很小、也不会闪（同一刻内先写它再写我们），先这么放着。</p>
     */
    public static void apply(@Nullable WaveRaidState state, @Nullable ServerBossEvent bar) {
        if (state == null || bar == null) {
            return;
        }

        List<LivingEntity> bosses = RaidPlusConfig.BOSS_BAR_ENABLED.get() ? bossesOf(state) : List.of();
        if (RaidPlusConfig.DETECT_OWN_BOSS_BAR.get()) {
            // 自带血条的（扫荡者推土机）交给它自己，再挂一条就是重复
            bosses = bosses.stream().filter(boss -> !usesOwnBossBar(boss)).toList();
        }

        // 一波清完、正在等下一波：这条 bar 让给倒计时（原版村庄袭击也是「清完一波 → 等一会儿 → 下一波」）
        ServerLevel level = ((WaveRaidStateAccessor) state).getLevel();
        long waiting = RaidWaveDelay.remainingTicks(state, level);
        if (waiting > 0L) {
            long delay = Math.max(1L, RaidPlusConfig.WAVE_DELAY_TICKS.get());
            int seconds = (int) Math.ceil(waiting / 20.0D);
            bar.setName(WaveRaidUtil.getBossBarLabel(state.getWaveRaidData(), state.getCurrentWave())
                    .copy()
                    .append(" · ")
                    .append(Component.translatable("scgextra_raidplus.raid.next_wave", seconds)));
            bar.setColor(BossBarColor.RED);
            bar.setProgress(Mth.clamp((float) waiting / (float) delay, 0.0F, 1.0F));
            RaidBossBars.clear(state);
            return;
        }

        if (!bosses.isEmpty()) {
            // 第一只 boss 就用 scgextra 那条 bar
            LivingEntity primary = bosses.get(0);
            bar.setName(primary.getDisplayName());
            bar.setColor(BossBarColor.PURPLE);
            bar.setProgress(Mth.clamp(primary.getHealth() / Math.max(1.0F, primary.getMaxHealth()), 0.0F, 1.0F));
        } else {
            // 从 boss 模式退回波次模式；值没变时 ServerBossEvent 自己不会发包
            bar.setColor(BossBarColor.RED);
            if (RaidPlusConfig.PROGRESS_BY_HEALTH.get()) {
                float progress = healthProgress(state);
                if (progress >= 0.0F) {
                    bar.setProgress(progress);
                }
            }
        }

        // 超级袭击的终波是 boss: 2（asgharian_super 还可能一火一魂两种），多出来的 boss 自己开条
        if (bosses.size() > 1 && RaidPlusConfig.EXTRA_BOSS_BARS.get()) {
            RaidBossBars.sync(state, ((WaveRaidStateAccessor) state).getLevel(), state.getCenter(),
                    new ArrayList<>(bosses.subList(1, bosses.size())));
        } else {
            RaidBossBars.clear(state);
        }
    }

    /** 名单里还活着（或至少还解析得到）的 BOSS 档怪，按刷怪顺序。 */
    public static List<LivingEntity> bossesOf(WaveRaidState state) {
        WaveStats stats = STATS.get(state);
        if (stats == null || stats.bossIds.isEmpty()) {
            return List.of();
        }

        Map<UUID, LivingEntity> raiders = ((WaveRaidStateAccessor) state).getRaiders();
        List<LivingEntity> bosses = new ArrayList<>(stats.bossIds.size());
        for (UUID bossId : stats.bossIds) {
            LivingEntity boss = raiders.get(bossId);
            // null = 读档后还没解析回来（区块没加载），这种情况先不算，等它加载回来
            if (boss != null && !RaidRoster.isGone(boss)) {
                bosses.add(boss);
            }
        }
        return bosses;
    }

    /**
     * 这个 boss 是不是自带血条（实体类里有 {@code ServerBossEvent} 类型的字段）。
     *
     * <p>已核实：scgextra 3.1.3 里只有 {@code scgextra:wrecker_dozer} 这样做
     * （{@code new ServerBossEvent(...)} + {@code startSeenByPlayer} / {@code stopSeenByPlayer}），
     * 其余 BOSS 档实体（{@code fac_tank} / {@code cog_juggernaut} / {@code candle_fiend} /
     * {@code soul_ripper} / {@code flaming_head} / {@code armored_whale}）都没有。
     * 用字段探测而不是写死实体名单：数据包换 boss、或者以后 scgextra 给别的 boss 加血条，都不用改代码。</p>
     *
     * <p>注意：如果一个实体只是「声明了但从来不显示」，我们会误判成自带血条而不管它 ——
     * 这种情况下把 {@code boss_bar.detect_own_boss_bar} 关掉即可。</p>
     */
    public static boolean usesOwnBossBar(LivingEntity boss) {
        return OWN_BOSS_BAR.computeIfAbsent(boss.getClass(), RaidBar::detectOwnBossBar);
    }

    private static boolean detectOwnBossBar(Class<?> type) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            for (Field field : current.getDeclaredFields()) {
                if (ServerBossEvent.class.isAssignableFrom(field.getType())) {
                    return true;
                }
            }
        }
        return false;
    }

    /**
     * 按血量的进度：Σ存活怪当前血量 / 本波累计血量上限。
     *
     * @return 负数表示「算不了」（读档后本波没走过 {@code addRaider}，没有累计值），交给原逻辑
     */
    public static float healthProgress(WaveRaidState state) {
        WaveStats stats = STATS.get(state);
        if (stats == null || stats.waveTotal <= 0.0) {
            return -1.0F;
        }

        double current = 0.0;
        for (Map.Entry<UUID, LivingEntity> entry : ((WaveRaidStateAccessor) state).getRaiders().entrySet()) {
            LivingEntity raider = entry.getValue();
            if (raider != null && !RaidRoster.isGone(raider)) {
                current += Math.max(0.0, raider.getHealth());
            } else {
                // 没加载（或值还没解析回来）：按出生上限算满血
                current += stats.spawnHealth.getOrDefault(entry.getKey(), 0.0);
            }
        }
        return (float) Mth.clamp(current / stats.waveTotal, 0.0, 1.0);
    }

    private static boolean isBossRank(WaveRaidState state, Mob mob) {
        for (WaveRaidData.RaiderEntry entry : state.getWaveRaidData().getRaiderEntries(WaveRaidData.Rank.BOSS)) {
            if (entry.entityType() == mob.getType()) {
                return true;
            }
        }
        return false;
    }
}
