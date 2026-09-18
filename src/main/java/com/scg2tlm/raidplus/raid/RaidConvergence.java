package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import com.scg2tlm.raidplus.RaidPlusMod;
import com.scg2tlm.raidplus.mixin.WaveRaidStateAccessor;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.ai.memory.WalkTarget;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 让袭击怪在没有可打目标时朝袭击中心靠拢。
 *
 * <h3>为什么需要</h3>
 * <p>scgextra 的袭击怪是纯「目标驱动」AI（{@code common/brain/GetCloseToTarget}、
 * {@code ApproachTargetIfTarget…}、{@code WalkUpToIdealRange} 之类），全部以
 * {@code ATTACK_TARGET} 为前提；刷怪时给的那个 {@code WalkTarget(EntityTracker(player))}
 * 也只活 200 刻。所以目标一丢（玩家躲进屋子、传送、死亡、或者打的是别人），
 * 怪就<b>原地发呆</b>，玩家得一个个去找 —— 这就是「打起来很麻烦」的来源。
 * 原版袭击用的是 {@code PathfindToRaidGoal}，这里补的就是这一环。</p>
 *
 * <h3>干预规则</h3>
 * <ul>
 *   <li>只处理活着、且离中心超过 {@code converge_distance} 的怪。</li>
 *   <li>{@code converge_idle_only}（默认开）时，正在打东西的怪完全不碰。</li>
 *   <li>走路目标只下到「离中心 4 格外」的一小步（最多 16 格），避免超长寻路失败；
 *       每 {@code converge_interval} 刻重下一次。</li>
 *   <li>怪自己注册了 {@code WALK_TARGET} 记忆就走 Brain 记忆（brain 怪的正常通道），
 *       否则退回 {@code navigation.moveTo}（纯 Goal AI 的怪）。</li>
 * </ul>
 */
public final class RaidConvergence {
    /** 走路目标离中心多近算「到了」。 */
    private static final int CLOSE_ENOUGH = 3;
    /** 单次下发的最大步长（格）。 */
    private static final double MAX_STEP = 16.0;
    /** 地表高度与怪自身高度差超过这个值就不信地表高度（洞穴、下界顶）。 */
    private static final double MAX_HEIGHT_OFFSET = 6.0;
    /** 走路目标过期余量（刻）。 */
    private static final long EXPIRY_MARGIN = 40L;

    private RaidConvergence() {
    }

    /** 从 {@code WaveRaidManager.tick()} 结尾调用。 */
    public static void tick(ServerLevel level, WaveRaidState state) {
        if (!RaidPlusConfig.CONVERGE_ENABLED.get()) return;

        int interval = RaidPlusConfig.CONVERGE_INTERVAL.get();
        if (interval <= 0 || level.getGameTime() % (long) interval != 0L) return;

        double keepAway = RaidPlusConfig.CONVERGE_DISTANCE.get();
        Vec3 center = state.getCenter();
        Map<UUID, LivingEntity> raiders = ((WaveRaidStateAccessor) state).getRaiders();

        for (Map.Entry<UUID, LivingEntity> entry : raiders.entrySet()) {
            LivingEntity raider = entry.getValue();
            if (raider == null) {
                // 读档/区块重载后是 null，按 scgextra 的约定懒解析一次。
                if (level.getEntity(entry.getKey()) instanceof LivingEntity resolved) {
                    raider = resolved;
                    entry.setValue(resolved);
                } else {
                    continue;
                }
            }
            if (!raider.isAlive() || raider.isRemoved()) continue;
            nudge(level, center, keepAway, raider);
        }
    }

    private static void nudge(ServerLevel level, Vec3 center, double keepAway, LivingEntity raider) {
        if (!(raider instanceof Mob mob)) return;

        if (RaidPlusConfig.CONVERGE_IDLE_ONLY.get() && hasLiveTarget(mob)) return;

        Vec3 here = raider.position();
        double dx = center.x - here.x;
        double dz = center.z - here.z;
        double distance = Math.sqrt(dx * dx + dz * dz);
        if (distance <= keepAway) return;

        double step = Math.min(distance - keepAway + 4.0, MAX_STEP);
        double scale = step / distance;
        double stepX = here.x + dx * scale;
        double stepZ = here.z + dz * scale;

        // 用地表高度当目标的 Y（陡坡上离得近才算「到了」）；但洞穴/下界里地表高度可能在天花板上，
        // 差得太远就退回怪自己的 Y，让寻路在本地解决。
        BlockPos surface = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(stepX, here.y, stepZ));
        double stepY = Math.abs(surface.getY() - here.y) <= MAX_HEIGHT_OFFSET ? surface.getY() : here.y;
        Vec3 target = new Vec3(stepX, stepY, stepZ);

        float speed = (float) (double) RaidPlusConfig.CONVERGE_SPEED.get();
        long expiry = RaidPlusConfig.CONVERGE_INTERVAL.get() + EXPIRY_MARGIN;

        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.WALK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemoryWithExpiry(MemoryModuleType.WALK_TARGET, new WalkTarget(target, speed, CLOSE_ENOUGH), expiry);
        } else if (!mob.getNavigation().moveTo(target.x, target.y, target.z, speed)) {
            if (RaidPlusConfig.CONVERGE_DEBUG.get()) {
                RaidPlusMod.LOGGER.debug("[RaidPlus] {} 靠拢寻路失败 -> {}", mob.getName().getString(), target);
            }
            return;
        }

        if (RaidPlusConfig.CONVERGE_DEBUG.get()) {
            RaidPlusMod.LOGGER.debug("[RaidPlus] 驱赶 {} 离中心 {} -> {}", mob.getName().getString(),
                    String.format("%.1f", distance), target);
        }
    }

    /** 有活着的攻击目标 = 正在打架，不干预。 */
    private static boolean hasLiveTarget(Mob mob) {
        LivingEntity target = mob.getTarget();

        if (target == null) {
            Brain<?> brain = mob.getBrain();
            if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                Optional<? extends LivingEntity> remembered = brain.getMemory(MemoryModuleType.ATTACK_TARGET);
                target = remembered.orElse(null);
            }
        }

        return target != null && target.isAlive();
    }
}
