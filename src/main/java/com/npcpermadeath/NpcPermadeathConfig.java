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
		description = "Only count NPCs you damaged or were fighting when they died. When off, every NPC you see die counts.",
		position = 0
	)
	default boolean onlyMyKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "announceKills",
		name = "Announce kills in chat",
		description = "After each kill, show how many of that NPC are slain in the area, e.g. \"Goblin: 12 of 53 slain in this area\".",
		position = 1
	)
	default boolean announceKills()
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
		description = "Tick to bring every hidden NPC back and clear all kill counts. Unticks itself.",
		position = 3
	)
	default boolean forgetAll()
	{
		return false;
	}
}
