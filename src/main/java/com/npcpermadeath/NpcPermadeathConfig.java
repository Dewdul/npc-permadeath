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
		name = "Remember between sessions",
		description = "Learn each slain NPC's spawn tile when it respawns and keep it hidden on later logins and other worlds. Saved per account.",
		position = 1
	)
	default boolean rememberSpawns()
	{
		return true;
	}

	@ConfigItem(
		keyName = "npcNames",
		name = "Only these NPCs",
		description = "Comma-separated NPC names this applies to (wildcards * allowed). Leave blank for every NPC.",
		position = 2
	)
	default String npcNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = KEY_FORGET_ALL,
		name = "Forget all slain NPCs",
		description = "Tick to bring every hidden NPC back and clear the saved list. Unticks itself.",
		position = 3
	)
	default boolean forgetAll()
	{
		return false;
	}
}
