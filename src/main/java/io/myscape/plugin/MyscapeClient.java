package io.myscape.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to myscape.io's account-linking and item-sync endpoints -- POST
 * /api/link/exchange, GET /api/players/me, and POST
 * /api/players/me/{bank,inventory,equipment,potion-storage} (see app/routers/linking.py
 * and app/main.py in the Mootrius/myscape repo for the server side of
 * this contract). Everything here is async (OkHttp's enqueue/Callback):
 * every caller is a Swing button handler, plugin startup, or a
 * background event handler, and blocking any of those would freeze the
 * client UI.
 */
class MyscapeClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final Gson GSON = new Gson();
	// The plugin used to expose this as a "API base URL" config setting
	// (only ever useful for pointing a client at a local dev server
	// instead of production) -- removed since it was never actually
	// used that way in practice, so this is just a plain constant now.
	private static final String API_BASE_URL = "https://myscape.io";

	private final OkHttpClient httpClient;
	private final ConfigManager configManager;

	MyscapeClient(OkHttpClient httpClient, ConfigManager configManager)
	{
		this.httpClient = httpClient;
		this.configManager = configManager;
	}

	/** One bank slot's contents -- just enough for the server to know
	 * what's owned and how many (see BankItem in the Mootrius/myscape
	 * repo's app/models.py). A *tradeable* item's name/icon/price are
	 * looked up server-side from the wiki's GE mapping, not sent from
	 * here -- itemName exists only so an untradeable item (which has no
	 * entry in that mapping at all) can still be matched by name against
	 * a gear-setup slot the player typed it into by hand; see BankItem's
	 * docstring server-side for the full rationale. Always populated
	 * (see MyscapePlugin's item-reading methods), even though only the
	 * untradeable case ever actually needs it -- simpler than this
	 * plugin having to know ahead of time which items are tradeable. */
	static final class BankItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		BankItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem, kept as its own type (rather than reusing
	 * BankItem) so a caller can never accidentally pass one container's
	 * snapshot to the other's sync method -- see InventoryItem in the
	 * Mootrius/myscape repo's app/models.py for the server side. */
	static final class InventoryItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		InventoryItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** One worn equipment slot -- unlike Bank/InventoryItem this also
	 * carries which slot it's in ("head", "weapon", ..., matching
	 * EQUIPMENT_SLOTS in the original Maintrius tool this is replacing),
	 * since equipment is keyed by slot server-side rather than being a
	 * flat list (see EquipmentItem in app/models.py). */
	static final class EquipmentItem
	{
		final String slot;
		final int itemId;
		final String itemName;
		final int quantity;

		EquipmentItem(String slot, int itemId, String itemName, int quantity)
		{
			this.slot = slot;
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem/InventoryItem again, for the
	 * Player-Owned-House Potion Storage -- see PotionStorageItem in the
	 * Mootrius/myscape repo's app/models.py for the server side, and
	 * MyscapePlugin#readPotionStorageItems for how this is actually
	 * built (Potion Storage isn't an ItemContainer, so it can't just be
	 * read the way bank/inventory/equipment are -- it's decomposed from
	 * each stored potion's total dose count into the same real item ids
	 * withdrawing it would hand back). This is what replaced the old
	 * "supplies" gear-slot flag -- a potion sitting in storage can now
	 * be recognized as genuinely owned instead of needing to be marked
	 * always-owned by hand. */
	static final class PotionStorageItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		PotionStorageItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem/InventoryItem again, for a Rune Pouch's
	 * contents -- see RunePouchItem in the Mootrius/myscape repo's
	 * app/models.py for the server side, and
	 * MyscapePlugin#readRunePouchItems for how this is actually built
	 * (a Rune Pouch is a single inventory item whose contents RuneLite
	 * only exposes through varbits/an enum, not an ItemContainer, so --
	 * same as Potion Storage -- it can't just be read the way
	 * bank/inventory/equipment are). Deliberately synced through its own
	 * endpoint rather than folded into InventoryItem's payload: the
	 * pouch itself is one inventory slot, but its contents can add up to
	 * several more distinct item entries, and /inventory's sync caps the
	 * number of distinct entries per request -- routing those through a
	 * separate endpoint (mirroring Potion Storage) avoids a stocked
	 * pouch ever pushing an otherwise-normal inventory over that cap. */
	static final class RunePouchItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		RunePouchItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem/InventoryItem again, for a Looting Bag's
	 * contents -- see LootingBagItem in the Mootrius/myscape repo's
	 * app/models.py for the server side, and
	 * MyscapePlugin#readLootingBagItems for how this is actually built.
	 * Unlike Potion Storage/Rune Pouch above, a Looting Bag genuinely IS
	 * an ItemContainer (InventoryID.LOOTING_BAG) -- it's read the same
	 * straightforward way Bank is, only reporting contents once the
	 * player has actually opened it at least once this session (same
	 * caveat as Bank). */
	static final class LootingBagItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		LootingBagItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem/InventoryItem/LootingBagItem again, for
	 * the Farming Guild Seed Vault's contents -- see SeedVaultItem in
	 * the Mootrius/myscape repo's app/models.py for the server side, and
	 * MyscapePlugin#readSeedVaultItems for how this is actually built.
	 * Same deal as Looting Bag above: a genuine ItemContainer
	 * (InventoryID.SEED_VAULT), read the same straightforward way, only
	 * reporting contents once the player has actually opened it at
	 * least once this session (same caveat as Bank/Looting Bag). */
	static final class SeedVaultItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		SeedVaultItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for a Tackle Box's contents -- see
	 * TackleBoxItem in the Mootrius/myscape repo's app/models.py for the
	 * server side, and MyscapePlugin#readTackleBoxItems for how this is
	 * actually built. Same deal as Looting Bag/Seed Vault above: a
	 * genuine ItemContainer (InventoryID.TACKLE_BOX), read the same
	 * straightforward way, only reporting contents once the player has
	 * actually opened it at least once this session (same caveat as
	 * Bank/Looting Bag/Seed Vault). */
	static final class TackleBoxItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		TackleBoxItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape as BankItem/InventoryItem/LootingBagItem again, for
	 * the whole Player-Owned-House Costume Room -- see PohItem in the
	 * Mootrius/myscape repo's app/models.py for the server side, and
	 * MyscapePlugin#readPohItems for how this is actually built. Named
	 * "PoH" (not "Costume Room") since that's the more general house-
	 * storage bucket Storages Viewer's location filter groups
	 * this under -- see PohItem's own docstring server-side.
	 * Originally six separate classes, one per piece of furniture,
	 * each assumed to back its own ItemContainer
	 * (InventoryID.POH_COSTUME_ROOM_MAGIC_WARDROBE_INV and friends).
	 * Live testing showed every piece actually fires
	 * ItemContainerChanged on the same containerId (33405, not any of
	 * those named constants) with identical contents regardless of
	 * which piece was opened -- Jagex's newer, consolidated Costume
	 * Room interface (InterfaceID.POH_COSTUMES) reads from one unified
	 * store, not six. Same open-it-once caveat as every other source
	 * here: contents are only reported once the player has opened the
	 * Costume Room interface at least once this session. */
	static final class PohItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		PohItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for the Forestry Kit's contents -- see
	 * ForestryKitItem in the Mootrius/myscape repo's app/models.py for
	 * the server side, and MyscapePlugin#readForestryKitItems for how
	 * this is actually built. A genuine ItemContainer
	 * (InventoryID.FORESTRY_KIT), read the same straightforward way as
	 * Tackle Box/Seed Vault above, only reporting contents once the
	 * player has actually opened it at least once this session (same
	 * caveat as everywhere else here). Grouped under "PORTABLE" rather
	 * than "PoH" on Storages Viewer -- it's carried, not house
	 * storage. */
	static final class ForestryKitItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		ForestryKitItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for a Seed Box's contents -- see SeedBoxItem
	 * in the Mootrius/myscape repo's app/models.py for the server side
	 * (distinct from SeedVaultItem above), and
	 * MyscapePlugin#readSeedBoxItems for how this is actually built. A
	 * genuine ItemContainer (InventoryID.SEED_BOX), read the same
	 * straightforward way as Tackle Box/Forestry Kit, only reporting
	 * contents once the player has actually opened it at least once
	 * this session. */
	static final class SeedBoxItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		SeedBoxItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for the Master Scroll Book's contents -- see
	 * MasterScrollBookItem in the Mootrius/myscape repo's app/models.py
	 * for the server side. Unlike every other source in this file, this
	 * one isn't backed by a RuneLite ItemContainer at all -- confirmed
	 * absent by research, then confirmed absent for real by a
	 * diagnostic that logged every otherwise-unrecognized container id
	 * for a full play session and caught nothing but unrelated Grand
	 * Exchange activity. MyscapePlugin#readMasterScrollBookItems reads
	 * this straight off InterfaceID.BOOKOFSCROLLS' own widgets instead
	 * -- see that method's own comment for the widget layout a second
	 * diagnostic found. */
	static final class MasterScrollBookItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		MasterScrollBookItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for a Huntsman's Kit's contents -- see
	 * HuntsmansKitItem in the Mootrius/myscape repo's app/models.py for
	 * the server side, and MyscapePlugin#readHuntsmansKitItems for how
	 * this is actually built. A genuine ItemContainer
	 * (InventoryID.HUNTSMANS_KIT), read the same straightforward way as
	 * Tackle Box/Forestry Kit/Seed Box, only reporting contents once the
	 * player has actually opened it at least once this session (and,
	 * since this is one of the "virtual storage" sources, now also once
	 * it's confirmed empty on that first open -- see
	 * MyscapePlugin#pendingContainerRereadTicks for that fix). */
	static final class HuntsmansKitItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		HuntsmansKitItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** Same shape again, for one boat's Cargo Hold contents -- see
	 * BoatCargoItem in the Mootrius/myscape repo's app/models.py for the
	 * server side. Deliberately carries no `slot` field: which of a
	 * player's up to five boats this came from is a property of the
	 * *sync call* (see syncBoatCargo below), not of the item data itself
	 * -- the server-side table is what actually needs a slot column,
	 * since (unlike PoH's furniture, which is identical no matter which
	 * house it's in) each boat's cargo hold is genuinely independent
	 * contents. A genuine ItemContainer
	 * (InventoryID.SAILING_BOAT_1_CARGOHOLD..SAILING_BOAT_5_CARGOHOLD,
	 * one per boat slot), read the same straightforward way as the other
	 * virtual storages -- see MyscapePlugin#readBoatCargoItems and
	 * #pendingContainerRereadTicks for the empty-on-first-open fix this
	 * source also needed. */
	static final class BoatCargoItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		BoatCargoItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** One skill's current level+xp -- see Player.skills' docstring in
	 * the Mootrius/myscape repo's app/models.py for the server side, and
	 * MyscapePlugin#readSkills for how this is actually built. `skill`
	 * is always the lowercase RuneLite enum name (e.g. "attack",
	 * "sailing"), matching SKILL_NAMES in app/mindmaps.py exactly since
	 * both sides ultimately derive from the same 24-entry set of real
	 * OSRS skills -- see readSkills' own comment for why this plugin
	 * never has to hardcode that list itself. `level` is the player's
	 * REAL (unboosted) level, not whatever a potion currently has it
	 * boosted to -- a boost is transient and shouldn't make a goal
	 * flicker complete and then incomplete again a few minutes later. */
	static final class SkillItem
	{
		final String skill;
		final int level;
		final int xp;

		SkillItem(String skill, int level, int xp)
		{
			this.skill = skill;
			this.level = level;
			this.xp = xp;
		}
	}

	/** One achievement diary area's highest completed tier -- see
	 * Player.diary_tiers' docstring in the Mootrius/myscape repo's
	 * app/models.py for the server side, and
	 * MyscapePlugin#readDiaryTiers for how this is actually built. Only
	 * ever sent for an area with at least Easy complete -- an area
	 * with nothing done yet is simply left out of the list, the same
	 * way the server itself only ever stores one current tier per area
	 * (see that same docstring). */
	static final class DiaryTierItem
	{
		final String area;
		final String tier;

		DiaryTierItem(String area, String tier)
		{
			this.area = area;
			this.tier = tier;
		}
	}

	/** The seven Character Summary stats read straight off the "Character
	 * Summary" tab of the quest journal's Account Summary sidepanel (see
	 * MyscapePlugin#readCharacterSummary for how these are actually read,
	 * and Player's docstring in the Mootrius/myscape repo's app/models.py
	 * for the server side). Unlike SkillItem/DiaryTierItem above, this
	 * panel is only ever readable while the player has that specific tab
	 * open in-game -- there's no ItemContainer or varbit backing any of
	 * these seven numbers, so a session that never opens that tab simply
	 * never has anything to sync here, the same way Master Scroll Book
	 * only ever syncs once BOOKOFSCROLLS has actually been opened.
	 *
	 * questsCompleted/questsTotal ("183/184" on the panel) stands in for
	 * quest points -- the panel doesn't surface a raw QP number, only
	 * this completion count. */
	static final class CharacterSummaryItem
	{
		final double combatLevel;
		final int totalLevel;
		final long totalXp;
		final int questsCompleted;
		final int questsTotal;
		final int achievementsCompleted;
		final int achievementsTotal;
		final int combatTasksCompleted;
		final int combatTasksTotal;
		final int collectionsCompleted;
		final int collectionsTotal;

		CharacterSummaryItem(
			double combatLevel, int totalLevel, long totalXp,
			int questsCompleted, int questsTotal,
			int achievementsCompleted, int achievementsTotal,
			int combatTasksCompleted, int combatTasksTotal,
			int collectionsCompleted, int collectionsTotal)
		{
			this.combatLevel = combatLevel;
			this.totalLevel = totalLevel;
			this.totalXp = totalXp;
			this.questsCompleted = questsCompleted;
			this.questsTotal = questsTotal;
			this.achievementsCompleted = achievementsCompleted;
			this.achievementsTotal = achievementsTotal;
			this.combatTasksCompleted = combatTasksCompleted;
			this.combatTasksTotal = combatTasksTotal;
			this.collectionsCompleted = collectionsCompleted;
			this.collectionsTotal = collectionsTotal;
		}
	}

	/** One item from a single loot event (a kill, a clue casket, a
	 * pickpocket) -- see LootEvent's docstring in the Mootrius/myscape
	 * repo's app/models.py for the server side. Unlike BankItem and
	 * friends above, this isn't part of a "current state" snapshot --
	 * see LootEvent below, and MyscapePlugin#onLootReceived for how
	 * this is actually built. */
	static final class LootEventItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		LootEventItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** One kill/casket/pickpocket's worth of loot -- see LootEvent's
	 * docstring in the Mootrius/myscape repo's app/models.py for the
	 * full rationale (this is an append-only historical record, not a
	 * snapshot the server deletes-and-replaces the way every other
	 * synced source above is). eventId is generated once, the moment
	 * the drop happens (see MyscapePlugin#onLootReceived), specifically
	 * so a retried or duplicated sync of the same event is safe instead
	 * of double-counted server-side. combatLevel is null for anything
	 * without one (clue caskets, pickpocket targets) -- RuneLite's own
	 * LootReceived event reports -1 in that case; see onLootReceived for
	 * where that's translated to null. */
	static final class LootEvent
	{
		final String eventId;
		final String sourceName;
		final String sourceType;
		final Integer combatLevel;
		final String occurredAt;
		final List<LootEventItem> items;

		LootEvent(String eventId, String sourceName, String sourceType, Integer combatLevel, String occurredAt, List<LootEventItem> items)
		{
			this.eventId = eventId;
			this.sourceName = sourceName;
			this.sourceType = sourceType;
			this.combatLevel = combatLevel;
			this.occurredAt = occurredAt;
			this.items = items;
		}
	}

	interface LinkCallback
	{
		void onSuccess(String runescapeName);
		void onFailure(String message);
	}

	interface StatusCallback
	{
		void onLinked(String runescapeName);
		void onNotLinked();
		void onError(String message);
	}

	interface UnlinkCallback
	{
		void onSuccess();
		void onFailure(String message);
	}

	/** Shared by every item-sync method (bank/inventory/equipment/potion
	 * storage) -- all four POST an {"items": [...]} body and get back
	 * the same {"item_count": N} shape, so one callback interface covers
	 * all of them rather than four identical ones. */
	interface SyncCallback
	{
		void onSuccess(int itemCount);
		void onFailure(String message);
	}

	/** Backs the side panel's Status section (see MyscapePanel's Status
	 * card and fetchSyncStatus below) -- storages is keyed by the same
	 * storage keys the server's own POSSESSION_STORAGE_KEYS uses ("bank",
	 * "equipment", "inventory", "potion_storage", "rune_pouch",
	 * "seed_vault", "looting_bag", "tackle_box", "poh", "forestry_kit",
	 * "master_scroll_book", "seed_box"), each value either an ISO-8601
	 * instant (that storage has synced at least once) or null (it never
	 * has -- see Player.storage_synced_at's own docstring server-side
	 * for why that's a real, distinct state worth showing rather than
	 * just inferring it from whether an item list happens to be
	 * empty). */
	interface SyncStatusCallback
	{
		void onSuccess(Map<String, String> storages);
		void onFailure(String message);
	}

	/** Exchanges a short-lived link code (pasted from the website) for a
	 * long-lived API key, and stores that key -- never shown again after
	 * this -- plus the player id it belongs to, via ConfigManager. */
	void exchangeLinkCode(String code, long accountHash, String runescapeName, LinkCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		// account_hash is naturally an unsigned 64-bit value and can
		// exceed Long.MAX_VALUE -- toUnsignedString avoids that coming
		// out as a negative number, which would silently produce a
		// *different* (and wrong) string than the same account would
		// ever produce again.
		body.addProperty("account_hash", Long.toUnsignedString(accountHash));
		if (runescapeName != null)
		{
			body.addProperty("runescape_name", runescapeName);
		}

		Request request = new Request.Builder()
			.url(apiUrl("/api/link/exchange"))
			.post(RequestBody.create(JSON, GSON.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not reach myscape.io. Check your connection and try again.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						callback.onFailure(describeError(r.code(), responseBody));
						return;
					}
					JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
					String apiKey = json.get("api_key").getAsString();
					String playerId = json.get("player_id").getAsString();
					configManager.setConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY, apiKey);
					configManager.setConfiguration(MyscapeConfig.GROUP, MyscapeConfig.PLAYER_ID_KEY, playerId);
					configManager.setConfiguration(MyscapeConfig.GROUP, MyscapeConfig.RUNESCAPE_NAME_KEY,
						runescapeName == null ? "" : runescapeName);
					callback.onSuccess(runescapeName);
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Confirms the stored API key (if any) is still valid by calling
	 * GET /api/players/me -- also picks up the player's current display
	 * name from the server, in case it's changed since linking. */
	void refreshLinkStatus(StatusCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			callback.onNotLinked();
			return;
		}

		Request request = new Request.Builder()
			.url(apiUrl("/api/players/me"))
			.header("Authorization", "Bearer " + apiKey)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onError("Could not reach myscape.io.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						// The key was revoked server-side (or never valid) --
						// clear it locally too, rather than leaving the panel
						// stuck claiming "linked" forever.
						clearStoredLink();
						callback.onNotLinked();
						return;
					}
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						callback.onError(describeError(r.code(), responseBody));
						return;
					}
					JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
					String name = json.has("runescape_name") && !json.get("runescape_name").isJsonNull()
						? json.get("runescape_name").getAsString()
						: null;
					callback.onLinked(name);
				}
				catch (Exception e)
				{
					callback.onError("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Calls the server to actually revoke the link (POST
	 * /api/players/me/unlink -- see app/main.py in the Mootrius/myscape
	 * repo), which is what clears account_hash there and brings the
	 * website's link banner back. Only clears the locally stored
	 * credential once the server confirms it, rather than forgetting it
	 * unconditionally: if this call fails (network down, say) and the
	 * plugin pretended it was unlinked anyway, the forgotten key would
	 * still be perfectly valid server-side, which is exactly the kind of
	 * silently-still-linked state this exists to avoid. */
	void unlink(UnlinkCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			// Nothing stored to revoke -- already effectively unlinked.
			clearStoredLink();
			callback.onSuccess();
			return;
		}

		Request request = new Request.Builder()
			.url(apiUrl("/api/players/me/unlink"))
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(JSON, ""))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not reach myscape.io. Check your connection and try again.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						// The key was already invalid (revoked/expired) --
						// there's nothing left to revoke server-side, so
						// this still counts as a successful unlink.
						clearStoredLink();
						callback.onSuccess();
						return;
					}
					if (!r.isSuccessful())
					{
						String responseBody = r.body() != null ? r.body().string() : "";
						callback.onFailure(describeError(r.code(), responseBody));
						return;
					}
					clearStoredLink();
					callback.onSuccess();
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Sends the current full bank snapshot to myscape.io (POST
	 * /api/players/me/bank -- see app/main.py in the Mootrius/myscape
	 * repo), for the Purchase Goals feature to compare against. Always a
	 * full replace server-side, never a merge -- see that endpoint's
	 * docstring -- so this should always be called with *everything*
	 * currently in the bank, not just what changed. */
	void syncBank(List<BankItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (BankItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/bank", itemsJson, callback);
	}

	/** Same deal as syncBank, for the main 28-slot inventory (POST
	 * /api/players/me/inventory). */
	void syncInventory(List<InventoryItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (InventoryItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/inventory", itemsJson, callback);
	}

	/** Same deal as syncBank, for worn equipment (POST
	 * /api/players/me/equipment) -- each item also carries its slot,
	 * since equipment is keyed by slot server-side, not a flat list. */
	void syncEquipment(List<EquipmentItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (EquipmentItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("slot", item.slot);
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/equipment", itemsJson, callback);
	}

	/** Same deal as syncBank, for Potion Storage (POST
	 * /api/players/me/potion-storage). */
	void syncPotionStorage(List<PotionStorageItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (PotionStorageItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/potion-storage", itemsJson, callback);
	}

	/** Same deal as syncBank, for a Rune Pouch's contents (POST
	 * /api/players/me/rune-pouch). */
	void syncRunePouch(List<RunePouchItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (RunePouchItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/rune-pouch", itemsJson, callback);
	}

	/** Same deal as syncBank, for a Looting Bag's contents (POST
	 * /api/players/me/looting-bag). */
	void syncLootingBag(List<LootingBagItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (LootingBagItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/looting-bag", itemsJson, callback);
	}

	/** Same deal as syncBank, for the Seed Vault's contents (POST
	 * /api/players/me/seed-vault). */
	void syncSeedVault(List<SeedVaultItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (SeedVaultItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/seed-vault", itemsJson, callback);
	}

	/** Same deal as syncBank, for a Tackle Box's contents (POST
	 * /api/players/me/tackle-box). */
	void syncTackleBox(List<TackleBoxItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (TackleBoxItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/tackle-box", itemsJson, callback);
	}

	/** Same deal as syncBank, for the whole PoH Costume Room's contents
	 * (POST /api/players/me/poh) -- one endpoint covering all six
	 * pieces of furniture, not six separate ones (see PohItem's own
	 * comment above for why). Path is "/poh", not "/costume-room" --
	 * see PohItem's comment for why this is named PoH rather than
	 * Costume Room. */
	void syncPoh(List<PohItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (PohItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/poh", itemsJson, callback);
	}

	/** Same deal as syncBank, for the Forestry Kit's contents (POST
	 * /api/players/me/forestry-kit). */
	void syncForestryKit(List<ForestryKitItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (ForestryKitItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/forestry-kit", itemsJson, callback);
	}

	/** Same deal as syncBank, for a Seed Box's contents (POST
	 * /api/players/me/seed-box). */
	void syncSeedBox(List<SeedBoxItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (SeedBoxItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/seed-box", itemsJson, callback);
	}

	/** Same deal as syncBank, for the Master Scroll Book's contents
	 * (POST /api/players/me/master-scroll-book) -- see
	 * MasterScrollBookItem's own comment above for how these are read,
	 * unlike every other source here. */
	void syncMasterScrollBook(List<MasterScrollBookItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (MasterScrollBookItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/master-scroll-book", itemsJson, callback);
	}

	/** Same deal as syncBank, for a Huntsman's Kit's contents (POST
	 * /api/players/me/huntsmans-kit). */
	void syncHuntsmansKit(List<HuntsmansKitItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (HuntsmansKitItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/huntsmans-kit", itemsJson, callback);
	}

	/** Same deal as syncBank, for ONE boat's Cargo Hold contents (POST
	 * /api/players/me/boat-cargo/{slot}) -- `slot` (1-5, one of a
	 * player's up to five independently-named boats, see BoatCargoItem's
	 * own comment above for why) is part of the URL path itself, matching
	 * the server-side endpoint exactly (see app/main.py's sync_boat_cargo
	 * in the Mootrius/myscape repo): the request body is just this slot's
	 * own items, same shape as every other syncX call here, so the
	 * server only ever deletes and replaces that one slot's rows rather
	 * than risking wiping out the other four boats' already-known cargo. */
	void syncBoatCargo(int slot, List<BoatCargoItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (BoatCargoItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/boat-cargo/" + slot, itemsJson, callback);
	}

	/** Polls GET /api/players/me/sync-status (see app/main.py in the
	 * Mootrius/myscape repo) -- what the side panel's Status section
	 * actually shows (see MyscapePanel's Status card). Deliberately its
	 * own request rather than folded into refreshLinkStatus above: link
	 * status is checked on a slow, fixed poll from plugin startup, while
	 * the Status section also needs to reflect this session's own syncs
	 * the moment they land, and mixing the two would mean either polling
	 * link status far more often than it needs (wasteful) or the Status
	 * section lagging behind syncs that just happened (misleading). */
	void fetchSyncStatus(SyncStatusCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			callback.onFailure("Not linked.");
			return;
		}

		Request request = new Request.Builder()
			.url(apiUrl("/api/players/me/sync-status"))
			.header("Authorization", "Bearer " + apiKey)
			.get()
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not reach myscape.io.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						clearStoredLink();
						callback.onFailure("No longer linked to myscape.io.");
						return;
					}
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						callback.onFailure(describeError(r.code(), responseBody));
						return;
					}
					JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
					JsonObject storagesJson = json.getAsJsonObject("storages");
					Map<String, String> storages = new LinkedHashMap<>();
					if (storagesJson != null)
					{
						for (Map.Entry<String, JsonElement> entry : storagesJson.entrySet())
						{
							JsonElement value = entry.getValue();
							storages.put(entry.getKey(), value.isJsonNull() ? null : value.getAsString());
						}
					}
					callback.onSuccess(storages);
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Sends the player's current level+xp in every skill the plugin has
	 * read at least once this session (POST /api/players/me/skills --
	 * see app/main.py in the Mootrius/myscape repo). Always a full
	 * replace server-side, same as syncBank and friends above -- but
	 * unlike those, this is never a *partial* snapshot in practice
	 * either, since MyscapePlugin reads every one of Skill.values() in
	 * one pass (see readSkills), not just whichever skill just changed. */
	void syncSkills(List<SkillItem> skills, SyncCallback callback)
	{
		JsonArray skillsJson = new JsonArray();
		for (SkillItem skill : skills)
		{
			JsonObject skillJson = new JsonObject();
			skillJson.addProperty("skill", skill.skill);
			skillJson.addProperty("level", skill.level);
			skillJson.addProperty("xp", skill.xp);
			skillsJson.add(skillJson);
		}
		JsonObject body = new JsonObject();
		body.add("skills", skillsJson);
		syncPayload("/api/players/me/skills", body, "skill_count", callback);
	}

	/** Sends the player's full completed quest/miniquest list (POST
	 * /api/players/me/quests -- see app/main.py in the Mootrius/myscape
	 * repo) -- every net.runelite.api.Quest this client thread has seen
	 * report QuestState.FINISHED (see MyscapePlugin#readCompletedQuests),
	 * by name, exactly as RuneLite itself names it; matched
	 * case-insensitively against a blue goal node's chosen quest
	 * server-side, so this doesn't need to normalize anything itself.
	 * Always a full replace, like every other current-state sync here. */
	void syncQuests(List<String> completedQuestNames, SyncCallback callback)
	{
		JsonArray questsJson = new JsonArray();
		for (String name : completedQuestNames)
		{
			questsJson.add(name);
		}
		JsonObject body = new JsonObject();
		body.add("completed_quests", questsJson);
		syncPayload("/api/players/me/quests", body, "quest_count", callback);
	}

	/** Sends the player's highest completed tier per achievement diary
	 * area (POST /api/players/me/diaries -- see app/main.py in the
	 * Mootrius/myscape repo) -- see MyscapePlugin#readDiaryTiers for how
	 * this is actually computed from RuneLite's own per-tier "complete"
	 * varbits. Always a full replace, like every other current-state
	 * sync here. */
	void syncDiaries(List<DiaryTierItem> diaryTiers, SyncCallback callback)
	{
		JsonArray diariesJson = new JsonArray();
		for (DiaryTierItem tier : diaryTiers)
		{
			JsonObject tierJson = new JsonObject();
			tierJson.addProperty("area", tier.area);
			tierJson.addProperty("tier", tier.tier);
			diariesJson.add(tierJson);
		}
		JsonObject body = new JsonObject();
		body.add("diary_tiers", diariesJson);
		syncPayload("/api/players/me/diaries", body, "area_count", callback);
	}

	/** Sends the seven Character Summary stats (POST
	 * /api/players/me/character-summary -- see app/main.py in the
	 * Mootrius/myscape repo) -- see MyscapePlugin#readCharacterSummary
	 * for how these are actually read off the in-game panel. Unlike
	 * every other sync* method above, this always sends exactly one
	 * object rather than a list (there's only ever one "current"
	 * summary), so the server's response field this reads back is a
	 * fixed stat_count rather than a count of items actually sent --
	 * see that endpoint's own comment for why. */
	void syncCharacterSummary(CharacterSummaryItem summary, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("combat_level", summary.combatLevel);
		body.addProperty("total_level", summary.totalLevel);
		body.addProperty("total_xp", summary.totalXp);
		body.addProperty("quests_completed", summary.questsCompleted);
		body.addProperty("quests_total", summary.questsTotal);
		body.addProperty("achievements_completed", summary.achievementsCompleted);
		body.addProperty("achievements_total", summary.achievementsTotal);
		body.addProperty("combat_tasks_completed", summary.combatTasksCompleted);
		body.addProperty("combat_tasks_total", summary.combatTasksTotal);
		body.addProperty("collections_completed", summary.collectionsCompleted);
		body.addProperty("collections_total", summary.collectionsTotal);
		syncPayload("/api/players/me/character-summary", body, "stat_count", callback);
	}

	/** Sends one loot event to myscape.io (POST /api/players/me/loot --
	 * see app/main.py in the Mootrius/myscape repo) the moment it
	 * happens, rather than being folded into the shared debounced sync
	 * the other five sync* methods above go through -- see LootEvent's
	 * docstring in the Mootrius/myscape repo's app/models.py for why
	 * this endpoint's request/response shape ({"events": [...]} in,
	 * {"added_count": N} out) is different enough from syncItems' shared
	 * {"items": [...]}/{"item_count": N} contract that it isn't reused
	 * here. callback's int is added_count (0 if the server already had
	 * this event on file -- a normal outcome on a retry, not a failure;
	 * see MyscapePlugin#onLootReceived), not a running total. */
	void syncLootEvent(LootEvent event, SyncCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			return;
		}

		JsonArray itemsJson = new JsonArray();
		for (LootEventItem item : event.items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		JsonObject eventJson = new JsonObject();
		eventJson.addProperty("event_id", event.eventId);
		eventJson.addProperty("source_name", event.sourceName);
		eventJson.addProperty("source_type", event.sourceType);
		if (event.combatLevel != null)
		{
			eventJson.addProperty("combat_level", event.combatLevel);
		}
		eventJson.addProperty("occurred_at", event.occurredAt);
		eventJson.add("items", itemsJson);

		JsonArray eventsJson = new JsonArray();
		eventsJson.add(eventJson);
		JsonObject body = new JsonObject();
		body.add("events", eventsJson);

		Request request = new Request.Builder()
			.url(apiUrl("/api/players/me/loot"))
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(JSON, GSON.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not reach myscape.io. Check your connection and try again.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						clearStoredLink();
						callback.onFailure("No longer linked to myscape.io.");
						return;
					}
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						callback.onFailure(describeError(r.code(), responseBody));
						return;
					}
					JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
					callback.onSuccess(json.get("added_count").getAsInt());
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Sends one live kill-count reading straight from the chatbox to
	 * myscape.io (POST /api/players/me/kill-counts -- see app/main.py
	 * and app/hiscores.py::apply_kill_count_updates in the
	 * Mootrius/myscape repo) the moment
	 * MyscapePlugin#onChatMessage parses one out. Unlike syncLootEvent
	 * above, this reuses syncPayload below rather than building its
	 * own request/response handling inline -- {"updates": [...]} in,
	 * {"applied_count": N} out fits syncPayload's generic contract
	 * exactly (same as syncQuests/syncDiaries above), so there's
	 * nothing bespoke enough here to justify not sharing it.
	 *
	 * bossName is sent exactly as RuneLite's own chatbox worded it
	 * (see KILLCOUNT_PATTERN's own comment in MyscapePlugin); any
	 * renaming happens server-side, so this plugin carries no name
	 * table of its own. An unrecognized name is silently dropped
	 * server-side rather than surfaced as a failure here -- there's
	 * nothing for this callback to distinguish, same as a loot event
	 * the server already had on file. callback's int is
	 * applied_count (0 or 1, never a running total) -- SyncCallback is
	 * reused here purely because the shape matches, same as every
	 * other sync method above. */
	void syncKillCountUpdate(String bossName, int killCount, SyncCallback callback)
	{
		JsonObject updateJson = new JsonObject();
		updateJson.addProperty("boss_name", bossName);
		updateJson.addProperty("kill_count", killCount);

		JsonArray updatesJson = new JsonArray();
		updatesJson.add(updateJson);
		JsonObject body = new JsonObject();
		body.add("updates", updatesJson);

		syncPayload("/api/players/me/kill-counts", body, "applied_count", callback);
	}

	/** Shared by every "current state" container sync above (syncBank,
	 * syncInventory, syncEquipment, syncPotionStorage, syncRunePouch,
	 * syncLootingBag, syncSeedVault, syncTackleBox, syncPoh,
	 * syncForestryKit, syncSeedBox, syncMasterScrollBook, ...)
	 * -- each POSTs an {"items": [...]} body to its own path and gets
	 * back the same {"item_count": N} response, so this just wraps the
	 * general syncPayload below with that one fixed field name. */
	private void syncItems(String path, JsonArray itemsJson, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.add("items", itemsJson);
		syncPayload(path, body, "item_count", callback);
	}

	/** Shared by every "current state" sync above -- syncItems (and
	 * through it, bank/inventory/equipment/potion storage/rune pouch),
	 * plus syncSkills/syncQuests/syncDiaries directly, since those three
	 * don't share the {"items": [...]} envelope but do share everything
	 * else about the request/response shape: POST `body` as-is to
	 * `path`, and read the response's `countField` back out as the
	 * count this sync's callback reports.
	 *
	 * Silently does nothing if not currently linked: there's no key to
	 * authenticate with, and more importantly nothing to attribute the
	 * snapshot to, so there's no failure worth surfacing to the player
	 * here the way there is for an explicit action like linking or
	 * unlinking -- these run automatically in the background every time
	 * the relevant state changes, whether or not the player has gotten
	 * around to linking yet. */
	private void syncPayload(String path, JsonObject body, String countField, SyncCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			return;
		}

		Request request = new Request.Builder()
			.url(apiUrl(path))
			.header("Authorization", "Bearer " + apiKey)
			.post(RequestBody.create(JSON, GSON.toJson(body)))
			.build();

		httpClient.newCall(request).enqueue(new Callback()
		{
			@Override
			public void onFailure(Call call, IOException e)
			{
				callback.onFailure("Could not reach myscape.io. Check your connection and try again.");
			}

			@Override
			public void onResponse(Call call, Response response)
			{
				try (Response r = response)
				{
					if (r.code() == 401)
					{
						// Key was revoked server-side since we last checked --
						// same handling as #refreshLinkStatus, so the plugin
						// doesn't keep quietly retrying with a dead key.
						clearStoredLink();
						callback.onFailure("No longer linked to myscape.io.");
						return;
					}
					String responseBody = r.body() != null ? r.body().string() : "";
					if (!r.isSuccessful())
					{
						callback.onFailure(describeError(r.code(), responseBody));
						return;
					}
					JsonObject json = GSON.fromJson(responseBody, JsonObject.class);
					callback.onSuccess(json.get(countField).getAsInt());
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Clears the locally stored credential -- called once #unlink has
	 * confirmed the server-side revocation succeeded (or found there was
	 * nothing to revoke), and also when the server tells us during a
	 * status check that the stored key is no longer valid. */
	void clearStoredLink()
	{
		configManager.unsetConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		configManager.unsetConfiguration(MyscapeConfig.GROUP, MyscapeConfig.PLAYER_ID_KEY);
		configManager.unsetConfiguration(MyscapeConfig.GROUP, MyscapeConfig.RUNESCAPE_NAME_KEY);
	}

	private String apiUrl(String path)
	{
		return API_BASE_URL + path;
	}

	private static String describeError(int code, String body)
	{
		try
		{
			JsonObject json = GSON.fromJson(body, JsonObject.class);
			if (json != null && json.has("detail") && json.get("detail").isJsonPrimitive())
			{
				return json.get("detail").getAsString();
			}
		}
		catch (Exception ignored)
		{
			// Fall through to the generic message below.
		}
		switch (code)
		{
			case 400:
				return "That code is invalid or has expired. Generate a new one on myscape.io.";
			case 409:
				return "This game account is already linked to a different myscape account.";
			case 429:
				return "Too many attempts. Wait a minute and try again.";
			default:
				return "myscape.io returned an error (" + code + ").";
		}
	}
}
