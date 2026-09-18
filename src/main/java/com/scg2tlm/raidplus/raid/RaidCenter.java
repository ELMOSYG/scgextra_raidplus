package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import com.scg2tlm.raidplus.RaidPlusMod;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 「袭击中心」的唯一真相。
 *
 * <h3>原实现的问题</h3>
 * <ol>
 *   <li>{@code WaveRaidManager.startRaid()} 里中心是 {@code player.position()} —— 信号弹打出去之后
 *       玩家还会跑，中心就停在开火那一刻的位置；scgextra 自己的 {@code findWaveSpawnLocation}
 *       更是每波都拿「当时最近的玩家」重算刷怪环。</li>
 *   <li>{@code WaveRaidState.updateRaiders()} 每 20 刻执行
 *       {@code spawnCenter = 所有袭击怪位置的平均值}。这不是中心，是个会跟着怪漂的质心 ——
 *       公告半径、Boss 血条名单、最近玩家、战利品落点、下一波刷怪原点全都读它。</li>
 * </ol>
 *
 * <h3>这里怎么做</h3>
 * <ul>
 *   <li><b>抓落点</b>：{@code RaidFlareEntity.performBurst()} 一开始就把信号弹当时的位置记下来
 *       （有效期 {@value #FLARE_TTL_TICKS} 刻，防止用到过期数据）。</li>
 *   <li><b>定锚点</b>：{@code startRaid} 一进来就 {@link #beginRaid}，算出这次袭击的预期中心，
 *       存进 {@link #PENDING}（按维度分槽）。</li>
 *   <li><b>锁住</b>：{@code WaveRaidState.updateRaiders()} 结尾调 {@link #claimCenter}，
 *       第一次看到某个袭击状态就把锚点认下来并缓存，之后每次覆盖都会把质心改回锚点。</li>
 * </ul>
 *
 * <p>读档时 {@code WaveRaidState} 由 NBT 重建（中心就是我们当初写进去的锚点），静态表是空的；
 * {@code WaveRaidManager.load()} 返回后会再 {@link #forceCenter} 一次，所以读档后同样不会漂。</p>
 */
public final class RaidCenter {
    /** 信号弹落点的有效期（刻）。 */
    private static final long FLARE_TTL_TICKS = 200L;

    /** 刚打出去还没落到 {@code startRaid} 的信号弹落点。 */
    @Nullable
    private static ServerLevel flareLevel;
    @Nullable
    private static Vec3 flarePos;
    private static long flareGameTime;

    /** 每个维度「正在开始的这次袭击」的预期中心，第一次 {@code updateRaiders} 时被认领。 */
    private static final Map<ServerLevel, Vec3> PENDING = Collections.synchronizedMap(new WeakHashMap<>());

    /** 每个袭击状态锁定的中心。弱引用键，袭击结束后自动回收。 */
    private static final Map<WaveRaidState, Vec3> FIXED = Collections.synchronizedMap(new WeakHashMap<>());

    private RaidCenter() {
    }

    // ------------------------------------------------------------------ 信号弹落点

    /** 信号弹炸开：记下落点。 */
    public static void captureFlare(ServerLevel level, Vec3 pos) {
        flareLevel = level;
        flarePos = pos;
        flareGameTime = level.getGameTime();
    }

    /** 只读取落点，不消费；过期或维度不符返回 null。 */
    @Nullable
    public static Vec3 peekFlare(ServerLevel level) {
        if (flarePos == null || flareLevel != level) return null;
        if (level.getGameTime() - flareGameTime > FLARE_TTL_TICKS) {
            flareLevel = null;
            flarePos = null;
            return null;
        }
        return flarePos;
    }

    /** 该维度当前该用的袭击中心锚点：优先信号弹落点，否则退回给定坐标。 */
    public static Vec3 anchorFor(ServerLevel level, Vec3 fallback) {
        Vec3 anchor = PENDING.get(level);
        if (anchor != null) return anchor;
        if (RaidPlusConfig.CENTER_FROM_FLARE.get()) {
            Vec3 flare = peekFlare(level);
            if (flare != null) return flare;
        }
        return fallback;
    }

    // ------------------------------------------------------------------ 袭击开始

    /**
     * {@code WaveRaidManager.startRaid()} 开头调用：决定这次袭击的中心。
     *
     * @param playerPos 原实现会当成中心的那个坐标（开火时的玩家位置）
     * @return 本次袭击的中心
     */
    public static Vec3 beginRaid(ServerLevel level, Vec3 playerPos) {
        Vec3 center = playerPos;
        if (RaidPlusConfig.CENTER_FROM_FLARE.get()) {
            Vec3 flare = peekFlare(level);
            if (flare != null) center = flare;
        }
        PENDING.put(level, center);
        return center;
    }

    /**
     * {@code WaveRaidState.updateRaiders()} 结尾调用：把质心改回锁定的中心。
     *
     * @param computed scgextra 刚算出来的值（质心，或者读档时从 NBT 恢复的值）
     * @return 真正应该写进 {@code spawnCenter} 的值
     */
    public static Vec3 claimCenter(WaveRaidState state, ServerLevel level, Vec3 computed) {
        if (!RaidPlusConfig.CENTER_LOCKED.get()) {
            return computed;
        }
        Vec3 fixed = FIXED.get(state);
        if (fixed != null) {
            return fixed;
        }
        Vec3 center = PENDING.remove(level);
        if (center == null) center = computed;
        FIXED.put(state, center);
        RaidPlusMod.LOGGER.info("[RaidPlus] 本次袭击中心锁定在 {}", center);
        return center;
    }

    /** 读档时用：把 NBT 里恢复出来的中心直接设为锁定值。 */
    public static void forceCenter(WaveRaidState state, Vec3 center) {
        FIXED.put(state, center);
    }

    /** 袭击结束：清掉记录。 */
    public static void forget(WaveRaidState state, ServerLevel level) {
        FIXED.remove(state);
        PENDING.remove(level);
    }
}
