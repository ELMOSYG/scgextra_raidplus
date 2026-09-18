package com.scg2tlm.raidplus.compat;

import com.scg2tlm.raidplus.RaidPlusMod;
import javax.annotation.Nullable;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityType;
import net.minecraftforge.fml.ModList;
import net.minecraftforge.registries.ForgeRegistries;

/**
 * 女仆识别（TLM 是可选依赖，所以这里一个 TLM 的类都不直接引用）。
 *
 * <p>两条路，任意一条命中就算女仆：</p>
 * <ol>
 *   <li><b>实体类型</b>：注册名 {@code touhou_little_maid:maid}。这是主路径 ——
 *       走注册表，不碰类加载，最稳。（TLM 还有 {@code fairy}/{@code sit}/{@code chair}/{@code tombstone}
 *       等实体，所以不能简单地按命名空间前缀匹配。）</li>
 *   <li><b>类名</b>：{@code com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid}，
 *       反射解析后 {@code isInstance} 判断，能覆盖子类。用
 *       {@code Class.forName(name, false, loader)} 不触发类初始化；解析失败只是少一条路。</li>
 * </ol>
 *
 * <p>顺带一提：scgextra 的阵营标签把玩家和女仆放在同一个 "player" 阵营，
 * 所以女仆和玩家之间不会被 {@code Faction.isEnemies} 判成敌人 —— 但这跟 {@code Mob.canAttack} 无关，
 * 我们把目标直接塞进 {@code ATTACK_TARGET} 就行。</p>
 */
public final class MaidSupport {
    private static final String MAID_MOD_ID = "touhou_little_maid";
    private static final String MAID_CLASS_NAME = "com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid";
    private static final ResourceLocation MAID_ENTITY_ID = new ResourceLocation(MAID_MOD_ID, "maid");

    @Nullable
    private static EntityType<?> maidType;
    @Nullable
    private static Class<?> maidClass;
    private static boolean initialised;

    private MaidSupport() {
    }

    /** 第一次用到时才解析；失败就永久当作「没有 TLM」。 */
    private static void init() {
        if (initialised) return;
        initialised = true;

        ModList modList = ModList.get();
        if (modList == null || !modList.isLoaded(MAID_MOD_ID)) {
            RaidPlusMod.LOGGER.info("[RaidPlus] 没有检测到 Touhou Little Maid，女仆目标功能关闭");
            return;
        }

        maidType = ForgeRegistries.ENTITY_TYPES.getValue(MAID_ENTITY_ID);
        maidClass = resolveMaidClass();

        if (maidType == null && maidClass == null) {
            RaidPlusMod.LOGGER.warn("[RaidPlus] TLM 已加载但既找不到实体 {} 也找不到类 {}，女仆目标功能关闭",
                    MAID_ENTITY_ID, MAID_CLASS_NAME);
        } else {
            RaidPlusMod.LOGGER.info("[RaidPlus] 已接入 Touhou Little Maid（实体={}, 类={}），女仆可以作为袭击目标",
                    maidType != null, maidClass != null);
        }
    }

    /** 按类名解析 EntityMaid；失败返回 null（还有实体类型那条路）。 */
    @Nullable
    private static Class<?> resolveMaidClass() {
        ClassLoader own = MaidSupport.class.getClassLoader();
        Class<?> resolved = tryLoad(own);
        if (resolved != null) return resolved;

        ClassLoader context = Thread.currentThread().getContextClassLoader();
        if (context != null && context != own) {
            resolved = tryLoad(context);
            if (resolved != null) return resolved;
        }
        return null;
    }

    @Nullable
    private static Class<?> tryLoad(@Nullable ClassLoader loader) {
        if (loader == null) return null;
        try {
            // initialize = false：只解析，不触发 TLM 的静态初始化
            return Class.forName(MAID_CLASS_NAME, false, loader);
        } catch (ClassNotFoundException | LinkageError e) {
            return null;
        }
    }

    /** 女仆能不能被当成目标（TLM 装了且至少有一条识别路径可用）。 */
    public static boolean isAvailable() {
        init();
        return maidType != null || maidClass != null;
    }

    public static boolean isMaid(Entity entity) {
        init();
        if (maidType != null && entity.getType() == maidType) return true;
        return maidClass != null && maidClass.isInstance(entity);
    }
}
