package io.myscape.plugin;

import com.google.inject.Provides;
import io.myscape.plugin.collectionlog.CollectionLogItemCapture;
import io.myscape.plugin.modelexport.ModelExporter;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.function.UnaryOperator;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.ChatMessageType;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Model;
import net.runelite.api.Player;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.TextureProvider;
import net.runelite.api.events.ChatMessage;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.ScriptPostFired;
import net.runelite.api.events.ScriptPreFired;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.gameval.VarPlayerID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.callback.ClientThread;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.events.ConfigChanged;
import net.runelite.client.game.ItemManager;
import net.runelite.client.game.ItemStack;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
import net.runelite.client.plugins.loottracker.LootReceived;
import net.runelite.client.ui.ClientToolbar;
import net.runelite.client.ui.NavigationButton;
import net.runelite.client.util.ImageUtil;
import okhttp3.OkHttpClient;

/**
 * Links this RuneLite client to a myscape.io account (the two-phase
 * handoff described in myscape's backend -- app/models.py /
 * app/routers/linking.py in the Mootrius/myscape repo: the player
 * generates a short-lived code on the website and pastes it into this
 * plugin's side panel, which exchanges it for a long-lived API key),
 * and, once linked, keeps the website's copy of the player's bank,
 * inventory, worn equipment, Rune Pouch, Potion Storage, and Looting Bag
 * in sync -- the data collection the Purchase Goals and Possessions
 * Monitoring features are built on (see
 * onItemContainerChanged, onGameTick, and onVarbitChanged below) -- and
 * reports every kill/clue-casket/pickpocket's loot as it happens (see
 * onLootReceived below), which the Loot Tracker feature is built on.
 *
 * Also keeps the player's current skill levels/xp, completed quests,
 * and achievement diary tiers in sync (see readSkills/
 * readCompletedQuestNames/readDiaryTiers, onStatChanged, onWidgetLoaded,
 * and onVarbitChanged below) -- what the Mindmaps feature's skill/quest/
 * diary goal nodes check themselves against server-side (see
 * evaluate_completion in the Mootrius/myscape repo's app/mindmaps.py).
 * Mindmaps' fourth goal type, kc (kill count), needs no plugin work of
 * its own here at all: it's checked against this same player's synced
 * LootEvent history (see onLootReceived below and kc_counts_by_player in
 * the Mootrius/myscape repo's app/loot.py), which was already being
 * captured for the Loot Tracker feature before Mindmaps existed.
 *
 * Also keeps Character Overview's seven-stat summary row live -- combat
 * level, total level, total XP, quests/achievement-diary-tiers/combat-
 * achievements/collection-log completed (see MyscapeClient.
 * CharacterSummaryItem's own doc for the full breakdown of which of the
 * seven have a native RuneLite signal of their own vs. which still rely
 * on readCharacterSummary/patchCharacterSummary below).
 */
@Slf4j
@PluginDescriptor(
	name = "myscape",
	description = "Sends in-game data to myscape.io for use with its various apps",
	tags = {"myscape", "loot", "tracker", "purchase", "goals", "ge", "grand exchange"}
)
public class MyscapePlugin extends Plugin
{
	@Inject
	private Client client;

	@Inject
	private ClientThread clientThread;

	@Inject
	private ClientToolbar clientToolbar;

	@Inject
	private ConfigManager configManager;

	@Inject
	private OkHttpClient okHttpClient;

	@Inject
	private MyscapeConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	// The website no longer has its own "Unlink" button (that action now
	// lives only in this plugin's sidebar), so there's no longer a way
	// for this client to be unlinked without being told directly, and
	// the periodic re-check that used to watch for exactly that is gone
	// too (see startUp() below). What's left of this poll is purely
	// cosmetic freshness: it keeps the Status section's per-storage rows
	// honest even for a storage nothing in this session has triggered a
	// resync of (see MyscapePanel#refreshSyncStatusInBackground), on a
	// cadence loose enough to stay cheap for every connected client
	// regardless of whether anyone's even looking at the panel.
	private static final int SYNC_STATUS_POLL_SECONDS = 30;

	// A container's ItemContainer fires a change event for every single
	// deposit/withdraw/equip, not just once the player's done -- syncing
	// on every one of those would mean several requests a second while
	// someone's actively banking or gearing up. Waiting for this many
	// quiet seconds after the *last* change across bank/inventory/
	// equipment (see onItemContainerChanged below, which reschedules
	// this shared debounce on every relevant event rather than letting
	// an earlier one fire) collapses a whole banking/gearing session
	// into one sync per container, sent once things settle.
	private static final int SYNC_DEBOUNCE_SECONDS = 2;

	// The plain "quiet seconds" debounce above never fires at all while
	// changes keep landing back-to-back -- which is exactly what
	// happens during sustained activity with no natural pause (killing
	// monsters continuously, thieving stalls, high alching), since each
	// new change cancels and reschedules the same task before it ever
	// gets to run. That's what made Storages Viewer look like it
	// barely updates: the inventory genuinely was changing constantly,
	// so the debounce kept getting pushed back instead of collapsing a
	// short burst the way it's meant to. Capping how long a single
	// burst can keep deferring the sync bounds the staleness even while
	// the burst is still going, without giving up the original
	// quiet-period collapsing for the common short-burst case (banking,
	// gearing up).
	private static final int SYNC_MAX_WAIT_SECONDS = 10;

	// The equipment container's items are indexed by slot (see
	// EquipmentInventorySlot#getSlotIdx), not aggregated by item id the
	// way bank/inventory are -- this maps each slot index this plugin
	// cares about to the name Maintrius's own gear-setup feature already
	// uses for it (see EQUIPMENT_SLOTS in the Mootrius/myscape repo's
	// original osrs_tracker.py), so the server and the eventual Purchase
	// Goals UI don't need to know anything about RuneLite's own slot
	// numbering. EquipmentInventorySlot also defines ARMS/HAIR/JAW
	// (indices 6, 8, 11) -- unused cosmetic-override slots that never
	// hold a real item in standard OSRS -- deliberately left out here,
	// same as Maintrius's own 11-slot list.
	private static final Map<Integer, String> EQUIPMENT_SLOT_NAMES = buildEquipmentSlotNames();

	private static Map<Integer, String> buildEquipmentSlotNames()
	{
		Map<Integer, String> slots = new LinkedHashMap<>();
		slots.put(EquipmentInventorySlot.HEAD.getSlotIdx(), "head");
		slots.put(EquipmentInventorySlot.CAPE.getSlotIdx(), "cape");
		slots.put(EquipmentInventorySlot.AMULET.getSlotIdx(), "neck");
		slots.put(EquipmentInventorySlot.WEAPON.getSlotIdx(), "weapon");
		slots.put(EquipmentInventorySlot.BODY.getSlotIdx(), "body");
		slots.put(EquipmentInventorySlot.SHIELD.getSlotIdx(), "shield");
		slots.put(EquipmentInventorySlot.LEGS.getSlotIdx(), "legs");
		slots.put(EquipmentInventorySlot.GLOVES.getSlotIdx(), "hands");
		slots.put(EquipmentInventorySlot.BOOTS.getSlotIdx(), "boots");
		slots.put(EquipmentInventorySlot.RING.getSlotIdx(), "ring");
		slots.put(EquipmentInventorySlot.AMMO.getSlotIdx(), "ammo");
		return Collections.unmodifiableMap(slots);
	}

	// Player-Owned-House Potion Storage isn't an ItemContainer the way
	// bank/inventory/equipment are -- RuneLite (and the client itself)
	// expose it through the same enum/script plumbing the built-in Bank
	// Tags plugin's own Potion Storage tab uses (see
	// net.runelite.client.plugins.banktags.tabs.PotionStorage, bundled
	// with RuneLite itself): two top-level enums whose values are each
	// one potion type's own enum id, and a client script that returns
	// how many total *doses* of that potion type are currently stored
	// (not a count of physical items -- Potion Storage tracks doses the
	// way a bank note tracks value, so 10 stored doses of Prayer potion
	// might have come from any mix of partially- and fully-doses
	// potions ever deposited). These ids are read directly off
	// RuneLite's actual shipped bytecode for that plugin, not guessed.
	private static final int[] POTION_STORAGE_ENUM_IDS = {4826, 4829};
	private static final int POTION_STORAGE_TOTAL_DOSES_SCRIPT_ID = 3750;
	// A potion's per-type enum carries the real item id for each dose
	// count from 1 to 4 (getIntValue(1)..getIntValue(4)) -- index 0 is
	// something else entirely (not a dose variant) and is never used
	// here. 4 is also the largest dose any potion in Potion Storage
	// actually goes up to.
	private static final int MAX_POTION_DOSES = 4;
	// The specific varp ids the Bank Tags plugin's own Potion Storage
	// tab treats as "something in Potion Storage changed, re-read it" --
	// used here as the trigger to recompute+resync this plugin's own
	// snapshot, the same way a bank/inventory/equipment
	// ItemContainerChanged event triggers a resync for those.
	private static final int POTION_STORAGE_VARP_MIN = 4272;
	private static final int POTION_STORAGE_VARP_MAX = 4277;

	// RuneLite's OWN Bank Tags Potion Storage tab doesn't actually stop
	// at that fixed six-varp range either -- alongside those base vars
	// it also watches a SET of additional var ids it reads fresh off
	// the Potion Storage widget itself every client tick
	// (w.getVarTransmitTrigger(), in its own PotionStorage.java), which
	// is how potions added to the feature after its original six-var
	// design (Divine potions among them) end up covered too -- their
	// dose counts apparently live outside POTION_STORAGE_VARP_MIN..MAX,
	// so depositing/withdrawing them alone never sets
	// potionStorageDirty and this plugin's own snapshot for them goes
	// stale until some unrelated (base-var) potion change happens to
	// force a full re-read. There's no ItemContainer or other widget
	// this plugin can reliably pull that same dynamic list from outside
	// of the Bank Tags UI actually being open, so rather than guess at
	// (and eventually go stale against again) more hardcoded ids, this
	// falls back to periodically re-reading Potion Storage outright --
	// see ticksSincePotionStorageRefresh below -- so a dose change on a
	// var this plugin doesn't know to watch still shows up within a
	// bounded time instead of sitting stale indefinitely.
	private static final int POTION_STORAGE_REFRESH_INTERVAL_TICKS = 50;

