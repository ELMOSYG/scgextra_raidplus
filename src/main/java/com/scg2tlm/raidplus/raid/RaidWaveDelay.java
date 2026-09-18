package com.scg2tlm.raidplus.raid;

import com.scg2tlm.raidplus.RaidPlusConfig;
import java.util.Collections;
import java.util.Map;
import java.util.WeakHashMap;
import javax.annotation.Nullable;
import net.minecraft.server.level.ServerLevel;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 波与波之间的等待（原版村庄袭击那种「打完一波，等一会儿再来下一波」）。
 *
 * <h3>scgextra 原实现</h3>
 * <p>{@code WaveRaidManager} 里 {@code NEXT_WAVE_DELAY = 30}（1.5 秒）而且是写死的：
 * 一波清完 30 刻后，下一波就在同一个中心直接刷出来，中间没有喘口气/补子弹的窗口。</p>
 *
 * <h3>这里怎么做</h3>
 * <p>不去改 scgextra 的常量，而是在每次 {@code tickRaid} 结尾把它私有的 {@code nextWaveDelay}
 * 「按住」：只要这一波已经清完、又不是终波、而且我们自己的倒计时还没到，就把它重新写回 30 ——
 * {@code nextWaveDelay-- &lt; 0} 永远不成立，波次就不会推进。倒计时到了就放手，
 * scgextra 自己会在约 {@value #SCGEXTRA_DELAY_TICKS} 刻后推进（这个尾巴已经算进倒计时里）。</p>
 *
 * <p>倒计时画在那条 Boss 条上（见 {@link RaidBar#apply}）：名字后面接「下一波 N 秒」，
 * 进度 = 剩余比例。开关 {@code wave_delay.wave_delay_enabled} / {@code wave_delay_ticks}。</p>
 */
public final class RaidWaveDelay {
    /** scgextra 自己那 30 刻的推进延迟（含它那次「减到负数才推进」的一刻）。 */
    private static final long SCGEXTRA_DELAY_TICKS = 31L;

    /** 每个袭击状态：倒计时截止时刻（不在等待时没有这条记录）。 */
    private static final Map<WaveRaidState, Long> DEADLINES = Collections.synchronizedMap(new WeakHashMap<>());

    private RaidWaveDelay() {
    }

    /**
     * 在 {@code WaveRaidManager.tickRaid} 结尾调用。
     *
     * @return 还要按住多少刻（&gt; 0 表示这次要把 {@code nextWaveDelay} 压回 30）
     */
    public static long holdTicks(WaveRaidState state, ServerLevel level) {
        if (!RaidPlusConfig.WAVE_DELAY_ENABLED.get() || state.isFinalWave() || state.raidersLeft() > 0) {
            DEADLINES.remove(state);
            return 0L;
        }

        long now = level.getGameTime();
        long delay = RaidPlusConfig.WAVE_DELAY_TICKS.get();
        Long deadline = DEADLINES.get(state);
        if (deadline == null || deadline - now > delay) {
            deadline = now + delay;
            DEADLINES.put(state, deadline);
        }

        long remaining = deadline - now;
        // 最后 31 刻交给 scgextra 自己走完，倒计时显示的秒数才不会和实际刷怪时间对不上
        return remaining > SCGEXTRA_DELAY_TICKS ? remaining : 0L;
    }

    /** 还在等的话返回剩余刻数，否则 -1。 */
    public static long remainingTicks(@Nullable WaveRaidState state, @Nullable ServerLevel level) {
        if (state == null || level == null || !RaidPlusConfig.WAVE_DELAY_ENABLED.get()) {
            return -1L;
        }
        Long deadline = DEADLINES.get(state);
        if (deadline == null) {
            return -1L;
        }
        long remaining = deadline - level.getGameTime();
        return remaining > 0L ? remaining : -1L;
    }

    /** 袭击结束：清记录。 */
    public static void forget(WaveRaidState state) {
        DEADLINES.remove(state);
    }
}
