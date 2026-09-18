# SCG Extra: Raid Plus

> 中文版：[README.zh_cn.md](README.zh_cn.md)

An add-on for **SCG Extra (scgextra)**'s wave raid system on Forge 1.20.1. It adds five things — a real raid center,
ring spawning around that center, raiders that converge on the center, maids in the target pool, and a speed buff on
fresh raiders — then closes three gaps where the wave raid behaves differently from a vanilla village raid
(chunk unload counted as death, progress measured in mob count instead of total health, no health bar for the final
boss), and finally re-paces the fight so the next wave no longer spawns in your face the instant the current one dies.

- Loader: Forge 1.20.1 (47.x)
- Requires: Scorched Guns 2 `0.5.5+`, SCG Extra `3.1.0+` (compiled and verified against **3.1.3**); Touhou Little Maid optional
- An add-on: it does not touch scgextra itself, and every change can be switched off individually from the config

## The nine changes

| # | Problem (scgextra's implementation) | What this mod does |
|---|---|---|
| 1 | The raid center is **the player's position at the moment the flare is fired** (`player.position()` in `WaveRaidManager.startRaid`), and `WaveRaidState.updateRaiders()` recomputes it every 20 ticks as **the average position of all raiders** — that is not a center, it is a centroid that follows the mobs (and the player they chase) | The center becomes **where the flare lands** (the flare's own position in `RaidFlareEntity.performBurst`), and it is **locked** so it is never recomputed |
| 2 | The spawn ring is drawn 30–44 blocks out, but candidate spots must also satisfy "32–48 blocks from a player", recomputed **every wave against whichever player is nearest at the time** — so the ring follows the player. On top of that, if the block 12 above the center is not air the method returns `null` and `startRaid` **silently gives up** (firing a flare in a cave, in the Nether or under a forest can do nothing at all) | Spawning becomes a **ring band around the raid center** (32–48 blocks by default) with terrain checks only; if all 40 attempts fail it falls back to the surface at the center, so it **never fails silently** |
| 3 | Raiders are purely target-driven AI (`GetCloseToTarget` / `WalkUpToIdealRange` and friends all need `ATTACK_TARGET`), and the `WalkTarget` handed to them at spawn expires after 200 ticks. Lose the target and they **stand there doing nothing**, so the player has to walk around hunting them down | Adds the vanilla `PathfindToRaidGoal` piece: a raider with nothing to shoot at, farther than `converge_distance` from the center, takes a step toward the center. **Raiders actively chasing something are never touched** |
| 4 | The target pool contains **players only**: SC2 mobs register `NearestAttackableTargetGoal(this, Player.class, true)` and nothing else, and scgextra's own `VarRangePlayerSensor` only feeds players into `NEAREST_VISIBLE_TARGETABLE_PLAYER`, which `StartAttacking` turns into `ATTACK_TARGET`. A maid only gets attacked if she shoots first | Targets are re-picked on an interval, **pool = players + TLM maids**, nearest wins. Occupying `ATTACK_TARGET` first (vanilla `StartAttacking` only runs while that memory is empty) keeps anything from resetting a maid target back to a player (**complementary**, see the note below) |
| 5 | The ring sits 32–48 blocks out and raiders stroll over | Fresh raiders spawn with **Speed I for 15 s** (`spawn_buff`; can be disabled, duration and level configurable) |
| 6 | The roster's death test counts **chunks that are not loaded** as death: `WaveRaidState.updateRaiders()` deletes a raider the moment `level.getEntity(uuid)` resolves to nothing, and deletes it when `isRemoved()` is true (which includes `UNLOADED_TO_CHUNK`). Die and respawn far away — or simply walk away — and the raid area's chunks unload, the whole wave is judged dead, `raidersLeft()==0`, so the raid **advances by itself**, burns through every remaining wave and still hands out loot as a "victory" | Raiders that cannot be resolved, or that are `isRemoved()` with reason `UNLOADED_TO_CHUNK`, are **kept**; only "resolvable and removed" (or health ≤ 0) counts as death (`roster`) |
| 7 | Progress is `raidersLeft() / totalWaveSpawned()` — numerator and denominator are both "how many are left" — while a vanilla village raid uses "total health of living raiders / total accumulated health" | Health caps are accumulated at spawn time (`getMaxHealth()`), reset when the wave advances; progress = Σ living raiders' current health / this wave's total, with unloaded raiders counted at their spawn cap (`progress`) |
| 8 | The final boss has no health bar: scgextra's only `ServerBossEvent` is the wave bar inside `WaveRaidManager`, and BOSS-rank mobs (say `scgextra:fac_tank`) carry nothing (compare SC2's own boss `ScampTankEntity`, which has a `bossEvent`). On top of that, a super raid's final wave is **`boss: 2`** (every `*_super` is `infantry 6 + elite 2 + boss 2`), which one bar cannot express | When BOSS-rank mobs spawn in the final wave, **the first one** repurposes that bar (name = boss name, colour = purple, progress = its own health) and **bosses 2..N** get bars of their own from this mod (`extra_boss_bars`) — the number of bars on screen equals the number of bosses; kill one and the next takes its place, kill them all and the wave bar returns. **Bosses that manage their own bar are not taken over** (`detect_own_boss_bar`): `scgextra:wrecker_dozer` already runs `ServerBossEvent + startSeenByPlayer/stopSeenByPlayer` itself, so one more would be a duplicate; detection walks the entity class hierarchy looking for a field of type `ServerBossEvent` |
| 9 | There is **no pause between waves**: `WaveRaidManager.NEXT_WAVE_DELAY = 30` (1.5 s) is hard-coded, so the next wave spawns at the same center the instant the current one is cleared — no window to reload, reposition or rescue a maid | The between-wave wait becomes configurable (`wave_delay`, 5 s by default): once a wave is cleared and it is not the last one, the private `nextWaveDelay` is held back until the countdown runs out, and the countdown is drawn on that bar (`FAC Raid Wave 2 · Next wave in 5s`, progress = time remaining) |

