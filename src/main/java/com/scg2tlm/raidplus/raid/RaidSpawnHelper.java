package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import javax.annotation.Nullable;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SupportType;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.pathfinder.PathComputationType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidUtil;

/**
 * 波次刷怪点的选择。
 *
 * <h3>原实现的两个问题</h3>
 * <ol>
 *   <li>{@code WaveRaidUtil.findWaveSpawnLocation(level, center, playerPos)} 是在
 *       {@code center} 外 30~44 格的环上取点，看起来没问题；但候选点还要满足「离玩家 32~48 格」，
 *       而 {@code tickRaid} 每波都拿<b>当时最近的玩家</b>当 {@code playerPos} 重算 ——
 *       于是刷怪环实际是贴着玩家走的，不是贴着袭击中心。</li>
 *   <li>它还会在「中心上方 12 格不是空气」时直接返回 {@code null}，而
 *       {@code WaveRaidManager.startRaid} 拿到 null 就<b>静默放弃</b>（公告、报错什么都没有）。
 *       也就是说在下界、洞穴、树林里打信号弹可能完全没反应。</li>
 * </ol>
 *
 * <h3>这里怎么做</h3>
 * <p>圆心一律取 {@code RaidCenter} 的锚点（信号弹落点）。候选点只做地形校验：
 * 露天（上方无遮挡）、下方有实心支撑、三格可站立 —— 全部沿用原文的思路。
 * 40 次尝试都失败时退回「中心地表」，所以<b>永远返回一个可用坐标</b>，不会再静默失败。</p>
 *
 * <p>{@code spawn_ring_enabled=false} 时保留原来的「离玩家 32~48 格」筛选（距离上下限直接读
 * {@code WaveRaidUtil} 的公开常量，保持一致），但圆心仍然是袭击中心而不是玩家。</p>
 */
public final class RaidSpawnHelper {
    /** 与 {@code WaveRaidUtil.FIND_SPAWN_LOCATION_ATTEMPTS} 一致。 */
    private static final int ATTEMPTS = WaveRaidUtil.FIND_SPAWN_LOCATION_ATTEMPTS;

    private RaidSpawnHelper() {
    }

    /**
     * @param level     目标维度
     * @param fallback  scgextra 原本传进来的中心（第一波是玩家位置，之后是袭击中心）
     * @param playerPos 最近玩家（可能为 null）；只在关掉 {@code spawn_ring_enabled} 时用于筛选
     */
    public static Vec3 findRingSpawn(ServerLevel level, Vec3 fallback, @Nullable Vec3 playerPos) {
        Vec3 center = RaidCenter.anchorFor(level, fallback);

        double min = RaidPlusConfig.SPAWN_RING_MIN.get();
        double max = RaidPlusConfig.SPAWN_RING_MAX.get();
        if (max < min) max = min;

        boolean playerBand = !RaidPlusConfig.SPAWN_RING_ENABLED.get() && playerPos != null;
        double bandMin = WaveRaidUtil.SPAWN_MIN_PLAYER_DISTANCE * WaveRaidUtil.SPAWN_MIN_PLAYER_DISTANCE;
        double bandMax = WaveRaidUtil.SPAWN_MAX_PLAYER_DISTANCE * WaveRaidUtil.SPAWN_MAX_PLAYER_DISTANCE;
        double keepOffPlayers = RaidPlusConfig.SPAWN_MIN_PLAYER_DISTANCE.get();
        double keepOffSqr = keepOffPlayers * keepOffPlayers;

        RandomSource random = level.getRandom();
        for (int attempt = 0; attempt < ATTEMPTS; attempt++) {
            double angle = random.nextDouble() * Math.PI * 2.0;
            double distance = min + random.nextDouble() * (max - min);
            int x = (int) Math.floor(center.x + Math.cos(angle) * distance);
            int z = (int) Math.floor(center.z + Math.sin(angle) * distance);

            if (playerBand) {
                double dx = x + 0.5 - playerPos.x;
                double dz = z + 0.5 - playerPos.z;
                double distanceSqr = dx * dx + dz * dz;
                if (distanceSqr < bandMin || distanceSqr > bandMax) continue;
            } else if (keepOffPlayers > 0.0 && tooCloseToPlayer(level, x, z, keepOffSqr)) {
                continue;
            }

            BlockPos pos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES, new BlockPos(x, 0, z));
            if (isOpenStandable(level, pos)) {
                return Vec3.atBottomCenterOf(pos);
            }
        }

        BlockPos fallbackPos = level.getHeightmapPos(Heightmap.Types.MOTION_BLOCKING_NO_LEAVES,
                BlockPos.containing(center.x, center.y, center.z));
        return Vec3.atBottomCenterOf(fallbackPos);
    }

    /** 该水平坐标是否离某个玩家太近（旁观/死亡的玩家不算）。 */
    private static boolean tooCloseToPlayer(ServerLevel level, int x, int z, double limitSqr) {
        for (ServerPlayer player : level.players()) {
            if (!player.isAlive() || player.isSpectator()) continue;
            double dx = x + 0.5 - player.getX();
            double dz = z + 0.5 - player.getZ();
            if (dx * dx + dz * dz < limitSqr) return true;
        }
        return false;
    }

    /** 露天 + 下方实心支撑 + 三格可站立。 */
    private static boolean isOpenStandable(ServerLevel level, BlockPos pos) {
        BlockHitResult roof = level.clip(new ClipContext(pos.above(8).getCenter(), pos.getCenter(),
                ClipContext.Block.COLLIDER, ClipContext.Fluid.ANY, null));
        if (roof.getType() == HitResult.Type.BLOCK) return false;

        BlockPos below = pos.below();
        if (!level.getBlockState(below).isFaceSturdy(level, below, Direction.UP, SupportType.FULL)) return false;
        if (!level.getBlockState(pos).isAir()) return false;
        if (!level.getBlockState(pos).isPathfindable(level, pos, PathComputationType.LAND)) return false;
        if (!level.getBlockState(pos.above()).isPathfindable(level, pos.above(), PathComputationType.LAND)) return false;
        return level.getBlockState(pos.above(2)).isPathfindable(level, pos.above(2), PathComputationType.LAND);
    }
}
