package io.myscape.plugin;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(MyscapeConfig.GROUP)
public interface MyscapeConfig extends Config
{
	String GROUP = "myscape";

	// Stored directly via ConfigManager after linking. Not @ConfigItem fields,
	// so they never appear in the config panel.
	String API_KEY_KEY = "apiKey";
	String PLAYER_ID_KEY = "playerId";
	String RUNESCAPE_NAME_KEY = "runescapeName";

	// Referenced by name in MyscapePlugin#onConfigChanged.
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