The center is read by a lot of code, so fixing #1 also brings these along: the announcement radius, the boss-bar
roster, the loot drop position, the next wave's spawn origin, and the "nearest player" test for drops.

> **On the scope of #4**: if the pack also ships `scg2_maid_compat`, it puts maids into the `scgextra:factions/player`
> faction tag, and scgextra's mobs (brain mobs via `StartAttacking(findNearestAttackableFactionEnemy)`; SC2 mobs
> adjusted by `EntityAdjustments` via `NearestAttackableTargetGoal(LivingEntity.class, ... Faction.isEnemies)`)
> **already attack maids on their own**. Under that setup #4 is a **fallback**, mainly for packs without the faction
> tag, or with `enable_player_faction` turned off, that still want raiders to come after maids.
> Evidence and the open questions are in `docs/TEST_FEEDBACK.md`.

## Config

`config/scgextra_raidplus-common.toml`

| Key | Default | Meaning |
|---|---|---|
| `raid_center.center_from_flare` | `true` | Center = where the flare lands; off = back to "the player's position when it was fired" |
| `raid_center.center_locked` | `true` | Lock the center so the centroid cannot drift; off = original behaviour |
| `spawn_ring.spawn_ring_enabled` | `true` | Spawn in a ring around the raid center; off = keep the original "32–48 blocks from a player" filter (still centered on the raid center) |
| `spawn_ring.spawn_ring_min` / `_max` | `32.0` / `48.0` | Inner / outer ring radius |
| `spawn_ring.spawn_min_player_distance` | `20.0` | Minimum horizontal distance from any player, so nothing spawns in your face; `0` = no limit |
| `converge.converge_enabled` | `true` | Master switch for converging |
| `converge.converge_distance` | `24.0` | Within this many blocks of the center they are left alone |
| `converge.converge_speed` | `1.0` | Movement speed multiplier |
| `converge.converge_interval` | `20` | How often (ticks) the walk order is re-issued |
| `converge.converge_idle_only` | `true` | Only drive raiders that have nothing to shoot at (never touches one chasing you). Set to `false` and every raider falls back to the center — a hold-the-point playstyle |
| `converge.converge_debug` | `false` | Log every push toward the center |
| `targeting.targeting_enabled` | `true` | Re-pick raider targets (players + maids) |
| `targeting.target_players` | `true` | Target pool includes players |
| `targeting.target_maids` | `true` | Target pool includes TLM maids (no-op without TLM) |
| `targeting.targeting_range` | `32.0` | Search radius; the raider's own `FOLLOW_RANGE` caps it again (SC2 mobs are usually 24–32) |
| `targeting.targeting_interval` | `20` | How often (ticks) targets are re-picked |
| `targeting.targeting_require_los` | `true` | Require line of sight (off = see through walls) |
| `targeting.targeting_switch_margin` | `4.0` | A new target must be this much closer before switching, so raiders do not flip-flop between a player and a maid |
| `targeting.targeting_debug` | `false` | Log every target change |
| `spawn_buff.spawn_buff_enabled` | `true` | Fresh raiders get Speed |
| `spawn_buff.spawn_buff_duration` | `300` | Duration in ticks; 300 = 15 s |
| `spawn_buff.spawn_buff_amplifier` | `0` | `0` = Speed I, `1` = Speed II |
| `spawn_buff.spawn_buff_particles` | `false` | Show potion particles |
| `roster.keep_unloaded_raiders` | `true` | Unloaded raiders do not count as dead (off = the original test, which brings back "die and the waves advance by themselves") |
| `roster.wave_debug` | `false` | Debug: log the roster pruning (`confirmed dead X, vanished Y, kept unloaded Z, still on the roster W`) plus one line per surviving raider (name, health, position, distance from the center, chunk loaded?) |
| `progress.progress_by_health` | `true` | Progress by total health (off = the original by-count) |
| `boss_bar.boss_bar_enabled` | `true` | Final-boss health bar (off = that bar is always the wave bar) |
| `boss_bar.extra_boss_bars` | `true` | Give bosses 2..N of a super raid a bar each (off = only the first one is shown) |
| `boss_bar.detect_own_boss_bar` | `true` | Do not take over bosses that bring their own bar (off = this mod takes over everything, so the Wrecker Dozer would show two) |
| `wave_delay.wave_delay_enabled` | `true` | Wait between waves (off = scgextra's original 1.5 s) |
| `wave_delay.wave_delay_ticks` | `100` | Wait length in ticks; 100 = 5 s; `0` = no extra wait |

## Implementation (mixin injection points)

| Class | Injection point | Purpose |
|---|---|---|
| `RaidFlareEntityMixin` | `RaidFlareEntity.performBurst` HEAD | Record where the flare lands |
| `WaveRaidManagerMixin` | `WaveRaidManager.startRaid` HEAD | Decide this raid's center |
| `WaveRaidManagerMixin` | the `WaveRaidUtil.findWaveSpawnLocation` call in `startRaid` / `tickRaid` (`@Redirect`) | Replace it with the center's ring band |
| `WaveRaidManagerMixin` | `WaveRaidManager.tick` TAIL | Converge |
| `WaveRaidManagerMixin` | `WaveRaidManager.tick` TAIL | Re-pick targets (players + maids) |
| `WaveRaidManagerMixin` | `WaveRaidManager.endRaid` HEAD / `load` RETURN | Clean up / restore the locked center after loading |
| `WaveRaidStateMixin` | `WaveRaidState.updateRaiders` TAIL | Put the locked center back over the centroid |
| `WaveRaidStateMixin` | `WaveRaidState.addRaider` TAIL | Speed buff on fresh raiders |
| `WaveRaidStateMixin` | the `Set.removeIf` call in `WaveRaidState.updateRaiders` (`@Redirect`) | Roster pruning: unloaded ≠ dead |
| `WaveRaidStateMixin` | `WaveRaidState.addRaider` TAIL | Accumulate the wave's health cap, recognise BOSS-rank mobs |
| `WaveRaidStateMixin` | `WaveRaidState.advanceWave` TAIL | Reset the wave's health accumulation |
| `WaveRaidManagerMixin` | `WaveRaidManager.tickBossBar` TAIL | Override progress (by health) / turn the bar into a boss bar on the final wave / draw the between-wave countdown |
| `WaveRaidManagerMixin` | `WaveRaidManager.tickRaid` TAIL | Hold `nextWaveDelay` back for the between-wave wait |
| `WaveRaidStateAccessor` | `spawnCenter` / `level` / `raiders` | Access to private fields |

Everything is `remap = false` (every target is a mod method; the one non-mod target is the JDK's
`java/util/Set.removeIf`, which needs no remapping either), and the refmap stays empty.
No mixin class ever names an MC member: every MC call lives in a helper class
(`RaidRoster` / `RaidBar` / `RaidCenter` / `RaidConvergence` / `RaidTargeting` / `RaidSpawnHelper`) and is remapped
by `reobfJar` as usual — which is exactly why the refmap is empty.

> A `@Inject` / `@Redirect` handler's **static modifier must match the method being injected into**,
> regardless of whether the redirected call itself is static. `raidplus$findWaveSpawn` started out static and
> crashed the game during the APPLY phase — recorded here so it does not happen a second time.

## Maid detection (TLM is optional)

`MaidSupport` **references none of TLM's classes directly**; there are two detection paths:

1. entity registry name `touhou_little_maid:maid` (via the registry, no class loading — the main path)
2. the class name `com.github.tartaricacid.touhoulittlemaid.entity.passive.EntityMaid`, resolved reflectively and
   then `isInstance` (covers subclasses; if it fails the main path still works)

Namespace-prefix matching is not enough on its own — TLM also has `fairy` / `sit` / `chair` / `tombstone` /
`danmaku` entities.

## Verified

- Compile dependencies match the deployed jars **class by class, by SHA-256**:
  - `ScorchedGuns-0.5.5-1.20.1.jar` and `curse.maven:scorched-guns-2-802940:7232063` have an identical
    `RaidFlareEntity.class` (so the `performBurst` injection point lines up)
  - `scgextra-forge-3.1.3.jar` (in `libs/`) has identical `WaveRaidManager` / `WaveRaidState` / `WaveRaidUtil`
    classes to the jar of the same name in `mods/`
- Every injection point was checked against the decompiled scgextra 3.1.3 sources for existence and uniqueness
- The newer injection points were checked with `javap` against the deployed jar:
  - `WaveRaidState.updateRaiders` starts with `raiders.entrySet()` → `invokedynamic(Predicate)` →
    `INVOKEINTERFACE java/util/Set.removeIf`, which makes that call the only (and smallest) place to hook the
    roster pruning
  - `WaveRaidManager.tickBossBar` / `advanceWave` / `addRaider(Mob)` / the private field `bossBar` all exist with
    matching signatures
- `m_21530_` (the call in `spawnCurrentWaveMobs` when a raider spawns) resolves to **`setPersistenceRequired`**
  according to `client_mappings.txt` in the `forge_gradle` cache — in other words raiders *are* persistent and do
  **not** despawn when the player leaves; the original "waves advance by themselves" was never "the mobs
  disappeared", it was "chunk unload judged as death by `updateRaiders`"

## To verify in game

1. Stand on flat ground and fire a raid flare → the log shows `[RaidPlus] 本次袭击中心锁定在 ...` and the
   coordinates should equal where the flare landed, not where you were standing when you fired
2. Afterwards walk far away → the center no longer drifts with you (boss-bar roster and announcement radius stay put)
3. Wave spawns should appear 32–48 blocks around the landing spot, not around you
4. Hide so raiders lose their target → they should walk toward the landing spot instead of standing still
5. Fire a flare in a cave or under a tree → no more "nothing happened"
6. Put a maid inside the raid area → raiders should attack her (`targeting_debug=true` shows it in the log);
   if no maid ever shows up there, first check that `[RaidPlus] 已接入 Touhou Little Maid` appears in the startup log
7. Freshly spawned raiders should have Speed I (turn on `spawn_buff_particles` to see it directly)
8. **The wave-advance one**: set `roster.wave_debug=true`, fire a flare and then **deliberately die far away**
   (or just run until the raid chunks unload). The log should show
   `[RaidPlus] 名单剪枝：确认死亡 0 只，判定消失 0 只，未加载保留 N 只，名单里还有 N 只` followed by one line per
   survivor (`[RaidPlus]   剩下：<name> 血量 x/y，坐标 (…)，离中心 N 格，区块已加载=…`) — **if it ever gets stuck
   again, those lines tell you exactly where the last raider is** — and **the wave must not advance on its own**;
   walk back and the raiders are still waiting for you (not "raiders judged dead → wave advances → free loot")
9. **Progress by health**: as this wave's raiders take damage the bar should fall smoothly, not drop in one big step
   per kill; hurting an elite and killing a grunt should contribute differently
10. **Boss bar**: once `fac_tank` spawns on the final wave, that bar's title should become **FAC Siege Tank**,
    its colour purple, and its progress the boss's own health percentage; when it dies the bar should fall back to
    the `FAC Raid · Final Wave` wave bar (or disappear with the raid)
11. **Two bosses in a super raid**: run `iron_super` (FAC super) → two `fac_tank` in the final wave, and there
    should be **two** "FAC Siege Tank" bars, each tracking its own boss; kill one and the other keeps going
    (for `asgharian_super`, where the two can be a Candle Fiend and a Soul Ripper, the two bars should carry those
    two names)
12. **No duplicate bars for self-managed bosses**: run `wrecker_super` → two Wrecker Dozers in the final wave and
    there should be **only the two bars they bring themselves**, never a third one added by this mod
    (with `roster.wave_debug=true` the log says `自带血条 → 不接管`, "has its own bar → not taken over")
13. **Wait between waves**: after a wave is cleared the bar should turn into a countdown like
    `FAC Raid Wave 2 · 下一波 5 秒`, with the progress draining as the seconds pass, and the next wave should only
    spawn about 5 seconds later (raise or lower `wave_delay_ticks` to see it directly); clearing the final wave
    waits for nothing and ends the raid with loot

## Known issues

> Test feedback and things that were observed but not yet decided on live in `docs/TEST_FEEDBACK.md`.

**~~`未能加载有效的 ResourcePackInfo` / `Missing metadata in pack mod:scgextra_raidplus`~~ — fixed (2026-09-14)**

- Symptom: the game log shows
  `[Render thread/WARN] [net.minecraft.server.packs.repository.Pack/]: Missing metadata in pack mod:scgextra_raidplus`,
  and the mod list complains that the jar has no valid ResourcePackInfo
- Cause: the jar had **no `pack.mcmeta`** (`assets/scgextra_raidplus/lang` was still an empty directory at the time,
  so the whole `assets/` tree never made it into the jar) — Forge could not read the metadata when building the
  built-in resource pack for the mod
- Fix: added `src/main/resources/pack.mcmeta` (`pack_format` = 15 for 1.20.1; the description uses `${mod_name}`,
  expanded by `filesMatching(['META-INF/mods.toml', 'pack.mcmeta'])` in `build.gradle` during `processResources`
  → `"SCG Extra: Raid Plus resources"`)
- Verified: after building, `build/resources/main/pack.mcmeta` is valid JSON with the placeholder expanded, and
  `pack.mcmeta` is visible inside the deployed jar

**~~A cleared wave does not advance (the raid hangs until the 10-minute timeout)~~ — fixed (2026-09-18, second test round)**

- Symptom: every mob is dead, the bar still shows progress, the next wave never spawns, and the raid eventually
  fails on the timeout (in the log, two raid starts are ≈11 minutes apart — exactly `RAID_TIMEOUT_TICKS = 12000`)
- Cause: **the entity object held in the roster becomes a "tombstone"**. MC's
  `Entity.setRemoved(RemovalReason)` is `final` and only writes when the field is currently `null`, so it can never
  be cleared; on chunk unload `PersistentEntitySectionManager` uses exactly that to mark entities
  `UNLOADED_TO_CHUNK` and keeps them in memory, while on chunk **reload** the raider that exists in the world is
  **a different object created from the save** (same UUID)
- So a raider that was unloaded once stays in the roster as the old object (`UNLOADED_TO_CHUNK`) forever: the player
  kills the new object and the roster never empties → `raidersLeft()` stays > 0 → no wave advance
- Fix: `RaidRoster` now **re-resolves every tick** and adopts the object that exists in the world; `isGone` also
  checks **health first** (dead is dead, whether or not it was tagged as a chunk unload), plus a 60-tick grace
  period for "the chunk is loaded but the entity is not in the world" to cover more exotic cases
- Verified: see item 8 of "To verify in game"

## Building

```powershell
$env:JAVA_TOOL_OPTIONS='-Duser.language=en -Duser.country=US -Dfile.encoding=UTF-8'
.\gradlew.bat build --offline
```

- Output: `build/libs/scgextra_raidplus-<version>.jar` (already reobfuscated, ready to drop into `mods/`)
- Compile dependencies:
  - Scorched Guns 2 / GeckoLib / Framework — fetched automatically through CurseMaven (declared in `build.gradle`)
  - **SCG Extra 3.1.3** — someone else's mod, **not redistributed here**: download `scgextra-forge-3.1.3.jar`
    from CurseForge and put it in `libs/` (`build.gradle` references it through `flatDir`, so the file name must
    match). The version has to match what the pack actually runs, otherwise the `WaveRaid*` injection points may
    not line up
- Local deployment (optional): put `mods_folder=D\:\\MCJAVA\\.minecraft\\versions\\1.20.1-Forge_47.4.21\\mods`
  in `~/.gradle/gradle.properties` and `build` will copy the jar into that directory when it finishes; leave it out
  (or point it at a directory that does not exist) and the copy is skipped, leaving the jar in `build/libs`
- `org.gradle.java.home` in `gradle.properties` points at a local JDK 17 — delete it or point it at your own when
  building on another machine
- **Never overwrite a jar the game has already loaded while it is running** (it invalidates the zip index →
  `NoClassDefFoundError`)

## License

GNU GPLv3, see `LICENSE`. The third-party mod jars you put in `libs/` are not covered by this repository's license
and are not distributed with it.
