package com.scg2tlm.raidplus.raid;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.WeakHashMap;
import net.minecraft.server.level.ServerBossEvent;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.util.Mth;
import net.minecraft.world.BossEvent.BossBarColor;
import net.minecraft.world.BossEvent.BossBarOverlay;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.phys.Vec3;
import net.zincstudios.scgextra.raid.WaveRaidState;

/**
 * 「多出来的」boss 血条。
 *
 * <p>scgextra 只有一条 bar（{@code WaveRaidManager.bossBar}）。普通袭击的终波只有一只 boss，
 * 那条 bar 换成 boss 血条就够了；但超级袭击的终波是 {@code boss: 2}
 * （{@code fac_super} 等是两台同型，{@code asgharian_super} 还是一火一魂两种），
 * 一条 bar 装不下两只 boss。</p>
 *
 * <p>约定：<b>刷怪顺序里的第一只 boss 用 scgextra 那条 bar</b>（见 {@link RaidBar#apply}），
 * 第 2..N 只用这里新建的 {@link ServerBossEvent}。这样屏幕上正好是「boss 数量」条血条：
 * 普通袭击 1 条、超级袭击 2 条，而且终波本来就没有独立的波次条，不会更挤。</p>
 *
 * <p>玩家列表照 scgextra 的做法来：离袭击中心 {@value #VISIBLE_RADIUS} 格内的存活玩家。
 * 每刻同步一次（值没变时 {@code ServerBossEvent} 自己不发包，所以是幂等的）。</p>
 *
 * <p>按袭击状态分开存（弱引用键），所以不同维度的袭击同时开也不会互相收条。</p>
 */
public final class RaidBossBars {
    /** 和 scgextra 那条 bar 用同一个可见半径。 */
    private static final double VISIBLE_RADIUS = 512.0;

    private static final Map<WaveRaidState, Map<UUID, ServerBossEvent>> BARS =
            Collections.synchronizedMap(new WeakHashMap<>());

    private RaidBossBars() {
    }

    /**
     * 让额外的 boss 血条和给定的 boss 列表对齐：少了的收掉、多了的补上、都在的刷新。
     *
     * @param extraBosses 第 2..N 只 boss（第一只不在这里 —— 它用的是 scgextra 那条 bar）
     */
    public static void sync(WaveRaidState state, ServerLevel level, Vec3 center, List<LivingEntity> extraBosses) {
        Map<UUID, ServerBossEvent> bars = BARS.computeIfAbsent(state, ignored -> new HashMap<>());
        Set<UUID> wanted = new HashSet<>();
        for (LivingEntity boss : extraBosses) {
            wanted.add(boss.getUUID());
        }

        Iterator<Map.Entry<UUID, ServerBossEvent>> iterator = bars.entrySet().iterator();
        while (iterator.hasNext()) {
            Map.Entry<UUID, ServerBossEvent> entry = iterator.next();
            if (!wanted.contains(entry.getKey())) {
                hide(entry.getValue());
                iterator.remove();
            }
        }

        for (LivingEntity boss : extraBosses) {
            ServerBossEvent bar = bars.computeIfAbsent(boss.getUUID(), ignored ->
                    new ServerBossEvent(boss.getDisplayName(), BossBarColor.PURPLE, BossBarOverlay.PROGRESS));
            bar.setName(boss.getDisplayName());
            bar.setColor(BossBarColor.PURPLE);
            bar.setProgress(Mth.clamp(boss.getHealth() / Math.max(1.0F, boss.getMaxHealth()), 0.0F, 1.0F));
            bar.setVisible(true);
            syncPlayers(level, center, bar);
        }
    }

    /** 这次的袭击不再需要额外血条了（boss 死了 / 关掉开关 / 袭击结束）：全部收掉。 */
    public static void clear(WaveRaidState state) {
        Map<UUID, ServerBossEvent> bars = BARS.remove(state);
        if (bars == null) {
            return;
        }
        for (ServerBossEvent bar : bars.values()) {
            hide(bar);
        }
    }

    private static void hide(ServerBossEvent bar) {
        bar.setVisible(false);
        bar.removeAllPlayers();
    }

    private static void syncPlayers(ServerLevel level, Vec3 center, ServerBossEvent bar) {
        List<ServerPlayer> nearby = new ArrayList<>();
        for (ServerPlayer player : level.players()) {
            if (player.isAlive() && !player.isRemoved() && !player.isSpectator()
                    && player.position().distanceToSqr(center) <= VISIBLE_RADIUS * VISIBLE_RADIUS) {
                nearby.add(player);
            }
        }

        for (ServerPlayer player : List.copyOf(bar.getPlayers())) {
            if (!nearby.contains(player)) {
                bar.removePlayer(player);
            }
        }
        for (ServerPlayer player : nearby) {
            if (!bar.getPlayers().contains(player)) {
                bar.addPlayer(player);
            }
        }
    }
}
