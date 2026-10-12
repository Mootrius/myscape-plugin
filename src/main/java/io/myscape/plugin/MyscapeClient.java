package io.myscape.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
import java.util.Base64;
import java.util.List;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Client for the myscape.io account-linking and item-sync endpoints.
 * All calls are asynchronous (OkHttp enqueue) so callers on the client thread
 * or the Swing event thread are never blocked.
 */
class MyscapeClient
{
	private static final MediaType JSON = MediaType.parse("application/json; charset=utf-8");
	private static final String API_BASE_URL = "https://myscape.io";

	private final OkHttpClient httpClient;
	private final ConfigManager configManager;
	// The client's own injected Gson (Plugin Hub rejects plugins that create their own).
	private final Gson gson;

	MyscapeClient(OkHttpClient httpClient, ConfigManager configManager, Gson gson)
	{
		this.httpClient = httpClient;
		this.configManager = configManager;
		this.gson = gson;
	}

	/** Id, name and quantity of one stored item. Each container has its own
	 * subclass so one container's snapshot cannot be passed to another's sync method. */
	abstract static class StoredItem
	{
		final int itemId;
		final String itemName;
		final int quantity;

		StoredItem(int itemId, String itemName, int quantity)
		{
			this.itemId = itemId;
			this.itemName = itemName;
			this.quantity = quantity;
		}
	}

	/** One bank slot. Item name is always sent so untradeable items can be matched by name. */
	static final class BankItem extends StoredItem
	{
		BankItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One inventory slot. */
	static final class InventoryItem extends StoredItem
	{
		InventoryItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One worn equipment item, keyed by slot ("head", "weapon", ...). */
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

	/** One Potion Storage entry, expanded from total doses into real item ids. */
	static final class PotionStorageItem extends StoredItem
	{
		PotionStorageItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Rune Pouch entry. Synced separately from the inventory so a stocked pouch
	 * cannot push the inventory over its per-request item cap. */
	static final class RunePouchItem extends StoredItem
	{
		RunePouchItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Looting Bag entry. Contents are only available once the bag has been opened in the session. */
	static final class LootingBagItem extends StoredItem
	{
		LootingBagItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Seed Vault entry. Contents are only available once the vault has been opened in the session. */
	static final class SeedVaultItem extends StoredItem
	{
		SeedVaultItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Tackle Box entry. Contents are only available once the box has been opened in the session. */
	static final class TackleBoxItem extends StoredItem
	{
		TackleBoxItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Player-Owned House Costume Room entry. All furniture pieces report through one
	 * consolidated container, so the room is synced as a single source. */
	static final class PohItem extends StoredItem
	{
		PohItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Forestry Kit entry. Contents are only available once the kit has been opened in the session. */
	static final class ForestryKitItem extends StoredItem
	{
		ForestryKitItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Seed Box entry. Contents are only available once the box has been opened in the session. */
	static final class SeedBoxItem extends StoredItem
	{
		SeedBoxItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Master Scroll Book entry. The book has no item container, so contents are read from its interface widgets. */
	static final class MasterScrollBookItem extends StoredItem
	{
		MasterScrollBookItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Huntsman's Kit entry. Contents are only available once the kit has been opened in the session. */
	static final class HuntsmansKitItem extends StoredItem
	{
		HuntsmansKitItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One Cargo Hold entry. The boat slot is passed to the sync call rather than stored per item. */
	static final class BoatCargoItem extends StoredItem
	{
		BoatCargoItem(int itemId, String itemName, int quantity)
		{
			super(itemId, itemName, quantity);
		}
	}

	/** One skill's level and xp. The skill name is the lowercase RuneLite enum name and
	 * the level is the real (unboosted) level. */
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

	/** The highest completed tier for one achievement diary area. Areas with nothing completed are omitted. */
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

	/** One obtained collection log item and its quantity. Captured while the collection log is open in game. */
	static final class CollectionLogItem
	{
		final int itemId;
		final int quantity;

		CollectionLogItem(int itemId, int quantity)
		{
			this.itemId = itemId;
			this.quantity = quantity;
		}
	}

	/** Character Summary stats. Most fields are kept current from skill, quest, diary,
	 * combat achievement and collection log reads. A periodic Character Summary read
	 * corrects the baseline. Quest counts stand in for quest points. */
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
		// Completed task counts for Easy through Grandmaster, in that order.
		// combatTasksCompleted is the sum of these.
		final int[] combatTaskTierCompleted;
		// "MAIN", "IRONMAN", "UIM", "HCIM", "GIM", "HCGIM" or "UGIM".
		final String accountType;

		CharacterSummaryItem(
			double combatLevel, int totalLevel, long totalXp,
			int questsCompleted, int questsTotal,
			int achievementsCompleted, int achievementsTotal,
			int combatTasksCompleted, int combatTasksTotal,
			int collectionsCompleted, int collectionsTotal,
			int[] combatTaskTierCompleted,
			String accountType)
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
			this.combatTaskTierCompleted = combatTaskTierCompleted;
			this.accountType = accountType;
		}
	}

