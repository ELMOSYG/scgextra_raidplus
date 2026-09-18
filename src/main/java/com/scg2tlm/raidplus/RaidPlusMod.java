package com.scg2tlm.raidplus;

import com.mojang.logging.LogUtils;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.config.ModConfig;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import org.slf4j.Logger;

/**
 * SCG Extra: Raid Plus —— 给 SCG Extra 的波次袭击补上「袭击中心」。
 *
 * <h2>本版做的三件事</h2>
 * <ol>
 *   <li><b>袭击中心 = 信号弹落点</b>。scgextra 原来的中心是 {@code player.position()}，
 *       而且 {@code WaveRaidState.updateRaiders()} 每 20 刻把中心重算成所有袭击怪位置的<b>平均值</b>，
 *       所以那根本不是中心、是个会漂的质心。</li>
 *   <li><b>刷怪围着中心</b>。原实现把候选点按「必须离玩家 32~48 格」筛一遍，
 *       而且每波都用<b>当时最近的玩家</b>重算，所以刷怪环是跟着玩家跑的。</li>
 *   <li><b>怪会朝中心靠拢</b>。scgextra 的怪是纯目标驱动 AI（GetCloseToTarget / ApproachTargetGoal
 *       之类），没有任何「移动到某个坐标」的行为，所以一旦没有攻击目标就原地发呆。</li>
 * </ol>
 *
 * <p>全部行为都接在 {@link RaidPlusConfig} 里，可以逐项关掉；关掉后完全退回 scgextra 原行为。</p>
 */
@Mod(RaidPlusMod.MODID)
public class RaidPlusMod {
    public static final String MODID = "scgextra_raidplus";
    public static final Logger LOGGER = LogUtils.getLogger();

    public RaidPlusMod(FMLJavaModLoadingContext context) {
        context.registerConfig(ModConfig.Type.COMMON, RaidPlusConfig.SPEC);
    }
}
