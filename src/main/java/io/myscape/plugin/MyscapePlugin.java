package io.myscape.plugin;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EnumComposition;
import net.runelite.api.EnumID;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.events.VarbitChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.api.gameval.ItemID;
import net.runelite.api.gameval.VarbitID;
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
 * inventory, worn equipment, Rune Pouch, and Potion Storage in sync --
 * the data collection the Purchase Goals feature is built on (see
 * onItemContainerChanged, onGameTick, and onVarbitChanged below) -- and
 * reports every kill/clue-casket/pickpocket's loot as it happens (see
 * onLootReceived below), which the Loot Tracker feature is built on.
 */
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

	private MyscapePanel panel;
	private NavigationButton navButton;
	private ScheduledFuture<?> linkStatusPollTask;
	private ScheduledFuture<?> syncDebounceTask;

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
				}
			},
			LINK_STATUS_POLL_SECONDS, LINK_STATUS_POLL_SECONDS, TimeUnit.SECONDS);
	}

	@Override
	protected void shutDown()
	{
		if (linkStatusPollTask != null)
		{
			linkStatusPollTask.cancel(false);
			linkStatusPollTask = null;
		}
		if (syncDebounceTask != null)
		{
			syncDebounceTask.cancel(false);
			syncDebounceTask = null;
		}
		clientToolbar.removeNavigation(navButton);
		panel = null;
		currentAccountHash = null;
		currentRuneScapeName = null;
		latestBankItems = null;
		latestInventoryItems = null;
		latestEquipmentItems = null;
		latestPotionStorageItems = null;
		latestRunePouchItems = null;
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
			items.add(new MyscapeClient.BankItem(entry.getKey(), resolveItemName(entry.getKey()), entry.getValue()));
		}
		return items;
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
	 * currently sitting in the player's inventory -- see
	 * readRunePouchItems's doc for why this gate exists. A pouch is
	 * inventory-only (it can't be worn), so unlike other ownership
	 * checks in this file, only the inventory container needs checking.
	 * Must only ever be called from the client thread, same as
	 * readRunePouchItems. */
	private boolean playerOwnsRunePouch()
	{
		ItemContainer inventory = client.getItemContainer(InventoryID.INV);
		if (inventory == null)
		{
			return false;
		}
		for (Item item : inventory.getItems())
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
	 * to still say -- when the player doesn't currently have a Rune
	 * Pouch anywhere in their inventory (see playerOwnsRunePouch):
	 * unlike a bank or Potion Storage, these varbits are the pouch's own
	 * carried state, so without this check, a pouch that was sold or
	 * otherwise given up while stocked would keep being reported as
	 * "owned" contents long after both it and the runes actually left
	 * the account. Multiple slots holding the same rune type (not
	 * possible in-game today, but nothing guarantees that) are merged
	 * into one entry, same as aggregateQuantities does for bank/
	 * inventory. */
	private List<MyscapeClient.RunePouchItem> readRunePouchItems()
	{
		if (!playerOwnsRunePouch())
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
		else
		{
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

	// Same deal, for a Rune Pouch's contents -- also set directly from
	// onItemContainerChanged above (see its comment) when the pouch's
	// own presence in the inventory could have changed, not just when
	// this listener below observes it.
	private boolean runePouchDirty;

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
	 * bank/inventory/equipment/potion storage rather than one per
	 * container, since a player actively shopping typically touches
	 * more than one of them in the same burst (withdrawing from the
	 * bank, then equipping what they withdrew), and there's no reason
	 * to send four separate requests a couple of ticks apart for what's
	 * really one action. When it fires, it syncs whichever of the four
	 * have actually been observed at least once (see the field docs
	 * above) -- reading only the already-captured volatile snapshots,
	 * never the RuneLite Client itself, since this runs on `executor`'s
	 * thread, not the client thread. */
	private void scheduleSyncDebounced()
	{
		if (syncDebounceTask != null)
		{
			syncDebounceTask.cancel(false);
		}
		syncDebounceTask = executor.schedule(
			() -> {
				if (panel != null)
				{
					panel.syncInBackground(latestBankItems, latestInventoryItems, latestEquipmentItems, latestPotionStorageItems, latestRunePouchItems);
				}
			},
			SYNC_DEBOUNCE_SECONDS, TimeUnit.SECONDS);
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

			// Potion Storage's own varps fired since the last tick (see
			// onVarbitChanged above) -- read its current contents now,
			// on the client thread, one tick later rather than inline in
			// that event handler (see potionStorageDirty's field doc for
			// why the deferral).
			if (potionStorageDirty)
			{
				potionStorageDirty = false;
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
				// Same reasoning as inventory/equipment just above: a Rune
				// Pouch stocked before this plugin ever loaded (or before
				// this login) has no "change" event left to fire for
				// state that was already set, so it's captured here too
				// rather than only ever reacting to onVarbitChanged/
				// onItemContainerChanged from this point forward.
				latestRunePouchItems = readRunePouchItems();
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
