package com.scg2tlm.raidplus.mixin;

import com.scg2tlm.raidplus.raid.RaidBar;
import com.scg2tlm.raidplus.raid.RaidCenter;
import com.scg2tlm.raidplus.raid.RaidConvergence;
import com.scg2tlm.raidplus.raid.RaidRoster;
import com.scg2tlm.raidplus.raid.RaidSpawnHelper;
import com.scg2tlm.raidplus.raid.RaidTargeting;
import com.scg2tlm.raidplus.raid.RaidWaveDelay;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidData;
import net.zincstudios.scgextra.raid.WaveRaidManager;
import net.zincstudios.scgextra.raid.WaveRaidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * 袭击中心 + 刷怪环 + 靠拢 + Boss 条，都挂在 {@code WaveRaidManager} 上。
 *
 * <ul>
 *   <li>{@code startRaid} 开头：把这次袭击该用的中心算出来（信号弹落点）。</li>
 *   <li>{@code startRaid} / {@code tickRaid} 里的 {@code findWaveSpawnLocation} 调用：
 *       换成以袭击中心为圆心的环带。</li>
 *   <li>{@code tick} 结尾：驱赶发呆的袭击怪朝中心靠拢。</li>
 *   <li>{@code tick} 结尾：给袭击怪重选目标（玩家 + 女仆）。</li>
 *   <li>{@code tickBossBar} 结尾：改那条 bar 的进度（按血量）和终波 boss 血条。</li>
 *   <li>{@code endRaid} / {@code load}：清理和读档时的中心记录 / bar 数据。</li>
 * </ul>
 *
 * <p>注意 {@code raidCenter} 这个局部变量本身没改 —— {@code WaveRaidState} 里那个会漂的
 * {@code spawnCenter} 是在 {@code updateRaiders()} 里被覆盖的，由
 * {@link WaveRaidStateMixin} 在那边改回锁定值，构造时的初始值无所谓。</p>
 */
@Mixin(value = WaveRaidManager.class, remap = false)
public abstract class WaveRaidManagerMixin {

    /** scgextra 那条袭击 Boss 条（波次条）。可能是 null（没在袭击 / 刚结束）。 */
    @Shadow
    private ServerBossEvent bossBar;

    /** scgextra 自己的波次推进倒计时（30 刻）。我们靠按住它来实现「打完一波等一会儿」。 */
    @Shadow
    private int nextWaveDelay;

    @Inject(method = "startRaid", at = @At("HEAD"))
    private void raidplus$beginRaid(WaveRaidData raidData, ServerLevel level, ServerPlayer player, CallbackInfo ci) {
        RaidCenter.beginRaid(level, player.position());
    }

    @Redirect(
            method = {"startRaid", "tickRaid"},
            at = @At(
                    value = "INVOKE",
                    target = "Lnet/zincstudios/scgextra/raid/WaveRaidUtil;findWaveSpawnLocation"
                            + "(Lnet/minecraft/server/level/ServerLevel;Lnet/minecraft/world/phys/Vec3;Lnet/minecraft/world/phys/Vec3;)"
                            + "Lnet/minecraft/world/phys/Vec3;"))
    /**
     * 注意：{@code @Redirect} 的 handler <b>不能</b>是 static —— Mixin 是按
     * 「被注入的那个方法（{@code startRaid} / {@code tickRaid} 都是实例方法）」来校验 handler 的
     * static 修饰符，跟被重定向的调用本身是不是静态无关。写成 static 会在 APPLY 阶段直接崩游戏。
     */
    private Vec3 raidplus$findWaveSpawn(ServerLevel level, Vec3 center, Vec3 playerPos) {
        return RaidSpawnHelper.findRingSpawn(level, center, playerPos);
    }

    @Inject(method = "tick", at = @At("TAIL"))
    private void raidplus$converge(ServerLevel level, CallbackInfo ci) {
        WaveRaidState state = ((WaveRaidManager) (Object) this).getCurrentRaidState();
        if (state != null) {
            RaidConvergence.tick(level, state);
        }
    }

    /** 另一路：给袭击怪重选目标（候选池 = 玩家 + 女仆）。 */
    @Inject(method = "tick", at = @At("TAIL"))
    private void raidplus$retarget(ServerLevel level, CallbackInfo ci) {
        WaveRaidState state = ((WaveRaidManager) (Object) this).getCurrentRaidState();
        if (state != null) {
            RaidTargeting.tick(level, state);
        }
    }

    /**
     * 波次之间的等待：这一波清完、又不是终波时，把 scgextra 的 {@code nextWaveDelay} 按住，
     * 直到我们自己的倒计时走完（倒计时画在那条 bar 上，见 {@link RaidBar#apply}）。
     *
     * <p>挂在 {@code tickRaid} 结尾：scgextra 这一帧已经判断过
     * {@code nextWaveDelay-- < 0}，我们在这里把值写回 30，它下一帧就推进不了。</p>
     */
    @Inject(method = "tickRaid", at = @At("TAIL"))
    private void raidplus$waveDelay(ServerLevel level, CallbackInfo ci) {
        WaveRaidState state = ((WaveRaidManager) (Object) this).getCurrentRaidState();
        if (state == null) {
            return;
        }
        if (RaidWaveDelay.holdTicks(state, level) > 0L) {
            this.nextWaveDelay = 30;
        }
    }

    /**
     * 那条 bar 最终显示什么：进度按血量算；终波刷出 BOSS 档怪之后整条变成 boss 血条。
     *
     * <p>挂在 {@code tickBossBar} 结尾 —— scgextra 先按数量算过一次进度、也写过波次名（
     * 「FAC Raid · Wave 2」），我们在这里覆盖成我们要的。{@code tick} 里的调用顺序是
     * {@code tickRaid} → {@code tickBossBar}，所以名单/血量都是最新的。</p>
     */
    @Inject(method = "tickBossBar", at = @At("TAIL"))
    private void raidplus$customBar(ServerLevel level, CallbackInfo ci) {
        RaidBar.apply(((WaveRaidManager) (Object) this).getCurrentRaidState(), this.bossBar);
    }

    @Inject(method = "endRaid", at = @At("HEAD"))
    private void raidplus$forgetCenter(ServerLevel level, boolean success, CallbackInfo ci) {
        WaveRaidState state = ((WaveRaidManager) (Object) this).getCurrentRaidState();
        if (state != null) {
            RaidCenter.forget(state, level);
            RaidBar.forget(state);
            RaidRoster.forget(state);
            RaidWaveDelay.forget(state);
        }
    }

    /**
     * 读档：{@code WaveRaidState} 的中心是从 NBT 恢复的（就是我们当初写进去的锚点），
     * 但静态表是空的，所以这里把它补成锁定值，避免读档后第一轮 {@code updateRaiders} 又把它带走。
     */
    @Inject(method = "load", at = @At("RETURN"))
    private static void raidplus$seedCenter(ServerLevel level, CompoundTag tag, CallbackInfoReturnable<WaveRaidManager> cir) {
        WaveRaidState state = cir.getReturnValue().getCurrentRaidState();
        if (state != null) {
            RaidCenter.forceCenter(state, ((WaveRaidStateAccessor) state).getSpawnCenter());
        }
    }
}
