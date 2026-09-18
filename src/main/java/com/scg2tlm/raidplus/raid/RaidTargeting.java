package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import com.scg2tlm.raidplus.RaidPlusMod;
import com.scg2tlm.raidplus.compat.MaidSupport;
import com.scg2tlm.raidplus.mixin.WaveRaidStateAccessor;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.entity.ai.Brain;
import net.minecraft.world.entity.ai.attributes.Attributes;
import net.minecraft.world.entity.ai.memory.MemoryModuleType;
import net.minecraft.world.entity.ai.memory.MemoryStatus;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.phys.AABB;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 给袭击怪重选目标：候选池 = 玩家 + 女仆。
 *
 * <h3>为什么需要自己选</h3>
 * <ul>
 *   <li>SC2 的怪（{@code scguns:adjudicator} / {@code praetor} / {@code cog_knight} 之类）在
 *       {@code registerGoals} 里只写了 {@code new NearestAttackableTargetGoal(this, Player.class, true)} ——
 *       <b>只有玩家</b>，女仆永远不会被它们主动盯上。</li>
 *   <li>scgextra 自己的怪（占袭击怪的大多数）走 Brain：
 *       {@code VarRangePlayerSensor} 只往 {@code NEAREST_VISIBLE_TARGETABLE_PLAYER} 里塞玩家，
 *       {@code StartAttacking.create(BrainUtils::findNearestVisibleAttackablePlayer)} 再把它转成
 *       {@code ATTACK_TARGET} —— 也一样只有玩家。</li>
 *   <li>女仆唯一会被打的情况是她们先开枪，触发 {@code AttackLastHurtIfNear} / {@code HurtByTargetGoal} 的反击。</li>
 * </ul>
 *
 * <h3>为什么「先占住 ATTACK_TARGET」就稳</h3>
 * <p>原版 {@code StartAttacking} 的记忆要求是 {@code ATTACK_TARGET 必须为空}（VALUE_ABSENT）才会运行。
 * 我们先把 {@code ATTACK_TARGET} 设成女仆，scgextra 自己那套索敌就<b>不会</b>把它改回玩家；
 * 只有目标死亡/消失、记忆被清空之后，它们才会重新自己找玩家 —— 那正是我们想要的行为。</p>
 *
 * <h3>单值记忆</h3>
 * <p>{@code ATTACK_TARGET} 只能放一个实体，所以「两个目标都加上」落地成
 * 「候选池包含玩家和女仆，谁近打谁」＋「明显更近才换目标」（{@code targeting_switch_margin} 防抖）。</p>
 */
public final class RaidTargeting {
    /** 走路目标的速度倍率，和 scgextra 刷怪时用的一致。 */
    private static final float WALK_SPEED = 1.2F;

    private RaidTargeting() {
    }

    /** 从 {@code WaveRaidManager.tick()} 结尾调用。 */
    public static void tick(ServerLevel level, WaveRaidState state) {
        if (!RaidPlusConfig.TARGETING_ENABLED.get()) return;

        int interval = RaidPlusConfig.TARGETING_INTERVAL.get();
        if (interval <= 0 || level.getGameTime() % (long) interval != 0L) return;

        boolean wantPlayers = RaidPlusConfig.TARGET_PLAYERS.get();
        boolean wantMaids = RaidPlusConfig.TARGET_MAIDS.get() && MaidSupport.isAvailable();
        if (!wantPlayers && !wantMaids) return;

        double configRange = RaidPlusConfig.TARGETING_RANGE.get();
        boolean needLos = RaidPlusConfig.TARGETING_REQUIRE_LOS.get();
        double margin = RaidPlusConfig.TARGETING_SWITCH_MARGIN.get();

        Map<UUID, LivingEntity> raiders = ((WaveRaidStateAccessor) state).getRaiders();
        for (Map.Entry<UUID, LivingEntity> entry : raiders.entrySet()) {
            LivingEntity raider = entry.getValue();
            if (raider == null) {
                if (level.getEntity(entry.getKey()) instanceof LivingEntity resolved) {
                    raider = resolved;
                    entry.setValue(resolved);
                } else {
                    continue;
                }
            }
            if (!(raider instanceof Mob mob) || !mob.isAlive() || mob.isRemoved()) continue;

            LivingEntity current = currentTarget(mob);
            // 正在打别的怪（派系内战之类）就别插手，只接管「跟玩家/女仆有关」的目标。
            if (current != null && !isPoolTarget(current)) continue;

            double range = effectiveRange(mob, configRange);
            LivingEntity best = findNearest(level, mob, range, wantPlayers, wantMaids, needLos);
            if (best == null || best == current) continue;

            if (current != null) {
                double currentDist = mob.distanceTo(current);
                double bestDist = mob.distanceTo(best);
                // 没有明显更近就保持现状，避免玩家和女仆之间来回横跳。
                if (currentDist <= bestDist + margin) continue;
            }

            assign(mob, best);
        }
    }

