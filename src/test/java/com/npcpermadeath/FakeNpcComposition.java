package com.npcpermadeath;

import net.runelite.api.EntityOps;
import net.runelite.api.IterableHashTable;
import net.runelite.api.Node;
import net.runelite.api.NPCComposition;

/** Minimal NPC definition for tests; only name and actions matter. */
class FakeNpcComposition implements NPCComposition
{
	private final String name;
	private final String[] actions;

	FakeNpcComposition(String name, String[] actions)
	{
		this.name = name;
		this.actions = actions;
	}

	@Override
	public String getName()
	{
		return name;
	}

	@Override
	public String[] getActions()
	{
		return actions;
	}

	@Override
	public int[] getModels()
	{
		return null;
	}

	@Override
	public int[] getChatheadModels()
	{
		return null;
	}

	@Override
	public EntityOps getOps()
	{
		return null;
	}

	@Override
	public boolean isInteractible()
	{
		return false;
	}

	@Override
	public boolean isMinimapVisible()
	{
		return false;
	}

	@Override
	public int getId()
	{
		return 0;
	}

	@Override
	public int getCombatLevel()
	{
		return 0;
	}

	@Override
	public int[] getConfigs()
	{
		return null;
	}

	@Override
	public NPCComposition transform()
	{
		return null;
	}

	@Override
	public int getSize()
	{
		return 0;
	}

	@Override
	public boolean isFollower()
	{
		return false;
	}

	@Override
	public short[] getColorToReplace()
	{
		return null;
	}

	@Override
	public short[] getColorToReplaceWith()
	{
		return null;
	}

	@Override
	public int getWidthScale()
	{
		return 0;
	}

	@Override
	public int getHeightScale()
	{
		return 0;
	}

	@Override
	public int getFootprintSize()
	{
		return 0;
	}

	@Override
	public int[] getStats()
	{
		return null;
	}

	@Override
	public IterableHashTable<Node> getParams()
	{
		return null;
	}

	@Override
	public int getIntValue(int param)
	{
		return 0;
	}

	@Override
	public void setValue(int param, int value)
	{
	}

	@Override
	public String getStringValue(int param)
	{
		return null;
	}

	@Override
	public void setValue(int param, String value)
	{
	}

	@Override
	public long getLongValue(int param)
	{
		return 0;
	}

	@Override
	public void setValue(int param, long value)
	{
	}
}
