package io.myscape.plugin;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * The `run` Gradle task's actual entry point (see build.gradle) -- NOT
 * `net.runelite.client.RuneLite` directly. RuneLite's own plugin
 * discovery only does two things: scan the `net.runelite.client.plugins`
 * package for core plugins, and load `.jar` files dropped in a
 * `sideloaded-plugins` directory. Neither one picks up a plugin class
 * sitting on the classpath in an arbitrary package like ours -- so
 * launching `RuneLite.main()` straight from the `run` task (which is
 * what this repo did initially) starts a perfectly normal client that
 * silently never loads MyscapePlugin at all: no crash, no log line, just
 * no icon in the sidebar. `ExternalPluginManager.loadBuiltin(...)`,
 * called before `RuneLite.main()` starts, is the actual mechanism
 * RuneLite provides for this -- it's the same pattern RuneLite's own
 * example-plugin template uses for its `run` task (see
 * ExamplePluginTest.java in runelite/example-plugin).
 */
public class MyscapePluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(MyscapePlugin.class);
		RuneLite.main(args);
	}
}
