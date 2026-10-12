package io.myscape.plugin;

import com.google.gson.Gson;
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
import net.runelite.api.Varbits;
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
import net.runelite.client.util.Text;
import okhttp3.OkHttpClient;

/**
 * Syncs a player's game state to their myscape.io account.
 *
 * The player links the client by pasting a short-lived code from the website into
 * the side panel, which is exchanged for an API key. Once linked, the plugin keeps
 * item storages (bank, inventory, equipment and others), skills, quests, diaries
 * and character summary stats in sync, and reports loot from kills, clue caskets
 * and pickpockets.
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
	private Gson gson;

	@Inject
	private MyscapeConfig config;

	@Inject
	private ScheduledExecutorService executor;

	@Inject
	private ItemManager itemManager;

	// Container change events fire on every deposit/withdraw/equip. Syncing only after
	// this many quiet seconds collapses a banking session into one sync per container.
	private static final int SYNC_DEBOUNCE_SECONDS = 2;

	// Upper bound on how long a continuous burst of changes can postpone the sync.
	// The quiet-period debounce alone never fires during sustained activity.
	private static final int SYNC_MAX_WAIT_SECONDS = 10;

	// Maps equipment slot indices to the slot names used by myscape.io. ARMS, HAIR and
	// JAW (6, 8, 11) are cosmetic slots that never hold an item and are left out.
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

	// Potion Storage is not an ItemContainer. As in RuneLite's Bank Tags PotionStorage,
	// it is read through two top-level enums (one enum id per potion type) and a client
	// script that returns the total stored doses for a potion type.
	private static final int[] POTION_STORAGE_ENUM_IDS = {4826, 4829};
	private static final int POTION_STORAGE_TOTAL_DOSES_SCRIPT_ID = 3750;
	// Enum values 1..4 hold the item id for each dose count.
	private static final int MAX_POTION_DOSES = 4;
	// Varps that Bank Tags treats as "Potion Storage changed".
	private static final int POTION_STORAGE_VARP_MIN = 4272;
	private static final int POTION_STORAGE_VARP_MAX = 4277;

	// Some potions (e.g. Divine) keep their doses in vars outside the range above, so
	// changes to them are not detected. Potion Storage is therefore also re-read
	// periodically (see ticksSincePotionStorageRefresh).
	private static final int POTION_STORAGE_REFRESH_INTERVAL_TICKS = 50;

	// A Rune Pouch (including Divine and trouver variants) is a single item, not a
	// container. Its contents are read from per-slot type/quantity varbits, as in
	// RuneLite's RunepouchOverlay. All 6 slots are read in case a larger pouch is added.
	private static final int[] RUNE_POUCH_TYPE_VARBITS = {
		VarbitID.RUNE_POUCH_TYPE_1, VarbitID.RUNE_POUCH_TYPE_2, VarbitID.RUNE_POUCH_TYPE_3,
		VarbitID.RUNE_POUCH_TYPE_4, VarbitID.RUNE_POUCH_TYPE_5, VarbitID.RUNE_POUCH_TYPE_6,
	};
	private static final int[] RUNE_POUCH_QUANTITY_VARBITS = {
		VarbitID.RUNE_POUCH_QUANTITY_1, VarbitID.RUNE_POUCH_QUANTITY_2, VarbitID.RUNE_POUCH_QUANTITY_3,
		VarbitID.RUNE_POUCH_QUANTITY_4, VarbitID.RUNE_POUCH_QUANTITY_5, VarbitID.RUNE_POUCH_QUANTITY_6,
	};
	// Each type varbit is a 1-based index into this enum (0 = empty), yielding the rune item id.
	private static final int RUNE_POUCH_RUNE_ENUM_ID = EnumID.RUNEPOUCH_RUNE;
	// Rune Pouch items; used to confirm ownership before trusting the varbits.
	private static final int[] RUNE_POUCH_ITEM_IDS = {
		ItemID.BH_RUNE_POUCH, ItemID.BH_RUNE_POUCH_TROUVER, ItemID.DIVINE_RUNE_POUCH, ItemID.DIVINE_RUNE_POUCH_TROUVER,
	};

	// Achievement diaries: one "tier complete" varbit per area and tier, ordered easy to
	// elite. Keys are myscape.io area slugs, which differ from RuneLite's for Kourend,
	// Western and Lumbridge. Karamja easy/medium/hard (ATJUN_*_DONE) count 0, 1 and 2
	// (complete); see DIARY_TIER_COMPLETE_VALUES.
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

	// Value at which each tier varbit counts as complete. All 1, except Karamja
	// easy/medium/hard, which reach 2.
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

	// Flat set of the varbits above, for a cheap membership check in onVarbitChanged.
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

	// Combat achievements: one "tasks completed" varbit per tier, summed into
	// combatTasksCompleted. No varbit exists for the total (see combatAchievementsDirty).
	private static final int[] COMBAT_ACHIEVEMENT_COMPLETED_VARBITS = {
		VarbitID.CA_TOTAL_TASKS_COMPLETED_EASY, VarbitID.CA_TOTAL_TASKS_COMPLETED_MEDIUM,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_HARD, VarbitID.CA_TOTAL_TASKS_COMPLETED_ELITE,
		VarbitID.CA_TOTAL_TASKS_COMPLETED_MASTER, VarbitID.CA_TOTAL_TASKS_COMPLETED_GRANDMASTER,
	};

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
	private ScheduledFuture<?> syncDebounceTask;

	// Start of the current debounce burst (see scheduleSyncDebounced), or -1 between
	// bursts. Only used for delay timing, so volatile is enough.
	private volatile long syncBurstStartMillis = -1;

	// Written only on the client thread (onGameStateChanged, onGameTick) and read from
	// the Swing EDT through getters, so the EDT never touches `client`.
	private volatile Long currentAccountHash;
	private volatile String currentRuneScapeName;

	// Display name last accepted by myscape.io (see reportRuneScapeNameIfChanged).
	// nameReportCooldownUntilMillis throttles retries while offline or not yet linked.
	private volatile String lastReportedRuneScapeName;
	private long nameReportCooldownUntilMillis;
	private static final long NAME_REPORT_RETRY_MILLIS = 60_000L;

	// Latest contents of each container. Captured on the client thread (on
	// ItemContainerChanged, or at login for inventory/equipment) and read by the debounce
	// task on the executor thread, which must not touch the Client. Null until seen.
	private volatile List<MyscapeClient.BankItem> latestBankItems;
	private volatile List<MyscapeClient.InventoryItem> latestInventoryItems;
	private volatile List<MyscapeClient.EquipmentItem> latestEquipmentItems;
	private volatile List<MyscapeClient.PotionStorageItem> latestPotionStorageItems;
	private volatile List<MyscapeClient.RunePouchItem> latestRunePouchItems;
	// Looting Bag, Seed Vault, Tackle Box, Forestry Kit, Seed Box and Huntsman's Kit are
	// real ItemContainers (InventoryID.*). Null until opened this session.
	private volatile List<MyscapeClient.LootingBagItem> latestLootingBagItems;
	private volatile List<MyscapeClient.SeedVaultItem> latestSeedVaultItems;
	private volatile List<MyscapeClient.TackleBoxItem> latestTackleBoxItems;
	// Every PoH Costume Room furniture piece fires ItemContainerChanged on one shared
	// container id (see POH_COSTUME_ROOM_CONTAINER_ID). Null until opened this session.
	private volatile List<MyscapeClient.PohItem> latestPohItems;
	private volatile List<MyscapeClient.ForestryKitItem> latestForestryKitItems;
	// Distinct from the Seed Vault. The Master Scroll Book has no container id (see
	// masterScrollBookDirty).
	private volatile List<MyscapeClient.SeedBoxItem> latestSeedBoxItems;
	private volatile List<MyscapeClient.HuntsmansKitItem> latestHuntsmansKitItems;
	// One field per boat slot (1-5); each cargo hold is a separate ItemContainer
	// (InventoryID.SAILING_BOAT_n_CARGOHOLD).
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo1Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo2Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo3Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo4Items;
	private volatile List<MyscapeClient.BoatCargoItem> latestBoatCargo5Items;
	// Skills, quests and diaries are read as soon as login is detected (see the
	// justBecameAvailable block in onGameTick), so they are not null for long.
	private volatile List<MyscapeClient.SkillItem> latestSkillItems;
	private volatile List<String> latestCompletedQuestNames;
	private volatile List<String> latestInProgressQuestNames;
	private volatile List<MyscapeClient.DiaryTierItem> latestDiaryTierItems;

	// The collection log is not read passively: collectionLogItemCapture re-triggers the
	// interface refresh, and only works once the log has been opened this session.
	private final CollectionLogItemCapture collectionLogItemCapture = new CollectionLogItemCapture();
	private volatile List<MyscapeClient.CollectionLogItem> latestCollectionLogItems;

	// The Master Scroll Book has no ItemContainer (see readMasterScrollBookItems). Set in
	// onWidgetLoaded when InterfaceID.BOOKOFSCROLLS loads.
	private volatile boolean masterScrollBookDirty;
	private volatile List<MyscapeClient.MasterScrollBookItem> latestMasterScrollBookItems;

	// The Character Summary tab (InterfaceID.ACCOUNT_SUMMARY_SIDEPANEL) is backed by
	// widgets, so it is flagged from onWidgetLoaded. Combat level, total level and total
	// XP still read as placeholders one tick after load, so this counts down from
	// CHARACTER_SUMMARY_REREAD_TICKS and re-reads each tick. Each re-read reschedules the
	// debounced sync, so the last read is sent.
	private static final int CHARACTER_SUMMARY_REREAD_TICKS = 8;
	private volatile int characterSummaryRereadTicksLeft;
	private volatile MyscapeClient.CharacterSummaryItem latestCharacterSummary;

	// Looting Bag, Seed Vault, Tackle Box, Forestry Kit, Seed Box, PoH Costume Room,
	// Huntsman's Kit and boat cargo holds share a quirk: opening one that is already
	// empty sends no ItemContainerChanged, so its empty state would never sync.
	// onWidgetLoaded therefore queues a re-read of the container id (mapped to the tick
	// it was queued on). It runs a tick later because the container may not be populated
	// when the widget loads, and onGameTick retries a null container up to
	// MAX_CONTAINER_REREAD_WAIT_TICKS. Only used from client-thread callbacks.
	private final Map<Integer, Integer> pendingContainerRereadTicks = new LinkedHashMap<>();

	// Extra ticks to keep retrying a queued re-read that returns null (about 6 seconds).
	// Bounded because some boat cargo slots are queued but never populate.
	private static final int MAX_CONTAINER_REREAD_WAIT_TICKS = 10;

	// Maps an interface's widget group id to the one ItemContainer id it backs. Boat
	// cargo is not listed because one shared interface can back any of five containers,
	// so onWidgetLoaded queues all five directly.
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
		// See POH_COSTUME_ROOM_CONTAINER_ID for why not InventoryID.POH_COSTUMES.
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
		MyscapeClient apiClient = new MyscapeClient(okHttpClient, configManager, gson);
		panel = new MyscapePanel(apiClient, this);

		BufferedImage icon = ImageUtil.loadImageResource(getClass(), "icon.png");
		navButton = NavigationButton.builder()
			.tooltip("myscape")
			.icon(icon)
			.priority(6)
			.panel(panel)
			.build();
		// Linking and syncing do not depend on the toolbar icon.
		if (config.showSidebar())
		{
			clientToolbar.addNavigation(navButton);
		}

		panel.refreshLinkStatus();
	}

	@Override
	protected void shutDown()
	{
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
		lastReportedRuneScapeName = null;
		nameReportCooldownUntilMillis = 0;
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

	/** Sums quantities by item id. Empty slots (id or quantity <= 0) are skipped. */
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

	/** Reads a container's items, merged by id, into the given item type. */
	private <T> List<T> readItems(ItemContainer container, ItemFactory<T> factory)
	{
		List<T> items = new ArrayList<>();
		for (Map.Entry<Integer, Integer> entry : aggregateQuantities(container).entrySet())
		{
			items.add(factory.create(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
	}

	private interface ItemFactory<T>
	{
		T create(int itemId, String itemName, int quantity);
	}

	/** Looks up an item's display name from RuneLite's item definitions. Client thread only. */
	private String resolveItemName(int itemId)
	{
		try
		{
			return itemManager.getItemComposition(itemId).getName();
		}
		catch (Exception e)
		{
			// An unknown id should not stop the sync; the item still syncs by id.
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

	/**
	 * A bank placeholder is a separate item id (template id other than -1) with a nonzero
	 * quantity, so aggregateQuantities does not skip it.
	 */
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
		return readItems(inventory, MyscapeClient.InventoryItem::new);
	}

	/** A Looting Bag is a real ItemContainer (InventoryID.LOOTING_BAG), read like the bank. */
	private List<MyscapeClient.LootingBagItem> readLootingBagItems(ItemContainer lootingBag)
	{
		return readItems(lootingBag, MyscapeClient.LootingBagItem::new);
	}

	private List<MyscapeClient.SeedVaultItem> readSeedVaultItems(ItemContainer seedVault)
	{
		return readItems(seedVault, MyscapeClient.SeedVaultItem::new);
	}

	private List<MyscapeClient.TackleBoxItem> readTackleBoxItems(ItemContainer tackleBox)
	{
		return readItems(tackleBox, MyscapeClient.TackleBoxItem::new);
	}

	/** Read from the one container shared by all PoH Costume Room furniture. */
	private List<MyscapeClient.PohItem> readPohItems(ItemContainer poh)
	{
		return readItems(poh, MyscapeClient.PohItem::new);
	}

	private List<MyscapeClient.ForestryKitItem> readForestryKitItems(ItemContainer forestryKit)
	{
		return readItems(forestryKit, MyscapeClient.ForestryKitItem::new);
	}

	private List<MyscapeClient.SeedBoxItem> readSeedBoxItems(ItemContainer seedBox)
	{
		return readItems(seedBox, MyscapeClient.SeedBoxItem::new);
	}

	private List<MyscapeClient.HuntsmansKitItem> readHuntsmansKitItems(ItemContainer huntsmansKit)
	{
		return readItems(huntsmansKit, MyscapeClient.HuntsmansKitItem::new);
	}

	/** One boat's cargo hold (InventoryID.SAILING_BOAT_n_CARGOHOLD); the caller passes the container id. */
	private List<MyscapeClient.BoatCargoItem> readBoatCargoItems(ItemContainer boatCargo)
	{
		return readItems(boatCargo, MyscapeClient.BoatCargoItem::new);
	}

	/** Equipment is read by fixed slot index (EQUIPMENT_SLOT_NAMES), not aggregated. */
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

	/**
	 * Reads Potion Storage (see POTION_STORAGE_ENUM_IDS). Each potion's total doses are
	 * reported as the potions a withdrawal would give, e.g. 10 doses of Prayer potion
	 * become 2 x (4) and 1 x (2). Client thread only.
	 */
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

	/**
	 * Whether a Rune Pouch is in the inventory or bank. The bank container is null
	 * until opened this session. Client thread only.
	 */
	private boolean playerOwnsRunePouch()
	{
		return containerHasRunePouch(client.getItemContainer(InventoryID.INV))
			|| containerHasRunePouch(client.getItemContainer(InventoryID.BANK));
	}

	/**
	 * Whether the player is confirmed to no longer own a Rune Pouch. Right after login
	 * the bank container is still null, so a banked pouch would look unowned; this stays
	 * false until the bank has loaded. Client thread only.
	 */
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

	/**
	 * Reads a Rune Pouch from its varbits (see RUNE_POUCH_TYPE_VARBITS). Returns an empty
	 * list once the pouch is confirmed gone, since the varbits would keep reporting its
	 * old contents. Client thread only.
	 */
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

	/** Reports every skill RuneLite knows, at its real (unboosted) level. Client thread only. */
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

	/** Names of finished quests and miniquests. Quest#getState runs a client script, so client thread only. */
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

	/** Names of quests that are QuestState.IN_PROGRESS. Client thread only. */
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

	/**
	 * The highest completed tier per diary area, omitting areas with none. Stops at the
	 * first incomplete tier. Client thread only.
	 */
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

	// The Costume Room's contents (all furniture pieces) arrive on one container id.
	// It is assigned at runtime by the consolidated Costume Room interface script, so
	// there is no InventoryID constant for it.
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

	/** Per-container dispatch, shared with the deferred re-read in onGameTick. Returns true if tracked. */
	private boolean applyContainerSnapshot(int containerId, ItemContainer container)
	{
		if (containerId == InventoryID.BANK)
		{
			latestBankItems = readBankItems(container);
			// A Rune Pouch can be banked, so ownership may have changed.
			runePouchDirty = true;
		}
		else if (containerId == InventoryID.INV)
		{
			latestInventoryItems = readInventoryItems(container);
			// Picking up or dropping a Rune Pouch changes ownership without any pouch
			// varbit changing, so re-read it on the next tick.
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
			// Not a container this plugin tracks.
			return false;
		}

		return true;
	}

	/**
	 * Handles loot published by RuneLite's Loot Tracker plugin, which must stay enabled.
	 * Each event is sent immediately (panel#syncLootInBackground), not debounced. The
	 * type is passed through by name so unknown types still sync. Clue caskets arrive as
	 * "Casket" with no tier, so the tier from the chat line (see pendingClueTier) is
	 * applied here. A combat level of -1 means none and is sent as null.
	 */
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

		// A Casket event shortly after a clue completion line is that clue's reward.
		// The tier is cleared immediately so it cannot go stale.
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

	// Tier (e.g. "Hard") from the "You have completed N <tier> Treasure Trails." chat
	// line, waiting for the matching Casket loot event (the chat line has no items).
	private volatile String pendingClueTier;

	// Tick on which pendingClueTier was set, used to reject a stale tier.
	private int pendingClueTierTick;

	// Maximum ticks (~0.6s each) between the clue chat line and its Casket loot event.
	private static final int CLUE_TIER_MAX_TICK_GAP = 5;

	private static final String CASKET_EVENT_NAME = "Casket";

	// Line printed when a collection log slot unlocks. Matched after colour tags are stripped.
	private static final Pattern COLLECTION_LOG_ITEM_PATTERN =
		Pattern.compile("New item added to your collection log:\\s*(.+)");

	/**
	 * Chat line printed when a Treasure Trail is completed. Based on Loot Tracker's
	 * CLUE_SCROLL_PATTERN, with a looser tier group that is capitalized in onChatMessage.
	 */
	private static final Pattern CLUE_SCROLL_PATTERN =
		Pattern.compile("You have completed [0-9,]+ ([a-zA-Z]+) Treasure Trails?\\.");

	// Same pattern as RuneLite's ChatCommandsPlugin KILLCOUNT_PATTERN, e.g.
	// "Your Zulrah kill count is: 1,234." Boss names are forwarded as matched and any
	// renaming is done server-side.
	private static final Pattern KILLCOUNT_PATTERN = Pattern.compile(
		"Your (?<pre>completion count for |subdued |completed )?(?:<col=[0-9a-f]{6}>)?(?<boss>.+?)(?:</col>)? (?<post>(?:(?:kill|harvest|lap|completion|success|Total Ticket) )?(?:count )?)is: ?<col=[0-9a-f]{6}>(?<kc>[0-9,]+)</col>");

	/**
	 * Parses chat lines: kill counts, collection log unlocks and clue completions.
	 * Only GAMEMESSAGE and SPAM are checked, as in ChatCommandsPlugin. KILLCOUNT_PATTERN
	 * also matches unrelated "Your ... is: n" lines, which are rejected when both
	 * pre and post are empty, as ChatCommandsPlugin does.
	 */
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

		// A new collection log slot unlocked. Send the item by name now, since the full
		// capture only runs when the log is opened. Clearing latestCollectionLogItems stops
		// a debounced sync from overwriting this addition with an older full list.
		Matcher collectionLogMatcher = COLLECTION_LOG_ITEM_PATTERN.matcher(Text.removeTags(event.getMessage()));
		if (collectionLogMatcher.find())
		{
			String itemName = collectionLogMatcher.group(1).trim();
			if (!itemName.isEmpty())
			{
				latestCollectionLogItems = null;
				panel.syncCollectionLogItemInBackground(itemName);
			}
			return;
		}

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

	// Set when a Potion Storage varp fires and handled on the next game tick, as Bank
	// Tags does, rather than reading script state mid-event. Client thread only.
	private boolean potionStorageDirty;

	private int ticksSincePotionStorageRefresh;

	// Rune Pouch contents or ownership may have changed. Also set from applyContainerSnapshot.
	private boolean runePouchDirty;

	private boolean skillsDirty;

	// Set when the quest complete scroll loads. No single varbit signals every quest.
	private boolean questsDirty;

	private boolean diariesDirty;

	// Set when a combat achievement varbit changes. Only combatTasksCompleted is patched
	// live; the total has no varbit, so it comes from the Character Summary read.
	private boolean combatAchievementsDirty;

	// Set when the collection log count varps change. Both completed and total have varps.
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

	/** Entry point for the per-item collection log capture. Fires when the log's setup script runs. */
	@Subscribe
	public void onScriptPostFired(ScriptPostFired event)
	{
		collectionLogItemCapture.onCollectionLogOpened(event.getScriptId(), client);
	}

	@Subscribe
	public void onScriptPreFired(ScriptPreFired event)
	{
		collectionLogItemCapture.onScriptPreFired(
			event.getScriptId(), event.getScriptEvent().getArguments(), client);
	}

	/** Boost-only changes are not filtered out; readSkills re-reads every skill and the resend is harmless. */
	@Subscribe
	public void onStatChanged(StatChanged event)
	{
		skillsDirty = true;
	}

	/**
	 * Flags a quest re-check when the quest complete scroll loads (only the trigger;
	 * Quest#getState provides the data). Also flags the Master Scroll Book and Character
	 * Summary reads and queues virtual storage re-reads (see pendingContainerRereadTicks).
	 */
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
			// All five boat slots share this interface and the event does not say which one
			// is open, so queue all five. onGameTick skips those that return null.
			queueContainerReread(InventoryID.SAILING_BOAT_1_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_2_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_3_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_4_CARGOHOLD);
			queueContainerReread(InventoryID.SAILING_BOAT_5_CARGOHOLD);
		}
	}

	private void queueContainerReread(int containerId)
	{
		pendingContainerRereadTicks.put(containerId, client.getTickCount());
	}

	/**
	 * Reads the Master Scroll Book from its widgets (it has no ItemContainer). A widget
	 * with an item id is followed by siblings holding the quantity and the display name.
	 * Child ids are irregular, so the loop matches on adjacency. Zero quantities are
	 * skipped and stray "<br>" is stripped from names.
	 */
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

	// Dynamic-child index of each Character Summary stat's value text (found with the
	// Widget Inspector on SUMMARY_CONTENTS). If a game update changes the layout,
	// readCharacterSummary returns null instead of a wrong number.
	private static final int SUMMARY_INDEX_COMBAT_LEVEL = 11;
	private static final int SUMMARY_INDEX_TOTAL_LEVEL = 24;
	private static final int SUMMARY_INDEX_TOTAL_XP = 36;
	private static final int SUMMARY_INDEX_QUESTS = 49;
	private static final int SUMMARY_INDEX_ACHIEVEMENTS = 62;
	private static final int SUMMARY_INDEX_COMBAT_TASKS = 75;
	private static final int SUMMARY_INDEX_COLLECTIONS = 88;

	/** Reads the seven Character Summary stats. Returns null if the panel is closed or parsing fails. */
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
			// Per-tier combat achievement counts are not shown on this panel. They are left
			// zeroed and filled in by patchCombatAchievementStats.
			new int[COMBAT_ACHIEVEMENT_COMPLETED_VARBITS.length],
			readAccountType());
	}

	/**
	 * Maps Varbits.ACCOUNT_TYPE (0-6) to the account type codes used by myscape.io.
	 * Client#getAccountType is not used because it cannot distinguish an unranked
	 * group ironman (runelite/runelite#16510). Unknown values map to "MAIN".
	 */
	private String readAccountType()
	{
		switch (client.getVarbitValue(Varbits.ACCOUNT_TYPE))
		{
			case 1:
				return "IRONMAN";
			case 2:
				return "UIM";
			case 3:
				return "HCIM";
			case 4:
				return "GIM";
			case 5:
				return "HCGIM";
			case 6:
				return "UGIM";
			default:
				return "MAIN";
		}
	}

	private static Widget summaryChild(Widget[] children, int index)
	{
		return index >= 0 && index < children.length ? children[index] : null;
	}

	/** Parses a widget's text, minus colour markup, as a decimal (used for Combat Level). */
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

	/** Like parseSummaryInt but as a long, since Total XP can exceed Integer.MAX_VALUE. */
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

	/** Parses an "X/Y" widget into {X, Y} after stripping colour markup. */
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

	/**
	 * Rebuilds latestCharacterSummary with the fields the caller has fresh data for. A
	 * no-op until readCharacterSummary has established a baseline. Client thread only.
	 */
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

	/**
	 * Patches combat level, total level and total XP from the latest skills, without
	 * needing the Character Summary panel open.
	 */
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
		final int finalTotalLevel = totalLevel;
		final long finalTotalXp = totalXp;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			combatLevel, finalTotalLevel, finalTotalXp,
			prev.questsCompleted, prev.questsTotal,
			prev.achievementsCompleted, prev.achievementsTotal,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			prev.combatTaskTierCompleted,
			prev.accountType));
	}

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
			prev.combatTaskTierCompleted,
			prev.accountType));
	}

	/**
	 * Patches the achievement counts from latestDiaryTierItems. An area's entry is its
	 * highest tier, so "elite" counts as 4 completed tiers. The total is 12 areas x 4.
	 */
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
		final int finalCompleted = completed;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			prev.questsCompleted, prev.questsTotal,
			finalCompleted, total,
			prev.combatTasksCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			prev.combatTaskTierCompleted,
			prev.accountType));
	}

	/**
	 * Patches combatTasksCompleted and the per-tier counts. combatTasksTotal has no
	 * varbit and keeps its last Character Summary value.
	 */
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
		final int finalCompleted = completed;
		final int[] finalTierCompleted = tierCompleted;
		patchCharacterSummary(prev -> new MyscapeClient.CharacterSummaryItem(
			prev.combatLevel, prev.totalLevel, prev.totalXp,
			prev.questsCompleted, prev.questsTotal,
			prev.achievementsCompleted, prev.achievementsTotal,
			finalCompleted, prev.combatTasksTotal,
			prev.collectionsCompleted, prev.collectionsTotal,
			finalTierCompleted,
			prev.accountType));
	}

	/**
	 * Patches the collection log counts from COLLECTION_COUNT and COLLECTION_COUNT_MAX.
	 * A value of 0 is treated as not yet populated, as in DinkPlugin's CollectionNotifier.
	 */
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
			prev.combatTaskTierCompleted,
			prev.accountType));
	}

	/** Whether varbitId is one of the 12 Rune Pouch varbits (not contiguous, so matched by exact id). */
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

	/**
	 * (Re)schedules the single debounce task shared by all sources. When it fires it
	 * syncs whichever sources have been seen, reading only the captured snapshots since
	 * it runs on the executor thread. The delay is clamped so a long burst still syncs
	 * at SYNC_MAX_WAIT_SECONDS.
	 */
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

	/**
	 * Sends the display name to myscape.io when it differs from the last one accepted.
	 * Runs on the client thread; the HTTP call is async.
	 */
	private void reportRuneScapeNameIfChanged()
	{
		String name = currentRuneScapeName;
		Long accountHash = currentAccountHash;
		if (panel == null || name == null || name.isEmpty() || accountHash == null || accountHash == -1L
			|| name.equals(lastReportedRuneScapeName))
		{
			return;
		}
		long now = System.currentTimeMillis();
		if (now < nameReportCooldownUntilMillis)
		{
			return;
		}
		nameReportCooldownUntilMillis = now + NAME_REPORT_RETRY_MILLIS;
		panel.syncRunescapeNameInBackground(accountHash, name, () -> lastReportedRuneScapeName = name);
	}

	@Subscribe
	public void onGameTick(GameTick tick)
	{
		// The local player and name can take a tick or two to become available after
		// GameState flips to LOGGED_IN, so keep both cached values current.
		if (client.getGameState() == GameState.LOGGED_IN)
		{
			boolean justBecameAvailable = currentAccountHash == null;
			currentAccountHash = client.getAccountHash();
			if (client.getLocalPlayer() != null)
			{
				currentRuneScapeName = client.getLocalPlayer().getName();
				reportRuneScapeNameIfChanged();
			}

			// Read Potion Storage one tick after a tracked varp fired (see potionStorageDirty)
			// or when the periodic refresh interval has elapsed.
			if (potionStorageDirty || ++ticksSincePotionStorageRefresh >= POTION_STORAGE_REFRESH_INTERVAL_TICKS)
			{
				potionStorageDirty = false;
				ticksSincePotionStorageRefresh = 0;
				latestPotionStorageItems = readPotionStorageItems();
				scheduleSyncDebounced();
			}

			if (runePouchDirty)
			{
				runePouchDirty = false;
				latestRunePouchItems = readRunePouchItems();
				scheduleSyncDebounced();
			}

			if (skillsDirty)
			{
				skillsDirty = false;
				latestSkillItems = readSkills();
				scheduleSyncDebounced();
				patchCombatAndTotalStats();
			}

			if (questsDirty)
			{
				questsDirty = false;
				latestCompletedQuestNames = readCompletedQuestNames();
				latestInProgressQuestNames = readInProgressQuestNames();
				scheduleSyncDebounced();
				patchQuestStats();
			}

			if (diariesDirty)
			{
				diariesDirty = false;
				latestDiaryTierItems = readDiaryTiers();
				scheduleSyncDebounced();
				patchDiaryStats();
			}

			if (combatAchievementsDirty)
			{
				combatAchievementsDirty = false;
				patchCombatAchievementStats();
			}

			if (collectionsDirty)
			{
				collectionsDirty = false;
				patchCollectionStats();
			}

			// A collection log item capture does not complete on a single event: the client
			// fires one script per obtained item with no end marker. collectionLogItemCapture
			// decides a pass is finished once no new item has arrived for a couple of ticks.
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

			if (masterScrollBookDirty)
			{
				masterScrollBookDirty = false;
				latestMasterScrollBookItems = readMasterScrollBookItems();
				scheduleSyncDebounced();
			}

			if (characterSummaryRereadTicksLeft > 0)
			{
				characterSummaryRereadTicksLeft--;
				MyscapeClient.CharacterSummaryItem summary = readCharacterSummary();
				if (summary != null)
				{
					latestCharacterSummary = summary;
					scheduleSyncDebounced();
					// readCharacterSummary zeroes combatTaskTierCompleted. Patch it here so the
					// per-tier breakdown is correct as soon as the panel is first opened, instead of
					// only after the next combat task is completed.
					patchCombatAchievementStats();
				}
			}

			// Consume pendingContainerRereadTicks: once this tick is after the widget's, read the
			// container explicitly, since no ItemContainerChanged fires for an already-empty
			// storage. A null read stays queued until MAX_CONTAINER_REREAD_WAIT_TICKS passes.
			// Entries are removed via the iterator to avoid a ConcurrentModificationException.
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

			// onGameStateChanged fires when the state flips to LOGGED_IN, but getAccountHash()
			// is not populated until this tick (and no event fires at all if the plugin starts
			// while already in game). Notify the panel again once the hash is available.
			if (justBecameAvailable)
			{
				if (panel != null)
				{
					panel.onGameStateChanged();
				}

				// Inventory and equipment are populated at login, unlike the bank, so capture
				// them now for an accurate initial snapshot.
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
				// Potion Storage has no container and is otherwise read only after a tracked
				// varp fires, so read it once here. The periodic refresh keeps it current.
				latestPotionStorageItems = readPotionStorageItems();
				ticksSincePotionStorageRefresh = 0;
				latestRunePouchItems = readRunePouchItems();
				// Skills, quests and diaries are fully known at login. Capture them now so
				// existing progress is reported without waiting for a change.
				latestSkillItems = readSkills();
				latestCompletedQuestNames = readCompletedQuestNames();
				latestInProgressQuestNames = readInProgressQuestNames();
				latestDiaryTierItems = readDiaryTiers();
				scheduleSyncDebounced();
			}
		}
	}

	/** The account hash to link with, or null if not logged in. Safe to call from the EDT. */
	Long getAccountHash()
	{
		return currentAccountHash;
	}

	/** The current display name, or null if not yet known. Informational only. Safe to call from the EDT. */
	String getRuneScapeName()
	{
		return currentRuneScapeName;
	}

	/** Called on the client thread (not the EDT) when a model capture finishes. */
	interface ModelCaptureCallback
	{
		void onSuccess(byte[] glb);

		void onFailure(String message);
	}

	/**
	 * Captures the local player's current model as a .glb file. A Model's vertex and face
	 * arrays are only valid on the client thread, so the read and conversion run in one
	 * clientThread.invoke. See the modelexport package.
	 */
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
				// Use the client's TextureProvider if available for its brightness setting and real
				// texture pixels. Without it, textured faces fall back to flat vertex colours.
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
