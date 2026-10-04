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
 * Captures which collection log items a player has obtained, and their
 * quantities -- the one piece of the collection log that isn't cache
 * data (see {@link CollectionLogSchemaExporter}'s own doc for the
 * structure side, which is). Obtained state only becomes readable by
 * actually opening the collection log widget in-game: this class drives
 * that open itself (re-triggering the same client-side search-then-reset
 * the collection log interface's own "Search" button uses to refresh
 * its item grid) and accumulates the resulting per-item id/quantity
 * broadcast as the client dribbles it out, one script firing per item,
 * with no explicit "that's all of them" signal of its own.
 *
 * <p>Credited technique: RuneProfile's own plugin
 * (github.com/RuneProfile/runeprofile-plugin, BSD-2-Clause) --
 * specifically its {@code CollectionLogWidgetSubscriber} -- which in
 * turn credits WikiSync (github.com/weirdgloop/WikiSync, BSD-2-Clause)
 * for the underlying trick. There is no independently-discoverable way
 * to read this data that doesn't amount to the same reverse-engineering
 * those two projects already did, so it is credited here rather than
 * silently reproduced; the sequencing below (which script ids to
 * trigger/watch, and the two-tick settle buffer in {@link
 * #onGameTick(Client)}) matches that source directly.
 *
 * <p>Deliberately holds state (unlike {@link CollectionLogSchemaExporter},
 * a one-shot stateless read) since the client answers across many
 * script firings rather than in one call -- but the actual RuneLite
 * event registration stays in {@code MyscapePlugin}'s own monolithic
 * {@code @Subscribe} handlers, matching every other concern in this
 * plugin: callers hand this class the specific pieces of each event it
 * needs (a script id, its arguments, the {@code Client}) rather than
 * this class registering itself on the event bus.
 */
public final class CollectionLogItemCapture
{
	/** Fires once per obtained item, as a result of {@link
	 * #onCollectionLogOpened}'s own triggered refresh -- args[1] is the
	 * item's widget-facing id (the same id space {@link
	 * CollectionLogSchemaExporter}'s exported schema already uses) and
	 * args[2] its quantity. */
	private static final int COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID = 4100;

	/** Fires every time the collection log interface finishes its own
	 * normal setup -- both the first time a player opens it AND again,
	 * as a side effect, every time {@link #onCollectionLogOpened}
	 * re-triggers that same setup below. */
	private static final int COLLECTION_LOG_SETUP_SCRIPT_ID = 7797;

	/** Re-run by {@link #onCollectionLogOpened} immediately after
	 * toggling search on then off, which is what actually makes the
	 * client dump every obtained item via {@link
	 * #COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID} and then reset the view. */
	private static final int COLLECTION_LOG_INIT_SCRIPT_ID = 2240;

	/** How many ticks of silence (no further {@link
	 * #COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID} firing) mean a capture
	 * pass is actually finished, not just between items -- there is no
	 * explicit "that's all of them" event, so this is the same settle
	 * buffer the credited source uses to infer it. */
	private static final int SETTLE_BUFFER_TICKS = 2;

	/** Item id -> quantity, accumulated one {@link
	 * #COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID} firing at a time.
	 * LinkedHashMap purely so a caller iterating this sees items in the
	 * same order the client sent them, not because anything here
	 * depends on that order. */
	private final Map<Integer, Integer> capturedItems = new LinkedHashMap<>();

	/** Ids the log's item script announces that are NOT the id the log's
	 * own structure (see {@link CollectionLogSchemaExporter}) lists the
	 * slot under, mapped back to that structure id. Found by comparing
	 * a live capture against the structure: the Yama Dossier slot is
	 * announced as 30805 (the Dossier's placeholder variant -- two above
	 * its real id, the usual placeholder offset) while the structure,
	 * and the website, know it as 30803, so it was silently never
	 * matched. */
	private static final Map<Integer, Integer> ANNOUNCED_ID_TO_STRUCTURE_ID = buildAnnouncedIdRemap();

	private static Map<Integer, Integer> buildAnnouncedIdRemap()
	{
		Map<Integer, Integer> remap = new HashMap<>();
		remap.put(30805, 30803); // Dossier (Yama)
		return Collections.unmodifiableMap(remap);
	}

	/** Set the instant {@link #onCollectionLogOpened} triggers its own
	 * search-toggle + init-script sequence, and cleared once {@link
	 * #onGameTick} decides that sequence's transmissions have settled.
	 * Guards {@link #onCollectionLogOpened} against re-triggering
	 * itself off of the very setup re-fire its own init-script causes
	 * -- without this, that re-fire would trigger another
	 * search-toggle + init-script, which would cause another re-fire,
	 * forever. */
	private boolean triggeringCapture;

	/** The client tick {@link #COLLECTION_DELAYED_TRANSMIT_SCRIPT_ID}
	 * last fired on, or -1 when no capture pass is currently in
	 * flight. Read and advanced only from {@link #onScriptPreFired}
	 * and {@link #onGameTick}, both always called from the client
	 * thread same as every other RuneLite event handler. */
	private int lastItemScriptFiredTick = -1;

	/** Call from {@code MyscapePlugin}'s own {@code onScriptPostFired}
	 * for every script post-fire, not just this one -- this method
	 * itself checks the script id and returns immediately for any
	 * other. Triggers a fresh capture pass: toggles the collection
	 * log's own "Search" button on then off (the exact op the
	 * interface's own button performs) and re-runs its init script,
	 * which is what makes the client actually dump every obtained
	 * item's id and quantity.
	 *
	 * <p>Skips entirely -- and clears anything captured so far -- when
	 * this collection log was opened by viewing another player's
	 * collection log through a player-owned-house adventure log/book,
	 * so viewing someone else's collection log can never get captured
	 * and reported as the local player's own. */
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

	/** Call from {@code MyscapePlugin}'s own {@code onScriptPreFired}
	 * for every script pre-fire, not just this one -- this method
	 * itself checks the script id and returns immediately for any
	 * other. */
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
		// The script only fires for items the log counts as obtained, so
		// a firing at all means "obtained" even with a quantity argument
		// below 1 -- store at least 1 (the server drops anything under 1
		// as "not obtained", which would silently lose the item).
		capturedItems.put(itemId, Math.max(1, quantity));
	}

	/** Call from {@code MyscapePlugin}'s own {@code onVarbitChanged}
	 * whenever {@code VarbitID.COLLECTION_POH_HOST_BOOK_OPEN} itself
	 * changes -- a fail-safe alongside the same guard already inside
	 * {@link #onCollectionLogOpened}/{@link #onScriptPreFired} above,
	 * in case the adventure log's book opens (or is already open) at
	 * some point other than exactly when those two fire. */
	public void onAdventureLogVarbitChanged(Client client)
	{
		if (isOpenedFromAdventureLog(client))
		{
			capturedItems.clear();
		}
	}

	/** Call once per {@code GameTick}. Returns true exactly once per
	 * capture pass, the tick a pass is actually decided to be finished
	 * -- {@link #SETTLE_BUFFER_TICKS} ticks after the last item
	 * transmission, since there is no event that says "that was the
	 * last one" any more directly than that. Callers should read
	 * {@link #snapshotCapturedItems()} once this returns true. */
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

	/** A snapshot of every item id -> quantity captured so far this
	 * session. Copied out rather than exposing the live map, since a
	 * caller may hold onto this across ticks (e.g. to hand off to a
	 * sync call) while capture keeps running concurrently on the
	 * client thread. */
	public Map<Integer, Integer> snapshotCapturedItems()
	{
		return new LinkedHashMap<>(capturedItems);
	}

	private static boolean isOpenedFromAdventureLog(Client client)
	{
		return client.getVarbitValue(VarbitID.COLLECTION_POH_HOST_BOOK_OPEN) == 1;
	}
}