	/** One item from a single loot event. Loot is an append-only record, not a state snapshot. */
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

	/** One kill, clue casket or pickpocket worth of loot. eventId is generated when the drop
	 * happens so a retried sync is not double counted. combatLevel is null when the
	 * source has no combat level. */
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

	/** Shared by the item sync methods. The server replies with {"item_count": N}. */
	interface SyncCallback
	{
		void onSuccess(int itemCount);
		void onFailure(String message);
	}

	/** Exchanges a short-lived link code (pasted from the website) for a
	 * long-lived API key, and stores that key -- never shown again after
	 * this -- plus the player id it belongs to, via ConfigManager. */
	void exchangeLinkCode(String code, long accountHash, String runescapeName, LinkCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("code", code);
		// Account hash is unsigned 64-bit; format it unsigned so it never turns negative.
		body.addProperty("account_hash", Long.toUnsignedString(accountHash));
		if (runescapeName != null)
		{
			body.addProperty("runescape_name", runescapeName);
		}

		Request request = new Request.Builder()
			.url(apiUrl("/api/link/exchange"))
			.post(RequestBody.create(JSON, gson.toJson(body)))
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
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
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
						// Key was revoked server-side. Clear it locally as well.
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
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
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

	/** Revokes the link on the server (POST /api/players/me/unlink). The stored credential
	 * is only cleared once the server confirms, so a failed call does not leave a
	 * still-valid key forgotten locally. */
	void unlink(UnlinkCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			// Nothing stored to revoke.
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
						// Key already invalid, so there is nothing left to revoke.
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

	/** Replaces the full bank snapshot on the server (POST /api/players/me/bank).
	 * Always pass the complete bank contents. */
	void syncBank(List<BankItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/bank", items, callback);
	}

	/** Replaces the inventory on the server (POST /api/players/me/inventory). */
	void syncInventory(List<InventoryItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/inventory", items, callback);
	}

	/** Replaces worn equipment on the server (POST /api/players/me/equipment). */
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

	/** Replaces Potion Storage on the server (POST /api/players/me/potion-storage). */
	void syncPotionStorage(List<PotionStorageItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/potion-storage", items, callback);
	}

	/** Replaces Rune Pouch contents on the server (POST /api/players/me/rune-pouch). */
	void syncRunePouch(List<RunePouchItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/rune-pouch", items, callback);
	}

	/** Replaces Looting Bag contents on the server (POST /api/players/me/looting-bag). */
	void syncLootingBag(List<LootingBagItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/looting-bag", items, callback);
	}

	/** Replaces Seed Vault contents on the server (POST /api/players/me/seed-vault). */
	void syncSeedVault(List<SeedVaultItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/seed-vault", items, callback);
	}

	/** Replaces Tackle Box contents on the server (POST /api/players/me/tackle-box). */
	void syncTackleBox(List<TackleBoxItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/tackle-box", items, callback);
	}

	/** Replaces the Costume Room contents on the server (POST /api/players/me/poh). */
	void syncPoh(List<PohItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/poh", items, callback);
	}

	/** Replaces Forestry Kit contents on the server (POST /api/players/me/forestry-kit). */
	void syncForestryKit(List<ForestryKitItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/forestry-kit", items, callback);
	}

	/** Replaces Seed Box contents on the server (POST /api/players/me/seed-box). */
	void syncSeedBox(List<SeedBoxItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/seed-box", items, callback);
	}

	/** Replaces Master Scroll Book contents on the server (POST /api/players/me/master-scroll-book). */
	void syncMasterScrollBook(List<MasterScrollBookItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/master-scroll-book", items, callback);
	}

	/** Replaces Huntsman's Kit contents on the server (POST /api/players/me/huntsmans-kit). */
	void syncHuntsmansKit(List<HuntsmansKitItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/huntsmans-kit", items, callback);
	}

	/** Replaces one boat's Cargo Hold contents (POST /api/players/me/boat-cargo/{slot}).
	 * The slot (1-5) is part of the path, so only that boat's rows are replaced. */
	void syncBoatCargo(int slot, List<BoatCargoItem> items, SyncCallback callback)
	{
		syncStorage("/api/players/me/boat-cargo/" + slot, items, callback);
	}

	/** Replaces the skill levels and xp on the server (POST /api/players/me/skills). */
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

	/** Reports the character's current display name (POST /api/players/me/runescape-name).
	 * The account hash restricts the rename to the linked character. The callback receives
	 * 1 if the stored name changed, otherwise 0. */
	void syncRunescapeName(long accountHash, String runescapeName, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("account_hash", Long.toUnsignedString(accountHash));
		body.addProperty("runescape_name", runescapeName);
		syncPayload("/api/players/me/runescape-name", body, "updated", callback);
	}

	/** Replaces the completed and in-progress quest lists on the server
	 * (POST /api/players/me/quests). Names are sent as RuneLite reports them. */
	void syncQuests(List<String> completedQuestNames, List<String> inProgressQuestNames, SyncCallback callback)
	{
		JsonArray questsJson = new JsonArray();
		for (String name : completedQuestNames)
		{
			questsJson.add(name);
		}
		JsonArray inProgressJson = new JsonArray();
		for (String name : inProgressQuestNames)
		{
			inProgressJson.add(name);
		}
		JsonObject body = new JsonObject();
		body.add("completed_quests", questsJson);
		// Quests in neither list are treated as not started.
		body.add("in_progress_quests", inProgressJson);
		syncPayload("/api/players/me/quests", body, "quest_count", callback);
	}

	/** Replaces the highest completed diary tier per area on the server
	 * (POST /api/players/me/diaries). */
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

	/** Replaces the obtained collection log items on the server
	 * (POST /api/players/me/collection-log). Always pass the full captured snapshot. */
	void syncCollectionLogItems(List<CollectionLogItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (CollectionLogItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems("/api/players/me/collection-log", itemsJson, callback);
	}

	/** Sends the Character Summary stats as a single object (POST
	 * /api/players/me/character-summary). The callback receives a fixed stat count. */
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
		// Keyed by tier name so the wire format does not depend on array order.
		String[] tierNames = {"easy", "medium", "hard", "elite", "master", "grandmaster"};
		JsonObject tierJson = new JsonObject();
		for (int i = 0; i < tierNames.length && i < summary.combatTaskTierCompleted.length; i++)
		{
			tierJson.addProperty(tierNames[i], summary.combatTaskTierCompleted[i]);
		}
		body.add("combat_task_tier_completed", tierJson);
		// Gson serializes a null account type as JSON null, which the server accepts.
		body.addProperty("account_type", summary.accountType);
		syncPayload("/api/players/me/character-summary", body, "stat_count", callback);
	}

	/** Replaces the character's 3D model on the server (POST
	 * /api/players/me/character-model). The .glb file is sent base64-encoded in a JSON body
	 * so it can share the common request handling. The callback receives the byte count
	 * the server decoded, which serves as a transfer integrity check. */
	void syncCharacterModel(byte[] glb, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("glb_base64", Base64.getEncoder().encodeToString(glb));
		syncPayload("/api/players/me/character-model", body, "byte_count", callback);
	}

	/** Sends one loot event immediately (POST /api/players/me/loot). This appends rather than
	 * replaces, and uses its own {"events": [...]} body. The callback receives added_count,
	 * which is 0 when the server already has the event. */
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
			.post(RequestBody.create(JSON, gson.toJson(body)))
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
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
					callback.onSuccess(json.get("added_count").getAsInt());
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Sends one kill-count reading parsed from chat (POST /api/players/me/kill-counts).
	 * The boss name is sent as shown in chat and resolved server-side. The callback receives
	 * applied_count, which is 0 or 1. */
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

	/** Adds one item to the collection log by the name shown in the in-game chat message
	 * (POST /api/players/me/collection-log/item). Adds rather than replaces. The callback
	 * receives applied_count, which is 0 or 1. */
	void syncCollectionLogItemAdded(String itemName, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.addProperty("item_name", itemName);
		syncPayload("/api/players/me/collection-log/item", body, "applied_count", callback);
	}

	/** Sends one storage container's contents as an {"items": [...]} body. */
	private void syncStorage(String path, List<? extends StoredItem> items, SyncCallback callback)
	{
		JsonArray itemsJson = new JsonArray();
		for (StoredItem item : items)
		{
			JsonObject itemJson = new JsonObject();
			itemJson.addProperty("item_id", item.itemId);
			itemJson.addProperty("item_name", item.itemName);
			itemJson.addProperty("quantity", item.quantity);
			itemsJson.add(itemJson);
		}
		syncItems(path, itemsJson, callback);
	}

	/** Wraps syncPayload for container syncs that POST {"items": [...]} and read item_count. */
	private void syncItems(String path, JsonArray itemsJson, SyncCallback callback)
	{
		JsonObject body = new JsonObject();
		body.add("items", itemsJson);
		syncPayload(path, body, "item_count", callback);
	}

	/** Common request handling for the sync methods. POSTs the body to the path and reports
	 * the integer in countField. Does nothing when not linked, since these run in the
	 * background without user action. */
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
			.post(RequestBody.create(JSON, gson.toJson(body)))
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
						// Key was revoked server-side. Clear it so the plugin stops retrying.
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
					JsonObject json = gson.fromJson(responseBody, JsonObject.class);
					callback.onSuccess(json.get(countField).getAsInt());
				}
				catch (Exception e)
				{
					callback.onFailure("Unexpected response from myscape.io.");
				}
			}
		});
	}

	/** Clears the stored credential. */
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

	private String describeError(int code, String body)
	{
		try
		{
			JsonObject json = gson.fromJson(body, JsonObject.class);
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
