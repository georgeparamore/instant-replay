package com.cliprecorder;

import java.awt.event.KeyEvent;
import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Keybind;
import net.runelite.client.config.Range;
import net.runelite.client.config.Units;

@ConfigGroup(ClipRecorderConfig.GROUP)
public interface ClipRecorderConfig extends Config
{
	String GROUP = "cliprecorder";

	@ConfigSection(
		name = "Triggers",
		description = "Events that automatically save a clip",
		position = 10
	)
	String triggerSection = "triggers";

	@ConfigSection(
		name = "Advanced",
		description = "Fine-tuning; the defaults are fine for most people",
		position = 20,
		closedByDefault = true
	)
	String advancedSection = "advanced";

	@Range(min = 10, max = 120)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "bufferSeconds",
		name = "Clip length",
		description = "How many seconds of gameplay each clip contains (the plugin always remembers this much gameplay)",
		position = 0
	)
	default int bufferSeconds()
	{
		return 30;
	}

	@ConfigItem(
		keyName = "resolution",
		name = "Resolution",
		description = "Video size of saved clips. Lower resolutions use less memory and make smaller files",
		position = 1
	)
	default ClipResolution resolution()
	{
		return ClipResolution.P720;
	}

	@ConfigItem(
		keyName = "fps",
		name = "Frame rate",
		description = "How smooth clips are. Higher uses more memory and CPU",
		position = 2
	)
	default CaptureFps fps()
	{
		return CaptureFps.FPS_30;
	}

	@ConfigItem(
		keyName = "smallerFiles",
		name = "Smaller files",
		description = "Saves clips at 30 FPS even when capturing at 60 - roughly halves the file size, "
			+ "with no extra saving time. Gameplay still buffers at your full frame rate",
		position = 3
	)
	default boolean smallerFiles()
	{
		return false;
	}

	@ConfigItem(
		keyName = "clipHotkey",
		name = "Clip hotkey",
		description = "Save a clip of the last moments right now",
		position = 5
	)
	default Keybind clipHotkey()
	{
		return new Keybind(KeyEvent.VK_F8, 0);
	}

	@ConfigItem(
		keyName = "chatMessageOnClip",
		name = "Chat message when saved",
		description = "Print a message in the chatbox when a clip is saved",
		position = 6
	)
	default boolean chatMessageOnClip()
	{
		return true;
	}

	@ConfigItem(
		keyName = "notifyOnClip",
		name = "System notification",
		description = "Also show a desktop notification when a clip is saved",
		position = 7
	)
	default boolean notifyOnClip()
	{
		return false;
	}

	// ---- Triggers ----

	@ConfigItem(
		keyName = "clipOnPb",
		name = "New personal best",
		description = "Save a clip when a kill time is a new personal best",
		section = triggerSection,
		position = 0
	)
	default boolean clipOnPb()
	{
		return true;
	}

	@ConfigItem(
		keyName = "pbFullKill",
		name = "PB clips cover the whole kill",
		description = "PB messages include the kill time - start the clip at the beginning of the kill "
			+ "instead of only the last 'Clip length' seconds. Needs 'PB kill memory' below to be at "
			+ "least as long as your kills",
		section = triggerSection,
		position = 1
	)
	default boolean pbFullKill()
	{
		return true;
	}

	@Range(min = 1, max = 5)
	@ConfigItem(
		keyName = "pbMemoryMinutes",
		name = "PB kill memory (minutes)",
		description = "How many minutes of gameplay stay in memory for whole-kill PB clips. "
			+ "More minutes = more RAM used (roughly 250 MB per minute at 1080p/60, half that at 720p/30)",
		section = triggerSection,
		position = 2
	)
	default int pbMemoryMinutes()
	{
		return 3;
	}

	@ConfigItem(
		keyName = "clipOnValuableDrop",
		name = "Valuable drop",
		description = "Save a clip when loot worth more than the threshold is received",
		section = triggerSection,
		position = 3
	)
	default boolean clipOnValuableDrop()
	{
		return true;
	}

	@ConfigItem(
		keyName = "valuableDropThreshold",
		name = "Drop value threshold",
		description = "Minimum GE value (in coins) of a drop to trigger a clip",
		section = triggerSection,
		position = 4
	)
	default int valuableDropThreshold()
	{
		return 1_000_000;
	}

	@ConfigItem(
		keyName = "clipOnCollectionLog",
		name = "Collection log slot",
		description = "Save a clip on a new collection log entry. Requires the in-game setting 'Collection log - New addition notification' to be ON",
		section = triggerSection,
		position = 5
	)
	default boolean clipOnCollectionLog()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clipOnQuest",
		name = "Quest completion",
		description = "Save a clip when a quest is completed",
		section = triggerSection,
		position = 6
	)
	default boolean clipOnQuest()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clipOnCombatAchievement",
		name = "Combat achievement",
		description = "Save a clip when a combat achievement task is completed",
		section = triggerSection,
		position = 7
	)
	default boolean clipOnCombatAchievement()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clipOnLevelUp",
		name = "Level up",
		description = "Save a clip when a skill level is gained (fires often - off by default)",
		section = triggerSection,
		position = 8
	)
	default boolean clipOnLevelUp()
	{
		return false;
	}

	@ConfigItem(
		keyName = "clipOnPet",
		name = "Pet drop",
		description = "Save a clip when you receive a pet ('You have a funny feeling...')",
		section = triggerSection,
		position = 9
	)
	default boolean clipOnPet()
	{
		return true;
	}

	@ConfigItem(
		keyName = "clipOnDeath",
		name = "Death",
		description = "Save a clip when your character dies (off by default)",
		section = triggerSection,
		position = 10
	)
	default boolean clipOnDeath()
	{
		return false;
	}

	// ---- Advanced ----

	@Range(min = 0, max = 30)
	@Units(Units.SECONDS)
	@ConfigItem(
		keyName = "postRollSeconds",
		name = "Record after trigger",
		description = "Extra seconds recorded AFTER an automatic trigger (like a drop) before the clip is saved, so the full animation is included",
		section = advancedSection,
		position = 0
	)
	default int postRollSeconds()
	{
		return 10;
	}

	@Range(min = 30, max = 100)
	@ConfigItem(
		keyName = "jpegQuality",
		name = "Image quality",
		description = "Compression quality of the video image (this is NOT resolution). "
			+ "Higher looks better but makes larger files and uses more memory; 70 is hard to tell from 80 in motion",
		section = advancedSection,
		position = 1
	)
	default int jpegQuality()
	{
		return 70;
	}

	@ConfigItem(
		keyName = "outputFolder",
		name = "Custom clips folder",
		description = "Folder to save clips in. Leave blank for the default (.runelite\\clips)",
		section = advancedSection,
		position = 3
	)
	default String outputFolder()
	{
		return "";
	}
}
