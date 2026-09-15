# myscape-plugin

The RuneLite plugin half of myscape (an Old School RuneScape companion --
purchase-goal tracking, loot tracking, and more). The other half is
[Mootrius/myscape](https://github.com/Mootrius/myscape), the FastAPI +
PostgreSQL backend and website both this plugin and the browser talk to.

## What's here right now

Account linking, plus the character data collection built on top of it:
automatic bank, inventory, worn-equipment, Potion Storage, and Rune
Pouch syncing (what feeds the website's Purchase Goals feature), every
kill/clue-casket/pickpocket's loot as it happens (what feeds the Loot
Tracker), and current skill levels/xp, completed quests, and achievement
diary tiers (what feeds Mindmaps' skill/quest/diary goal nodes -- see
"Skills, quests, and achievement diaries sync" below; Mindmaps' fourth
goal type, kill count, needs nothing further here at all -- it's checked
against the same synced loot history the Loot Tracker already collects).

- `src/main/java/io/myscape/plugin/MyscapePlugin.java` -- the plugin
  entry point. Adds a sidebar icon/panel, tracks the logged-in player's
  RuneLite `account_hash` and display name on the client thread so the
  panel can read them safely from Swing, and (see "Bank, inventory, and
  equipment sync" below) subscribes to all three containers' content
  changes and debounces them into synced snapshots behind one shared
  timer.
- `MyscapeConfig.java` -- just an `apiBaseUrl` setting (defaults to
  `https://myscape.io`; only worth changing to point at a local dev
  server). The link state itself (API key, player id, display name)
  is stored via `ConfigManager` directly rather than as a visible config
  field -- it's not something a player should ever hand-type or see
  echoed back in the settings panel.
- `MyscapeClient.java` -- calls the backend's linking endpoints
  (`POST /api/link/exchange` to trade a link code for a long-lived API
  key, `GET /api/players/me` to confirm a stored key is still valid) and
  `syncBank()`/`syncInventory()`/`syncEquipment()` (`POST
  /api/players/me/bank`/`.../inventory`/`.../equipment`, see "Bank,
  inventory, and equipment sync" below). See `app/routers/linking.py`
  and `app/models.py` in the myscape repo for the full design rationale
  behind the two-phase link-code/API-key handoff.
- `MyscapePanel.java` -- the sidebar panel: paste the code shown by
  myscape.io's "Generate code" banner, link, and see "Linked as
  \<name\>" (with an Unlink button) afterward, plus a small status line
  reflecting the most recent sync of each container (e.g. "Synced: 24
  bank, 28 inventory, 11 equipment"). Shows a "log into the game first"
  state if there's no account hash to link with yet.

## How linking works, end to end

1. On myscape.io, a signed-in player clicks "Generate code" (the
   site-wide banner shown until they've linked) and gets a short-lived,
   single-use code.
2. They paste that code into this plugin's side panel and click "Link
   account".
3. The plugin calls `POST /api/link/exchange` with the code, the
   account's stable `account_hash` (from `Client#getAccountHash()`, not
   the mutable display name), and the current display name.
4. The server verifies the code, binds the game account to the player,
   and returns a long-lived API key. The plugin stores it via
   `ConfigManager` and sends it as `Authorization: Bearer <key>` on
   every future request -- `GET /api/players/me` on startup, to confirm
   the stored key is still valid before showing "Linked".

## Bank, inventory, and equipment sync

Once linked, the plugin subscribes to `ItemContainerChanged` and
branches on which container changed (`net.runelite.api.gameval
.InventoryID.BANK`/`.INV`/`.WORN` -- the non-deprecated replacements for
the older `net.runelite.api.InventoryID` enum values, confirmed via
`javap`). Each container is read on the client thread the moment it
changes (`onItemContainerChanged` in `MyscapePlugin.java`) -- bank and
inventory contents are aggregated by item id first (a container can
report the same item split across multiple stack slots), while
equipment is read slot-by-slot instead, since a worn item's *slot* is
what matters, not a quantity total. Inventory and equipment are also
read proactively at login (`onGameTick`'s `justBecameAvailable` branch),
since unlike the bank they're always populated and don't need an
interface opened first to report anything.

Every item read this way also gets its display name resolved via
`ItemManager#getItemComposition(int).getName()` (`resolveItemName`,
client-thread-only) alongside its id -- needed because an untradeable
item has no entry in the OSRS Wiki's price mapping the website's
Purchase Goals feature prices tradeable items against, so the *only*
way it can ever be recognized as "already owned" is by name instead.
Sent for every item regardless of tradeability, since there's no cheap
way to know ahead of time which items are which.

All three containers' changes are debounced behind one shared 2-second
timer rather than three independent ones (`scheduleSyncDebounced` /
`SYNC_DEBOUNCE_SECONDS`) -- a player actively shopping (withdrawing GP
and gear from the bank, moving it into inventory, equipping it) tends to
touch more than one container in a single burst, so one shared debounce
collapses that whole burst into as few requests as possible, the same
way the bank's own debounce did before this. RuneLite's `Client` and its
`ItemContainer`s may only safely be read from the client thread, so each
container's contents are captured into plain-data snapshots
synchronously on that thread and stashed in `volatile` fields; the
debounce timer itself runs on a background executor and only ever reads
those already-converted snapshots, never touching `Client` directly.

When the timer fires, `MyscapePanel#syncInBackground` calls whichever of
`MyscapeClient#syncBank`/`syncInventory`/`syncEquipment` have fresh data,
each POSTing to its own endpoint (`.../bank`, `.../inventory`,
`.../equipment`, all API-key authenticated). Every one is always a full
replace, never a merge -- the backend deletes and re-inserts, so an item
sold, withdrawn, or unequipped since the last sync disappears
server-side too, matching what actually happened in the game. See
"Bank, inventory, and equipment sync" in the myscape repo's README for
the backend side of this.

## Skills, quests, and achievement diaries sync

Once linked, three more things sync alongside bank/inventory/equipment,
each feeding a Mindmaps goal type (`app/mindmaps.py` in the myscape
repo):

- **Skills** (green goals) -- every `net.runelite.api.Skill` value's
  real (unboosted) level and xp, read in one pass (`readSkills`) rather
  than hardcoding the 24 real OSRS skills separately here, so a skill
  this client's RuneLite build doesn't yet know about is simply never
  reported, and one it's since added (Sailing) is picked up
  automatically. Resynced whenever `StatChanged` fires for any skill.
- **Completed quests** (blue goals) -- every `net.runelite.api.Quest`
  (RuneLite's own full quest-and-miniquest list) this account has
  finished, by RuneLite's own name for it (`readCompletedQuestNames`).
  There's no single varp/varbit that changes for "a quest finished" the
  way there is for the sync sources above, so this resyncs off the one
  reliable generic signal instead: the "quest complete" scroll interface
  loading (`InterfaceID.QUESTSCROLL`, via `onWidgetLoaded`).
- **Achievement diary tiers** (purple goals) -- the highest completed
  tier per diary area, from RuneLite's own per-area-per-tier "complete"
  varbits (`readDiaryTiers`, `DIARY_TIER_VARBITS`) -- confirmed against
  both RuneLite's current gameval `VarbitID` constants and the older,
  manually-maintained `net.runelite.api.Varbits` class (which agree on
  every id), plus the OSRS Wiki's own varbit documentation, rather than
  guessed. Resynced whenever one of those 48 varbits itself changes.

All three are also read proactively at login (`onGameTick`'s
`justBecameAvailable` branch), same as inventory/equipment/Rune Pouch --
a player's existing levels, completed quests, and diary tiers are
already real progress the moment login finishes, not something that
needs to change again first to be worth reporting. And, same as bank/
inventory/equipment/Potion Storage/Rune Pouch, all three go through the
one shared 2-second debounce (`scheduleSyncDebounced`) rather than
syncing on every single individual change.

## Building this

`./gradlew build` compiles clean (zero errors, zero warnings) against
the real `net.runelite:client` (1.12.38, `latest.release` at the time
this was verified) -- this was actually run, not just written to look
right. The wrapper (`gradlew`/`gradlew.bat`/`gradle/wrapper/*`) is
committed, so no local Gradle install is needed; it downloads Gradle
8.14.3 on first run.

That first real build did catch two genuine bugs the code hadn't been
checked against before:

- `CardLayout` is in `java.awt`, not `javax.swing`.
- RuneLite's bundled OkHttp expects
  `RequestBody.create(MediaType, String)`, not the newer
  `(String, MediaType)` overload order.

Both are fixed now. `./gradlew run` launches a local RuneLite client in
developer mode with this plugin side-loaded -- the fastest way to test
against the real API without going through the plugin hub. Run in a
headless sandbox (no display), it gets as far as RuneLite's own startup
UI before hitting `java.awt.HeadlessException` trying to open its
window -- expected there and not a plugin issue, since nothing about
loading *this* plugin ever runs before RuneLite's own UI does. One
`ERROR injected-client - Client error` line also appears during that
run; that's RuneLite's injected-client test harness logging its own
expected failure mode in a headless environment, not this plugin's
code -- confirmed by checking what actually logs it, and unchanged
across every verification pass since. Compiling clean and reaching that
same, already-understood wall (with no *new* errors) after each round of
changes -- most recently the inventory/equipment sync above -- is the
bar this has been held to in a sandbox without a real game client to
link against; actually linking against a real myscape.io account in a
real RuneLite client, with a display, is still the real end-to-end test
and hasn't been done.

The skill/quest/diary sync code above was verified the same real way --
`compileJava` against the actual `net.runelite:client` jar (1.12.38),
not just written to look right -- though as a `compileJava`-only check
in a separate build environment, not a full `./gradlew build` run
through the wrapper the way the paragraph above describes; a full
`./gradlew build`/`./gradlew run` pass covering this round's changes
specifically is still worth doing before calling this end-to-end
verified.

## Next steps (not built yet)

- ~~Purchase Goals: bank reading/syncing is done; inventory and GE data
  aren't collected yet, and the website side still doesn't display any
  of it~~ done -- bank, inventory, and equipment are all synced (see
  "Bank, inventory, and equipment sync" above) and the website's
  Purchase Goals page reads all three live, at
  `/dashboard/purchase-goals` in the myscape repo.
- ~~Loot Tracker: subscribe to RuneLite's loot-received events (NPC
  kills, chests, etc.) and POST them to the backend once that data
  model/API exists there~~ done -- see `onLootReceived` in
  `MyscapePlugin.java`.
- ~~Mindmaps' kc/skill/quest/diary goal types: nothing synced them
  yet~~ done -- see "Skills, quests, and achievement diaries sync"
  above (kc needed no new sync at all; it reads the same loot history
  the line above already covers).
- Player-Owned-House Seed Vault: the backend already has a sync endpoint
  for it (`POST /api/players/me/seed-vault`, mirroring Potion Storage/
  Rune Pouch), but nothing in this plugin reads or sends it yet.
- Submitting to the RuneLite Plugin Hub, once there's more than linking
  to show -- see https://github.com/runelite/plugin-hub for its
  requirements (this plugin isn't in a submittable state yet: no unit
  tests, unverified build).
