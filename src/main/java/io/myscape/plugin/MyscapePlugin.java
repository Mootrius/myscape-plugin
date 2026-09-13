package io.myscape.plugin;

import com.google.inject.Provides;
import java.awt.image.BufferedImage;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import javax.inject.Inject;
import net.runelite.api.Client;
import net.runelite.api.EquipmentInventorySlot;
import net.runelite.api.GameState;
import net.runelite.api.Item;
import net.runelite.api.ItemContainer;
import net.runelite.api.events.GameStateChanged;
import net.runelite.api.events.GameTick;
import net.runelite.api.events.ItemContainerChanged;
import net.runelite.api.gameval.InventoryID;
import net.runelite.client.config.ConfigManager;
import net.runelite.client.eventbus.Subscribe;
import net.runelite.client.game.ItemManager;
import net.runelite.client.plugins.Plugin;
import net.runelite.client.plugins.PluginDescriptor;
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
 * inventory, and worn equipment in sync -- the data collection the
 * Purchase Goals feature is built on (see onItemContainerChanged and
 * onGameTick below). Loot Tracker data collection builds on top of the
 * same link later.
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

	/** (Re)schedules the single shared debounce task -- shared across
	 * bank/inventory/equipment rather than one per container, since a
	 * player actively shopping typically touches more than one of them
	 * in the same burst (withdrawing from the bank, then equipping what
	 * they withdrew), and there's no reason to send three separate
	 * requests a couple of ticks apart for what's really one action.
	 * When it fires, it syncs whichever of the three have actually been
	 * observed at least once (see the field docs above) -- reading only
	 * the already-captured volatile snapshots, never the RuneLite Client
	 * itself, since this runs on `executor`'s thread, not the client
	 * thread. */
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
					panel.syncInBackground(latestBankItems, latestInventoryItems, latestEquipmentItems);
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
