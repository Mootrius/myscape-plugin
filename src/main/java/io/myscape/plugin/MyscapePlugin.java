package io.myscape.plugin;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import lombok.extern.slf4j.Slf4j;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.Quest;
import net.runelite.api.QuestState;
import net.runelite.api.Skill;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.StatChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.events.WidgetLoaded;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
import net.runelite.api.widgets.Widget;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
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
 */
@Slf4j
@PluginDescriptor(
	name = "myscape",
	description = "Links this client to your myscape.io account for purchase-goal and loot tracking.",
	tags = {"myscape", "loot", "tracker", "purchase", "goals", "ge", "grand exchange"}
)
public class MyscapePlugin extends Plugin
{
	@Inject
	private Client client;

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

	// The website has its own independent way to unlink (a session-
	// authenticated "Unlink" button on /dashboard -- see revoke_link()
	// in the Mootrius/myscape repo's app/routers/linking.py), specifically
	// so a lost or invalid plugin API key is never a dead end. That
	// means this client can be unlinked without ever being told
	// directly, the same way this plugin unlinking doesn't reach an
	// already-open browser tab either. Polling periodically -- rather
	// than only checking once in startUp() below -- is what catches
	// that, so the panel doesn't keep claiming "Linked" long after the
	// website says otherwise.
	private static final int LINK_STATUS_POLL_SECONDS = 30;

	// The 30-second baseline above is deliberately loose -- it runs for
	// every connected client regardless of whether anyone's even looking
	// at this panel right now, so it has to stay cheap at scale. But
	// staying loose is exactly what made an Unlink clicked on the
	// website feel laggy to notice here: the site's own mirror-image
	// poller (the footer's unlinkPollInterval in app/pages.py, for the
	// opposite direction) runs every 3 seconds, so unlinking from the
	// plugin reads as near-instant on the website while unlinking from
	// the website could sit showing "Linked" here for up to half a
	// minute. This is that same 3-second cadence, but only ever running
	// while the player actually has this panel open (see
	// MyscapePanel#onActivate/onDeactivate below, which start and stop
	// it) -- the baseline poll above still covers everyone else, so
	// this doesn't multiply the steady-state request volume, just gives
	// whoever's actively looking at "Linked as X" the same near-instant
	// feedback the website already has.
	private static final int LINK_STATUS_ACTIVE_POLL_SECONDS = 3;

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
	// DIARY_KARAMJA_EASY/MEDIUM/HARD).
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

	private MyscapePanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> linkStatusPollTask;
	// Only non-null while the panel is the active sidebar tab -- see
	// onPanelActivated/onPanelDeactivated below, and
	// LINK_STATUS_ACTIVE_POLL_SECONDS' own comment for why this exists
	// alongside linkStatusPollTask rather than replacing it.
	private ScheduledFuture<?> activeLinkStatusPollTask;
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
	// Same deal, for skills/quests/diaries -- unlike Potion Storage/Rune
	// Pouch, though, these are never left null for long once logged in:
	// see onGameTick's justBecameAvailable block, which reads all three
	// the moment login is detected, the same reasoning that block already
	// applies to inventory/equipment/rune pouch (nothing to "change" from
	// a cold start -- there's already a real level/quest/diary state to
	// report from the first tick).
	private volatile List<MyscapeClient.SkillItem> latestSkillItems;
	private volatile List<String> latestCompletedQuestNames;
	private volatile List<MyscapeClient.DiaryTierItem> latestDiaryTierItems;

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

	@Provides
	MyscapeConfig provideConfig(ConfigManager configManager)
	{
		return configManager.getConfig(MyscapeConfig.class);
	}

	@Override
	protected void startUp()
	{
		MyscapeClient apiClient = new MyscapeClient(okHttpClient, config, configManager);
		panel = new MyscapePanel(apiClient, this);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("myscape")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		clientToolbar.addNavigation(navButton);

		panel.refreshLinkStatus();

		linkStatusPollTask = executor.scheduleWithFixedDelay(
			() -> {
				if (panel != null)
				{
					panel.recheckLinkStatusInBackground();
					// Same cadence as the link-status check just above --
					// keeps the Status section's per-storage rows honest
					// even for a storage nothing in this session has
					// triggered a resync of (see
					// MyscapePanel#refreshSyncStatusInBackground).
					panel.refreshSyncStatusInBackground();
				}
			},
			LINK_STATUS_POLL_SECONDS, LINK_STATUS_POLL_SECONDS, TimeUnit.SECONDS);
	}

	/** Called by MyscapePanel#onActivate whenever it becomes the
	 * sidebar's visible tab (opening it fresh, or switching back to it
	 * from another plugin's panel) -- starts the faster
	 * LINK_STATUS_ACTIVE_POLL_SECONDS poll for as long as it stays the
	 * one showing, on top of (not instead of) the slow baseline poll
	 * from startUp() above. Fires an immediate check first (zero
	 * initial delay) rather than waiting out the first interval, so
	 * reopening the panel after unlinking elsewhere doesn't sit showing
	 * stale "Linked" state for up to another 3 seconds on top of
	 * whatever it already missed while closed. Guarded against already
	 * running so a stray extra onActivate (RuneLite's own, not
	 * something this plugin causes deliberately) can't stack a second
	 * poll on top of the first. */
	void onPanelActivated()
	{
		if (activeLinkStatusPollTask != null)
		{
			return;
		}
		activeLinkStatusPollTask = executor.scheduleWithFixedDelay(
			() -> {
				if (panel != null)
				{
					panel.recheckLinkStatusInBackground();
					panel.refreshSyncStatusInBackground();
				}
			},
			0, LINK_STATUS_ACTIVE_POLL_SECONDS, TimeUnit.SECONDS);
	}

