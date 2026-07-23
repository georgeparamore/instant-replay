package com.cliprecorder;

import net.runelite.client.RuneLite;
import net.runelite.client.externalplugins.ExternalPluginManager;

/**
 * Dev-mode launcher: runs the full RuneLite client with this plugin loaded.
 */
public class ClipRecorderPluginTest
{
	public static void main(String[] args) throws Exception
	{
		ExternalPluginManager.loadBuiltin(ClipRecorderPlugin.class);
		RuneLite.main(args);
	}
}
