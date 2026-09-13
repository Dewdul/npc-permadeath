package com.npcpermadeath;

import lombok.Value;

/**
 * One NPC type in one map region (a 64x64 tile chunk). Kill counts are kept
 * per area so that killing goblins in Lumbridge does not hide goblins in the
 * Goblin Village.
 */
@Value
public class AreaKey
{
	String name;
	int region;

	String serialize()
	{
		return region + "/" + name;
	}

	/**
	 * @return the parsed key, or null when the text is not a serialized key
	 */
	static AreaKey parse(String text)
	{
		int slash = text.indexOf('/');
		if (slash <= 0 || slash == text.length() - 1)
		{
			return null;
		}
		try
		{
			return new AreaKey(text.substring(slash + 1), Integer.parseInt(text.substring(0, slash)));
		}
		catch (NumberFormatException e)
		{
			return null;
		}
	}
}