	/** Called by MyscapePanel#onDeactivate whenever it stops being the
	 * sidebar's visible tab (switching to a different plugin's panel,
	 * or closing the sidebar outright) -- stops the faster poll started
	 * by onPanelActivated above, leaving just the slow baseline poll
	 * running for as long as the plugin stays enabled. */
	void onPanelDeactivated()
	{
		if (activeLinkStatusPollTask != null)
		{
			activeLinkStatusPollTask.cancel(false);
			activeLinkStatusPollTask = null;
		}
	}

	@Override
	protected void shutDown()
	{
		if (linkStatusPollTask != null)
		{
			linkStatusPollTask.cancel(false);
			linkStatusPollTask = null;
		}
		if (activeLinkStatusPollTask != null)
		{
			activeLinkStatusPollTask.cancel(false);
			activeLinkStatusPollTask = null;
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
		latestDiaryTierItems = null;
		masterScrollBookDirty = false;
		latestMasterScrollBookItems = null;
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

	/** This account's highest completed tier per achievement diary area,
	 * from RuneLite's own per-tier "complete" varbits (see
	 * DIARY_TIER_VARBITS above) -- an area with nothing completed yet is
	 * simply left out of the result, matching how Player.diary_tiers only
	 * ever stores one current tier per area server-side (see that same
	 * docstring). Each area's 4 varbits are checked low-to-high and this
	 * stops at the first one that ISN'T set, since the real diary journal
	 * only ever lets a player finish a tier once every lower tier is
	 * already done -- there's no such thing as Hard being complete while
	 * Medium isn't, so nothing here needs to handle that. Must only ever
	 * be called from the client thread, same as the other readXItems
	 * methods above (both call sites below already guarantee that). */
	private List<MyscapeClient.DiaryTierItem> readDiaryTiers()
	{
		List<MyscapeClient.DiaryTierItem> items = new ArrayList<>();
		for (Map.Entry<String, int[]> entry : DIARY_TIER_VARBITS.entrySet())
		{
			String area = entry.getKey();
			int[] tierVarbits = entry.getValue();
			String highestTier = null;
			for (int i = 0; i < tierVarbits.length; i++)
			{
				if (client.getVarbitValue(tierVarbits[i]) != 1)
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
		int containerId = event.getContainerId();
		ItemContainer container = event.getItemContainer();
		if (container == null)
		{
			return;
		}

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
		else if (containerId == POH_COSTUME_ROOM_CONTAINER_ID)
		{
			latestPohItems = readPohItems(container);
		}
		else
		{
			// The Master Scroll Book was the reason this branch used to
			// carry a temporary diagnostic -- confirmed by that
			// diagnostic to genuinely never fire ItemContainerChanged at
			// all (see bookOfScrollsDumpPending's field doc below for the
			// full story), so there's nothing left here to catch.
			return;
		}

		scheduleSyncDebounced();
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
	 * "Casket", no tier -- a real limitation of this event, not
	 * something worked around here (see app/loot.py's module docstring
	 * server-side). combatLevel is -1 for anything without one (a
	 * casket, a pickpocket target) -- translated to null below rather
	 * than synced as a fake level. */
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

		Integer combatLevel = event.getCombatLevel() >= 0 ? event.getCombatLevel() : null;
		MyscapeClient.LootEvent lootEvent = new MyscapeClient.LootEvent(
			UUID.randomUUID().toString(),
			event.getName(),
			event.getType().name().toLowerCase(),
			combatLevel,
			Instant.now().toString(),
			items
		);
		panel.syncLootInBackground(lootEvent);
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

	@Subscribe
	public void onVarbitChanged(VarbitChanged event)
	{
		int varpId = event.getVarpId();
		if (varpId >= POTION_STORAGE_VARP_MIN && varpId <= POTION_STORAGE_VARP_MAX)
		{
			potionStorageDirty = true;
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
	 * source in this file. */
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
	 * Book/skills/quests/diaries rather than one per source, since
	 * a player actively shopping or grinding typically touches more
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
						latestSkillItems, latestCompletedQuestNames, latestDiaryTierItems);
				}
			},
			delaySeconds, TimeUnit.SECONDS);
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

			// Same deal, for skills (see skillsDirty's field doc).
			if (skillsDirty)
			{
				skillsDirty = false;
				latestSkillItems = readSkills();
				scheduleSyncDebounced();
			}

			// Same deal, for completed quests (see questsDirty's field doc).
			if (questsDirty)
			{
				questsDirty = false;
				latestCompletedQuestNames = readCompletedQuestNames();
				scheduleSyncDebounced();
			}

			// Same deal, for achievement diary tiers (see diariesDirty's
			// field doc).
			if (diariesDirty)
			{
				diariesDirty = false;
				latestDiaryTierItems = readDiaryTiers();
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
}
