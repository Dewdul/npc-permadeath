package com.npcpermadeath;

import lombok.Value;
import net.runelite.api.coords.WorldPoint;

/**
 * Identifies one NPC spawn point: the NPC type and the tile it respawns on.
 * Stable across worlds, logins and game updates, unlike an NPC's index.
 */
@Value
public class SpawnKey
{
	int npcId;
	int x;
	int y;
	int plane;

	static SpawnKey of(int npcId, WorldPoint point)
	{
		return new SpawnKey(npcId, point.getX(), point.getY(), point.getPlane());
	}

	String serialize()
	{
		return npcId + ":" + x + ":" + y + ":" + plane;
	}

	/**
	 * @return the parsed key, or null when the text is not a serialized key
	 */
	static SpawnKey parse(String text)
	{
		String[] parts = text.trim().split(":");
		if (parts.length != 4)
		{
			return null;
		}
		try
		{
			return new SpawnKey(
				Integer.parseInt(parts[0]),
				Integer.parseInt(parts[1]),
				Integer.parseInt(parts[2]),
				Integer.parseInt(parts[3]));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
