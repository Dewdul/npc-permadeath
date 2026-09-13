package com.npcpermadeath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;

@ConfigGroup(NpcPermadeathConfig.GROUP)
public interface NpcPermadeathConfig extends Config
{
	String GROUP = "npcpermadeath";
	String KEY_FORGET_ALL = "forgetAll";

	@ConfigItem(
		keyName = "onlyMyKills",
		name = "Only NPCs you killed",
		description = "Only hide NPCs you damaged or were fighting when they died. When off, every NPC you see die stays dead.",
		position = 0
	)
	default boolean onlyMyKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "rememberSpawns",
		name = "Learn spawn tiles",
		description = "When a slain NPC is seen respawning, also remember its spawn tile so the same spawn stays hidden even if the server renumbers NPCs.",
		position = 1
	)
	default boolean rememberSpawns()
	{
		return true;
	}

	@ConfigItem(
		keyName = "acrossWorlds",
		name = "Apply on every world",
		description = "Kills are remembered by NPC number. On, that number hides the matching NPC on any world; off, only on the world you killed it on.",
		position = 2
	)
	default boolean acrossWorlds()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcNames",
		name = "Only these NPCs",
		description = "Comma-separated NPC names this applies to (wildcards * allowed). Leave blank for every NPC.",
		position = 3
	)
	default String npcNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = KEY_FORGET_ALL,
		name = "Forget all slain NPCs",
		description = "Tick to bring every hidden NPC back and clear the saved list. Unticks itself.",
		position = 4
	)
	default boolean forgetAll()
	{
		return false;
	}
}