    /**
     * 只设置 ATTACK_TARGET（brain 怪）／setTarget（纯 Goal AI 的怪）。
     *
     * <p>不去动 {@code WALK_TARGET}：scgextra 的 {@code GetCloseToTarget}（要求 ATTACK_TARGET 存在、
     * WALK_TARGET 不存在）和 {@code WalkUpToIdealRange} 会自己按武器的理想射程下发走路目标，
     * 替它们写反而会把这两个行为屏蔽掉。</p>
     */
    private static void assign(Mob mob, LivingEntity target) {
        Brain<?> brain = mob.getBrain();
        if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
            brain.setMemory(MemoryModuleType.ATTACK_TARGET, target);
        }
        // 目标选择器（NearestAttackableTargetGoal 之类）和 Mob#getTarget 的世界也要同步，
        // 原版 StartAttacking 也是两边一起写。
        mob.setTarget(target);

        if (RaidPlusConfig.TARGETING_DEBUG.get()) {
            RaidPlusMod.LOGGER.debug("[RaidPlus] {} 锁定目标 {}（{} 格）", mob.getName().getString(),
                    target.getName().getString(), String.format("%.1f", mob.distanceTo(target)));
        }
    }

    /** 怪当前「活着的」目标：先看 getTarget，再退回 ATTACK_TARGET 记忆。 */
    @Nullable
    private static LivingEntity currentTarget(Mob mob) {
        LivingEntity target = mob.getTarget();
        if (target == null || !target.isAlive()) {
            Brain<?> brain = mob.getBrain();
            if (brain.checkMemory(MemoryModuleType.ATTACK_TARGET, MemoryStatus.REGISTERED)) {
                LivingEntity remembered = brain.getMemory(MemoryModuleType.ATTACK_TARGET).orElse(null);
                if (remembered != null && remembered.isAlive()) target = remembered;
            }
        }
        return target != null && target.isAlive() ? target : null;
    }

    /** 这个目标是不是属于我们的候选池（玩家或女仆）。 */
    private static boolean isPoolTarget(LivingEntity entity) {
        return entity instanceof Player || MaidSupport.isMaid(entity);
    }

    /**
     * 实际索敌半径 = min(配置值, FOLLOW_RANGE)。
     *
     * <p>scgextra 的 {@code BrainUtils.isTargetStillValid} 要求目标在怪的跟随范围内，
     * 超出范围的目标会被判无效并清掉，所以锁一个够不着的目标没有意义。</p>
     */
    private static double effectiveRange(Mob mob, double configRange) {
        double followRange = mob.getAttributeValue(Attributes.FOLLOW_RANGE);
        return Math.max(2.0, Math.min(configRange, followRange));
    }

    @Nullable
    private static LivingEntity findNearest(ServerLevel level, Mob mob, double range, boolean wantPlayers,
                                            boolean wantMaids, boolean needLos) {
        double bestSqr = range * range;
        LivingEntity best = null;

        if (wantPlayers) {
            for (ServerPlayer player : level.players()) {
                if (!isValidTarget(mob, player, needLos)) continue;
                double distanceSqr = mob.distanceToSqr(player);
                if (distanceSqr < bestSqr) {
                    bestSqr = distanceSqr;
                    best = player;
                }
            }
        }

        if (wantMaids) {
            AABB box = mob.getBoundingBox().inflate(range);
            List<LivingEntity> maids = level.getEntitiesOfClass(LivingEntity.class, box, MaidSupport::isMaid);
            for (LivingEntity maid : maids) {
                if (!isValidTarget(mob, maid, needLos)) continue;
                double distanceSqr = mob.distanceToSqr(maid);
                if (distanceSqr < bestSqr) {
                    bestSqr = distanceSqr;
                    best = maid;
                }
            }
        }

        return best;
    }

    private static boolean isValidTarget(Mob mob, LivingEntity candidate, boolean needLos) {
        if (candidate == mob || !candidate.isAlive() || candidate.isRemoved()) return false;
        if (candidate instanceof Player player && (player.isCreative() || player.isSpectator())) return false;
        if (!mob.canAttack(candidate)) return false;
        return !needLos || mob.hasLineOfSight(candidate);
    }
}
