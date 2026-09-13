# myscape-plugin

The RuneLite plugin half of myscape (an Old School RuneScape companion --
purchase-goal tracking, loot tracking, and more). The other half is
[Mootrius/myscape](https://github.com/Mootrius/myscape), the FastAPI +
PostgreSQL backend and website both this plugin and the browser talk to.

## What's here right now

Just account linking -- the prerequisite for everything else. Nothing
tracks purchases or loot yet; see "Next steps" below.

- `src/main/java/io/myscape/plugin/MyscapePlugin.java` -- the plugin
  entry point. Adds a sidebar icon/panel, and tracks the logged-in
  player's RuneLite `account_hash` and display name on the client thread
  so the panel can read them safely from Swing.
- `MyscapeConfig.java` -- just an `apiBaseUrl` setting (defaults to
  `https://myscape.io`; only worth changing to point at a local dev
  server). The link state itself (API key, player id, display name)
  is stored via `ConfigManager` directly rather than as a visible config
  field -- it's not something a player should ever hand-type or see
  echoed back in the settings panel.
- `MyscapeClient.java` -- calls the backend's two linking endpoints:
  `POST /api/link/exchange` (trade a link code for a long-lived API key)
  and `GET /api/players/me` (confirm a stored key is still valid). See
  `app/routers/linking.py` and `app/models.py` in the myscape repo for
  the full design rationale behind the two-phase link-code/API-key
  handoff.
- `MyscapePanel.java` -- the sidebar panel: paste the code shown by
  myscape.io's "Generate code" banner, link, and see "Linked as
  \<name\>" (with an Unlink button) afterward. Shows a "log into the
  game first" state if there's no account hash to link with yet.

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

Both are fixed now. What's *not* verified yet is the actual runtime
behavior -- linking against a real myscape.io account, in a real
RuneLite client. `./gradlew run` launches a local RuneLite client in
developer mode with this plugin side-loaded -- the fastest way to test
the link flow against the real API without going through the plugin
hub. Do that before trusting this beyond "it compiles."

## Next steps (not built yet)

- Purchase Goals: read bank/inventory/GE data and POST it to the
  backend once that data model/API exists there.
- Loot Tracker: subscribe to RuneLite's loot-received events (NPC kills,
  chests, etc.) and POST them to the backend once that data model/API
  exists there.
- Submitting to the RuneLite Plugin Hub, once there's more than linking
  to show -- see https://github.com/runelite/plugin-hub for its
  requirements (this plugin isn't in a submittable state yet: no unit
  tests, unverified build).
