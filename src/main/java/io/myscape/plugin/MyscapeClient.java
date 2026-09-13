package io.myscape.plugin;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import java.io.IOException;
import net.runelite.client.config.ConfigManager;
import okhttp3.Call;
import okhttp3.Callback;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;

/**
 * Talks to myscape.io's account-linking endpoints -- POST
 * /api/link/exchange and GET /api/players/me (see app/routers/linking.py
 * and app/main.py in the Mootrius/myscape repo for the server side of
 * this contract). Everything here is async (OkHttp's enqueue/Callback):
 * every caller is a Swing button handler or plugin startup, and blocking
 * either would freeze the client UI.
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

	/** Clears the locally stored credential -- used both by the panel's
	 * "Unlink" button and when the server tells us the key is no longer
	 * valid. Doesn't call the server (there's no revoke-from-plugin
	 * endpoint yet; the website is the source of truth for that). */
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
