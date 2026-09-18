package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import com.scg2tlm.raidplus.RaidPlusMod;
import com.scg2tlm.raidplus.mixin.WaveRaidStateAccessor;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import java.util.function.Predicate;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.LivingEntity;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 袭击怪名单的剪枝规则。
 *
 * <h3>原实现的问题（两条）</h3>
 * <p><b>① 把「区块没加载」当成「怪死了」</b>：{@code WaveRaidState.updateRaiders()} 里的
 * {@code Set.removeIf} 只看「{@code level.getEntity(uuid)} 解析得到吗」和 {@code isRemoved()}。
 * 区块卸载时这两条同时成立（实体被标记 {@code UNLOADED_TO_CHUNK}、并从查找表里消失），
 * 于是整波怪被判死、名单清空、自动过波 —— 玩家死在远处复活最容易触发。</p>
 *
 * <p><b>② 手上那个实体对象会变成「墓碑」</b>（①修完之后才暴露出来的那条）：
 * MC 的 {@code Entity.setRemoved(RemovalReason)} 是 <b>final</b>，而且「只在当前为 null 时才写入」，
 * 永远不会被清掉；区块卸载时 {@code PersistentEntitySectionManager} 正是用它把实体标成
 * {@code UNLOADED_TO_CHUNK} 之后留在内存里。而区块<b>重新加载</b>时，世界里的那只怪是
 * <b>从存档新建的另一个对象</b>（同一个 UUID）：</p>
 * <pre>
 * Entity.setRemoved(RemovalReason r) { if (this.removalReason == null) this.removalReason = r; ... }
 * PersistentEntitySectionManager: entity.setRemoved(RemovalReason.UNLOADED_TO_CHUNK)   // 卸载
 * PersistentEntitySectionManager: chunkEntities.forEach(e -&gt; addEntity(e, true))       // 重新加载（新对象）
 * </pre>
 * <p>名单里如果一直握着旧对象，玩家把新对象打死之后名单也清不掉 →
 * {@code raidersLeft()} 永远 &gt; 0 → <b>打完一波不过波，整场袭击要拖到 10 分钟超时失败</b>。
 * 所以这里<b>每刻都重新解析一次</b>，解析到就换成世界里的那个对象。</p>
 *
 * <h3>判据</h3>
 * <ul>
 *   <li>解析得到（世界里有这只怪）→ 采用它；只有「血量 ≤ 0」或「已 removed（原因不是
 *       {@code UNLOADED_TO_CHUNK}）」才算死。</li>
 *   <li>解析不到 → 手上的对象还活着（血量 &gt; 0）就<b>保留</b>；但会额外看一眼
 *       「它最后所在的那个区块加载了吗」：区块<b>在</b>、世界里却没有它 →
 *       等 {@value #MISSING_GRACE_TICKS} 刻确认后判定为没了（避开区块刚加载那一两刻的误判）。</li>
 *   <li>{@code roster.keep_unloaded_raiders=false} 时完全退回原判据。</li>
 * </ul>
 */
public final class RaidRoster {
    /** 「区块在、世界里却没有这只怪」要连续多少刻才认定它真没了。 */
    private static final long MISSING_GRACE_TICKS = 60L;

    /** 每只「解析不到」的怪第一次被发现的时刻，用来做上面的宽限。 */
    private static final Map<WaveRaidState, Map<UUID, Long>> MISSING_SINCE =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RaidRoster() {
    }

    /**
     * 替代 {@code raiders.entrySet().removeIf(...)}。
     *
     * @param entries 名单的 entrySet 视图（{@code WaveRaidState.raiders} 是 {@code Map<UUID, LivingEntity>}）
     * @param legacy  原判据，关掉开关时原样使用
     */
    public static boolean prune(WaveRaidState state, Set<Map.Entry<UUID, LivingEntity>> entries,
                                Predicate<Map.Entry<UUID, LivingEntity>> legacy) {
        if (!RaidPlusConfig.KEEP_UNLOADED_RAIDERS.get()) {
            MISSING_SINCE.remove(state);
            return entries.removeIf(legacy);
        }

        ServerLevel level = ((WaveRaidStateAccessor) state).getLevel();
        Map<UUID, Long> missingSince = MISSING_SINCE.computeIfAbsent(state, ignored -> new HashMap<>());
        long now = level.getGameTime();

        Iterator<Map.Entry<UUID, LivingEntity>> iterator = entries.iterator();
        boolean changed = false;
        int confirmedDead = 0;
        int vanished = 0;
        int stillUnloaded = 0;

        while (iterator.hasNext()) {
            Map.Entry<UUID, LivingEntity> entry = iterator.next();
            UUID raiderId = entry.getKey();
            LivingEntity raider = entry.getValue();
            LivingEntity live = level.getEntity(raiderId) instanceof LivingEntity found ? found : null;

            if (live != null) {
                missingSince.remove(raiderId);
                if (live != raider) {
                    // 区块卸载再加载之后，世界里的那只怪是另一个对象 —— 换成它
                    entry.setValue(live);
                    raider = live;
                }
            }

            if (raider == null) {
                // 读档后还没解析回来：先留着，等它加载
                stillUnloaded++;
                continue;
            }

            if (isGone(raider)) {
                iterator.remove();
                missingSince.remove(raiderId);
                confirmedDead++;
                changed = true;
                continue;
            }

            if (live != null) {
                continue;
            }

            // 解析不到、但手上这只还活着：区分「没加载」和「确实没了」
            if (!level.isLoaded(BlockPos.containing(raider.position()))) {
                missingSince.remove(raiderId);
                stillUnloaded++;
                continue;
            }

            long firstMissing = missingSince.computeIfAbsent(raiderId, ignored -> now);
            if (now - firstMissing > MISSING_GRACE_TICKS) {
                // 区块是加载着的、世界里却找不到它，而且过了宽限期 → 认它没了
                iterator.remove();
                missingSince.remove(raiderId);
                vanished++;
                changed = true;
            } else {
                stillUnloaded++;
            }
        }

        boolean debugging = RaidPlusConfig.WAVE_DEBUG.get();
        if (debugging && (confirmedDead > 0 || vanished > 0 || !entries.isEmpty())
                && level.getGameTime() % 100L == 0L) {
            RaidPlusMod.LOGGER.info("[RaidPlus] 名单剪枝：确认死亡 {} 只，判定消失 {} 只，未加载保留 {} 只，名单里还有 {} 只",
                    confirmedDead, vanished, stillUnloaded, entries.size());
            logSurvivors(level, state, entries);
        }

        return changed;
    }

    /** 调试用：把还在名单里的怪逐只打出来（名字、血量、离袭击中心多远、区块加载状态）。 */
    private static void logSurvivors(ServerLevel level, WaveRaidState state,
                                     Set<Map.Entry<UUID, LivingEntity>> entries) {
        double centerX = state.getCenter().x;
        double centerZ = state.getCenter().z;
        for (Map.Entry<UUID, LivingEntity> entry : entries) {
            LivingEntity raider = entry.getValue();
            if (raider == null) {
                RaidPlusMod.LOGGER.info("[RaidPlus]   剩下：{}（还没解析回来）", entry.getKey());
                continue;
            }
            double distance = Math.sqrt(Math.pow(raider.getX() - centerX, 2.0D)
                    + Math.pow(raider.getZ() - centerZ, 2.0D));
            RaidPlusMod.LOGGER.info("[RaidPlus]   剩下：{} 血量 {}/{}，坐标 ({}, {}, {})，离中心 {} 格，区块已加载={}",
                    raider.getDisplayName().getString(), (int) raider.getHealth(), (int) raider.getMaxHealth(),
                    (int) raider.getX(), (int) raider.getY(), (int) raider.getZ(),
                    (int) distance, level.isLoaded(BlockPos.containing(raider.position())));
        }
    }

    /** 袭击结束：清辅助记录。 */
    public static void forget(WaveRaidState state) {
        MISSING_SINCE.remove(state);
    }

    /**
     * 「这只怪算不算没了」。血量先判 —— 死就是死，
     * 不管它是被正常移除、还是被标了 {@code UNLOADED_TO_CHUNK}（区块卸载留下的墓碑）。
     */
    public static boolean isGone(LivingEntity raider) {
        if (raider.getHealth() <= 0.0F) {
            return true;
        }
        if (!raider.isRemoved()) {
            return false;
        }
        return raider.getRemovalReason() != Entity.RemovalReason.UNLOADED_TO_CHUNK;
    }
}
