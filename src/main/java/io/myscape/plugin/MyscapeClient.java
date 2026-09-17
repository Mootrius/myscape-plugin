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

	private final OkHttpClient httpClient;
	private final MyscapeConfig config;
	private final ConfigManager configManager;

	MyscapeClient(OkHttpClient httpClient, MyscapeConfig config, ConfigManager configManager)
	{
		this.httpClient = httpClient;
		this.config = config;
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
	 * the whole Costume Room -- see CostumeRoomItem in the
	 * Mootrius/myscape repo's app/models.py for the server side, and
	 * MyscapePlugin#readCostumeRoomItems for how this is actually
	 * built. Originally six separate classes, one per piece of
	 * furniture, each assumed to back its own ItemContainer
	 * (InventoryID.POH_COSTUME_ROOM_MAGIC_WARDROBE_INV and friends).
	 * Live testing showed every piece actually fires
	 * ItemContainerChanged on the same containerId (33405, not any of
	 * those named constants) with identical contents regardless of
	 * which piece was opened -- Jagex's newer, consolidated Costume
	 * Room interface (InterfaceID.POH_COSTUMES) reads from one unified
	 * store, not six. Same open-it-once caveat as every other source
	 * here: contents are only reported once the player has opened the
	 * Costume Room interface at least once this session. */
	static final class CostumeRoomItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		CostumeRoomItem(int itemId, String itemName, int quantity)
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
	 * "equipment", "inventory", "potion_storage", "seed_vault",
	 * "rune_pouch", "looting_bag"), each value either an ISO-8601 instant
	 * (that storage has synced at least once) or null (it never has --
	 * see Player.storage_synced_at's own docstring server-side for why
	 * that's a real, distinct state worth showing rather than just
	 * inferring it from whether an item list happens to be empty). */
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

	/** Same deal as syncBank, for the whole Costume Room's contents
	 * (POST /api/players/me/costume-room) -- one endpoint covering all
	 * six pieces of furniture now, not six separate ones (see
	 * CostumeRoomItem's own comment above for why). */
	void syncCostumeRoom(List<CostumeRoomItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (CostumeRoomItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/costume-room", itemsJson, callback);
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

	/** Shared by every "current state" container sync above (syncBank,
	 * syncInventory, syncEquipment, syncPotionStorage, syncRunePouch,
	 * syncLootingBag, syncSeedVault, syncTackleBox, syncCostumeRoom,
	 * ...)
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
		String base = config.apiBaseUrl();
		if (base == null || base.isEmpty())
		{
			base = MyscapeConfig.DEFAULT_API_BASE_URL;
		}
		if (base.endsWith("/"))
		{
			base = base.substring(0, base.length() - 1);
		}
		return base + path;
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