	// A Rune Pouch (regular or Divine, including their League/seasonal
	// "Trouver parcel" cosmetic reclaims -- BH_RUNE_POUCH_TROUVER and
	// DIVINE_RUNE_POUCH_TROUVER) is a single inventory item, not an
	// ItemContainer -- RuneLite exposes what's actually stored inside
	// one the same way it exposes Potion Storage above: not through a
	// container-changed event, but through a fixed set of varbits (one
	// pair -- "type" and "quantity" -- per pouch slot) read directly.
	// This mirrors exactly how RuneLite's own bundled "Rune Pouch"
	// overlay plugin reads the pouch it draws on top of (see
	// net.runelite.client.plugins.runepouch.RunepouchOverlay, bundled
	// with RuneLite itself) -- these are its actual named constants,
	// not guessed. A regular Rune Pouch only ever fills 3 of these slots
	// and a Divine Rune Pouch 4, but all 6 are read defensively so this
	// never silently drops runes if Jagex ever ships a bigger pouch.
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	private static final int[] RUNE_POUCH_QUANTITY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};
	// Each "type" varbit above is a small 1-based index into this enum,
	// not a real item id -- 0 means that slot is empty. getIntValue(type)
	// resolves it to the actual rune item id, same as RunepouchOverlay.
	private static final int RUNE_POUCH_RUNE_ENUM_ID = EnumID.RUNEPOUCH_RUNE;
	// Which physical items are themselves a Rune Pouch -- used to check
	// the player still actually owns one before trusting these varbits
	// (unlike Potion Storage, which has no equivalent "do they still own
	// the tab" question). See readRunePouchItems's doc for why this
	// check exists.
	private static final int[] RUNE_POUCH_ITEM_IDS = {
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER,
	};

	// Achievement diaries -- unlike bank/inventory/potion storage/rune
	// pouch above, there's no ItemContainer-style "contents" to read at
	// all here: each area+tier just has its own boolean "have every task
	// in this tier been done" varbit, one of 48 (12 areas x 4 tiers).
	// These are RuneLite's own named gameval VarbitID constants (e.g.
	// VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE), not raw ids guessed at --
	// see e.g. the OSRS Wiki's RuneScape:Varbit/4478 page for
	// KANDARIN_DIARY_ELITE_COMPLETE's own documented meaning ("Set to 1
	// if you've completed all the tasks for the Elite Kandarin Diary").
	// Keyed by the same lowercase/slugified area name DIARY_AREAS uses
	// server-side (app/mindmaps.py in the Mootrius/myscape repo) --
	// RuneLite's own area names don't all match that 1:1 (its "KOUREND"/
	// "WESTERN"/"LUMBRIDGE" are this app's "kourend_kebos"/
	// "western_provinces"/"lumbridge_draynor"), so this table is also
	// what bridges that naming gap; each area's 4-element array is
	// ordered low-to-high, matching DIARY_TIERS server-side (and
	// DIARY_TIER_NAMES below). Karamja's easy/medium/hard varbits are
	// gameval-named ATJUN_*_DONE rather than KARAMJA_DIARY_*_COMPLETE --
	// a quirk of Jagex's own internal naming for that one area, not a
	// mismatch on this end (confirmed against the same numeric ids the
	// older, manually-maintained net.runelite.api.Varbits class uses for
	// DIARY_KARAMJA_EASY/MEDIUM/HARD). Those same three ALSO differ from
	// every other area's tier varbit (and from Karamja's own elite
	// varbit) in what "complete" actually means: every other tier
	// varbit here is a plain 0/1 flag, but ATJUN_EASY_DONE/MED_DONE/
	// HARD_DONE are documented (OSRS Wiki, RuneScape:Varbit/3578 et al)
	// as a three-state counter -- 0 not started, 1 started, 2 complete
	// -- left over from Karamja Diary predating the unified Achievement
	// Diary system every other area uses. DIARY_TIER_COMPLETE_VALUES
	// below is what readDiaryTiers checks each tier varbit against
	// instead of a hardcoded 1, precisely so this one area's quirk
	// doesn't silently read as "never completes."
	private static final String[] DIARY_TIER_NAMES = {"easy", "medium", "hard", "elite"};
	private static final Map<String, int[]> DIARY_TIER_VARBITS = buildDiaryTierVarbits();

	private static Map<String, int[]> buildDiaryTierVarbits()
	{
		Map<String, int[]> areas = new LinkedHashMap<>();
		areas.put("ardougne", new int[]{
			VarbitID.ARDOUGNE_DIARY_EASY_COMPLETE, VarbitID.ARDOUGNE_DIARY_MEDIUM_COMPLETE,
			VarbitID.ARDOUGNE_DIARY_HARD_COMPLETE, VarbitID.ARDOUGNE_DIARY_ELITE_COMPLETE,
		});
		areas.put("desert", new int[]{
			VarbitID.DESERT_DIARY_EASY_COMPLETE, VarbitID.DESERT_DIARY_MEDIUM_COMPLETE,
			VarbitID.DESERT_DIARY_HARD_COMPLETE, VarbitID.DESERT_DIARY_ELITE_COMPLETE,
		});
		areas.put("falador", new int[]{
			VarbitID.FALADOR_DIARY_EASY_COMPLETE, VarbitID.FALADOR_DIARY_MEDIUM_COMPLETE,
			VarbitID.FALADOR_DIARY_HARD_COMPLETE, VarbitID.FALADOR_DIARY_ELITE_COMPLETE,
		});
		areas.put("fremennik", new int[]{
			VarbitID.FREMENNIK_DIARY_EASY_COMPLETE, VarbitID.FREMENNIK_DIARY_MEDIUM_COMPLETE,
			VarbitID.FREMENNIK_DIARY_HARD_COMPLETE, VarbitID.FREMENNIK_DIARY_ELITE_COMPLETE,
		});
		areas.put("kandarin", new int[]{
			VarbitID.KANDARIN_DIARY_EASY_COMPLETE, VarbitID.KANDARIN_DIARY_MEDIUM_COMPLETE,
			VarbitID.KANDARIN_DIARY_HARD_COMPLETE, VarbitID.KANDARIN_DIARY_ELITE_COMPLETE,
		});
		areas.put("karamja", new int[]{
			VarbitID.ATJUN_EASY_DONE, VarbitID.ATJUN_MED_DONE,
			VarbitID.ATJUN_HARD_DONE, VarbitID.KARAMJA_DIARY_ELITE_COMPLETE,
		});
		areas.put("kourend_kebos", new int[]{
			VarbitID.KOUREND_DIARY_EASY_COMPLETE, VarbitID.KOUREND_DIARY_MEDIUM_COMPLETE,
			VarbitID.KOUREND_DIARY_HARD_COMPLETE, VarbitID.KOUREND_DIARY_ELITE_COMPLETE,
		});
		areas.put("lumbridge_draynor", new int[]{
			VarbitID.LUMBRIDGE_DIARY_EASY_COMPLETE, VarbitID.LUMBRIDGE_DIARY_MEDIUM_COMPLETE,
			VarbitID.LUMBRIDGE_DIARY_HARD_COMPLETE, VarbitID.LUMBRIDGE_DIARY_ELITE_COMPLETE,
		});
		areas.put("morytania", new int[]{
			VarbitID.MORYTANIA_DIARY_EASY_COMPLETE, VarbitID.MORYTANIA_DIARY_MEDIUM_COMPLETE,
			VarbitID.MORYTANIA_DIARY_HARD_COMPLETE, VarbitID.MORYTANIA_DIARY_ELITE_COMPLETE,
		});
		areas.put("varrock", new int[]{
			VarbitID.VARROCK_DIARY_EASY_COMPLETE, VarbitID.VARROCK_DIARY_MEDIUM_COMPLETE,
			VarbitID.VARROCK_DIARY_HARD_COMPLETE, VarbitID.VARROCK_DIARY_ELITE_COMPLETE,
		});
		areas.put("western_provinces", new int[]{
			VarbitID.WESTERN_DIARY_EASY_COMPLETE, VarbitID.WESTERN_DIARY_MEDIUM_COMPLETE,
			VarbitID.WESTERN_DIARY_HARD_COMPLETE, VarbitID.WESTERN_DIARY_ELITE_COMPLETE,
		});
		areas.put("wilderness", new int[]{
			VarbitID.WILDERNESS_DIARY_EASY_COMPLETE, VarbitID.WILDERNESS_DIARY_MEDIUM_COMPLETE,
			VarbitID.WILDERNESS_DIARY_HARD_COMPLETE, VarbitID.WILDERNESS_DIARY_ELITE_COMPLETE,
		});
		return Collections.unmodifiableMap(areas);
	}

	// Per-area, per-tier "this value means complete" thresholds, same
	// shape/order/keys as DIARY_TIER_VARBITS above -- every area but
	// Karamja is {1, 1, 1, 1} (a plain 0/1 flag for all four tiers), and
	// Karamja is {2, 2, 2, 1}: its easy/medium/hard varbits only reach
	// 2 on completion (see DIARY_TIER_VARBITS' own comment above), while
	// its elite varbit (KARAMJA_DIARY_ELITE_COMPLETE) is a normal 0/1
	// flag like every other area's elite tier.
	private static final Map<String, int[]> DIARY_TIER_COMPLETE_VALUES = buildDiaryTierCompleteValues();

	private static Map<String, int[]> buildDiaryTierCompleteValues()
	{
		Map<String, int[]> values = new LinkedHashMap<>();
		for (String area : DIARY_TIER_VARBITS.keySet())
		{
			values.put(area, area.equals("karamja") ? new int[]{2, 2, 2, 1} : new int[]{1, 1, 1, 1});
		}
		return Collections.unmodifiableMap(values);
	}

	// Every varbit id that appears anywhere in DIARY_TIER_VARBITS above,
	// flattened into one set purely so onVarbitChanged below can do a
	// single cheap membership check per event instead of walking all 12
	// areas' arrays on every single varbit change in the game (most of
	// which have nothing to do with diaries at all).
	private static final Set<Integer> DIARY_VARBIT_IDS = buildDiaryVarbitIds();

	private static Set<Integer> buildDiaryVarbitIds()
	{
		Set<Integer> ids = new HashSet<>();
		for (int[] tierVarbits : DIARY_TIER_VARBITS.values())
		{
			for (int varbitId : tierVarbits)
			{
				ids.add(varbitId);
			}
		}
		return Collections.unmodifiableSet(ids);
	}

	// Combat achievements -- six per-tier "total tasks completed in this
	// tier" varbits (RuneLite's own named gameval VarbitID constants,
	// confirmed against the actual RuneLite API jar this plugin builds
	// against, same as DIARY_TIER_VARBITS above), summed together in
	// patchCombatAchievementStats below for combatTasksCompleted. There
	// is no single "combat tasks completed" varbit the way there's a
	// single QUESTS_COMPLETED_COUNT for quests, so this is the closest
	// equivalent -- and no "combat tasks total" varbit at all (see
	// combatAchievementsDirty's own doc for why combatTasksTotal is left
	// out of this file's own live updates entirely).
	private static final int[] COMBAT_ACHIEVEMENT_COMPLETED_VARBITS = {
		VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD, VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER, VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER,
	};

	// Same reasoning as DIARY_VARBIT_IDS above -- a flattened set purely
	// for a cheap onVarbitChanged membership check.
	private static final Set<Integer> COMBAT_ACHIEVEMENT_VARBIT_IDS = buildCombatAchievementVarbitIds();

	private static Set<Integer> buildCombatAchievementVarbitIds()
	{
		Set<Integer> ids = new HashSet<>();
		for (int varbitId : COMBAT_ACHIEVEMENT_COMPLETED_VARBITS)
		{
			ids.add(varbitId);
		}
		return Collections.unmodifiableSet(ids);
	}

	private MyscapePanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> syncStatusPollTask;
	private ScheduledFuture<?> syncDebounceTask;

	// When the current debounce burst started (see scheduleSyncDebounced
	// below), or -1 between bursts. Written from the client thread
	// (every scheduleSyncDebounced call happens there) and reset back to
	// -1 from `executor`'s thread once the debounce task actually fires
	// -- a plain volatile long is enough here since it's only ever used
	// to compute a delay heuristic, not for correctness the way the
	// synced snapshot fields are.
	private volatile long syncBurstStartMillis = -1;

	// Only ever written from the client thread (see onGameStateChanged /
	// onGameTick below, both guaranteed by RuneLite's event bus to run
	// there) and only ever read from the Swing EDT via the getters below
	// -- volatile so a read on the EDT always sees the latest value
	// without needing to touch `client` itself off the client thread.
	private volatile Long currentAccountHash;
	private volatile String currentRuneScapeName;

	// Each container's most recently observed contents, captured
	// synchronously on the client thread the moment its
	// ItemContainerChanged fires (or, for inventory/equipment, the
	// moment login is first detected -- see onGameTick) and read back
	// later by the debounce task below, which itself runs on
	// `executor`'s own thread, not the client thread. RuneLite's Client
	// (and its ItemContainers) may only safely be read from the client
	// thread, so the debounce task never touches them directly -- it
	// only ever reads these already-converted, plain-data snapshots.
	// Null until the relevant container has been seen at least once
	// (the bank, in particular, reports nothing until the player has
	// opened it at least once this session -- same as the RuneLite
	// Character Exporter plugin Maintrius originally relied on).
	private volatile List<MyscapeClient.BankItem> latestBankItems;
	private volatile List<MyscapeClient.InventoryItem> latestInventoryItems;
	private volatile List<MyscapeClient.EquipmentItem> latestEquipmentItems;
	// Same deal, for Potion Storage -- null until the relevant varps
	// have actually fired at least once this session (see
	// onVarbitChanged/onWidgetLoaded below), same as the bank only
	// reporting contents once it's been opened.
	private volatile List<MyscapeClient.PotionStorageItem> latestPotionStorageItems;
	// Same deal, for a Rune Pouch's contents -- null until the relevant
	// varbits have actually fired at least once this session, same as
	// Potion Storage above.
	private volatile List<MyscapeClient.RunePouchItem> latestRunePouchItems;
	// Same deal, for a Looting Bag's contents -- unlike Potion Storage/
	// Rune Pouch above, a Looting Bag genuinely is an ItemContainer
	// (InventoryID.LOOTING_BAG), read the same straightforward way Bank
	// is -- null until the player has actually opened it at least once
	// this session, same caveat as Bank.
	private volatile List<MyscapeClient.LootingBagItem> latestLootingBagItems;
	// Same deal again, for the Seed Vault's contents -- also a genuine
	// ItemContainer (InventoryID.SEED_VAULT), read and gated the same
	// way as the Looting Bag just above: null until the player has
	// actually opened it at least once this session.
	private volatile List<MyscapeClient.SeedVaultItem> latestSeedVaultItems;
	// Same deal again, for a Tackle Box's contents -- also a genuine
	// ItemContainer (InventoryID.TACKLE_BOX), read and gated the same
	// way as Looting Bag/Seed Vault above: null until the player has
	// actually opened it at least once this session.
	private volatile List<MyscapeClient.TackleBoxItem> latestTackleBoxItems;
	// Same deal again, for the whole PoH Costume Room's contents.
	// Originally six separate fields, one per piece of furniture, each
	// assumed to back its own ItemContainer -- live testing found every
	// piece actually fires ItemContainerChanged on the same containerId
	// (33405, not any of the InventoryID.POH_COSTUME_ROOM_* constants),
	// with identical contents regardless of which piece was opened, so
	// this is read and gated the same simple way as Looting Bag/Seed
	// Vault/Tackle Box above: null until the player has actually opened
	// the Costume Room interface at least once this session. Named
	// latestPohItems (not latestCostumeRoomItems) to match PohItem --
	// see that class's own comment in MyscapeClient.java.
	private volatile List<MyscapeClient.PohItem> latestPohItems;
	// Same deal again, for the Forestry Kit's contents -- also a
	// genuine ItemContainer (InventoryID.FORESTRY_KIT), read and gated
	// the same way as Looting Bag/Seed Vault/Tackle Box above: null
	// until the player has actually opened it at least once this
	// session.
	private volatile List<MyscapeClient.ForestryKitItem> latestForestryKitItems;
	// Same deal again, for a Seed Box's contents -- also a genuine
	// ItemContainer (InventoryID.SEED_BOX), read and gated the same
	// way as Looting Bag/Seed Vault/Tackle Box/Forestry Kit above: null
	// until the player has actually opened it at least once this
	// session. Distinct from the Seed Vault above -- see SeedBoxItem's
	// own comment in MyscapeClient.java. There is deliberately no
	// latestMasterScrollBookItems field alongside this one: unlike
	// every other source here, the Master Scroll Book has no confirmed
	// container id to read yet (see MasterScrollBookItem's own comment
	// in MyscapeClient.java) -- see the temporary diagnostic in
	// onItemContainerChanged below instead.
	private volatile List<MyscapeClient.SeedBoxItem> latestSeedBoxItems;
	// Same deal again, for a Huntsman's Kit's contents -- also a genuine
	// ItemContainer (InventoryID.HUNTSMANS_KIT), read and gated the same
	// way as Looting Bag/Seed Vault/Tackle Box/Forestry Kit/Seed Box
	// above: null until the player has actually opened it at least once
	// this session.
	private volatile List<MyscapeClient.HuntsmansKitItem> latestHuntsmansKitItems;
	// Same deal again, for a boat's cargo hold -- but one field PER BOAT
	// SLOT (1-5, the most a player can own at once -- see the OSRS
	// Wiki's "Boat" article) rather than one shared field, since each
	// boat is a genuinely separate ItemContainer (InventoryID.
	// SAILING_BOAT_1_CARGOHOLD through SAILING_BOAT_5_CARGOHOLD) with
	// its own independent contents -- unlike PoH's six furniture pieces
	// above, which turned out to all read from one shared container.
	// Each stays null until THAT boat's cargo hold has actually been
	// opened at least once this session -- see readBoatCargoItems and
	// BoatCargoItem's own comment in MyscapeClient.java.
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo1Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo2Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo3Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo4Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo5Items;
	// Same deal, for skills/quests/diaries -- unlike Potion Storage/Rune
	// Pouch, though, these are never left null for long once logged in:
	// see onGameTick's justBecameAvailable block, which reads all three
	// the moment login is detected, the same reasoning that block already
	// applies to inventory/equipment/rune pouch (nothing to "change" from
	// a cold start -- there's already a real level/quest/diary state to
	// report from the first tick).
	private volatile List<MyscapeClient.SkillItem> latestSkillItems;
	private volatile List<String> latestCompletedQuestNames;
	// Same deal as latestCompletedQuestNames above, but for
	// QuestState.IN_PROGRESS instead of FINISHED -- see
	// readInProgressQuestNames' own doc below. Added for Character
	// Overview's quest list section (red/yellow/green per quest),
	// which needs a third state beyond just "done"/"not done".
	private volatile List<String> latestInProgressQuestNames;
	private volatile List<MyscapeClient.DiaryTierItem> latestDiaryTierItems;

	// The collection log's per-item obtained/quantity state, unlike
	// every other source above, isn't read passively off an
	// ItemContainer/varbit/widget at all -- collectionLogItemCapture
	// (see its own class doc) has to actively re-trigger the collection
	// log interface's own refresh to make the client hand this over,
	// and only once the player has opened the collection log widget at
	// least once this session (there is no equivalent of inventory/
	// equipment's login-time freebie here). Set from onGameTick below,
	// the tick collectionLogItemCapture itself decides a capture pass
	// has settled -- see its onGameTick doc. Item ids here are in the
	// same id space CollectionLogSchemaExporter's own exported schema
	// already uses.
	private final CollectionLogItemCapture collectionLogItemCapture = new CollectionLogItemCapture();
	private volatile List<MyscapeClient.CollectionLogItem> latestCollectionLogItems;

	// The Master Scroll Book, unlike every other source above, isn't
	// backed by a RuneLite ItemContainer -- two rounds of diagnostics
	// confirmed that (see readMasterScrollBookItems' own comment for
	// the full story and the widget layout they found), so there's no
	// ItemContainerChanged to key off of. This flag is set from
	// onWidgetLoaded below instead, whenever InterfaceID.BOOKOFSCROLLS
	// itself loads (i.e. the player opened it), and consumed on the
	// next game tick the same way skillsDirty/questsDirty/diariesDirty
	// above are.
	private volatile boolean masterScrollBookDirty;
	private volatile List<MyscapeClient.MasterScrollBookItem> latestMasterScrollBookItems;

	// Same story as the Master Scroll Book just above: the "Character
	// Summary" tab of the quest journal's Account Summary sidepanel
	// (InterfaceID.ACCOUNT_SUMMARY_SIDEPANEL) has no ItemContainer
	// backing any of its seven stats, only widgets that exist while that
	// specific tab is open -- so this is likewise set from onWidgetLoaded
	// below. (Six of the seven DO now have their own live varbit/varp or
	// derived signal -- see patchCombatAndTotalStats/patchQuestStats/
	// patchDiaryStats/patchCombatAchievementStats/patchCollectionStats
	// below and CharacterSummaryItem's own doc in MyscapeClient.java --
	// but this widget-driven path is still what establishes the very
	// first baseline each session, and still the periodic full-accuracy
	// correction pass every one of those live patches implicitly relies
	// on to fix any drift.) Unlike masterScrollBookDirty though, this
	// isn't a one-shot flag consumed on the very next tick -- a live
	// test showed combat level/total level/total XP specifically (NOT
	// the four completion-count stats below them on the same panel)
	// still read as placeholder values (0.0/100/100) one tick after
	// load, meaning whatever script actually computes those three
	// (summed across all 24 skills, unlike the other four's simple
	// stored counts) hadn't finished yet. This counts down instead --
	// set to CHARACTER_SUMMARY_REREAD_TICKS on load, re-read on every
	// tick while positive (see onGameTick below) -- so whichever later
	// tick the real numbers actually land on still gets captured. Each
	// successful re-read also re-calls scheduleSyncDebounced, which
	// pushes the actual HTTP send further out every time (see that
	// method's own doc) -- so the value that ends up syncing is
	// whichever read happened last, not the first.
	private static final int CHARACTER_SUMMARY_REREAD_TICKS = 8;
	private volatile int characterSummaryRereadTicksLeft;
	private volatile MyscapeClient.CharacterSummaryItem latestCharacterSummary;

	// A handful of the ItemContainer-backed sources above (Looting Bag,
	// Seed Vault, Tackle Box, Forestry Kit, Seed Box, PoH Costume Room,
	// Huntsman's Kit, and each boat's Cargo Hold) share a real game
	// quirk their read*Items methods alone can't work around: checking
	// one of these while it's ALREADY empty sends no ItemContainerChanged
	// at all -- confirmed live (and independently, by another RuneLite
	// plugin dedicated to the Looting Bag specifically, which hit and
	// solved the exact same problem the same way this does) -- so
	// onItemContainerChanged simply never fires to report the empty
	// state, and a storage that's genuinely empty the first time a
	// player ever checks it stays permanently un-synced instead of
	// syncing as empty. The interface itself still opens either way
	// (there's something on screen to see "empty" from), just with no
	// underlying container array write for RuneLite's own change-
	// detection to key off of.
	//
	// This map is the fix: onWidgetLoaded below queues an explicit,
	// unconditional re-read of the relevant container id(s) (mapped to
	// whichever tick they were queued on) the moment one of these
	// storages' own interface loads, regardless of whether
	// ItemContainerChanged separately fires for the same open -- and
	// ALSO regardless of whether the container turns out empty or not,
	// so this doubles as a second (harmless, idempotent) read on a
	// normal non-empty open rather than needing to special-case "empty"
	// at all. Deliberately not read on the very same tick it's queued:
	// mirroring the other Looting Bag plugin's own fix, the container
	// isn't guaranteed populated until the tick after its widget loads.
	//
	// That "one tick later" read used to be a single, one-shot attempt
	// -- if client.getItemContainer(containerId) still came back null
	// then, the entry was dropped and nothing retried it, on the
	// assumption that one tick is always enough. That assumption is the
	// suspected cause of a real report: every other virtual storage
	// here registered as synced promptly, but the PoH Costume Room
	// consistently took noticeably longer. Unlike the other virtual
	// storages, the Costume Room's container id is assigned at runtime
	// by a newer, consolidated interface script (see
	// POH_COSTUME_ROOM_CONTAINER_ID's own comment) that plausibly needs
	// more than one tick to finish building its combined view across
	// all six furniture pieces -- if so, the single-shot reread was
	// firing before the container actually existed yet, and then just
	// giving up, leaving that sync to wait on the next real
	// ItemContainerChanged (a deposit/withdrawal), which might not come
	// for a while. onGameTick below now keeps
	// retrying a still-null entry on every subsequent tick instead of
	// abandoning it after one miss, up to MAX_CONTAINER_REREAD_WAIT_TICKS
	// -- long enough to cover a slow-to-build interface like the
	// Costume Room, but still bounded so the four boat-cargo slots that
	// are queued together but never actually populate for a given
	// player (see VIRTUAL_STORAGE_WIDGET_CONTAINER_IDS' own comment)
	// don't retry forever. Set only from onWidgetLoaded and consumed
	// only from onGameTick -- both client-thread-only callbacks, so
	// this needs no synchronization of its own despite being a plain
	// (non-volatile) mutable map, the same reasoning
	// potionStorageDirty/runePouchDirty above already rely on.
	private final Map<Integer, Integer> pendingContainerRereadTicks = new LinkedHashMap<>();

	// How many extra ticks onGameTick below keeps retrying a queued
	// container reread that's still coming back null, before giving up
	// on it -- see pendingContainerRereadTicks' own field doc for why
	// this exists (the PoH Costume Room's own build script suspected
	// of needing more than one tick to populate) and why it's bounded
	// rather than unconditional (the boat-cargo slots that are queued
	// together but never actually populate for a given player). 10
	// ticks is ~6 seconds real time -- comfortably more than any
	// virtual storage here has been observed to need, while still
	// short enough that a doomed boat-cargo entry doesn't sit around
	// retrying for the rest of the session.
	private static final int MAX_CONTAINER_REREAD_WAIT_TICKS = 10;

	// Maps a "check it" interface's own widget group id to the single
	// ItemContainer id it backs, for every source above with exactly
	// one such container -- consulted from onWidgetLoaded below to
	// populate pendingContainerRereadTicks. Boat Cargo Hold isn't in
	// here: its one shared interface (InterfaceID.SAILING_BOAT_CARGOHOLD)
	// can back any of five different container ids depending on which
	// boat was opened, so onWidgetLoaded queues all five directly
	// instead of going through a 1:1 lookup here.
	private static final Map<Integer, Integer> VIRTUAL_STORAGE_WIDGET_CONTAINER_IDS = buildVirtualStorageWidgetContainerIds();

	private static Map<Integer, Integer> buildVirtualStorageWidgetContainerIds()
	{
		Map<Integer, Integer> ids = new LinkedHashMap<>();
		ids.put(InterfaceID.WILDERNESS_LOOTINGBAG, InventoryID.LOOTING_BAG);
		ids.put(InterfaceID.SEED_VAULT, InventoryID.SEED_VAULT);
		ids.put(InterfaceID.TACKLE_BOX_MAIN, InventoryID.TACKLE_BOX);
		ids.put(InterfaceID.TACKLE_BOX_SIDE, InventoryID.TACKLE_BOX);
		ids.put(InterfaceID.FORESTRY_KIT_MAIN, InventoryID.FORESTRY_KIT);
		ids.put(InterfaceID.FORESTRY_KIT_SIDE, InventoryID.FORESTRY_KIT);
		ids.put(InterfaceID.HOSIDIUS_SEEDBOX, InventoryID.SEED_BOX);
		// POH_COSTUME_ROOM_CONTAINER_ID, not InventoryID.POH_COSTUMES --
		// see that constant's own comment below for why.
		ids.put(InterfaceID.POH_COSTUMES, POH_COSTUME_ROOM_CONTAINER_ID);
		ids.put(InterfaceID.POH_COSTUMES_SIDE, POH_COSTUME_ROOM_CONTAINER_ID);
		ids.put(InterfaceID.HUNTSMANS_KIT, InventoryID.HUNTSMANS_KIT);
		ids.put(InterfaceID.HUNTSMANS_KIT_SIDE, InventoryID.HUNTSMANS_KIT);
		return Collections.unmodifiableMap(ids);
	}

	@Provides
	MyscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MyscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		MyscapeClient apiClient = new MyscapeClient(okHttpClient, configManager);
		panel = new MyscapePanel(apiClient, this);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("myscape")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		// panel/navButton are always built above regardless of this
		// setting -- background linking/syncing has nothing to do with
		// whether the toolbar icon that opens the panel is showing, so
		// turning "Show sidebar" off just leaves this one addNavigation
		// call skipped (onConfigChanged below adds/removes it live if
		// the player flips the setting later without restarting).
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(navButton);
		}

		panel.refreshLinkStatus();

		syncStatusPollTask = executor.scheduleWithFixedDelay(
			() -> {
				if (panel != null)
				{
					// Keeps the Status section's per-storage rows honest
					// even for a storage nothing in this session has
					// triggered a resync of (see
					// MyscapePanel#refreshSyncStatusInBackground).
					panel.refreshSyncStatusInBackground();
				}
			},
			SYNC_STATUS_POLL_SECONDS, SYNC_STATUS_POLL_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (syncStatusPollTask != null)
		{
			syncStatusPollTask.cancel(false);
			syncStatusPollTask = null;
		}
		if (syncDebounceTask != null)
		{
			syncDebounceTask.cancel(false);
			syncDebounceTask = null;
		}
		syncBurstStartMillis = -1;
		clientToolbar.removeNavigation(navButton);
		panel = null;
		currentAccountHash = null;
		currentRuneScapeName = null;
		latestBankItems = null;
		latestInventoryItems = null;
		latestEquipmentItems = null;
		latestPotionStorageItems = null;
		latestRunePouchItems = null;
		latestLootingBagItems = null;
		latestSeedVaultItems = null;
		latestTackleBoxItems = null;
		latestPohItems = null;
		latestForestryKitItems = null;
		latestSeedBoxItems = null;
		latestSkillItems = null;
		latestCompletedQuestNames = null;
		latestInProgressQuestNames = null;
		latestDiaryTierItems = null;
		masterScrollBookDirty = false;
		latestMasterScrollBookItems = null;
		characterSummaryRereadTicksLeft = 0;
		latestCharacterSummary = null;
		latestHuntsmansKitItems = null;
		latestBoatCargo1Items = null;
		latestBoatCargo2Items = null;
		latestBoatCargo3Items = null;
		latestBoatCargo4Items = null;
		latestBoatCargo5Items = null;
		pendingContainerRereadTicks.clear();
	}

	/** Aggregates a container's raw slots by item id -- necessary (not
	 * just defensive) for the inventory, where the same untradeable item
	 * commonly occupies several separate slots at quantity 1 each; a
	 * bank slot never actually splits one item across two slots, but
	 * there's no reason to assume that here either when a Map costs
	 * nothing extra. -1/0-quantity entries are how RuneLite represents
	 * an empty slot, not a real item -- skipped rather than counted. */
	private static Map<Integer, Integer> aggregateQuantities(ItemContainer container)
	{
		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (Item item : container.getItems())
		{
			if (item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			quantities.merge(item.getId(), item.getQuantity(), Integer::sum);
		}
		return quantities;
	}

	/** Looks up an item's real display name via RuneLite's own item
	 * definitions (not the OSRS Wiki) -- unlike the wiki's GE mapping,
	 * this covers every item in the game, tradeable or not, which is
	 * exactly why it's needed: see BankItem's docstring in
	 * MyscapeClient.java for what this name is actually used for
	 * server-side. Must only ever be called from the client thread (see
	 * the field doc on latestBankItems and friends) -- both call sites
	 * below (onItemContainerChanged, onGameTick) already guarantee that. */
	private String resolveItemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			// An unrecognized id should never stop the rest of the sync --
			// the item still syncs by id, it just won't name-match an
			// untradeable gear-setup slot until this resolves some other
			// way (e.g. the wiki mapping, if it's actually tradeable).
			return null;
		}
	}

	private List<MyscapeClient.BankItem> readBankItems(ItemContainer bank)
	{
		List<MyscapeClient.BankItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(bank).entrySet())
		{
			int itemId = entry.getKey();
			if (isPlaceholder(itemId))
			{
				continue;
			}
			items.add(new MyscapeClient.BankItem(itemId, resolveItemName(itemId), entry.getValue()));
		}
		return items;
	}

	/** A bank placeholder -- the greyed-out marker "Always Placeholder"/
	 * withdraw-as-placeholder leaves behind in a slot to show "you keep
	 * one of these here" without actually holding any -- is its own
	 * distinct item id in the client's item table (a real ItemContainer
	 * slot, with a real nonzero quantity, so aggregateQuantities' own
	 * quantity<=0 check above never catches it), not a quantity-0
	 * marker on the real item's own id. RuneLite exposes that via
	 * getPlaceholderTemplateId(), which is -1 for every genuine item and
	 * only ever set on a placeholder's own id -- the one reliable way to
	 * tell "an empty placeholder slot" apart from "one of the real
	 * thing" (see readBankItems above, which is the only caller: a
	 * placeholder is a bank-only concept, nothing else read here can
	 * have one). */
	private boolean isPlaceholder(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getPlaceholderTemplateId() != -1;
		}
		catch (Exception e)
		{
			return false;
		}
	}

	private List<MyscapeClient.InventoryItem> readInventoryItems(ItemContainer inventory)
	{
		List<MyscapeClient.InventoryItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(inventory).entrySet())
		{
			items.add(new MyscapeClient.InventoryItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** A Looting Bag genuinely is an ItemContainer (InventoryID.
	 * LOOTING_BAG), unlike Potion Storage/Rune Pouch below -- read the
	 * same straightforward way Bank is (see readBankItems above), only
	 * reporting contents once the player has actually opened it at
	 * least once this session (same caveat as Bank). */
	private List<MyscapeClient.LootingBagItem> readLootingBagItems(ItemContainer lootingBag)
	{
		List<MyscapeClient.LootingBagItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(lootingBag).entrySet())
		{
			items.add(new MyscapeClient.LootingBagItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** The Farming Guild Seed Vault also genuinely is an ItemContainer
	 * (InventoryID.SEED_VAULT), same as the Looting Bag just above --
	 * read the exact same straightforward way, only reporting contents
	 * once the player has actually opened it at least once this
	 * session (same caveat as Bank/Looting Bag). */
	private List<MyscapeClient.SeedVaultItem> readSeedVaultItems(ItemContainer seedVault)
	{
		List<MyscapeClient.SeedVaultItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(seedVault).entrySet())
		{
			items.add(new MyscapeClient.SeedVaultItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** A Tackle Box also genuinely is an ItemContainer (InventoryID.
	 * TACKLE_BOX), same as the Looting Bag/Seed Vault above -- read the
	 * exact same straightforward way, only reporting contents once the
	 * player has actually opened it at least once this session (same
	 * caveat as Bank/Looting Bag/Seed Vault). */
	private List<MyscapeClient.TackleBoxItem> readTackleBoxItems(ItemContainer tackleBox)
	{
		List<MyscapeClient.TackleBoxItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(tackleBox).entrySet())
		{
			items.add(new MyscapeClient.TackleBoxItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** The whole PoH Costume Room also genuinely is an ItemContainer --
	 * read the exact same straightforward way as Looting Bag/Seed
	 * Vault/Tackle Box above, only reporting contents once the player
	 * has actually opened the Costume Room interface at least once
	 * this session (same caveat as Bank/Looting Bag/Seed Vault/Tackle
	 * Box). Originally six separate read methods, one per piece of
	 * furniture, each assumed to read its own dedicated ItemContainer
	 * and (for the Treasure Chest/Cape Rack) merge several widget
	 * pages together. Live testing found that assumption wrong: every
	 * piece fires ItemContainerChanged on the same containerId (33405,
	 * not any of the InventoryID.POH_COSTUME_ROOM_* constants those six
	 * methods checked for) with identical contents regardless of which
	 * piece was opened, so this collapses back down to the same simple
	 * single-container pattern as readTackleBoxItems above -- no
	 * per-page merging needed. Named readPohItems (not
	 * readCostumeRoomItems) to match PohItem -- see that class's own
	 * comment in MyscapeClient.java. */
	private List<MyscapeClient.PohItem> readPohItems(ItemContainer poh)
	{
		List<MyscapeClient.PohItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(poh).entrySet())
		{
			items.add(new MyscapeClient.PohItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** The Forestry Kit also genuinely is an ItemContainer
	 * (InventoryID.FORESTRY_KIT), same as the Looting Bag/Seed Vault/
	 * Tackle Box above -- read the exact same straightforward way,
	 * only reporting contents once the player has actually opened it
	 * at least once this session. */
	private List<MyscapeClient.ForestryKitItem> readForestryKitItems(ItemContainer forestryKit)
	{
		List<MyscapeClient.ForestryKitItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(forestryKit).entrySet())
		{
			items.add(new MyscapeClient.ForestryKitItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** A Seed Box also genuinely is an ItemContainer
	 * (InventoryID.SEED_BOX), same as the Looting Bag/Seed Vault/
	 * Tackle Box/Forestry Kit above -- read the exact same
	 * straightforward way, only reporting contents once the player
	 * has actually opened it at least once this session. Distinct
	 * from the Seed Vault above -- see SeedBoxItem's own comment in
	 * MyscapeClient.java. */
	private List<MyscapeClient.SeedBoxItem> readSeedBoxItems(ItemContainer seedBox)
	{
		List<MyscapeClient.SeedBoxItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(seedBox).entrySet())
		{
			items.add(new MyscapeClient.SeedBoxItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** A Huntsman's Kit also genuinely is an ItemContainer
	 * (InventoryID.HUNTSMANS_KIT), same as the Looting Bag/Seed Vault/
	 * Tackle Box/Forestry Kit/Seed Box above -- read the exact same
	 * straightforward way, only reporting contents once the player has
	 * actually opened it at least once this session. */
	private List<MyscapeClient.HuntsmansKitItem> readHuntsmansKitItems(ItemContainer huntsmansKit)
	{
		List<MyscapeClient.HuntsmansKitItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(huntsmansKit).entrySet())
		{
			items.add(new MyscapeClient.HuntsmansKitItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** One boat's Cargo Hold also genuinely is an ItemContainer (one of
	 * InventoryID.SAILING_BOAT_1_CARGOHOLD through
	 * SAILING_BOAT_5_CARGOHOLD, depending which boat -- see
	 * applyContainerSnapshot's own dispatch above), read the exact same
	 * straightforward way as every other single-container source above,
	 * only reporting a given boat's contents once the player has
	 * actually opened THAT boat's cargo hold at least once this
	 * session. Shared by all five slots (which boat this reads is
	 * entirely up to which container id the caller passes in) rather
	 * than five near-identical methods, since the read itself doesn't
	 * care which boat it's reading -- see BoatCargoItem's own comment
	 * in MyscapeClient.java for why the slot number still matters
	 * everywhere else around this. */
	private List<MyscapeClient.BoatCargoItem> readBoatCargoItems(ItemContainer boatCargo)
	{
		List<MyscapeClient.BoatCargoItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(boatCargo).entrySet())
		{
			items.add(new MyscapeClient.BoatCargoItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** Unlike bank/inventory, equipment is read by fixed slot index
	 * (EQUIPMENT_SLOT_NAMES) rather than aggregated -- exactly one item
	 * can ever occupy a given equipment slot. */
	private List<MyscapeClient.EquipmentItem> readEquipmentItems(ItemContainer equipment)
	{
		Item[] slots = equipment.getItems();
		List<MyscapeClient.EquipmentItem> items = new ArrayList<>();
		for (Map.Entry<Integer, String> entry : EQUIPMENT_SLOT_NAMES.entrySet())
		{
			int slotIdx = entry.getKey();
			if (slotIdx >= slots.length)
			{
				continue;
			}
			Item item = slots[slotIdx];
			if (item == null || item.getId() <= 0 || item.getQuantity() <= 0)
			{
				continue;
			}
			items.add(new MyscapeClient.EquipmentItem(
				entry.getValue(), item.getId(), resolveItemName(item.getId()), item.getQuantity()));
		}
		return items;
	}

	/** Reads Potion Storage's current contents -- unlike
	 * readBankItems/readInventoryItems/readEquipmentItems above, there's
	 * no ItemContainer to read this off of; Potion Storage tracks total
	 * *doses* per potion type, not discrete item stacks (see the field
	 * doc on POTION_STORAGE_ENUM_IDS above for the full mechanism this
	 * mirrors, straight from RuneLite's own bundled Bank Tags plugin).
	 * Must only ever be called from the client thread, same as the
	 * other readXItems methods (both call sites below already
	 * guarantee that).
	 *
	 * Reports each potion type as however many physical items totalling
	 * that many doses withdrawing it "all at once" would actually hand
	 * back -- e.g. 10 stored doses of Prayer potion becomes two
	 * Prayer potion(4) plus one Prayer potion(2) -- rather than the raw
	 * dose count itself, so the result slots into the exact same
	 * item-id/quantity shape bank/inventory/equipment already use, and
	 * the server needs zero special-casing to treat it as just another
	 * owned-item source (see PotionStorageItem's docstring in the
	 * Mootrius/myscape repo's app/models.py). */
	private List<MyscapeClient.PotionStorageItem> readPotionStorageItems()
	{
		List<MyscapeClient.PotionStorageItem> items = new ArrayList<>();
		for (int topEnumId : POTION_STORAGE_ENUM_IDS)
		{
			EnumComposition topEnum = client.getEnum(topEnumId);
			if (topEnum == null)
			{
				continue;
			}
			for (int potionEnumId : topEnum.getIntVals())
			{
				EnumComposition potionEnum = client.getEnum(potionEnumId);
				if (potionEnum == null)
				{
					continue;
				}

				client.runScript(POTION_STORAGE_TOTAL_DOSES_SCRIPT_ID, potionEnumId);
				int[] stack = client.getIntStack();
				int totalDoses = stack.length > 0 ? stack[0] : 0;
				if (totalDoses <= 0)
				{
					continue;
				}

				int remaining = totalDoses;
				for (int dose = MAX_POTION_DOSES; dose >= 1 && remaining > 0; dose--)
				{
					int itemId = potionEnum.getIntValue(dose);
					if (itemId <= 0)
					{
						continue;
					}
					int count = remaining / dose;
					if (count <= 0)
					{
						continue;
					}
					items.add(new MyscapeClient.PotionStorageItem(itemId, resolveItemName(itemId), count));
					remaining -= count * dose;
				}
			}
		}
		return items;
	}

	/** Whether any of the Rune Pouch variants (see RUNE_POUCH_ITEM_IDS) is
	 * currently sitting in the player's inventory OR bank -- see
	 * readRunePouchItems's doc for why this gate exists. A pouch can't be
	 * worn, but it CAN be banked (this used not to be true in OSRS, but
	 * is again as of the update that let players store one in the bank),
	 * and its contents are account-wide varbit state, not something that
	 * stops being real just because the physical pouch is sitting in the
	 * bank rather than the inventory -- checking inventory alone meant a
	 * banked pouch's runes silently vanished from every synced snapshot
	 * even though the player still owns them, exactly as much as the
	 * rest of what's in their bank. The bank container is null until the
	 * player has opened it at least once this session (same caveat as
	 * everywhere else in this file that reads it) -- until then, a
	 * banked pouch simply isn't detectable yet, same as the rest of the
	 * bank's own contents. Must only ever be called from the client
	 * thread, same as readRunePouchItems. Only ever consulted by
	 * playerDefinitelyDoesNotOwnRunePouch below now, not
	 * readRunePouchItems directly -- see that method's own doc for why
	 * "not found here (yet)" and "confirmed not owned" ended up needing
	 * to be two different questions. */
	private boolean playerOwnsRunePouch()
	{
		return containerHasRunePouch(client.getItemContainer(InventoryID.INV))
			|| containerHasRunePouch(client.getItemContainer(InventoryID.BANK));
	}

	/** The actual gate readRunePouchItems uses -- deliberately NOT just
	 * "!playerOwnsRunePouch()". A fresh login populates the Rune Pouch
	 * varbits immediately (account-wide state, same as every other stat)
	 * but leaves the bank ItemContainer null until the player opens their
	 * bank at least once this session (see playerOwnsRunePouch's own
	 * comment) -- so right after login, with a pouch stocked and safely
	 * banked, playerOwnsRunePouch() would report false (inventory empty,
	 * bank not loaded yet) even though the player still genuinely owns
	 * it, and readRunePouchItems would wrongly report an empty pouch
	 * until the bank happened to get opened. "Haven't found it (in a
	 * container we can't even see yet)" and "confirmed it's actually
	 * gone" are different things, and only the second one should ever
	 * suppress otherwise-valid varbit data: this returns true (state the
	 * varbits every session actually trust) whenever inventory AND a
	 * *loaded* bank both fail to show a pouch, but stays false -- "not
	 * proven, so don't assume it's gone" -- for as long as the bank
	 * container is still null, exactly the login gap this exists to
	 * cover. Must only ever be called from the client thread, same as
	 * readRunePouchItems. */
	private boolean playerDefinitelyDoesNotOwnRunePouch()
	{
		if (client.getItemContainer(InventoryID.BANK) == null)
		{
			return false;
		}
		return !playerOwnsRunePouch();
	}

	private static boolean containerHasRunePouch(ItemContainer container)
	{
		if (container == null)
		{
			return false;
		}
		for (Item item : container.getItems())
		{
			for (int pouchId : RUNE_POUCH_ITEM_IDS)
			{
				if (item.getId() == pouchId)
				{
					return true;
				}
			}
		}
		return false;
	}

	/** Reads a Rune Pouch's current contents -- unlike
	 * readBankItems/readInventoryItems/readEquipmentItems above, there's
	 * no ItemContainer to read this off of; RuneLite exposes it through
	 * six type/quantity varbit pairs instead (see the field doc on
	 * RUNE_POUCH_TYPE_VARBITS above for the full mechanism this mirrors,
	 * straight from RuneLite's own bundled Rune Pouch overlay plugin).
	 * Must only ever be called from the client thread, same as the other
	 * readXItems methods (both call sites below already guarantee that).
	 *
	 * Returns an empty list -- rather than whatever the varbits happen
	 * to still say -- once the player's CONFIRMED to no longer have a
	 * Rune Pouch anywhere in their inventory or a *loaded* bank (see
	 * playerDefinitelyDoesNotOwnRunePouch): unlike Potion Storage, these
	 * varbits are the pouch's own carried state, so without some check
	 * here, a pouch that was sold or otherwise given up while stocked
	 * would keep being reported as "owned" contents long after both it
	 * and the runes actually left the account. Deliberately NOT gated on
	 * plain ownership (playerOwnsRunePouch) any more, though -- see
	 * playerDefinitelyDoesNotOwnRunePouch's own doc for the login-gap bug
	 * that distinction fixes. Multiple slots holding the same rune type
	 * (not possible in-game today, but nothing guarantees that) are
	 * merged into one entry, same as aggregateQuantities does for bank/
	 * inventory. */
	private List<MyscapeClient.RunePouchItem> readRunePouchItems()
	{
		if (playerDefinitelyDoesNotOwnRunePouch())
		{
			return Collections.emptyList();
		}

		EnumComposition runeEnum = client.getEnum(RUNE_POUCH_RUNE_ENUM_ID);
		if (runeEnum == null)
		{
			return Collections.emptyList();
		}

		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (int slot = 0; slot < RUNE_POUCH_TYPE_VARBITS.length; slot++)
		{
			int runeType = client.getVarbitValue(RUNE_POUCH_TYPE_VARBITS[slot]);
			int amount = client.getVarbitValue(RUNE_POUCH_QUANTITY_VARBITS[slot]);
			if (runeType <= 0 || amount <= 0)
			{
				continue;
			}
			int itemId = runeEnum.getIntValue(runeType);
			if (itemId <= 0)
			{
				continue;
			}
			quantities.merge(itemId, amount, Integer::sum);
		}

		List<MyscapeClient.RunePouchItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			items.add(new MyscapeClient.RunePouchItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	/** Every one of RuneLite's own Skill.values() -- real skill names as
	 * RuneLite itself defines them, not some separately hardcoded 24-
	 * entry list of this plugin's own, so a skill this client's RuneLite
	 * build doesn't know about is simply never reported (and one it's
	 * since added, like Sailing, is picked up automatically without this
	 * plugin needing an update just to keep pace). `skill.name()` lower-
	 * cased is always a single lowercase word for a real OSRS skill (e.g.
	 * "attack", "sailing"), matching SKILL_NAMES in the Mootrius/myscape
	 * repo's app/mindmaps.py exactly. getRealSkillLevel is the player's
	 * base level (unaffected by a temporary boost/drain from a potion or
	 * prayer) -- a goal should track real progress, not whatever a
	 * player's level happens to be boosted to for the next few minutes.
	 * Must only ever be called from the client thread, same as the other
	 * readXItems methods above (both call sites below already guarantee
	 * that). */
	private List<MyscapeClient.SkillItem> readSkills()
	{
		List<MyscapeClient.SkillItem> items = new ArrayList<>();
		for (Skill skill : Skill.values())
		{
			items.add(new MyscapeClient.SkillItem(
				skill.name().toLowerCase(), client.getRealSkillLevel(skill), client.getSkillExperience(skill)));
		}
		return items;
	}

	/** Every net.runelite.api.Quest (RuneLite's own full quest and
	 * miniquest list) this account has finished, by RuneLite's own
	 * display name for it (e.g. "Cook's Assistant") -- matched case-
	 * insensitively against a blue goal node's chosen quest server-side
	 * (see Player.completed_quests' docstring in the Mootrius/myscape
	 * repo's app/models.py), so nothing here needs to normalize or
	 * cross-reference names against that server-side quest list itself.
	 * Quest#getState is client-thread-only, same as the other readXItems
	 * methods above (both call sites below already guarantee that) -- it
	 * runs a client script and reads the result straight off the
	 * client's own int stack, the same mechanism readPotionStorageItems
	 * above already uses for a different script. */
	private List<String> readCompletedQuestNames()
	{
		List<String> names = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			if (quest.getState(client) == QuestState.FINISHED)
			{
				names.add(quest.getName());
			}
		}
		return names;
	}

	/** Same scan as readCompletedQuestNames above, but collecting every
	 * quest/miniquest QuestState.IN_PROGRESS instead of FINISHED --
	 * Character Overview's quest list section uses this, alongside
	 * completed_quests, to tell "started but not finished" (yellow)
	 * apart from "never started" (red, i.e. absent from both lists) --
	 * see Player.completed_quests' docstring in the Mootrius/myscape
	 * repo's app/models.py for the server-side field this feeds. */
	private List<String> readInProgressQuestNames()
	{
		List<String> names = new ArrayList<>();
		for (Quest quest : Quest.values())
		{
			if (quest.getState(client) == QuestState.IN_PROGRESS)
			{
				names.add(quest.getName());
			}
		}
		return names;
	}

	/** This account's highest completed tier per achievement diary area,
	 * from RuneLite's own per-tier "complete" varbits (see
	 * DIARY_TIER_VARBITS above) -- an area with nothing completed yet is
	 * simply left out of the result, matching how Player.diary_tiers only
	 * ever stores one current tier per area server-side (see that same
	 * docstring). Each area's 4 varbits are checked low-to-high against
	 * that same tier's DIARY_TIER_COMPLETE_VALUES threshold (1 for every
	 * area/tier except Karamja's easy/medium/hard, which complete at 2 --
	 * see that map's own doc), and this stops at the first one that
	 * hasn't reached it, since the real diary journal only ever lets a
	 * player finish a tier once every lower tier is already done --
	 * there's no such thing as Hard being complete while Medium isn't, so
	 * nothing here needs to handle that. Must only ever be called from
	 * the client thread, same as the other readXItems methods above
	 * (both call sites below already guarantee that). */
	private List<MyscapeClient.DiaryTierItem> readDiaryTiers()
	{
		List<MyscapeClient.DiaryTierItem> items = new ArrayList<>();
		for (Map.Entry<String, int[]> entry : DIARY_TIER_VARBITS.entrySet())
		{
			String area = entry.getKey();
			int[] tierVarbits = entry.getValue();
			int[] completeValues = DIARY_TIER_COMPLETE_VALUES.get(area);
			String highestTier = null;
			for (int i = 0; i < tierVarbits.length; i++)
			{
				if (client.getVarbitValue(tierVarbits[i]) < completeValues[i])
				{
					break;
				}
				highestTier = DIARY_TIER_NAMES[i];
			}
			if (highestTier != null)
			{
				items.add(new MyscapeClient.DiaryTierItem(area, highestTier));
			}
		}
		return items;
	}

	// The whole Costume Room's contents (every piece of furniture
	// combined -- see PohItem's own comment above) live behind
	// this one dynamically-assigned containerId. It's not one of
	// RuneLite's named InventoryID constants -- gameval only names the
	// fixed, game-load-time-assigned containers, while this one is
	// assigned at runtime by Jagex's newer, consolidated Costume Room
	// interface script (InterfaceID.POH_COSTUMES) -- so unlike every
	// other containerId check in onItemContainerChanged below, there's
	// no InventoryID.* constant to reference here. Confirmed by live
	// testing: opening each of the six pieces of furniture in turn
	// (Magic Wardrobe, Armour Case, Fancy Dress Box, Toy Box, Treasure
	// Chest, Magic Cape Rack) fired ItemContainerChanged with this
	// exact id every time, with identical contents regardless of which
	// piece was opened.
	private static final int POH_COSTUME_ROOM_CONTAINER_ID = 33405;

	@Subscribe
	public void onItemContainerChanged(ItemContainerChanged event)
	{
		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}
		if (applyContainerSnapshot(event.getContainerId(), container))
		{
			scheduleSyncDebounced();
		}
	}

	/** The actual per-container dispatch onItemContainerChanged above
	 * runs -- pulled out into its own method so onGameTick's deferred
	 * empty-storage reread below (see pendingContainerRereadTicks' own
	 * field doc) can apply exactly the same snapshot logic to a
	 * container it read directly off `client`, without onItemContainerChanged
	 * ever having fired for it. Returns whether `containerId` was one
	 * this plugin actually recognized (and therefore updated a
	 * latest*Items field for) -- both call sites use that to decide
	 * whether a sync is actually worth scheduling. */
	private boolean applyContainerSnapshot(int containerId, ItemContainer container)
	{
		if (containerId == InventoryID.BANK)
		{
			latestBankItems = readBankItems(container);
			// Whether a Rune Pouch is sitting in the BANK at all can
			// change here too (depositing/withdrawing it) -- same
			// reasoning as the INV branch just below, now that a pouch
			// can be banked (see playerOwnsRunePouch's own comment).
			runePouchDirty = true;
		}
		else if (containerId == InventoryID.INV)
		{
			latestInventoryItems = readInventoryItems(container);
			// Whether a Rune Pouch is even in the inventory at all (not
			// just what it contains) can change here too -- picking one
			// up, dropping it, selling it, trading it away. That's not
			// something a Rune Pouch varbit change would ever announce on
			// its own (the varbits are the pouch's carried state, not its
			// presence), so this is flagged dirty the same way a genuine
			// varbit change is, and re-read defensively on the next tick
			// (see playerOwnsRunePouch/readRunePouchItems) rather than
			// only trusting onVarbitChanged below.
			runePouchDirty = true;
		}
		else if (containerId == InventoryID.WORN)
		{
			latestEquipmentItems = readEquipmentItems(container);
		}
		else if (containerId == InventoryID.LOOTING_BAG)
		{
			latestLootingBagItems = readLootingBagItems(container);
		}
		else if (containerId == InventoryID.SEED_VAULT)
		{
			latestSeedVaultItems = readSeedVaultItems(container);
		}
		else if (containerId == InventoryID.TACKLE_BOX)
		{
			latestTackleBoxItems = readTackleBoxItems(container);
		}
		else if (containerId == InventoryID.FORESTRY_KIT)
		{
			latestForestryKitItems = readForestryKitItems(container);
		}
		else if (containerId == InventoryID.SEED_BOX)
		{
			latestSeedBoxItems = readSeedBoxItems(container);
		}
		else if (containerId == InventoryID.HUNTSMANS_KIT)
		{
			latestHuntsmansKitItems = readHuntsmansKitItems(container);
		}
		else if (containerId == POH_COSTUME_ROOM_CONTAINER_ID)
		{
			latestPohItems = readPohItems(container);
		}
		else if (containerId == InventoryID.SAILING_BOAT_1_CARGOHOLD)
		{
			latestBoatCargo1Items = readBoatCargoItems(container);
		}
		else if (containerId == InventoryID.SAILING_BOAT_2_CARGOHOLD)
		{
			latestBoatCargo2Items = readBoatCargoItems(container);
		}
		else if (containerId == InventoryID.SAILING_BOAT_3_CARGOHOLD)
		{
			latestBoatCargo3Items = readBoatCargoItems(container);
		}
		else if (containerId == InventoryID.SAILING_BOAT_4_CARGOHOLD)
		{
			latestBoatCargo4Items = readBoatCargoItems(container);
		}
		else if (containerId == InventoryID.SAILING_BOAT_5_CARGOHOLD)
		{
			latestBoatCargo5Items = readBoatCargoItems(container);
		}
		else
		{
			// The Master Scroll Book was the reason this branch used to
			// carry a temporary diagnostic -- confirmed by that
			// diagnostic to genuinely never fire ItemContainerChanged at
			// all (see bookOfScrollsDumpPending's field doc below for the
			// full story), so there's nothing left here to catch.
			return false;
		}

		return true;
	}

	/** Fires the moment a kill/clue-casket/pickpocket hands over loot --
	 * published by RuneLite's own bundled "Loot Tracker" core plugin
	 * (net.runelite.client.plugins.loottracker), not a raw game-state
	 * event -- the same integration point real third-party loot-logging
	 * plugins use rather than reimplementing NPC-death loot detection
	 * from scratch (diffing ground items around a death tile, handling
	 * despawn timers, multi-target loot, ...), which is exactly why
	 * that's the dependency here too. Requires RuneLite's bundled Loot
	 * Tracker plugin to stay enabled -- if the player has turned it off,
	 * this event simply never fires, same as any other plugin that
	 * depends on it.
	 *
	 * Unlike bank/inventory/equipment/potion storage/rune pouch above,
	 * a loot event isn't "current state" to snapshot and debounce -- it's
	 * a discrete thing that already happened, so this builds and sends
	 * it immediately (via panel#syncLootInBackground) rather than
	 * folding it into scheduleSyncDebounced. See LootEvent's docstring
	 * in the Mootrius/myscape repo's app/models.py for the full
	 * rationale, including why each event carries its own
	 * client-generated id.
	 *
	 * event.getType() is RuneLite's own LootRecordType (NPC/PLAYER/
	 * EVENT/PICKPOCKET/UNKNOWN) -- passed through by name rather than
	 * switched on, so a value this code doesn't specifically know about
	 * still syncs correctly instead of being silently dropped. Every
	 * clue casket comes through with event.getName() as the bare word
	 * "Casket", no tier -- a real limitation of this event (RuneLite's
	 * own internal attempt to tier it reads from a reward-interface
	 * item container this plugin can't confirm still populates on every
	 * casket open in current-day OSRS, and empirically it's this bare
	 * "Casket" name that actually arrives here, every time). Rather
	 * than lose the tier entirely, this plugin does its own detection
	 * off the chatbox's own completion line instead -- see
	 * pendingClueTier's own comment and onChatMessage's
	 * CLUE_SCROLL_PATTERN handling below -- and relabels a "Casket"
	 * event to "Clue Scroll (<Tier>)" right here, the moment one is
	 * still pending, before this event is ever built or sent; nothing
	 * downstream (app/loot.py server-side) needs to know this
	 * relabeling happens at all, it just sees a properly tiered
	 * source_name that was never actually "Casket" to begin with.
	 * combatLevel is -1 for anything without one (a casket, a
	 * pickpocket target) -- translated to null below rather than
	 * synced as a fake level. */
	@Subscribe
	public void onLootReceived(LootReceived event)
	{
		if (panel == null)
		{
			return;
		}

		Map<Integer, Integer> quantities = new LinkedHashMap<>();
		for (ItemStack stack : event.getItems())
		{
			if (stack.getId() <= 0 || stack.getQuantity() <= 0)
			{
				continue;
			}
			quantities.merge(stack.getId(), stack.getQuantity(), Integer::sum);
		}
		if (quantities.isEmpty())
		{
			return;
		}

		List<MyscapeClient.LootEventItem> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : quantities.entrySet())
		{
			items.add(new MyscapeClient.LootEventItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}

		// See pendingClueTier's own comment: a "Casket" event this close
		// behind a "You have completed N <tier> Treasure Trails." chat
		// line IS that clue's own reward, so it gets tagged with the
		// tier here instead of going out as the bare, tier-less
		// "Casket" name -- one-shot (cleared immediately below) so a
		// later, unrelated casket open can never inherit a stale tier.
		String sourceName = event.getName();
		if ("EVENT".equals(event.getType().name()) && CASKET_EVENT_NAME.equals(sourceName)
			&& pendingClueTier != null
			&& client.getTickCount() - pendingClueTierTick <= CLUE_TIER_MAX_TICK_GAP)
		{
			sourceName = "Clue Scroll (" + pendingClueTier + ")";
		}
		pendingClueTier = null;

		Integer combatLevel = event.getCombatLevel() >= 0 ? event.getCombatLevel() : null;
		MyscapeClient.LootEvent lootEvent = new MyscapeClient.LootEvent(
			UUID.randomUUID().toString(),
			sourceName,
			event.getType().name().toLowerCase(),
			combatLevel,
			Instant.now().toString(),
			items
		);
		panel.syncLootInBackground(lootEvent);
	}

	// Set (on the client thread, from onChatMessage's CLUE_SCROLL_PATTERN
	// handling below) the moment a "You have completed N <tier> Treasure
	// Trails." chatbox line is seen -- the capitalized tier name
	// ("Hard"), or null when there's no clue completion still waiting to
	// be paired with its own casket-opening LootReceived event. Read
	// (and unconditionally cleared) the next time onLootReceived sees a
	// "Casket" event, using pendingClueTierTick below to decide whether
	// it's still fresh enough to trust -- see onLootReceived's own
	// comment for why this pairing happens there rather than acting on
	// the chat line alone (the chat line carries a tier but no items;
	// only the paired LootReceived event carries the actual reward
	// items to attach that tier to).
	private volatile String pendingClueTier;

	// The client tick (see client.getTickCount()) pendingClueTier was
	// last set on -- compared against the current tick in
	// onLootReceived, not just checked for non-null, so a "Casket"
	// event that shows up long after some earlier, already-stale clue
	// completion message (the player opened a DIFFERENT casket later,
	// say) can't wrongly inherit that old tier.
	private int pendingClueTierTick;

	// How many client ticks (~0.6s each) a pending clue tier (see
	// pendingClueTier above) stays valid for pairing with the next
	// Casket LootReceived event -- generous enough to cover ordinary
	// client/network jitter between the chat line and the loot event
	// landing (both fire off the same single "open casket" action, so
	// in practice they land the same tick or the next one), tight
	// enough that ambling off and opening some other casket minutes
	// later can't accidentally inherit a leftover tier.
	private static final int CLUE_TIER_MAX_TICK_GAP = 5;

	// The exact, bare event name RuneLite's own bundled Loot Tracker
	// plugin gives every clue casket open (see onLootReceived's own
	// comment) -- kept as a named constant here (rather than an inline
	// literal) purely so onLootReceived's own tier-relabeling check and
	// this comment can't silently drift apart from each other.
	private static final String CASKET_EVENT_NAME = "Casket";

	/** Matches a chatbox kill-count announcement ("Your Zulrah kill
	 * count is: 1,234.", "Your subdued Duke Sucellus count is: 12.",
	 * "Your completed Chambers of Xeric count is: 30.", "Your
	 * completion count for Tempoross is: 50.", etc.) -- byte-for-byte
	 * the same KILLCOUNT_PATTERN RuneLite's own core
	 * ChatCommandsPlugin uses for its !kc chat command (confirmed
	 * against RuneLite's GitHub source,
	 * runelite-client/.../chatcommands/ChatCommandsPlugin.java), so
	 * this plugin doesn't need its own per-boss wording list -- every
	 * boss/activity's phrasing RuneLite itself recognizes is already
	 * covered by this one pattern. The boss name is forwarded to
	 * myscape.io exactly as matched (see onChatMessage below); any
	 * renaming/normalization (e.g. "Barrows chest" -> "Barrows
	 * Chests", mirroring KILLCOUNT_RENAMES in RuneLite's own
	 * ChatCommandsPlugin) happens server-side -- see
	 * CHAT_NAME_OVERRIDES in app/bosses.py in the Mootrius/myscape
	 * repo -- so this plugin stays free of its own copy of that table
	 * to keep in sync as RuneLite adds bosses. */
	/** Matches the chatbox line the game itself prints the instant a
	 * Treasure Trail is completed ("You have completed 15 hard Treasure
	 * Trails."), used to detect a clue casket's tier (see
	 * pendingClueTier's own comment above and onLootReceived, where the
	 * tier this captures actually gets attached to that casket's loot
	 * event) since RuneLite's own LootReceived event never carries one
	 * (see onLootReceived's own docstring). Byte-for-byte the same
	 * CLUE_SCROLL_PATTERN RuneLite's own bundled Loot Tracker plugin
	 * uses internally for its own (here, unconfirmed-reliable) tiering
	 * attempt (net.runelite.client.plugins.loottracker.
	 * LootTrackerPlugin), just with the tier group loosened to
	 * [a-zA-Z]+ rather than assumed-lowercase [a-z]+ -- this plugin
	 * capitalizes the captured word itself (see onChatMessage below)
	 * rather than trust the exact casing the live chat line happens to
	 * use. */
	private static final Pattern CLUE_SCROLL_PATTERN =
		Pattern.compile("You have completed [0-9,]+ ([a-zA-Z]+) Treasure Trails?\\.");

	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
		"Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? (?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?<col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");

	/** This plugin's whole source of live kill-count data -- see the
	 * Mootrius/myscape repo's app/routers/linking.py (fetches an
	 * OSRS-hiscores baseline once, the moment a character links or
	 * re-links) and app/hiscores.py::apply_kill_count_updates (applies
	 * what this method reports) for the other half of the design. No
	 * interval polling anywhere in this plugin: every kill count is
	 * either that one link-time baseline or a reading straight out of
	 * the chatbox the instant the game itself announces it, which is
	 * as fresh and authoritative as a number can be -- see
	 * KILLCOUNT_PATTERN's own comment for where the regex came from.
	 *
	 * GAMEMESSAGE/SPAM are the two ChatMessageTypes a kill-count line
	 * actually arrives as (same two RuneLite's own ChatCommandsPlugin
	 * checks for this case -- it also allows TRADE and
	 * FRIENDSCHATNOTIFICATION, but those back a different feature of
	 * its own, parsing *other* players' broadcast chat, which has no
	 * equivalent here).
	 *
	 * KILLCOUNT_PATTERN is written loosely enough to also match a
	 * handful of unrelated "Your ... is: <number>" chat lines that
	 * aren't actually a kill-count report -- pre/post both being empty
	 * is how RuneLite's own ChatCommandsPlugin tells those apart (see
	 * its onChatMessage), so this mirrors that same guard rather than
	 * risking a false positive getting synced as some boss's kill
	 * count. */
	@Subscribe
	public void onChatMessage(ChatMessage event)
	{
		if (panel == null)
		{
			return;
		}

		if (event.getType() != ChatMessageType.GAMEMESSAGE && event.getType() != ChatMessageType.SPAM)
		{
			return;
		}

		// See pendingClueTier's own comment -- this only ever records a
		// tier for onLootReceived's own "Casket" event to pick up right
		// after; it doesn't sync anything by itself.
		Matcher clueMatcher = CLUE_SCROLL_PATTERN.matcher(event.getMessage());
		if (clueMatcher.find())
		{
			String tier = clueMatcher.group(1);
			if (tier != null && !tier.isEmpty())
			{
				pendingClueTier = Character.toUpperCase(tier.charAt(0)) + tier.substring(1).toLowerCase();
				pendingClueTierTick = client.getTickCount();
			}
			return;
		}

		Matcher matcher = KILLCOUNT_PATTERN.matcher(event.getMessage());
		if (!matcher.find())
		{
			return;
		}

		String boss = matcher.group("boss");
		String pre = matcher.group("pre");
		String post = matcher.group("post");
		if ((pre == null || pre.isEmpty()) && (post == null || post.isEmpty()))
		{
			return;
		}
		if (boss == null || boss.isEmpty())
		{
			return;
		}

		int killCount;
		try
		{
			killCount = Integer.parseInt(matcher.group("kc").replace(",", ""));
		}
		catch (NumberFormatException e)
		{
			return;
		}

		panel.syncKillCountInBackground(boss, killCount);
	}

	// Set (on the client thread, from onVarbitChanged below) the moment
	// one of Potion Storage's own varps fires, and consumed on the next
	// onGameTick rather than acted on immediately -- deferring by one
	// tick mirrors how RuneLite's own bundled Bank Tags plugin handles
	// the exact same varps (it defers to the next client tick before
	// rebuilding), rather than assuming it's safe to read Potion
	// Storage's backing script state mid-event. Both the write (here)
	// and the read (onGameTick) only ever happen on the client thread,
	// so this needs no synchronization of its own.
	private boolean potionStorageDirty;

	// How many ticks it's been since Potion Storage was last actually
	// re-read (by either path -- the dirty flag above or this counter
	// hitting its own interval), reset to 0 by both. Only ever touched
	// from onGameTick, on the client thread, same as potionStorageDirty.
	// See POTION_STORAGE_REFRESH_INTERVAL_TICKS's own comment for why
	// this periodic fallback exists at all.
	private int ticksSincePotionStorageRefresh;

	// Same deal, for a Rune Pouch's contents -- also set directly from
	// onItemContainerChanged above (see its comment) when the pouch's
	// own presence in the inventory could have changed, not just when
	// this listener below observes it.
	private boolean runePouchDirty;

	// Same deal, for skills -- set from onStatChanged below, which fires
	// once per skill on login (as every skill's real value first becomes
	// known) and again any time a skill's xp/level actually changes,
	// deliberately NOT any time its BOOSTED level alone changes (a
	// potion/prayer wearing off shouldn't trigger a resync -- see
	// onStatChanged's own comment).
	private boolean skillsDirty;

	// Same deal, for completed quests -- set from onWidgetLoaded below
	// when the "quest complete" scroll interface loads, the one reliable
	// generic signal that SOME quest just finished (there's no single
	// varp/varbit that changes for every quest the way there is for
	// bank/potion storage/rune pouch above -- each quest tracks its own
	// completion in its own varps, which is exactly what
	// Quest#getState reads per-quest, not something this plugin
	// duplicates here).
	private boolean questsDirty;

	// Same deal, for achievement diary tiers -- set from onVarbitChanged
	// below when one of the 48 diary "tier complete" varbits themselves
	// changes (see DIARY_VARBIT_IDS), unlike quests above where no single
	// generic signal exists.
	private boolean diariesDirty;

	// Same deal, for combat achievements -- set from onVarbitChanged
	// below when one of the six COMBAT_ACHIEVEMENT_COMPLETED_VARBITS
	// changes. Consumed by patchCombatAchievementStats below, which only
	// ever patches combatTasksCompleted, never combatTasksTotal -- unlike
	// the 48 diary varbits (a fixed, essentially-never-changing count of
	// 48 tiers total) there's no equivalent "how many combat tasks exist
	// in total" varbit, and that number genuinely does grow as Jagex
	// adds new bosses/tasks, so combatTasksTotal is left alone here and
	// only ever updated by the Character Summary panel read
	// (readCharacterSummary) the way it always was.
	private boolean combatAchievementsDirty;

	// Same deal, for the collection log -- set from onVarbitChanged below
	// when VarPlayerID.COLLECTION_COUNT or COLLECTION_COUNT_MAX itself
	// changes. Unlike combat achievements above, both completed AND
	// total have their own live varp here, so patchCollectionStats below
	// patches both fields together.
	private boolean collectionsDirty;

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varpId = event.getVarpId();
		if (varpId >= POTION_STORAGE_VARP_MIN && varpId <= POTION_STORAGE_VARP_MAX)
		{
			potionStorageDirty = true;
		}
		if (varpId == VarPlayerID.COLLECTION_COUNT || varpId == VarPlayerID.COLLECTION_COUNT_MAX)
		{
			collectionsDirty = true;
		}

		int varbitId = event.getVarbitId();
		if (isRunePouchVarbit(varbitId))
		{
			runePouchDirty = true;
		}
		if (DIARY_VARBIT_IDS.contains(varbitId))
		{
			diariesDirty = true;
		}
		if (COMBAT_ACHIEVEMENT_VARBIT_IDS.contains(varbitId))
		{
			combatAchievementsDirty = true;
		}
		if (varbitId == VarbitID.COLLECTION_POH_HOST_BOOK_OPEN)
		{
			collectionLogItemCapture.onAdventureLogVarbitChanged(client);
		}
	}

	/** Fires the moment the collection log widget's own setup script
	 * runs -- both the first time a player opens it this session and
	 * (as a side effect of collectionLogItemCapture's own re-trigger)
	 * every time after, so collectionLogItemCapture itself is what
	 * tells those two cases apart (see its onCollectionLogOpened doc).
	 * This is the entry point for the whole per-item obtained/quantity
	 * capture -- see collectionLogItemCapture's own class doc for why
	 * this can't just be read passively the way structure
	 * (CollectionLogSchemaExporter) can. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		collectionLogItemCapture.onCollectionLogOpened(event.getScriptId(), client);
	}

	/** Fires once per obtained collection log item, as a result of
	 * onScriptPostFired's own triggered refresh above -- see
	 * collectionLogItemCapture's own onScriptPreFired doc for the
	 * script id/argument shape. */
	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		collectionLogItemCapture.onScriptPreFired(
			event.getScriptId(), event.getScriptEvent().getArguments(), client);
	}

	/** Fires once per skill the instant its xp, real level, or boosted
	 * level changes -- including, notably, a temporary boost/drain from a
	 * potion or prayer alone, with xp/real level unchanged. Only THOSE
	 * two (xp or real level) actually moving is worth a resync (a skill
	 * goal tracks real progress, never a transient boost -- see
	 * readSkills' own comment), but this still marks skillsDirty on every
	 * firing rather than filtering here: readSkills re-reads all 24
	 * skills in one pass regardless of which one changed, so a spurious
	 * boost-only firing costs nothing beyond scheduling a debounce that
	 * (correctly) resends the exact same numbers as last time. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		skillsDirty = true;
	}

	/** Fires whenever any game interface loads -- checked here for
	 * specifically the "quest complete" scroll (InterfaceID.QUESTSCROLL,
	 * RuneLite's own named id for it), the interface OSRS shows the
	 * instant a quest is finished. This is the trigger for WHEN to
	 * recheck, not the data itself -- readCompletedQuestNames (via
	 * Quest#getState) is what actually determines which quests are done,
	 * same division of labor as onVarbitChanged/readDiaryTiers below.
	 *
	 * Also sets masterScrollBookDirty the moment
	 * InterfaceID.BOOKOFSCROLLS loads -- see that field's own comment
	 * and readMasterScrollBookItems below for why this is the only
	 * signal available for the Master Scroll Book, unlike every other
	 * source in this file.
	 *
	 * Also queues an explicit re-read of whichever ItemContainer(s)
	 * back the interface that just loaded, for every "check it" widget
	 * VIRTUAL_STORAGE_WIDGET_CONTAINER_IDS (or, for the Cargo Hold, the
	 * boat-slot branch below) knows about -- see
	 * pendingContainerRereadTicks' own field doc for the empty-storage
	 * bug this exists to fix, and onGameTick below for where the actual
	 * re-read happens. */
	@Subscribe
	public void onWidgetLoaded(WidgetLoaded event)
	{
		int groupId = event.getGroupId();
		if (groupId == InterfaceID.QUESTSCROLL)
		{
			questsDirty = true;
		}
		else if (groupId == InterfaceID.BOOKOFSCROLLS)
		{
			masterScrollBookDirty = true;
		}
		else if (groupId == InterfaceID.ACCOUNT_SUMMARY_SIDEPANEL)
		{
			characterSummaryRereadTicksLeft = CHARACTER_SUMMARY_REREAD_TICKS;
		}

		Integer virtualContainerId = VIRTUAL_STORAGE_WIDGET_CONTAINER_IDS.get(groupId);
		if (virtualContainerId != null)
		{
			queueContainerReread(virtualContainerId);
		}
		else if (groupId == InterfaceID.SAILING_BOAT_CARGOHOLD || groupId == InterfaceID.SAILING_BOAT_CARGOHOLD_SIDE)
		{
			// Whichever boat this was, its cargo hold shares this one
			// interface with the other four (see
			// VIRTUAL_STORAGE_WIDGET_CONTAINER_IDS' own field doc) --
			// there's nothing on this event that says which container id
			// is actually backing it this time, so all five get queued
			// and onGameTick below simply finds nothing to read (client.
			// getItemContainer returns null) for whichever four aren't
			// the one that was just opened.
			queueContainerReread(InventoryID.SAILING_BOAT_1_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_2_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_3_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_4_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_5_CARGOHOLD);
		}
	}

	/** Queues `containerId` to be explicitly re-read (see
	 * applyContainerSnapshot) on the next tick strictly after this one
	 * -- see pendingContainerRereadTicks' own field doc for why this
	 * exists and why it waits a tick rather than reading immediately. */
	private void queueContainerReread(int containerId)
	{
		pendingContainerRereadTicks.put(containerId, client.getTickCount());
	}

	/** Reads the Master Scroll Book's contents straight off
	 * InterfaceID.BOOKOFSCROLLS' own widgets -- the only source in this
	 * file that isn't a RuneLite ItemContainer (see this field's
	 * MyscapeClient.java comment for the two rounds of diagnostics that
	 * confirmed that). A live widget dump showed a consistent pattern
	 * across all 20 teleport scrolls this interface holds, including
	 * the several whose child component ids don't follow the tidy
	 * TELEPORTSCROLL_X/GFX_X/TEXT_X/TITLE_X stride RuneLite's own
	 * gameval constants suggest (Cerberus, Revenants, Watson, Guthixian
	 * Temple are numbered irregularly): whichever widget actually
	 * carries a real itemId is immediately followed by a sibling widget
	 * whose text is the held quantity as a plain integer ("0" for a
	 * scroll type never deposited), then one more sibling whose text is
	 * the scroll's display name. Walking every child by id and matching
	 * that adjacency avoids hardcoding 20 irregular id triples by hand.
	 * A quantity of 0 is skipped, same as an empty/untouched slot
	 * anywhere else in this file (see aggregateQuantities' own
	 * comment). Display names sometimes carry a stray "<br>" from the
	 * interface's own line-wrapping (e.g. "Arde-<br>aglais") -- stripped
	 * here rather than synced verbatim. */
	private List<MyscapeClient.MasterScrollBookItem> readMasterScrollBookItems()
	{
		List<MyscapeClient.MasterScrollBookItem> items = new ArrayList<>();
		for (int childId = 0; childId <= 0x54; childId++)
		{
			Widget iconWidget = client.getWidget(InterfaceID.BOOKOFSCROLLS, childId);
			if (iconWidget == null || iconWidget.getItemId() <= 0)
			{
				continue;
			}
			Widget quantityWidget = client.getWidget(InterfaceID.BOOKOFSCROLLS, childId + 1);
			if (quantityWidget == null)
			{
				continue;
			}
			int quantity;
			try
			{
				quantity = Integer.parseInt(quantityWidget.getText().trim());
			}
			catch (NumberFormatException e)
			{
				continue;
			}
			if (quantity <= 0)
			{
				continue;
			}
			Widget nameWidget = client.getWidget(InterfaceID.BOOKOFSCROLLS, childId + 2);
			String name = nameWidget == null ? "" : nameWidget.getText().replace("<br>", "");
			items.add(new MyscapeClient.MasterScrollBookItem(iconWidget.getItemId(), name, quantity));
		}
		return items;
	}

	// The dynamic-child index each Character Summary stat's value text
	// sits at, confirmed with a live widget dump (Widget Inspector) of
	// InterfaceID.AccountSummarySidepanel.SUMMARY_CONTENTS while the
	// "Character Summary" tab was open -- 100 dynamic children total
	// (this panel draws every skill/diary-area/CA-tier icon too, not
	// just these seven), spaced roughly 12-13 apart in a consistent
	// per-stat block. Nothing else on the panel identifies which row is
	// which without also parsing icon sprite ids, so these are
	// hardcoded rather than searched for. A future game update that
	// reshuffles this layout will show up as readCharacterSummary
	// returning null (a missing/mismatched widget) rather than a wrong
	// number -- see that method's own comment.
	private static final int SUMMARY_INDEX_COMBAT_LEVEL = 11;
	private static final int SUMMARY_INDEX_TOTAL_LEVEL = 24;
	private static final int SUMMARY_INDEX_TOTAL_XP = 36;
	private static final int SUMMARY_INDEX_QUESTS = 49;
	private static final int SUMMARY_INDEX_ACHIEVEMENTS = 62;
	private static final int SUMMARY_INDEX_COMBAT_TASKS = 75;
	private static final int SUMMARY_INDEX_COLLECTIONS = 88;

	/** Reads the seven Character Summary stats (see
	 * MyscapeClient.CharacterSummaryItem's own doc for what each one
	 * means and why questsCompleted/questsTotal stands in for quest
	 * points) straight off InterfaceID.AccountSummarySidepanel's dynamic
	 * children -- see the SUMMARY_INDEX_* constants above for how those
	 * fixed indices were confirmed. Returns null wholesale rather than a
	 * partially-filled result if the panel isn't currently open (its
	 * widget won't exist) or any single expected value fails to parse --
	 * a null here is exactly what characterSummaryRereadTicksLeft's onGameTick
	 * handler treats as "nothing new to sync this round", same as every
	 * other read* method returning an empty/unchanged result. */
	private MyscapeClient.CharacterSummaryItem readCharacterSummary()
	{
		Widget contents = client.getWidget(InterfaceID.AccountSummarySidepanel.SUMMARY_CONTENTS);
		if (contents == null)
		{
			return null;
		}
		Widget[] children = contents.getDynamicChildren();
		if (children == null)
		{
			return null;
		}

		Double combatLevel = parseSummaryDecimal(summaryChild(children, SUMMARY_INDEX_COMBAT_LEVEL));
		Integer totalLevel = parseSummaryInt(summaryChild(children, SUMMARY_INDEX_TOTAL_LEVEL));
		Long totalXp = parseSummaryLong(summaryChild(children, SUMMARY_INDEX_TOTAL_XP));
		int[] quests = parseSummaryFraction(summaryChild(children, SUMMARY_INDEX_QUESTS));
		int[] achievements = parseSummaryFraction(summaryChild(children, SUMMARY_INDEX_ACHIEVEMENTS));
		int[] combatTasks = parseSummaryFraction(summaryChild(children, SUMMARY_INDEX_COMBAT_TASKS));
		int[] collections = parseSummaryFraction(summaryChild(children, SUMMARY_INDEX_COLLECTIONS));

		if (combatLevel == null || totalLevel == null || totalXp == null
			|| quests == null || achievements == null || combatTasks == null || collections == null)
		{
			return null;
		}

		return new MyscapeClient.CharacterSummaryItem(
			combatLevel, totalLevel, totalXp,
			quests[0], quests[1],
			achievements[0], achievements[1],
			combatTasks[0], combatTasks[1],
			collections[0], collections[1],
			// Per-tier combat achievement counts aren't shown on this
			// panel at all (just the combatTasks fraction above), so
			// this baseline read has nothing to put here -- zeroed out
			// and left for patchCombatAchievementStats (which runs
			// moments later off the same onGameTick pass, see
			// combatAchievementsDirty's own doc) to fill in for real,
			// same as this field's own doc describes.
			new int[COMBAT_ACHIEVEMENT_COMPLETED_VARBITS.length]);
	}

	/** children[index], or null if index is out of bounds -- guards
	 * readCharacterSummary above against a future layout with fewer
	 * dynamic children than SUMMARY_INDEX_COLLECTIONS expects. */
	private static Widget summaryChild(Widget[] children, int index)
	{
		return index >= 0 && index < children.length ? children[index] : null;
	}

	/** Strips a Character Summary widget's RuneScape colour markup
	 * (e.g. "<col=0dc10d>126.1</col>") and parses what's left as a
	 * decimal -- used only for Combat Level, the one stat this panel
	 * shows with a fractional part (see COMBAT_LEVEL_DECIMAL_ENABLED
	 * client-side). */
	private static Double parseSummaryDecimal(Widget widget)
	{
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		String cleaned = widget.getText().replaceAll("<[^>]*>", "").trim();
		try
		{
			return Double.parseDouble(cleaned);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Strips everything but digits from a Character Summary widget's
	 * text (colour markup, a "Total XP: " label prefix, thousands
	 * commas) and parses what's left as an int -- used for Total
	 * Level. */
	private static Integer parseSummaryInt(Widget widget)
	{
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		String digits = widget.getText().replaceAll("<[^>]*>", "").replaceAll("[^0-9]", "");
		if (digits.isEmpty())
		{
			return null;
		}
		try
		{
			return Integer.parseInt(digits);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Same as parseSummaryInt just above, but as a long -- used for
	 * Total XP, whose value ("Total XP: <col=...>503,004,594</col>")
	 * can exceed Integer.MAX_VALUE for a sufficiently played account. */
	private static Long parseSummaryLong(Widget widget)
	{
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		String digits = widget.getText().replaceAll("<[^>]*>", "").replaceAll("[^0-9]", "");
		if (digits.isEmpty())
		{
			return null;
		}
		try
		{
			return Long.parseLong(digits);
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Parses a Character Summary "X/Y" completion-count widget (Quests
	 * Completed, Achievements Completed, Combat Tasks Completed,
	 * Collections Logged all share this exact format) into a
	 * {completed, total} pair, after stripping colour markup. */
	private static int[] parseSummaryFraction(Widget widget)
	{
		if (widget == null || widget.getText() == null)
		{
			return null;
		}
		String cleaned = widget.getText().replaceAll("<[^>]*>", "").trim();
		String[] parts = cleaned.split("/");
		if (parts.length != 2)
		{
			return null;
		}
		try
		{
			return new int[] { Integer.parseInt(parts[0].trim()), Integer.parseInt(parts[1].trim()) };
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}

	/** Rebuilds latestCharacterSummary with just the field(s) one of the
	 * patchXStats methods below actually has fresh data for, copying
	 * everything else across unchanged from whatever was last known (the
	 * most recent full readCharacterSummary above, or a previous patch)
	 * -- CharacterSummaryItem's fields are all primitive and required
	 * (see its own doc), so there's no way to send a partial "some
	 * fields still unknown" summary to the server. A first successful
	 * readCharacterSummary -- i.e. the player opening the Character
	 * Summary tab at least once this session -- is what establishes the
	 * baseline these live updates patch onto; a null latestCharacterSummary
	 * means that baseline doesn't exist yet, so this is simply a no-op
	 * until it does. From that point on, patchXStats calls below can
	 * arrive in any order within the same onGameTick pass (multiple
	 * dirty flags firing on the same tick, e.g. a quest that also grants
	 * xp) and still converge correctly, since each only ever overwrites
	 * its own field(s) and carries every other field forward untouched.
	 * Must only ever be called from the client thread -- every call site
	 * below already is (onGameTick), matching the same single-writer
	 * assumption latestCharacterSummary's own volatile already relies on
	 * (see its field doc). */
	private void patchCharacterSummary(UnaryOperator<MyscapeClient.CharacterSummaryItem> patch)
	{
		MyscapeClient.CharacterSummaryItem current = latestCharacterSummary;
		if (current == null)
		{
			return;
		}
		latestCharacterSummary = patch.apply(current);
		scheduleSyncDebounced();
	}

	/** Patches combatLevel/totalLevel/totalXp onto latestCharacterSummary
	 * from the skills data skillsDirty's onGameTick handler just read,
	 * independent of whether the Character Summary panel has ever been
	 * opened this session (compare readCharacterSummary above, which
	 * needs that panel open). Combat level comes straight from
	 * RuneLite's own Player#getCombatLevel -- it already computes the
	 * real combat formula, no reason to duplicate it here -- as a plain
	 * integer rather than the panel's own optional decimal-precision
	 * display (e.g. 126 here vs. that panel's own "126.1"), which is
	 * fine: the website already rounds this to a whole number either
	 * way (see render_character_overview's own comment in the Mootrius/
	 * myscape repo's app/pages.py). Total level is the sum of all 24
	 * real (unboosted) skill levels, and total XP the sum of all 24
	 * skills' xp, both off latestSkillItems -- the exact same read
	 * readSkills just produced, so these three always agree with
	 * whatever skills the server is about to receive in the very same
	 * sync. See patchCharacterSummary's own doc for why this only does
	 * anything once a first Character Summary read has already
	 * established a baseline. */
	private void patchCombatAndTotalStats()
	{
		if (latestSkillItems == null || client.getLocalPlayer() == null)
		{
			return;
		}
		int totalLevel = 0;
		long totalXp = 0;
		for (MyscapeClient.SkillItem item : latestSkillItems)
		{
			totalLevel += item.level;
			totalXp += item.xp;
		}
		double combatLevel = client.getLocalPlayer().getCombatLevel();
		// Lambdas below can only capture effectively-final locals, so
		// totalLevel/totalXp (both accumulated in the loop above) need
		// their own final copies -- combatLevel is already effectively
		// final (a single assignment) and needs none.
		final int finalTotalLevel = totalLevel;
		final long finalTotalXp = totalXp;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			combatLevel, finalTotalLevel, finalTotalXp,
			prev.questsCompleted, prev.questsTotal,
			prev.achievementsCompleted, prev.achievementsTotal,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			prev.combatTaskTierCompleted));
	}

	/** Patches questsCompleted/questsTotal onto latestCharacterSummary
	 * from latestCompletedQuestNames (just read by questsDirty's onGameTick
	 * handler) and RuneLite's own full Quest enum -- like
	 * patchCombatAndTotalStats above, this needs no Character Summary
	 * panel open, unlike readCharacterSummary. See patchCharacterSummary's
	 * own doc for why this is a no-op before a first panel read has ever
	 * established a baseline this session. */
	private void patchQuestStats()
	{
		if (latestCompletedQuestNames == null)
		{
			return;
		}
		int completed = latestCompletedQuestNames.size();
		int total = Quest.values().length;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			completed, total,
			prev.achievementsCompleted, prev.achievementsTotal,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			prev.combatTaskTierCompleted));
	}

	/** Patches achievementsCompleted/achievementsTotal onto
	 * latestCharacterSummary from latestDiaryTierItems (just read by
	 * diariesDirty's onGameTick handler) -- each area's entry there is
	 * its HIGHEST completed tier (see readDiaryTiers' own doc: a real
	 * diary journal can't have Elite done without Easy/Medium/Hard also
	 * done), so an area at "elite" counts as 4 completed tiers here, one
	 * at "hard" counts as 3, and so on -- matching how the Character
	 * Summary panel's own "Achievement diaries completed" count counts
	 * individual tier completions, not areas. achievementsTotal is
	 * DIARY_VARBIT_IDS.size() (48: 12 areas x 4 tiers) -- a genuinely
	 * fixed number (new diary tiers are far rarer than new combat
	 * achievements, see combatAchievementsDirty's own doc), safe to
	 * derive here rather than needing the panel read the way
	 * combatTasksTotal still does. */
	private void patchDiaryStats()
	{
		if (latestDiaryTierItems == null)
		{
			return;
		}
		int completed = 0;
		for (MyscapeClient.DiaryTierItem item : latestDiaryTierItems)
		{
			for (int i = 0; i < DIARY_TIER_NAMES.length; i++)
			{
				if (DIARY_TIER_NAMES[i].equals(item.tier))
				{
					completed += i + 1;
					break;
				}
			}
		}
		int total = DIARY_VARBIT_IDS.size();
		// See patchCombatAndTotalStats's own comment on why this final
		// copy is needed: completed is accumulated in the loop above, so
		// it isn't effectively final on its own.
		final int finalCompleted = completed;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			prev.questsCompleted, prev.questsTotal,
			finalCompleted, total,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			prev.combatTaskTierCompleted));
	}

	/** Patches combatTasksCompleted onto latestCharacterSummary by
	 * summing COMBAT_ACHIEVEMENT_COMPLETED_VARBITS' six current values
	 * directly -- never combatTasksTotal, which has no equivalent live
	 * varbit and so is left exactly as the last Character Summary panel
	 * read set it (see combatAchievementsDirty's own doc). Also patches
	 * combatTaskTierCompleted with those same six values kept separate
	 * (easy..grandmaster, same order as COMBAT_ACHIEVEMENT_COMPLETED_
	 * VARBITS itself) -- Character Overview's combat achievements
	 * section needs the per-tier breakdown, not just the sum. */
	private void patchCombatAchievementStats()
	{
		int completed = 0;
		int[] tierCompleted = new int[COMBAT_ACHIEVEMENT_COMPLETED_VARBITS.length];
		for (int i = 0; i < COMBAT_ACHIEVEMENT_COMPLETED_VARBITS.length; i++)
		{
			int value = client.getVarbitValue(COMBAT_ACHIEVEMENT_COMPLETED_VARBITS[i]);
			tierCompleted[i] = value;
			completed += value;
		}
		// See patchCombatAndTotalStats's own comment on why these final
		// copies are needed.
		final int finalCompleted = completed;
		final int[] finalTierCompleted = tierCompleted;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			prev.questsCompleted, prev.questsTotal,
			prev.achievementsCompleted, prev.achievementsTotal,
			finalCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			finalTierCompleted));
	}

	/** Patches collectionsCompleted/collectionsTotal onto
	 * latestCharacterSummary from VarPlayerID.COLLECTION_COUNT/
	 * COLLECTION_COUNT_MAX directly -- unlike combat achievements above,
	 * both completed AND total have their own live varp here (it's what
	 * Jagex's own client uses to build the Character Summary panel's own
	 * "Collections logged" line in the first place), so both fields get
	 * patched together. A value of 0 for either is treated as "not
	 * populated yet" rather than a real reading -- matching a similar
	 * >0 guard in pajlads/DinkPlugin's own CollectionNotifier (a widely-
	 * used third-party RuneLite plugin that reads these same two varps),
	 * whose own comment notes this can read as unpopulated until the
	 * player has had the Character Summary tab open at some point --
	 * from then on, though, these two stay live without needing that tab
	 * open again. */
	private void patchCollectionStats()
	{
		int completed = client.getVarpValue(VarPlayerID.COLLECTION_COUNT);
		int total = client.getVarpValue(VarPlayerID.COLLECTION_COUNT_MAX);
		if (completed <= 0 || total <= 0)
		{
			return;
		}
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			prev.questsCompleted, prev.questsTotal,
			prev.achievementsCompleted, prev.achievementsTotal,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			completed, total,
			prev.combatTaskTierCompleted));
	}

	/** Whether varbitId is one of the 12 Rune Pouch type/quantity slot
	 * varbits (see RUNE_POUCH_TYPE_VARBITS/RUNE_POUCH_QUANTITY_VARBITS) --
	 * checked by exact id, not a min/max range like Potion Storage's
	 * varps above, since these 12 ids are individual varbits (cache-
	 * assigned, not necessarily contiguous with each other or exclusive
	 * of unrelated varbits in between) rather than a dedicated
	 * contiguous varp block. */
	private static boolean isRunePouchVarbit(int varbitId)
	{
		if (varbitId < 0)
		{
			return false;
		}
		for (int id : RUNE_POUCH_TYPE_VARBITS)
		{
			if (id == varbitId)
			{
				return true;
			}
		}
		for (int id : RUNE_POUCH_QUANTITY_VARBITS)
		{
			if (id == varbitId)
			{
				return true;
			}
		}
		return false;
	}

	/** (Re)schedules the single shared debounce task -- shared across
	 * bank/inventory/equipment/potion storage/rune pouch/looting bag/
	 * seed vault/tackle box/PoH/Forestry Kit/Seed Box/Master Scroll
	 * Book/skills/quests/diaries/collection log items rather than one
	 * per source, since a player actively shopping or grinding typically touches more
	 * than one of them in the same burst (withdrawing from the bank,
	 * then equipping what they withdrew; training a skill while also
	 * banking its product),
	 * and there's no reason to send several separate requests a couple
	 * of ticks apart for what's really one burst of activity. When it
	 * fires, it syncs whichever of these have actually been observed at
	 * least once (see the field docs above) -- reading only the
	 * already-captured volatile snapshots, never the RuneLite Client
	 * itself, since this runs on `executor`'s thread, not the client
	 * thread.
	 *
	 * The delay itself shrinks as a single burst goes on: it's normally
	 * the full SYNC_DEBOUNCE_SECONDS quiet period, but once the burst
	 * that started at syncBurstStartMillis has been running for close to
	 * SYNC_MAX_WAIT_SECONDS, the delay is clamped down so the task still
	 * fires at that cap rather than waiting for a quiet moment that a
	 * sustained activity (fighting, thieving, high alching) might not
	 * give it for minutes. A short burst never notices the cap -- it
	 * only bites once a burst has genuinely run long. */
	private void scheduleSyncDebounced()
	{
		long now = System.currentTimeMillis();
		if (syncBurstStartMillis < 0)
		{
			syncBurstStartMillis = now;
		}
		long elapsedSeconds = (now - syncBurstStartMillis) / 1000;
		long delaySeconds = Math.max(0, Math.min(SYNC_DEBOUNCE_SECONDS, SYNC_MAX_WAIT_SECONDS - elapsedSeconds));

		if (syncDebounceTask != null)
		{
			syncDebounceTask.cancel(false);
		}
		syncDebounceTask = executor.schedule(
			() -> {
				syncBurstStartMillis = -1;
				if (panel != null)
				{
					panel.syncInBackground(
						latestBankItems, latestInventoryItems, latestEquipmentItems,
						latestPotionStorageItems, latestRunePouchItems, latestLootingBagItems,
						latestSeedVaultItems, latestTackleBoxItems, latestPohItems,
						latestForestryKitItems, latestSeedBoxItems, latestMasterScrollBookItems,
						latestSkillItems, latestCompletedQuestNames, latestInProgressQuestNames,
						latestDiaryTierItems,
						latestHuntsmansKitItems, latestBoatCargo1Items, latestBoatCargo2Items,
						latestBoatCargo3Items, latestBoatCargo4Items, latestBoatCargo5Items,
						latestCharacterSummary, latestCollectionLogItems);
				}
			},
			delaySeconds, TimeUnit.SECONDS);
	}

	/** Adds/removes the toolbar icon the instant the player flips "Show
	 * sidebar" in the plugin's own config panel -- panel/navButton
	 * themselves are always built in startUp() regardless (see its own
	 * comment), so this never needs to touch anything but the
	 * toolbar registration. A plain string match on the key name
	 * rather than a shared constant, matching how the old apiBaseUrl
	 * setting's keyName was never referenced outside its own config
	 * annotation either -- there's only the one config value left in
	 * this group to filter on. */
	@Subscribe
	public void onConfigChanged(ConfigChanged event)
	{
		if (!MyscapeConfig.GROUP.equals(event.getGroup()) || !"showSidebar".equals(event.getKey()))
		{
			return;
		}
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(navButton);
		}
		else
		{
			clientToolbar.removeNavigation(navButton);
		}
	}

	@Subscribe
	public void onGameStateChanged(GameStateChanged event)
	{
		GameState state = event.getGameState();
		if (state == GameState.LOGIN_SCREEN || state == GameState.HOPPING || state == GameState.LOGGING_IN)
		{
			currentAccountHash = null;
			currentRuneScapeName = null;
		}
		if (panel != null)
		{
			panel.onGameStateChanged();
		}
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// The local player (and their display name) can take a tick or
		// two to become available right after GameState flips to
		// LOGGED_IN, so this keeps both cached values current rather
		// than trying to capture them exactly once at login.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			boolean justBecameAvailable = currentAccountHash == null;
			currentAccountHash = client.getAccountHash();
			if (client.getLocalPlayer() != null)
			{
				currentRuneScapeName = client.getLocalPlayer().getName();
			}

			// Either Potion Storage's own (tracked) varps fired since the
			// last tick (see onVarbitChanged above -- read one tick later
			// rather than inline there, per potionStorageDirty's field
			// doc) or the periodic fallback interval has elapsed (see
			// POTION_STORAGE_REFRESH_INTERVAL_TICKS's own doc for why
			// that exists) -- either way, read Potion Storage's current
			// contents now, on the client thread.
			if (potionStorageDirty || ++ticksSincePotionStorageRefresh >= POTION_STORAGE_REFRESH_INTERVAL_TICKS)
			{
				potionStorageDirty = false;
				ticksSincePotionStorageRefresh = 0;
				latestPotionStorageItems = readPotionStorageItems();
				scheduleSyncDebounced();
			}

			// Same deal, for a Rune Pouch (see runePouchDirty's field doc
			// for why this gets set, and readRunePouchItems for why it's
			// re-read in full here rather than incrementally).
			if (runePouchDirty)
			{
				runePouchDirty = false;
				latestRunePouchItems = readRunePouchItems();
				scheduleSyncDebounced();
			}

			// Same deal, for skills (see skillsDirty's field doc). Also
			// patches Character Overview's combatLevel/totalLevel/totalXp
			// off this same read (see patchCombatAndTotalStats's own doc)
			// -- independent of whether the Character Summary panel has
			// ever been opened this session.
			if (skillsDirty)
			{
				skillsDirty = false;
				latestSkillItems = readSkills();
				scheduleSyncDebounced();
				patchCombatAndTotalStats();
			}

			// Same deal, for completed quests (see questsDirty's field
			// doc). Also patches Character Overview's questsCompleted/
			// questsTotal off this same read (see patchQuestStats).
			if (questsDirty)
			{
				questsDirty = false;
				latestCompletedQuestNames = readCompletedQuestNames();
				latestInProgressQuestNames = readInProgressQuestNames();
				scheduleSyncDebounced();
				patchQuestStats();
			}

			// Same deal, for achievement diary tiers (see diariesDirty's
			// field doc). Also patches Character Overview's
			// achievementsCompleted/achievementsTotal off this same read
			// (see patchDiaryStats).
			if (diariesDirty)
			{
				diariesDirty = false;
				latestDiaryTierItems = readDiaryTiers();
				scheduleSyncDebounced();
				patchDiaryStats();
			}

			// Same deal, for combat achievements (see
			// combatAchievementsDirty's field doc) -- no intermediate
			// "latest*" list field needed here, unlike skills/quests/
			// diaries above, since nothing else in this file needs the
			// raw per-tier counts beyond the one sum patchCombatAchieve-
			// mentStats computes.
			if (combatAchievementsDirty)
			{
				combatAchievementsDirty = false;
				patchCombatAchievementStats();
			}

			// Same deal, for the collection log's own completed/total
			// counts (see collectionsDirty's field doc) -- distinct
			// from collectionLogItemCapture just below, which is the
			// per-item obtained/quantity state, not this pair of sums.
			if (collectionsDirty)
			{
				collectionsDirty = false;
				patchCollectionStats();
			}

			// Unlike every dirty flag above, a collection log item
			// capture pass doesn't complete on a single event -- the
			// client dribbles out one script firing per obtained item
			// with no explicit "that's all of them" signal, so this is
			// collectionLogItemCapture deciding, tick by tick, that no
			// new item has arrived in a couple of ticks and the pass
			// already in flight (if any -- most ticks there isn't one)
			// is therefore finished. See its own onGameTick doc.
			if (collectionLogItemCapture.onGameTick(client))
			{
				List<MyscapeClient.CollectionLogItem> items = new ArrayList<>();
				for (Map.Entry<Integer, Integer> entry : collectionLogItemCapture.snapshotCapturedItems().entrySet())
				{
					items.add(new MyscapeClient.CollectionLogItem(entry.getKey(), entry.getValue()));
				}
				latestCollectionLogItems = items;
				scheduleSyncDebounced();
			}

			// Same deal, for the Master Scroll Book (see
			// masterScrollBookDirty's field doc) -- set from onWidgetLoaded
			// above rather than an ItemContainerChanged branch, since this
			// is the only source in this file with no ItemContainer to key
			// off of.
			if (masterScrollBookDirty)
			{
				masterScrollBookDirty = false;
				latestMasterScrollBookItems = readMasterScrollBookItems();
				scheduleSyncDebounced();
			}

			// Same deal, for the Character Summary panel (see
			// characterSummaryRereadTicksLeft's field doc for why this is
			// a countdown rather than a one-shot flag) -- also set only
			// from onWidgetLoaded, for the same "no ItemContainer/varbit
			// to react to" reason as the Master Scroll Book just above.
			if (characterSummaryRereadTicksLeft > 0)
			{
				characterSummaryRereadTicksLeft--;
				MyscapeClient.CharacterSummaryItem summary = readCharacterSummary();
				if (summary != null)
				{
					latestCharacterSummary = summary;
					scheduleSyncDebounced();
					// readCharacterSummary above deliberately zeroes
					// combatTaskTierCompleted (see its own comment) and
					// relies on patchCombatAchievementStats to fill it
					// in for real -- but that only otherwise runs when
					// combatAchievementsDirty happens to already be
					// true, which isn't guaranteed on the very tick this
					// baseline is first established (nor on any tick
					// before the player next completes a fresh combat
					// achievement task). Calling it here too means the
					// per-tier breakdown is correct from the moment the
					// Character Summary panel is first opened, not just
					// from the next task completed afterward -- the gap
					// this closes is exactly why an account with
					// pre-existing combat achievement progress could
					// open the panel and still see nothing but em
					// dashes on the website's Combat Achievements
					// section.
					patchCombatAchievementStats();
				}
			}

			// Consumes pendingContainerRereadTicks (see its own field doc,
			// and onWidgetLoaded above for how entries get queued): once
			// this tick is strictly after the tick a storage's widget
			// loaded on, its container is read explicitly now rather
			// than relying on ItemContainerChanged, which the game
			// engine skips firing when a virtual storage was already
			// empty. Most containers are already populated by that next
			// tick, but not always guaranteed to be (see
			// MAX_CONTAINER_REREAD_WAIT_TICKS' own doc) -- a still-null
			// read keeps its entry queued and is retried on the ticks
			// after, up to that cap, rather than giving up on the first
			// miss. Removes each entry through the iterator itself (not
			// a plain Map.remove call, which would throw
			// ConcurrentModificationException mid-iteration) only once
			// it's either successfully applied or past that cap, so a
			// successful re-read still only ever fires once per queued
			// open.
			if (!pendingContainerRereadTicks.isEmpty())
			{
				int currentTick = client.getTickCount();
				Iterator<Map.Entry<Integer, Integer>> pendingIterator = pendingContainerRereadTicks.entrySet().iterator();
				while (pendingIterator.hasNext())
				{
					Map.Entry<Integer, Integer> pending = pendingIterator.next();
					int containerId = pending.getKey();
					int queuedTick = pending.getValue();
					if (currentTick > queuedTick)
					{
						ItemContainer container = client.getItemContainer(containerId);
						if (container != null)
						{
							pendingIterator.remove();
							if (applyContainerSnapshot(containerId, container))
							{
								scheduleSyncDebounced();
							}
						}
						else if (currentTick - queuedTick > MAX_CONTAINER_REREAD_WAIT_TICKS)
						{
							pendingIterator.remove();
						}
					}
				}
			}

			// onGameStateChanged (below) fires the instant the game
			// state flips to LOGGED_IN, but getAccountHash() isn't
			// populated until this tick handler runs afterward -- so
			// the panel's very first re-check, done from that event,
			// always read a stale null and got stuck showing "log in
			// first" even once the player genuinely was logged in (this
			// also covers starting the plugin while already in-game,
			// where no GameStateChanged fires at all). Telling the
			// panel again right here, the moment the hash actually
			// becomes available, is what the state-changed notification
			// was supposed to guarantee in the first place.
			if (justBecameAvailable)
			{
				if (panel != null)
				{
					panel.onGameStateChanged();
				}

				// Unlike the bank (which only ever reports contents once
				// the player has opened it, and so is left to
				// onItemContainerChanged), inventory and worn equipment
				// are always populated the moment a character is logged
				// in -- capturing them here, on the client thread, means
				// a fresh login syncs an accurate "already own" snapshot
				// right away rather than waiting for the player to
				// happen to touch either one first. This runs on the
				// client thread (onGameTick is only ever called there),
				// so reading the containers directly here is safe.
				ItemContainer inventory = client.getItemContainer(InventoryID.INV);
				if (inventory != null)
				{
					latestInventoryItems = readInventoryItems(inventory);
				}
				ItemContainer equipment = client.getItemContainer(InventoryID.WORN);
				if (equipment != null)
				{
					latestEquipmentItems = readEquipmentItems(equipment);
				}
				// Potion Storage has no login-time freebie the way
				// inventory/equipment do (nothing hands back its
				// contents just because the player logged in) -- but it
				// has the opposite problem left unhandled before: with
				// no ItemContainer of its own, the ONLY thing that ever
				// triggered a read was potionStorageDirty, so a session
				// that never happened to touch a tracked base-var potion
				// left it unread for the whole session. Reading it once
				// here guarantees every session starts with a real
				// snapshot regardless of what does or doesn't get
				// deposited/withdrawn afterward; the periodic fallback
				// (see POTION_STORAGE_REFRESH_INTERVAL_TICKS) keeps it
				// honest from here on.
				latestPotionStorageItems = readPotionStorageItems();
				ticksSincePotionStorageRefresh = 0;
				// Same reasoning as inventory/equipment just above: a Rune
				// Pouch stocked before this plugin ever loaded (or before
				// this login) has no "change" event left to fire for
				// state that was already set, so it's captured here too
				// rather than only ever reacting to onVarbitChanged/
				// onItemContainerChanged from this point forward.
				latestRunePouchItems = readRunePouchItems();
				// Same reasoning again, for skills/quests/diaries: all
				// three are already fully known the instant login
				// finishes (skill levels don't need to be trained this
				// session, quests/diaries don't need to be completed this
				// session, to be worth reporting) -- captured here rather
				// than only ever reacting to onStatChanged/onWidgetLoaded/
				// onVarbitChanged from this point forward, so a fresh
				// install reports a player's already-existing progress
				// right away instead of waiting for the next change in
				// each.
				latestSkillItems = readSkills();
				latestCompletedQuestNames = readCompletedQuestNames();
				latestInProgressQuestNames = readInProgressQuestNames();
				latestDiaryTierItems = readDiaryTiers();
				scheduleSyncDebounced();
			}
		}
	}

	/** The account hash to link with, or null if not currently logged
	 * into the game -- safe to call from the Swing EDT (see the field
	 * comment above). */
	Long getAccountHash()
	{
		return currentAccountHash;
	}

	/** The current display name, or null if not yet known -- purely
	 * informational (sent once at link time so the dashboard has
	 * something to show before the server hears from RuneLite again;
	 * the account hash, not this, is what actually identifies the
	 * account). Safe to call from the Swing EDT. */
	String getRuneScapeName()
	{
		return currentRuneScapeName;
	}

	/** Fired once a capture attempt from captureCharacterModel below
	 * finishes, one way or the other -- always on the RuneLite client
	 * thread (see that method's own doc), never the Swing EDT.
	 * MyscapePanel's own call site is what hops back to the EDT for
	 * anything UI-facing, the same split every other MyscapeClient
	 * callback interface (SyncCallback, LinkCallback, ...) already
	 * leaves to its own callers. */
	interface ModelCaptureCallback
	{
		void onSuccess(byte[] glb);

		void onFailure(String message);
	}

	/** Captures the local player's current model -- base skeleton,
	 * every equipped item, and current animation pose, all already
	 * composited by the game engine exactly as
	 * {@code Renderable#getModel()} hands it to the client's own
	 * renderer -- as a standalone .glb file, for MyscapePanel's
	 * "Export 3D model" button (see MyscapeClient#syncCharacterModel
	 * for what happens to the bytes next). See package
	 * io.myscape.plugin.modelexport's own docs (ModelExporter,
	 * ModelMeshBuilder, GlbWriter) for the actual conversion.
	 *
	 * <p>A Model's own vertex/face arrays are only valid to read while
	 * still on the client thread (RuneLite reuses/pools the backing
	 * arrays across frames for some renderables -- see
	 * ModelMeshBuilder's own top-of-file doc in the modelexport
	 * package), so the whole read-and-convert pass runs inside one
	 * clientThread.invoke: by the time callback fires, the live Model
	 * has already been fully copied out into a plain byte[], safe to
	 * hand off anywhere. callback itself still runs on the client
	 * thread, not the Swing EDT -- see ModelCaptureCallback's own doc
	 * above. */
	void captureCharacterModel(ModelCaptureCallback callback)
	{
		clientThread.invoke(() ->
		{
			Player localPlayer = client.getLocalPlayer();
			if (localPlayer == null)
			{
				callback.onFailure("Log into the game first.");
				return;
			}
			Model model = localPlayer.getModel();
			if (model == null)
			{
				callback.onFailure("Character isn't ready to capture yet -- try again in a moment.");
				return;
			}
			try
			{
				// The live client's own TextureProvider, where one's
				// available, for both its brightness setting (rather than
				// always falling back to ModelExporter's own 0.8 default --
				// see HslColor#DEFAULT_BRIGHTNESS's own doc in the
				// modelexport package for why that matters to a player
				// who's actually moved that slider) and its real texture
				// pixel data (see TextureExtractor's own doc) -- without a
				// TextureProvider this still captures geometry/color fine,
				// just with every textured face falling back to flat
				// vertex-color shading instead of its real texture image.
				TextureProvider textureProvider = client.getTextureProvider();
				byte[] glb = textureProvider != null
					? ModelExporter.captureGlb(model, textureProvider.getBrightness(), textureProvider)
					: ModelExporter.captureGlb(model);
				callback.onSuccess(glb);
			}
			catch (Exception e)
			{
				log.warn("Failed to capture character model", e);
				callback.onFailure("Something went wrong capturing your character model.");
			}
		});
	}
}
