package io.myscape.plugin.collectionlog;

import java.util.LinkedHashMap;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import net.runelite.api.Client;
import net.runelite.api.MenuAction;
import net.runelite.api.gameval.InterfaceID;
import net.runelite.api.gameval.VarbitID;

/**
 * Captures which collection log items the player has obtained, and their quantities.
 *
 * <p>Obtained state is only readable by opening the collection log in-game. This class
 * re-triggers the client's search-then-reset refresh and accumulates the per-item id/quantity
 * broadcast. Event registration stays in {@code MyscapePlugin}, which passes in event data.
 *
 * <p>Technique credit: RuneProfile's plugin (github.com/RuneProfile/runeprofile-plugin,
 * BSD-2-Clause), which credits WikiSync (github.com/weirdgloop/WikiSync, BSD-2-Clause).
 * The script ids and the two-tick settle buffer follow those projects.
 */
public final class CollectionLogItemCapture
{
	/** Fires once per obtained item after the refresh. args[1] is the item id, args[2] the quantity. */
	private static final int COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID = 4100;

	/** Fires whenever the collection log finishes setup, including after our own re-trigger. */
	private static final int COLLECTION_LOG_SETUP_SCRIPT_ID = 7797;

	/** Run after toggling search on then off; makes the client transmit every obtained item. */
	private static final int COLLECTION_LOG_INIT_SCRIPT_ID = 2240;

	/** Ticks without an item transmission after which a pass counts as finished (no explicit end signal exists). */
	private static final int SETTLE_BUFFER_TICKS = 2;

	/** Item id -> quantity, in the order the client sent them. */
	private final Map<Integer, Integer> capturedItems = new LinkedHashMap<>();

	/**
	 * Ids announced by the item script that differ from the id the log structure lists
	 * (see {@link CollectionLogSchemaExporter}). The Yama Dossier is announced as its
	 * placeholder variant 30805 but listed as 30803.
	 */
	private static final Map<Integer, Integer> ANNOUNCED_ID_TO_STRUCTURE_ID = buildAnnouncedIdRemap();

	private static Map<Integer, Integer> buildAnnouncedIdRemap()
	{
		Map<Integer, Integer> remap = new HashMap<>();
		remap.put(30805, 30803); // Dossier (Yama)
		return Collections.unmodifiableMap(remap);
	}

	/** True from the start of a capture until {@link #onGameTick} sees it settle; stops the setup re-fire from re-triggering. */
	private boolean triggeringCapture;

	/** Client tick of the last item transmission, or -1 when no capture pass is in flight. Client thread only. */
	private int lastItemScriptFiredTick = -1;

	/**
	 * Call for every script post-fire. On the setup script, starts a capture by toggling "Search"
	 * on then off and re-running the init script. Clears data and returns when the log was
	 * opened from another player's adventure log book.
	 */
	public void onCollectionLogOpened(int scriptId, Client client)
	{
		if (scriptId != COLLECTION_LOG_SETUP_SCRIPT_ID)
		{
			return;
		}

		if (isOpenedFromAdventureLog(client))
		{
			capturedItems.clear();
			return;
		}

		if (triggeringCapture)
		{
			return;
		}

		triggeringCapture = true;
		client.menuAction(-1, InterfaceID.Collection.SEARCH_TOGGLE, MenuAction.CC_OP, 1, -1, "Search", null);
		client.runScript(COLLECTION_LOG_INIT_SCRIPT_ID);
	}

	/** Call for every script pre-fire; ignores all but the item transmission script. */
	public void onScriptPreFired(int scriptId, Object[] scriptArgs, Client client)
	{
		if (scriptId != COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID)
		{
			return;
		}

		if (isOpenedFromAdventureLog(client))
		{
			return;
		}

		lastItemScriptFiredTick = client.getTickCount();

		int itemId = ANNOUNCED_ID_TO_STRUCTURE_ID.getOrDefault((int) scriptArgs[1], (int) scriptArgs[1]);
		int quantity = (int) scriptArgs[2];
		// The script only fires for obtained items, so store at least 1 even if the
		// quantity argument is lower (the server treats under 1 as not obtained).
		capturedItems.put(itemId, Math.max(1, quantity));
	}

	/** Call when {@code VarbitID.COLLECTION_POH_HOST_BOOK_OPEN} changes. Fail-safe for the book opening between script firings. */
	public void onAdventureLogVarbitChanged(Client client)
	{
		if (isOpenedFromAdventureLog(client))
		{
			capturedItems.clear();
		}
	}

	/** Call each {@code GameTick}. Returns true once per pass, {@link #SETTLE_BUFFER_TICKS} ticks after the last item. */
	public boolean onGameTick(Client client)
	{
		boolean hasCapturePassInFlight = lastItemScriptFiredTick != -1;
		boolean settleBufferElapsed = lastItemScriptFiredTick + SETTLE_BUFFER_TICKS < client.getTickCount();
		if (hasCapturePassInFlight && settleBufferElapsed)
		{
			lastItemScriptFiredTick = -1;
			triggeringCapture = false;
			return true;
		}
		return false;
	}

	/** Returns a copy of the captured item id -> quantity map, safe to hold across ticks. */
	public Map<Integer, Integer> snapshotCapturedItems()
	{
		return new LinkedHashMap<>(capturedItems);
	}

	private static boolean isOpenedFromAdventureLog(Client client)
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}
}
