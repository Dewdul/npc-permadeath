package com.npcpermadeath;

import net.runelite.client.config.Config;
import net.runelite.client.config.ConfigGroup;
import net.runelite.client.config.ConfigItem;
import net.runelite.client.config.ConfigSection;
import net.runelite.client.config.Range;

@ConfigGroup(NpcPermadeathConfig.GROUP)
public interface NpcPermadeathConfig extends Config
{
	String GROUP = "npcpermadeath";
	String KEY_FORGET_ALL = "forgetAll";
	String KEY_GHOSTS = "ghosts";
	String KEY_GHOST_OPACITY = "ghostOpacity";
	String KEY_GHOST_TINT = "ghostTint";
	String KEY_NPC_NAMES = "npcNames";
	String KEY_IGNORED_NAMES = "ignoredNames";

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
		name = "Only your kills",
		description = "Only count kills that were yours: your loot dropped from it, or, for NPCs that drop nothing, you dealt the most damage. When off, every NPC you see die counts.",
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
		description = "Bosses (anything the OSRS Wiki lists as a boss) can be killed for good too. Off means bosses always respawn as normal.",
		section = whichSection,
		position = 12
	)
	default boolean includeBosses()
	{
		return true;
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
		return true;
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
		keyName = KEY_NPC_NAMES,
		name = "Only these NPCs",
		description = "Comma-separated NPC names this applies to (wildcards * allowed). Leave blank for every NPC. The plugin panel edits this list with name suggestions.",
		section = whichSection,
		position = 15
	)
	default String npcNames()
	{
		return "";
	}

	@ConfigItem(
		keyName = KEY_IGNORED_NAMES,
		name = "Never these NPCs",
		description = "Comma-separated NPC names that always respawn as normal (wildcards * allowed). The plugin panel edits this list with name suggestions.",
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
		keyName = KEY_GHOSTS,
		name = "Show slain NPCs as ghosts",
		description = "Draw hidden NPCs as translucent ghosts instead of hiding them completely. Ghosts still cannot be clicked, attacked or looted.",
		section = experienceSection,
		position = 24
	)
	default boolean ghosts()
	{
		return true;
	}

	@Range(min = 10, max = 80)
	@ConfigItem(
		keyName = KEY_GHOST_OPACITY,
		name = "Ghost opacity",
		description = "How solid ghosts look, in percent. Higher is easier to see.",
		section = experienceSection,
		position = 25
	)
	default int ghostOpacity()
	{
		return 15;
	}

	@ConfigItem(
		keyName = KEY_GHOST_TINT,
		name = "Pale ghost colour",
		description = "Recolour ghosts a pale spectral blue-white instead of the NPC's own colours.",
		section = experienceSection,
		position = 26
	)
	default boolean ghostTint()
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
