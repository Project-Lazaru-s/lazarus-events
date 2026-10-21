# Prompt para o Codex: T4 (queda de helicóptero)

**Como usar:** rode o Codex com a pasta `cor/work` como raiz, para ele enxergar os dois repositórios (o evento fica no `lazarus_events`, os GameTests no `lazarus_npcs`). Os dois estão na branch `KaiD3v/T3-T4`. Exemplo (PowerShell):

```powershell
cd C:\Users\kaikr\Documents\Codex\2026-10-13\cor\work
codex exec -s workspace-write -C . "Read lazarus_events/docs/PROMPT_CODEX_T4.md (section 'Prompt') and lazarus_events/docs/PLANO_T4_QUEDA_HELICOPTERO.md, then implement T4."
```

Ou cole o bloco abaixo numa sessão interativa do Codex aberta em `cor/work`.

---

## Prompt

You are implementing phase **T4 — helicopter crash** of the *Lazarus Events* Minecraft mod (Forge 1.20.1, official
mappings, Java 17), part of the **Project Lazarus** zombie-apocalypse modpack. Read
`lazarus_events/docs/PLANO_T4_QUEDA_HELICOPTERO.md` (the design, in Portuguese) before writing code; this prompt gives
you the project context and the rules.

### The project
- `lazarus_events/` — the mod you change. Encounters placed in the world while players explore: camps, roadblocks,
  rescues, military checkpoints, hostile hideouts, zombie nests, infection gas zones, a military quarantine, a sewer
  smuggler den, furnished "lived-in" hideouts. Package `com.lazarus.events`, classes package-private.
  Key files: `LazarusEvents.java` (registry of types, config, draw weights, `attempt`/`place` flow, radio, `onTick`),
  `EventData.java` (saved encounters; `Encounter.data` is a free `CompoundTag`), `EncounterScenery.java` (NBT templates),
  `RescueEncounter.java` (wounded survivor + escort task — the model for the pilot), `MilitaryCollapse.java`
  (`equip(...)` for uniformed zombies), `ZombieNest.java`/`InfestedZone.java` (periodic spawning out of sight, caps),
  `Quarantine.java` (a scene built block by block with a wall-relative frame), `HideoutKits.java` (furniture kits from
  `data/lazarusevents/kits`), `ApiSupport.java` (`stack`, `state` with fallback, `actors`, `LOGGER`).
- `lazarus_npcs/` — the companion NPC mod. It exposes the public API `com.lazarus.npcs.api.NpcApi` (identity,
  reputation, scenario orders, dialogues, encounter tasks) that Events uses. **All GameTests live here**, in
  `src/test/java/com/lazarus/npcs/EventsScenarioGameTests.java`; they reach Events' package-private classes by
  reflection through the helper `call(type, name, types, args)` (see the existing smuggler den and quarantine tests).
- Other mods in the pack you can reference by id (always with a vanilla fallback via `ApiSupport.state`/`stack`):
  Apocalypse Now (`apocalypsenow:*` military crates, barrels, sandbags, military NPCs `military_01/02`, gas masks),
  Dyairdrop (loot tables `dyairdrop:chests/weaponairdropN`, `largeairdropN`, `medicalairdropN`, `smallairdropN`, N = tier
  1..5), Immersive Weathering (charred/ash blocks), Lazarus NPCs. A list of modded block ids and properties is in
  `lazarus_events/build/kit_reference/modded_blocks.txt` if present.

### What to build
A new encounter type `heli_crash` as specified in the plan: crashed military helicopter built in code (4 rotations),
contained fire and tall signal-campfire smoke visible from far away, wreck debris, two military loot crates, 3-4
uniformed infected, a 40% wounded pilot rescue (escort task like `RescueEncounter`, reward +3 Military), a horde drawn
by the smoke over time with a cap, fire then smoke going out over time, radio "Mayday" line, config
(`enableHeliCrashes`, `heliCrashFromDay` = 15), draw weight 1, test command support
(`/lazarusevents test heli_crash near|natural`), README section, and GameTests.

