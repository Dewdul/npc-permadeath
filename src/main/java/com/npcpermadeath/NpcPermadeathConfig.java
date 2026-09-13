package com.npcpermadeath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;

@ConfigGroup(NpcPermadeathConfig.GROUP)
public interface NpcPermadeathConfig extends Config
{
	String GROUP = "npcpermadeath";
	String KEY_FORGET_ALL = "forgetAll";

	@ConfigSection(
		name = "Which NPCs count",
		description = "Choose which NPCs can be killed for good.",
		position = 10
	)
	String whichSection = "which";

	@ConfigSection(
		name = "Experience",
		description = "How hidden NPCs behave.",
		position = 20
	)
	String experienceSection = "experience";

	@ConfigItem(
		keyName = "onlyMyKills",
		name = "Only NPCs you killed",
		description = "Only count NPCs you damaged or were fighting when they died. When off, every NPC you see die counts.",
		section = whichSection,
		position = 11
	)
	default boolean onlyMyKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = "includeBosses",
		name = "Include bosses",
		description = "Let bosses (anything the OSRS Wiki lists as a boss) be killed for good too. Off means bosses always respawn as normal.",
		section = whichSection,
		position = 12
	)
	default boolean includeBosses()
	{
		return false;
	}

	@ConfigItem(
		keyName = "includeInstances",
		name = "Include instanced areas",
		description = "Count kills inside instances (boss rooms, raids, minigames) and hide NPCs there. Off means instances are left alone.",
		section = whichSection,
		position = 13
	)
	default boolean includeInstances()
	{
		return false;
	}

	@ConfigItem(
		keyName = "maxCombatLevel",
		name = "Max combat level",
		description = "NPCs above this combat level always respawn as normal. 0 means no limit.",
		section = whichSection,
		position = 14
	)
	default int maxCombatLevel()
	{
		return 0;
	}

	@ConfigItem(
		keyName = "npcNames",
		name = "Only these NPCs",
		description = "Comma-separated NPC names this applies to (wildcards * allowed). Leave blank for every NPC.",
		section = whichSection,
		position = 15
	)
	default String npcNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = "ignoredNames",
		name = "Never these NPCs",
		description = "Comma-separated NPC names that always respawn as normal (wildcards * allowed).",
		section = whichSection,
		position = 16
	)
	default String ignoredNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = "revealAttackers",
		name = "Reveal NPCs attacking you",
		description = "A hidden NPC that starts attacking you becomes visible so you can fight back, and another NPC of its kind is hidden in its place.",
		section = experienceSection,
		position = 21
	)
	default boolean revealAttackers()
	{
		return true;
	}

	@ConfigItem(
		keyName = "hideLoot",
		name = "Hide loot from hidden NPCs",
		description = "Drops from a hidden NPC (killed by area attacks or a cannon) are not shown and cannot be picked up. Other items on the tile are unaffected.",
		section = experienceSection,
		position = 22
	)
	default boolean hideLoot()
	{
		return true;
	}

	@ConfigItem(
		keyName = "announceKills",
		name = "Announce kills in chat",
		description = "After each kill, show how many of that NPC are slain in the area, e.g. \"Goblin: 12 of 53 slain in this area\".",
		section = experienceSection,
		position = 23
	)
	default boolean announceKills()
	{
		return true;
	}

	@ConfigItem(
		keyName = KEY_FORGET_ALL,
		name = "Forget all slain NPCs",
		description = "Tick to bring every hidden NPC back and clear all kill counts. Unticks itself.",
		position = 30
	)
	default boolean forgetAll()
	{
		return false;
	}
}
