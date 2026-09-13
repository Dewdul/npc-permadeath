package com.npcpermadeath;

import net.runelite.api.Model;
import net.runelite.api.Node;
import net.runelite.api.TileItem;

/** Minimal ground item for tests; only id and ownership matter to the plugin. */
class FakeTileItem implements TileItem
{
	private final int id;
	private final int ownership;

	FakeTileItem(int id, int ownership)
	{
		this.id = id;
		this.ownership = ownership;
	}

	@Override
	public int getId()
	{
		return id;
	}

	@Override
	public int getOwnership()
	{
		return ownership;
	}

	@Override
	public int getQuantity()
	{
		return 1;
	}

	@Override
	public int getVisibleTime()
	{
		return 0;
	}

	@Override
	public int getDespawnTime()
	{
		return 0;
	}

	@Override
	public boolean isPrivate()
	{
		return ownership == OWNERSHIP_SELF;
	}

	@Override
	public Model getModel()
	{
		return null;
	}

	@Override
	public int getModelHeight()
	{
		return 0;
	}

	@Override
	public void setModelHeight(int height)
	{
	}

	@Override
	public int getAnimationHeightOffset()
	{
		return 0;
	}

	@Override
	public int getRenderMode()
	{
		return 0;
	}

	@Override
	public Node getNext()
	{
		return null;
	}

	@Override
	public Node getPrevious()
	{
		return null;
	}

	@Override
	public long getHash()
	{
		return id;
	}
}
