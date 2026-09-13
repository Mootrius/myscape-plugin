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

**This has not been compiled or run yet.** It was written in an
environment where outbound access to `repo.runelite.net`,
`repo1.maven.org`, and `plugins.gradle.org` was blocked by network
policy, so I couldn't resolve the `net.runelite:client` dependency or
generate a proper Gradle wrapper to verify any of this actually builds.
The code follows RuneLite's plugin API as I know it (the plugin-hub
`example-plugin` template's shape, `Plugin`/`@PluginDescriptor`,
`ConfigManager`, `ClientToolbar`/`NavigationButton`, OkHttp's async
`enqueue`), but there could be a signature or two that's drifted since
my training data. Please build it and fix whatever the compiler flags
before relying on it.

To build:

1. This repo has no `gradlew` yet (same network restriction above blocked
   generating one). Either:
   - Open the folder in IntelliJ IDEA with the Gradle plugin -- it can
     import and build with its own bundled Gradle, no wrapper needed, or
   - Run `gradle wrapper --gradle-version 7.6.4` yourself once (needs a
     local Gradle install, e.g. via Homebrew: `brew install gradle`) to
     generate `gradlew`/`gradlew.bat`/`gradle/wrapper/*`, then commit
     those so future clones don't need Gradle preinstalled.
2. `./gradlew build` (or IntelliJ's Build) to compile and resolve
   dependencies from Maven Central / `repo.runelite.net`.
3. `./gradlew run` launches a local RuneLite client in developer mode
   with this plugin side-loaded -- the fastest way to test the link flow
   against the real myscape.io API without going through the plugin hub.

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
