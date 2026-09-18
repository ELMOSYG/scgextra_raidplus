> 中文版：[TEST_FEEDBACK.zh_cn.md](TEST_FEEDBACK.zh_cn.md)
# Test Feedback

> This file only holds things that were "observed but not yet decided whether to change", to avoid re-deriving them at the next round of feedback.
> The status line is authoritative: `undecided / fixed / won't fix`.

---

## 2026-09-14 · Maid used the Ganju Medicine 5 times in one FAC normal raid

**Test conditions** (provided by the user): a maid with 100 HP, diamond armor + shield and the weapon `scguns:greaser_smg`, fighting a FAC normal flare raid (4 waves).

**Result**: across the 4 waves the maid triggered the Ganju Medicine 5 times = she died 5 times.

**Purpose of the test (added by the user)**: measure **the maid's ability to solo the raid (without depending on the player)** — not the normal "player + maid" way of playing, but "the maid carrying a whole raid on her own".

**Status: undecided — the user chose "don't change it for now, keep it as a record" (2026-09-14).**

### 0. The premise of "not depending on the player"

When scgextra spawns mobs, the source of the **initial target** is the player:

- `startRaid`: the first wave sets every mob's target to **the player who fired the flare** (`player`)
- `tickRaid` on later waves: `WaveRaidUtil.findNearestPlayer(level, center, 512)`, **if no player is found it is `null`**
- `WaveRaidState.spawnCurrentWaveMobs(..., target)`: when `target == null` it goes down `mob.setTarget(null)` → the mob **has no target when it spawns**

But "no target at spawn" **does not mean it will just idle**: the mob's own AI re-acquires its target, and that **does not depend on the player** —
see the correction in §3: scgextra's brain mobs use `StartAttacking(findNearestAttackableFactionEnemy)`,
and the adjusted SC2 mobs use `NearestAttackableTargetGoal(LivingEntity.class, ... Faction.isEnemies)`.
With `scg2_maid_compat` installed (maids belong to the `player` faction), the maid herself is a legitimate enemy target for them.

Conclusion: **5 revives ≈ the price of soloing a player-scale raid (23 gunners + 1 boss)**, not broken numbers;
and "the maid being attacked on purpose" is a pre-existing result of the faction design on the `SCG2_TLM` side, not something this mod introduced.

### 0.1 A control experiment that needs no code changes (suggested way to feed data next time)

Flip the switches in `config/scgextra_raidplus-common.toml` one at a time, run the same FAC normal raid, and record the number of revives:

| Experiment | Config | What we want to measure |
|---|---|---|
| Baseline | `targeting_enabled=false` + `converge_enabled=false` + `spawn_buff_enabled=false` | What level the maid solos at under pure scgextra + the faction design (expected: she still gets attacked; the difference is in "the first target at spawn" and the advance speed) |
| Targeting only | `targeting_enabled=true`, the rest off | How much "whoever is closest gets hit" contributes on its own |
| Swiftness only | `spawn_buff_enabled=true`, the rest off | How much Swiftness II contributes on its own (expected: this one is the most obvious) |
| All on | all defaults | the 5 revives currently measured in game |

Comparing these four tells you whether the levers in §5 should be touched, instead of tuning by feel.

### 1. Arithmetic: this raid has 23 mobs in total

