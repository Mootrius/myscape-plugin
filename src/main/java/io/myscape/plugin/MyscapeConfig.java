package io.myscape.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MyscapeConfig.GROUP)
public interface MyscapeConfig extends Config
{
	String GROUP = "myscape";

	// Keys the plugin stores directly via ConfigManager once linking
	// succeeds (see MyscapeClient) -- deliberately not @ConfigItem
	// fields, since they're not something a player should ever type or
	// see rendered back in the config panel. The side panel's
	// Link/Unlink flow is the only thing that ever sets or clears them.
	String API_KEY_KEY = "apiKey";
	String PLAYER_ID_KEY = "playerId";
	String RUNESCAPE_NAME_KEY = "runescapeName";

	// Used directly by name ("showSidebar") in MyscapePlugin#onConfigChanged
	// rather than referenced as a constant from there -- see that
	// method's own comment for why a plain string match is fine here.
	@ConfigItem(
		keyName = "showSidebar",
		name = "Show sidebar",
		description = "Toggle visibility of the sidebar",
		position = 0
	)
	default boolean showSidebar()
	{
		return true;
	}
}
