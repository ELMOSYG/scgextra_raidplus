package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import net.minecraft.world.effect.MobEffectInstance;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.Mob;

/**
 * 新刷出来的袭击怪带一段迅捷，让它们从刷怪环上更快压到中心。
 *
 * <p>挂在 {@code WaveRaidState.addRaider(Mob)} 上 —— 那是每一只波次刷出来的怪都会经过的地方
 * （{@code spawnCurrentWaveMobs} 里 {@code level.addFreshEntity(mob)} 之后紧接着就是它）。</p>
 */
public final class RaidBuffs {
    private RaidBuffs() {
    }

    public static void onRaiderSpawn(Mob mob) {
        if (!RaidPlusConfig.SPAWN_BUFF_ENABLED.get()) return;

        int duration = RaidPlusConfig.SPAWN_BUFF_DURATION.get();
        int amplifier = RaidPlusConfig.SPAWN_BUFF_AMPLIFIER.get();
        boolean particles = RaidPlusConfig.SPAWN_BUFF_PARTICLES.get();

        // ambient=false；visible/showIcon 由配置决定。
        mob.addEffect(new MobEffectInstance(MobEffects.MOVEMENT_SPEED, duration, amplifier, false, particles, particles));
    }
}