### Rules and conventions
- Match the surrounding code: concise Javadoc on classes and non-obvious methods, package-private, no new libraries,
  no abstractions for one use. Read `Quarantine.java` and `SmugglerDen.java` first for style.
- Register the type everywhere a type is listed in `LazarusEvents` (`TYPES`, weights, placement, `label`, `radioLine`,
  config, `onTick` hook). Natural placement needs open sky and flat ground 60–120 blocks away; the `near` test mode
  uses 14–34 blocks.
- Fire must never spread or burn terrain: fire only on non-flammable blocks hidden in the wreck, nothing flammable next
  to it. Smoke: `minecraft:campfire[signal_fire=true]` on a hay bale.
- All spawned actors through `LazarusEvents.spawn(..., encounterId, persistent)` so they carry the encounter tag.
- Reputation through `NpcApi.adjustReputation(player, faction, delta, reason, encounterId)`: it deduplicates by
  reason + encounter, so use a distinct reason id per reward.
- Forge event listeners: two `@SubscribeEvent` methods with the same name and event type in different `Listener` classes
  collide; give new handlers distinct names.
- Bump the Events version to `0.24.0` in `lazarus_events/build.gradle` **and** in
  `lazarus_events/src/main/resources/META-INF/mods.toml` (the toml version is hard-coded), and update
  `lazarus_npcs/build.gradle` (`runtimeOnly fg.deobf("blank:lazarusevents:0.24.0")`).
- Update `lazarus_events/docs/PENDENCIAS_E_TASKS.md` (T4 section and status table) and the README (event table,
  weights, config keys, test command types list).

### GameTest environment quirks (important)
- The test arena is dug out underground: `canSeeSky` is false everywhere and the heightmap is far above. So test
  `HeliCrash.place(...)` directly on flat arena ground, not through the natural finder.
- Fresh fake players are invulnerable for ~60 ticks; delay damage checks.
- Reflection on Events classes needs `setAccessible(true)`, also for record accessors.
- Do not call `onEachTick` inside `runAfterDelay`. NPC AI needs a player within 64 blocks.
- Known flaky tests (not your concern): `surrenderedNpcStopsFightingAndDropsNothing`, `outpostHoldsGatesAndPaysThePerimeter`,
  `followOrderReachesThePlayer`, `escortDefendsOwner`, `escortFollowsWithoutCrowding`, rarely `smugglerDenGatesTheTunnelAndPaysWhenCleared`.

### Build and test (Windows, offline)
```powershell
$env:JAVA_HOME = "C:\Users\kaikr\Documents\Codex\2026-10-13\cor\work\jdk17\jdk-17.0.20.1+1"
$env:GRADLE_USER_HOME = "C:\Users\kaikr\Documents\Codex\2026-10-13\cor\work\lazarus_moodles\.gradle-user"
cd C:\Users\kaikr\Documents\Codex\2026-10-13\cor\work\lazarus_npcs;   .\gradlew.bat build --offline
Copy-Item build\libs\lazarusnpcs-1.16.0.jar ..\lazarus_events\libs\ -Force
cd ..\lazarus_events;  .\gradlew.bat build --offline
Copy-Item build\libs\lazarusevents-0.24.0.jar ..\lazarus_npcs\libs\ -Force
cd ..\lazarus_npcs;    .\gradlew.bat runGameTestServer --offline   # look for "All N required tests passed"
```
Events compiles against `lazarus_events/libs/lazarusnpcs-1.16.0.jar`; the NPCs GameTests load
`lazarus_npcs/libs/lazarusevents-<version>.jar` at runtime, so copy the new Events jar before running them.

### Do not
- Do not commit, push, switch branches or touch `main`.
- Do not copy jars into any Minecraft profile (`C:\Users\kaikr\curseforge\...`): installing is done by hand.
- Do not change unrelated encounters, kits or tests.

### Done when
The plan's acceptance criteria hold, the new GameTests pass along with the existing suite (except the known flaky
ones), and you report: files changed, how the wreck looks (a short block-by-block description per layer), the
GameTest result line, and anything you could not verify without the game client.
