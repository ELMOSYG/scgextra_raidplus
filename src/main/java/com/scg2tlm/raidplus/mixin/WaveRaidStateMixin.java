package com.scg2tlm.raidplus.mixin;

import com.scg2tlm.raidplus.raid.RaidBar;
import com.scg2tlm.raidplus.raid.RaidBuffs;
import com.scg2tlm.raidplus.raid.RaidCenter;
import com.scg2tlm.raidplus.raid.RaidRoster;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.entity.Mob;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidState;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * 把「会漂的质心」改回锁定的袭击中心，并且把名单剪枝换成「未加载 ≠ 死亡」。
 *
 * <p>原实现（{@code WaveRaidState.updateRaiders()} 第 104~111 行）每 20 刻执行一次：</p>
 * <pre>
 * if (level.getGameTime() % 20 == 1 &amp;&amp; !raiders.isEmpty()) {
 *     spawnCenter = raiders.map(Entity::position).reduce(ZERO, Vec3::add).scale(1.0 / raiders.size());
 * }
 * </pre>
 * <p>也就是「所有袭击怪位置的平均值」。玩家往哪跑，怪跟到哪，中心就跟到哪 ——
 * 公告半径、Boss 血条名单、最近玩家、战利品落点、下一波刷怪原点全读这个值，
 * 于是整场袭击会慢慢漂走。</p>
 *
 * <p>这里在方法结尾把值改回去。{@code spawn_ring_enabled} 之外的开关是
 * {@code center_locked}，关掉就完全恢复原行为。</p>
 *
 * <p>同一个方法开头的 {@code Set.removeIf} 被换成 {@link RaidRoster#prune}：
 * 原来的判据把「区块没加载」当成「怪死了」，玩家死亡后在远处复活就会把整波怪判死然后自动过波。
 * 开关是 {@code roster.keep_unloaded_raiders}。</p>
 */
@Mixin(value = WaveRaidState.class, remap = false)
public abstract class WaveRaidStateMixin {

    @Inject(method = "updateRaiders", at = @At("TAIL"))
    private void raidplus$lockCenter(CallbackInfo ci) {
        WaveRaidStateAccessor accessor = (WaveRaidStateAccessor) (Object) this;
        Vec3 computed = accessor.getSpawnCenter();
        Vec3 locked = RaidCenter.claimCenter((WaveRaidState) (Object) this, accessor.getLevel(), computed);
        if (!locked.equals(computed)) {
            accessor.setSpawnCenter(locked);
        }
    }

    /**
     * 名单剪枝。
     *
     * <p>{@code updateRaiders} 的字节码开头是
     * {@code raiders.entrySet()} → {@code invokedynamic(Predicate)} →
     * {@code INVOKEINTERFACE java/util/Set.removeIf}，所以这里重定向整个 {@code removeIf}，
     * 换成 {@link RaidRoster#prune}（关掉开关时它会原样用这个 legacy 判据）。</p>
     *
     * <p>注意 handler 不能是 static：{@code updateRaiders} 是实例方法。</p>
     */
    @Redirect(
            method = "updateRaiders",
            at = @At(
                    value = "INVOKE",
                    target = "Ljava/util/Set;removeIf(Ljava/util/function/Predicate;)Z"))
    private boolean raidplus$pruneRoster(Set<Map.Entry<UUID, LivingEntity>> entries,
                                         Predicate<Map.Entry<UUID, LivingEntity>> legacy) {
        return RaidRoster.prune((WaveRaidState) (Object) this, entries, legacy);
    }

    /** 每一只刷出来的袭击怪都会经过 {@code addRaider}，迅捷就挂在这里。 */
    @Inject(method = "addRaider", at = @At("TAIL"))
    private void raidplus$spawnBuff(Mob mob, CallbackInfo ci) {
        RaidBuffs.onRaiderSpawn(mob);
    }

    /** 同一挂点：记录本波血量上限、认出 BOSS 档怪（进度条和 boss 血条都用这份数据）。 */
    @Inject(method = "addRaider", at = @At("TAIL"))
    private void raidplus$trackRaider(Mob mob, CallbackInfo ci) {
        RaidBar.onRaiderSpawn((WaveRaidState) (Object) this, mob);
    }

    /** 过波：本波累计血量清零（下一波重新累加）。 */
    @Inject(method = "advanceWave", at = @At("TAIL"))
    private void raidplus$onWaveAdvanced(CallbackInfo ci) {
        RaidBar.onWaveAdvanced((WaveRaidState) (Object) this);
    }
}
