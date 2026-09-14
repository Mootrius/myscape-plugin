package io.myscape.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import java.io.IOException;
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

	/** Shared by syncBank/syncInventory/syncEquipment/syncPotionStorage/syncRunePouch -- all five POST
	 * an {"items": [...]} body to their own path and get back the same
	 * {"item_count": N} response.
	 *
	 * Silently does nothing if not currently linked: there's no key to
	 * authenticate with, and more importantly nothing to attribute the
	 * snapshot to, so there's no failure worth surfacing to the player
	 * here the way there is for an explicit action like linking or
	 * unlinking -- these run automatically in the background every time
	 * the relevant container changes, whether or not the player has
	 * gotten around to linking yet. */
	private void syncItems(String path, JsonArray itemsJson, SyncCallback callback)
	{
		String apiKey = configManager.getConfiguration(MyscapeConfig.GROUP, MyscapeConfig.API_KEY_KEY);
		if (apiKey == null || apiKey.isEmpty())
		{
			return;
		}

		JsonObject body = new JsonObject();
		body.add("items", itemsJson);

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
					callback.onSuccess(json.get("item_count").getAsInt());
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
