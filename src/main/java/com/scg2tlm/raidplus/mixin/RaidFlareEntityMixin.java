package com.scg2tlm.raidplus.mixin;

import com.scg2tlm.raidplus.raid.RaidCenter;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.Level;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import top.ribs.scguns.config.RaidFlareConfig;
import top.ribs.scguns.entity.projectile.RaidFlareEntity;

/**
 * 抓信号弹落点。
 *
 * <p>{@code RaidFlareEntity.performBurst()} 是 SC2 里唯一会启动袭击的地方，而它启动时用的中心是
 * <b>发射者的位置</b>（{@code performBurst} 里 {@code this.m_19749_()} 拿到的 ServerPlayer），
 * 不是信号弹自己的位置。所以在方法开头把信号弹当时的位置记下来，等
 * {@code WaveRaidManager.startRaid} 一进来就取用。</p>
 *
 * <p>方法体本身不改：scgextra 已经在这里拦截了 {@code getRaidByRaidId}，两边互不干扰。</p>
 */
@Mixin(value = RaidFlareEntity.class, remap = false)
public abstract class RaidFlareEntityMixin {

    @Inject(method = "performBurst", at = @At("HEAD"))
    private void raidplus$captureFlare(RaidFlareConfig.FlareData flareData, CallbackInfo ci) {
        RaidFlareEntity self = (RaidFlareEntity) (Object) this;
        Level level = self.level();
        // 客户端也会走到这里（performBurst 自己会提前 return），只有服务端才记。
        if (level instanceof ServerLevel serverLevel) {
            RaidCenter.captureFlare(serverLevel, self.position());
        }
    }
}
