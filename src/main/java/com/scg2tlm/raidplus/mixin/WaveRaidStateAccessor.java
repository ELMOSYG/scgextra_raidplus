package com.scg2tlm.raidplus.mixin;

import java.util.Map;
import java.util.UUID;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

/**
 * {@code WaveRaidState} 的私有字段入口：中心、维度、袭击怪名单。
 * 靠拢逻辑（{@link com.scg2tlm.raidplus.raid.RaidConvergence}）要用最后那个。
 */
@Mixin(value = WaveRaidState.class, remap = false)
public interface WaveRaidStateAccessor {

    @Accessor("spawnCenter")
    Vec3 getSpawnCenter();

    @Accessor("spawnCenter")
    void setSpawnCenter(Vec3 center);

    @Accessor("level")
    ServerLevel getLevel();

    /** 键是袭击怪 UUID，值可能是 null（读档后懒解析）。 */
    @Accessor("raiders")
    Map<UUID, LivingEntity> getRaiders();
}