`data/scgextra/raids/fac.json` (each point's `value` defaults to 1 = 1 mob):

| Wave | Composition | Count |
|---|---|---|
| 1 | infantry 8 | 8 |
| 2 | infantry 5 + elite 2 | 7 |
| 3 | infantry 3 + elite 3 + miniboss 1 | 7 |
| 4 | boss 1 (`fac_tank`) | 1 |
| | | **23** |

All of them carry SC2 guns. So the scale of "dying 5 times" = a 100 HP maid up against 23 guns, not some number being broken.

### 2. The amplifier: scgextra's own "faction alert" turns single-target aggro into group-wide aggro

Evidence (verified this time from the decompiled sources of scgextra 3.1.3):

- `CheckShouldAlert` (`net.zincstudios.scgextra.entity.common.brain.CheckShouldAlert`)
  - Memory requirement: `ATTACK_TARGET` must exist and `TO_ALERT` must not exist → it only runs **when it has a target**
  - Scans `entity.getBoundingBox().inflate(radius, radius/2, radius)`, **radius defaults to 64 blocks** (`CheckShouldAlert(int alertDuration)` → `this(alertDuration, 100, 64.0F)`)
  - Conditions to be added to `toAlert`: same faction (`Faction.isFriendlies`) and **it currently has no target of its own** (the brain's `ATTACK_TARGET` is VALUE_ABSENT, or `mob.getTarget() == null`)
  - Cooldown: `alertDuration(10) + alertCooldown(100)` ticks
- `AlertNearbyFactionMobs` (same package)
  - Calls `BrainUtils.setTarget(other, target)` on every mob in `TO_ALERT` — **it hands out the target of the mob that raised the alert verbatim**
  - `BrainUtils.setTarget`: writes the memory when `ATTACK_TARGET` is empty, otherwise `mob.setTarget`

Conclusion: as soon as **one** raider locks onto the maid, every same-faction mob still idling within 64 blocks starts attacking the maid **all at once** — no line of sight needed, no need to spot her on their own.

### 3. Correction: the maid being focus-fired is mainly **pre-existing design**, not caused by this mod's changes

The user pointed out that `SCG2_TLM` (the maid compatibility mod) already gives players and maids **separate factions**, and SCG mobs attack maids on their own anyway. Checking confirmed this holds:

- The deployed `scg2_maid_compat-1.2.0.jar` contains `data/scgextra/tags/entity_types/factions/player.json`:
  `["minecraft:player", "touhou_little_maid:maid"]` → maids belong to the `player` faction (`Faction.isEnemies` needs both sides to have a faction and for them to differ; the raid mobs' faction is `fac` etc. → judged hostile)
- scgextra gives **its own brain mobs**: `BrainCommons.initIdleActivity` →
  `StartAttacking.create(BrainUtils::findNearestAttackableFactionEnemy)` (`f_148205_` = filter `NEAREST_VISIBLE_LIVING_ENTITIES` by `Faction.isEnemies`)
- scgextra also added, for **SC2's mobs** (the `scguns:cog_knight` / `cog_minion` / `sky_carrier` / `trauma_unit` / `adjudicator` / `dissident` / `praetor` / `subjugator` used in raids are all on the list), at `EntityAdjustments.onEntityJoin`:
  `NearestAttackableTargetGoal(mob, LivingEntity.class, true, entity -> Faction.isEnemies(mob, entity))`, and removed the vanilla `HurtByTargetGoal`

So **under this configuration** the 23 FAC mobs would have actively locked onto the maid anyway, independently of `RaidTargeting`. The earlier note that "this mod's targeting change amplified the focused fire" **does not hold** for this test; corrected here.

What this mod actually changed in this test:

- Replacing "initial target = player" with "whoever is closest gets hit (including the maid, with a 4-block switch margin)" — the behavior changed, but under the faction system the outcome converges
- `spawn_buff` Swiftness II: mobs get in your face faster, the firing window is longer (**this one is a real, solid buff**)
- Spawn ring + convergence toward the center: mobs arrive from all sides almost simultaneously, so the focused fire is more concentrated (previously they spawned scattered around the player)

### 3.1 The resulting open question: is `RaidTargeting` still needed?

- Under a configuration with scg2_maid_compat installed and `enable_player_faction=true` (the default), it is **redundant**
- It also **fights** that switch: with `enable_player_faction=false` players + maids become `NO_FACTION` and the faction system is no longer hostile (which is exactly what that switch is for), yet `RaidTargeting` still force-feeds targets to raid mobs
- Its only remaining value: when scg2_maid_compat is not installed (no faction tag), or the user manually turned off the player faction but still wants raid mobs to attack maids/players

**Status: undecided — the user chose "leave it alone for now, keep recording" (2026-09-14).**

If it is to be changed later, note two things:

- **Satisfying only "fill in when the target is empty" is not enough**: the faction system assigns targets on its own, so an aggro cap would have to be able to **actively reassign the excess mobs** (which amounts to overriding scgextra's assignment)
- What really decides "is she attacked by one or by all 23" is the **64-block faction alert broadcast** in `CheckShouldAlert` (§2), not targeting itself

### 4. Additional observation from the same run: shield + diamond armor durability fully depleted

**Observation** (provided by the user): in this run the shield and diamond armor the maid was carrying both had their durability fully depleted.

**How to read this result**: durability depleted = this raid landed **several hundred effective hits**, not a few big damage hits.

- Scale of the durability pool: a full diamond set ≈ 363(helmet) + 528(chest) + 495(legs) + 429(boots) = **1815**, shield **336** → about **2150** in total
- Armor: each hit costs every piece `max(1, damage/4)` durability (`LivingEntity.hurtArmor`) → grinding through 1815 points takes **several hundred hits**
- Shield: each block costs `1 + floor(this hit's damage)` (`hurtCurrentlyUsedShield`, only deducted when damage ≥3) → SC2 bullets have high per-shot damage,
  **a 336-durability shield only lasts about twenty or thirty shots**, and under a volley of automatic weapons it is gone within tens of seconds
- If the gear has Unbreaking III (effective durability roughly ×4/×3), the actual number of hits is several times higher again, with no upper bound

**Conclusion**:

- The maid's damage reduction **is working** (the shield really blocks, the armor really absorbs), she was not one-shot — dying 5 times most likely happened **after the gear was ground through**
- This is a **war of attrition / hit count** problem, not a stats-exploding problem → it points to the same root cause as the faction alert in §2 (23 mobs pressing in at once),
  and it aligns with the direction of C (limit the 64-block broadcast) / D (shrink the waves) in §5

**Mechanism reminder on the TLM side (Mending cannot keep up)** — evidence (TLM 1.5.3 decompiled sources, `EntityMaid.java :: pickupXPOrb`):

```java
ItemStack itemstack = this.getRandomItemWithMendingEnchantments(allItems);   // 只挑带 Mending 的
int i = Math.min((int)(orb.f_20770_ * itemstack.getXpRepairRatio()), itemstack.m_41773_());
orb.f_20770_ -= i / 2;                    // 球的 XP 还要减半
itemstack.m_41721_(itemstack.m_41773_() - i);
```

- The prerequisite is that the maid has **her pickup switch on** (`isPickup()`, within `MAID_PICKUP_RANGE`)
- Each XP orb **repairs only one random** piece of Mending gear, and the repair amount is halved first
- Therefore: the maid's gear is basically **consumable** during a raid — sustained hits from 23 automatic weapons cannot be made up by a handful of XP orbs

**Two directions you can try without changing code**:

1. Widen `spawn_ring_min` / `spawn_ring_max` (e.g. 48 / 64) → mobs arrive in batches instead of pressing in at once, lowering the hits per second
2. `spawn_buff_enabled=false` → drop Swiftness II, likewise lowering the hits per unit of time

**Status: undecided — the user chose "leave it alone for now, keep recording" (2026-09-14).**

### 5. Candidate tuning levers (none of them done yet)

| | Approach | Cost |
|---|---|---|
| **C (looks the most critical now)** | Weaken the faction alert: mixin `CheckShouldAlert`, limit that 64-block broadcast radius / require the notified mob to see the target itself / turn the whole thing off | Changes scgextra's own behavior; but it is the direct cause of "23 mobs pressing one person at once" |
| **A** | Aggro cap: `max_attackers_per_target` (default 4) | **Note**: the faction system assigns targets on its own, so it must be able to actively reassign mobs that are already locked on, not just fill empty slots; it amounts to overriding scgextra's assignment logic |
| **B** | Player priority: don't pick the maid when a hittable player is in range (the maid becomes a fallback) | Opposite to the requirement that "maids must be attackable", i.e. half a rollback; and that faction-system path is not under its control |
| **D** | Shrink the waves: `wave_size_scale` (0.5 = wave value points halved, 23 → about 12 mobs) | Needs a mixin on `WaveRaidData.generateRaiders`; the most direct, and it improves performance along the way |

**The advice given at the time**: A + C was recommended originally; after correcting "the faction system has already taken over targeting", **C is the priority**, and A costs more to implement than originally estimated.

### 6. Data that would make the next round of feedback easier to pin down

- **Did she actually finish the raid**: all 23 cleared (raid victory) / 10-minute timeout / or the player later helped finish it — that is the core metric of "soloing ability", not how many times she died
- Was the maid killed by **focused fire**, or by **one particular mob** (e.g. the wave-4 `fac_tank`) → enable `targeting.targeting_debug=true` and look in the log at how many mobs are locked onto the maid at the same time
- When she died, was the shield actually in effect, and was it eaten through by SC2's armor-piercing/headshot mechanics
- Was the maid's own TLM combat task/avoidance working (`greaser_smg` is an SMG, so the engagement distance is very close)
- Roughly how long did soloing this raid take (4 waves × 30-tick interval + clearing time)

---

## 2026-09-18 · Three problems with scg-extra wave raids (user report) — located and fixed

**User report**: ① player death automatically advances the wave; ② the final boss has no boss bar; ③ the raid progress bar counts enemies (the vanilla village raid uses total health).

**Status: fixed (compiled + deployed, 2026-09-18 17:02 overwrote `mods\scgextra_raidplus-1.0.0.jar`, 36578 B / SHA-256 `A5B86D90…`), to verify in game.**

### 1. "Automatic wave advance" = treating "chunk not loaded" as "mob died"

There is only one path to advance a wave: `raidState.raidersLeft() == 0` (= `raiders.size()`) in `WaveRaidManager.tickRaid`.
The roster is pruned every tick by `WaveRaidState.updateRaiders()` with `removeIf`; the original criteria were two:

```java
if (entry.getValue() == null) {
   if (!(this.level.getEntity(entry.getKey()) instanceof LivingEntity living)) return true;   // ① 解析不到 → 删
   entry.setValue(living);
}
return entry.getValue().isRemoved();                                                           // ② removed → 删
```

**On a chunk unload both criteria hold at the same time**: the entity is `setRemoved(RemovalReason.UNLOADED_TO_CHUNK)`,
and simultaneously disappears from `ServerLevel.getEntity`'s lookup table. So "the mob is still there, just not loaded" is judged as "the mob died".

The chain of consequences: the roster empties instantly → 30 ticks later `advanceWave()` → the next wave spawns at the same center (which nobody is loading any more)
→ gets emptied again → burns through all the remaining waves → `endRaid(success=true)` and it even hands out loot.
After a player death, respawning in a bed/world spawn point (further from the raid center than the loading distance) is the easiest way to trigger it.

**Ruled out**: the mobs themselves are persistent — `spawnCurrentWaveMobs` calls `mob.m_21530_()`,
and checking against `client_mappings.txt` in `forge_gradle`, `m_21530_` = **`setPersistenceRequired`**.
So it is not "the mobs despawned", it is "not loaded being judged as dead".

**Fix** (`RaidRoster` + `WaveRaidStateMixin`'s `@Redirect` on `Set.removeIf`):
unresolvable / `UNLOADED_TO_CHUNK` → keep; only "resolvable and already removed" or "health ≤ 0" counts as dead.
Switch `roster.keep_unloaded_raiders` (off = the original criteria); `roster.wave_debug` lets the log show
`confirmed dead: X, kept as unloaded: Y, still on the roster: Z`.

### 2. Progress bar counts numbers

```java
this.bossBar.setProgress((float)this.raidState.raidersLeft() / (float)this.raidState.getTotalWaveSpawned());
```

Both numerator and denominator are "how many are left": `totalWaveSpawned` is written as `raiders.size()` in `addRaider`, and zeroed when a wave advances.
Vanilla `Raid` uses "total health of living raiders / cumulative total health".
The data side is fine: `raider.max_health` of -1 (the default) does not add a health bonus (in `RaiderEntry.createEntity` it is only added when `maxHealth > 0`),
so `getMaxHealth()` at spawn time is authoritative and can be summed per mob.

**Fix** (`RaidBar`): when `addRaider` runs, accumulate this wave's max health; progress = Σ current health of living mobs / this wave's max;
unloaded mobs count at their spawn max (a chunk unload should not make the progress drop by a chunk out of nowhere). Switch `progress.progress_by_health`.
After loading a save, if this wave never went through `addRaider` (no accumulated value) it returns -1 and falls back to the original counting formula, so there is no division by zero.

### 3. The final boss has no boss bar

Across all of scgextra there is exactly one `ServerBossEvent`: `WaveRaidManager.bossBar` (the RED / NOTCHED_10 wave bar).
BOSS-rank entities do not carry a bossEvent themselves (`FacTankEntity` is just the ordinary `Monster` set),
compare SC2's own boss: `ScampTankEntity` carries its own `bossEvent` (YELLOW / PROGRESS, color by health, bar on player enter/exit).

**Fix** (`RaidBar` + `WaveRaidManagerMixin.tickBossBar` TAIL): when `addRaider` runs, identify the boss from the datapack boss roster
(`WaveRaidData.getRaiderEntries(Rank.BOSS)` compared against the entity type);
while it is alive, swap that bar's title to the boss name, its color to PURPLE and its progress to its own health; as soon as it dies it automatically falls back to the wave bar.
Switch `boss_bar.boss_bar_enabled`.

### 3.1 A super raid has two bosses (the final wave of every `*_super` is `boss: 2`)

The final waves of `fac_super` / `cog_super` / `rrc_super` / `whaler_super` / `wrecker_super` are all
`infantry 6 + elite 2 + boss 2`; `asgharian_super`'s boss roster even has **two kinds**
(`candle_fiend` / `soul_ripper`), two picked at random by weight — it can be one fire and one soul, or two of the same kind.

One bar cannot hold two bosses, so: **the first one keeps using scgextra's bar**, and bosses 2..N get another `ServerBossEvent` opened by `RaidBossBars`
(name = boss name, purple, its own health; the player list is synced every tick by "living players within 512 blocks of the raid center"
— idempotent, no packet is sent when the value did not change). That way the number of bars on screen exactly equals the number of bosses;
the final wave has no separate wave bar anyway, so it does not get any more crowded. Switch `boss_bar.extra_boss_bars`.
When one boss dies it disappears from the list and the remaining ones automatically take its place; when all are dead it falls back to the wave bar.

### 3.2 Bosses with their own boss bar must not be hooked twice (`scgextra:wrecker_dozer` wrecker bulldozer)

Scanned the constant pools of every class in scgextra 3.1.3; only two reference `ServerBossEvent`:
`WaveRaidManager` (that wave bar) and `WreckerDozerEntity`. The latter has its own set, the same pattern as the vanilla wither:

```java
private final ServerBossEvent bossEvent = new ServerBossEvent(this.getDisplayName(), RED, PROGRESS);
public void tick() { bossEvent.setProgress(getHealth() / getMaxHealth()); }        // 每刻刷
public void startSeenByPlayer(ServerPlayer p) { bossEvent.addPlayer(p); }          // 玩家看到它就有条
public void stopSeenByPlayer(ServerPlayer p)  { bossEvent.removePlayer(p); }       // 看不到了就收
public void setCustomName(Component name)     { bossEvent.setName(getDisplayName()); }
```

So `wrecker_super` (final wave with 2 bulldozers), if we also took it over, would end up with "our bar + its own 2" = three duplicate bars.

**Fix**: bosses with their own boss bar are never taken over (`RaidBar.usesOwnBossBar`); the test = walk the entity class's inheritance chain looking for a field of type
`ServerBossEvent` (reflect once, cache by `Class`). Using field detection instead of a hardcoded entity list means:
swapping the datapack boss, or scgextra later giving another boss a boss bar, needs no code change.

- Mixed cases are covered too: with one self-barred and one not, only the one without its own bar uses our bar
- Risk: an entity that "declares a `ServerBossEvent` field but never displays it" would be misjudged as having its own boss bar and left unattended —
  in that case just turn off `boss_bar.detect_own_boss_bar`
- The actual look of the `wrecker_super` final wave: the wave bar (by health) + the bulldozers' own 2 bars. If three bars feel too crowded,
  the next step could add "hide that wave bar during the final wave"; not done for now

> Cost: in boss mode the title is overwritten once per tick, and scgextra, finding every tick that "the name on the bar ≠ the wave name", writes the wave name back,
> so there is one extra name packet per tick (very small, and the screen does not flicker, because within the same tick it writes first and we write after).
> Eliminating it completely would need another `@Redirect` on its `setName`, which would mean writing the SRG names of MC members inside the mixin —
> this project's convention is that only mod members appear in mixins and all MC calls live in helper classes, so no exception is made for now.

### Injection point verification (all done on the shipped jar)

- `javap -c` confirms `WaveRaidState.updateRaiders` starts with `raiders.entrySet()` → `invokedynamic(Predicate)`
  → `INVOKEINTERFACE java/util/Set.removeIf` (this is where `@Redirect` hooks in)
- After compiling, `javap -v` confirms: the `method` / `target` strings in the annotation are preserved verbatim;
  `raidplus$pruneRoster` is a non-static `(Set, Predicate)Z`; `@Shadow private ServerBossEvent bossBar` is in place
- refmap is still empty (all targets are mod members + the JDK's `Set`; no MC member needs remapping)

---

## 2026-09-18 (round two) · Clearing a wave does not advance it + the delay between waves

**User feedback**: ① all the mobs died, but the raid bar did not finish and it would not enter the next wave; ② as soon as a wave is cleared the next one spawns immediately, and the user wants the vanilla-raid kind of waiting progress.

**Status: both fixed (compiled + deployed, 47031 B / SHA-256 `C505D9999FDC2328…`), to verify in game.**

### 1. "Clearing a wave does not advance it" = the roster holds a "tombstone entity"

**Chain of evidence (verified from the decompiled sources of MC 1.20.1)**:

```java
// Entity：final，而且只在当前为 null 时才写入 —— 一旦标上就再也清不掉
public final void setRemoved(RemovalReason r) { if (this.removalReason == null) this.removalReason = r; ... }

// PersistentEntitySectionManager.m_157585_（区块卸载）：标记 + 摘掉 levelCallback，对象留在内存里当墓碑
private void m_157585_(EntityAccess e) {
   e.m_142467_(Entity.RemovalReason.UNLOADED_TO_CHUNK);   // setRemoved
   e.m_141960_(EntityInLevelCallback.f_156799_);
}

// PersistentEntitySectionManager.m_157582_（区块重新加载）：实体是**从存档新建的另一个对象**（同一 UUID）
private void m_157582_() {
   while ((entities = this.f_157500_.poll()) != null) {
      entities.m_156792_().forEach(e -> this.m_157538_(e, true));   // addEntity
   }
}
```

So the whole chain is: the mob was unloaded once → the roster holds the **old object** (permanently `UNLOADED_TO_CHUNK`, health frozen at the moment of unload)
→ the player kills the **new object** in the world → the roster is still non-empty → `raidersLeft()` is forever &gt; 0 → no wave advance;
that bar also keeps computing progress from the old object's health, so it looks like it "never finished".

Evidence from the logs: the gap between the two raid starts is ≈11 minutes (`17:24:37 → 17:35:30`),
exactly `RAID_TIMEOUT_TICKS = 12000` — meaning that run **got stuck until the timeout declared failure**.

**Fix** (`RaidRoster`):

- **Re-resolve every tick with `level.getEntity(uuid)`**, and when it resolves, swap in the object from the world (the key step;
  the previous version only resolved when the value was null, so the tombstone stayed on the roster forever)
- Change `isGone` to **check health first**: `getHealth() <= 0` counts as dead outright, regardless of whether it was marked `UNLOADED_TO_CHUNK`
- Keep it when it cannot be resolved (not loaded must not count as dead); but if "the chunk it was last in is loaded, yet it cannot be found in the world",
  it is only declared gone after 60 consecutive ticks of confirmation (avoiding the window where the chunk has just loaded and the entity is not yet in the lookup table for a tick or two)
- With `wave_debug=true` the log is thicker: besides the statistics it prints per mob
  `remaining: <mob name> health x/y, pos (…), N blocks from center, chunkLoaded=…` — so the next time it gets stuck, you can see at a glance where the one left over is

### 2. The wait between waves (the vanilla-raid kind of pacing)

scgextra hardcodes `NEXT_WAVE_DELAY = 30` (1.5 seconds). RaidPlus does not change its constant; instead, at the end of
`tickRaid` it **holds down** its private `nextWaveDelay`: when this wave is cleared, it is not the final wave, and our countdown has not yet arrived,
the value is written back to 30, so `nextWaveDelay-- < 0` never becomes true → the wave does not advance; when the countdown arrives we let go,
and scgextra advances by itself about 31 ticks later (those 31 ticks are counted inside the countdown, so the displayed seconds match up).

The countdown is drawn on that bar: `FAC Raid Wave 2 · next wave in 8s`, progress = remaining ratio.
The text goes through this mod's own lang (`assets/scgextra_raidplus/lang/{zh_cn,en_us}.json`,
new key `scgextra_raidplus.raid.next_wave`; this is also the first time a file exists under `assets/`).
Config: `wave_delay.wave_delay_enabled` / `wave_delay.wave_delay_ticks` (default **100 = 5 seconds**, cap 1200;
the first round of in-game testing used 200 = 10 seconds, and the logs show a 15-second empty-roster window after each of the 4 wave advances, which matches the config; the user then asked for it to be shortened to 5 seconds).

> This "hold down" depends on `@Shadow private int nextWaveDelay` (non-final, writable).
> If scgextra later changes its advance logic (no longer using this field), the injection will fail loudly because of `defaultRequire = 1`
> rather than silently doing nothing — the log will tell you the injection point needs updating.

---

## Other TODOs

- `Failed to load valid ResourcePackInfo` (missing `pack.mcmeta` in the jar): see "Known issues (not handled for now)" in `README.md`; the user asked to wait for the next round of feedback.
