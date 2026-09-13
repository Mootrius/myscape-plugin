package io.myscape.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MyscapeConfig.GROUP)
public interface MyscapeConfig extends Config
{
	String GROUP = "myscape";
	String DEFAULT_API_BASE_URL = "https://myscape.io";

	// Keys the plugin stores directly via ConfigManager once linking
	// succeeds (see MyscapeClient) -- deliberately not @ConfigItem
	// fields, since they're not something a player should ever type or
	// see rendered back in the config panel. The side panel's
	// Link/Unlink flow is the only thing that ever sets or clears them.
	String API_KEY_KEY = "apiKey";
	String PLAYER_ID_KEY = "playerId";
	String RUNESCAPE_NAME_KEY = "runescapeName";

	@ConfigItem(
		keyName = "apiBaseUrl",
		name = "API base URL",
		description = "Where the plugin sends linking/tracking requests. Only change this if you're running myscape locally for development.",
		position = 0
	)
	default String apiBaseUrl()
	{
		return DEFAULT_API_BASE_URL;
	}
}
